// 独自拡張 kibi:process/process（#74）の p1 の import を確かめる CLI。WasiP1KibiProcessTest が /bin/kp に置いて動かす。
// 親と子を同じプログラムで受け持つ（子は spawn("/bin/kp", ...) で作る）。
//
// 親（WasiP1KibiProcessTest が動かす）:
//   kp run <子の引数...>    子を作り、poll（FD_READ だけ。時計の購読なし）で終わりを待ち、status を2回（刈り取りの前後）読む
//   kp kill <sig>           眠る子を作って handle で kill し、poll で待って status を読む
//   kp ignore               Ctrl+C を無視する子に interrupt を送っても効かず、kill で止まる
//   kp close <how>          子を作ってハンドルを閉じる（drop・close・renumber・dupto）。子は親なしになったら書いて終わる
//   kp errors               spawn・kill-pid・set-signal-ignored・set-cwd の誤り
//   kp mfile                fd 表を満杯にしてから spawn すると mfile
//   kp cwd                  set-cwd・get-cwd と、子の cwd（none なら新しい cwd、some ならそれ）。相対パスの spawn
//   kp realloc-spawn        get-cwd の cabi_realloc の中で spawn を呼ぶ（トラップになるはず）
//   kp nowait               待たない設定で spawn（again になるはず）
//   kp misaligned <list>    align 4 に揃っていない list の ptr（長さ 0）で spawn する（トラップになるはず。#430）。list は args・env・fds。env-cwd は揃っていない env と不正な cwd の判別子（env のトラップが先）
//   kp misaligned-order     揃っていない list より先に読む引数（path・args・env・cwd）の誤りが、トラップより先に返る（#430。ADR 0074）
//   kp nowait-misaligned    待たない設定で、揃っていない args の ptr で spawn（引数を読む前に again になるはず）
//   kp self                 self-pid・parent-pid
//   kp ps                   眠る子・終わった子（ゾンビ）・親なしのゾンビ・シバンの子を作ってから list-processes を表示する（#452）
//   kp ps-realloc           list-processes の cabi_realloc の中で、もう一度 list-processes を呼ぶ（トラップしないはず）
// 子:
//   kp exit <n>             proc_exit(n)（32 ビットそのまま）
//   kp trap                 トラップする
//   kp sleep <ms>           眠って 0 で終わる
//   kp ignore-child         Ctrl+C を無視してから fd 1 に "r" を書き、眠り続ける
//   kp orphan               親なし（parent-pid が 0）になったら fd 1 に "orphaned" を書いて終わる
//   kp orphan-quiet         親なし（parent-pid が 0）になったら何も書かずに 0 で終わる（親が閉じるまで終わらないので、順序が時間に依らない）
//   kp cwd-child            get-cwd を表示する
//   kp hang                 止められるまで眠る（シバン #!/bin/kp hang のスクリプトから）
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <wasi/api.h>

#include "kibi_io.h"
#include "kibi_process.h"

#define SELF "/bin/kp"

static int realloc_spawn = 0;
static int realloc_list = 0;
static long inner_list_len = -1;

__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    if (realloc_spawn) {
        kibi_result_process_t res;
        kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 0, NULL, 0, 0, NULL, 0, NULL, 0, &res);
    }
    if (realloc_list) {
        // 入れ子は1段だけ
        realloc_list = 0;
        kibi_process_list_process_info_t inner;
        kibi_process_list_processes(&inner);
        // 書き出し（fd_write）は止まりうる import なので、cabi_realloc の中では呼ばない
        inner_list_len = (long)inner.len;
    }
    return aligned_alloc(align < sizeof(void *) ? sizeof(void *) : align, new_size == 0 ? 1 : new_size);
}

static void sleep_ms(long ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000L};
    nanosleep(&ts, NULL);
}

// 失敗なら -1 - error-code
static int64_t spawn_full(const char *path, int argc, const char **argv, const kibi_process_fd_assign_t *fds, size_t nfds,
                          const char *cwd) {
    kibi_process_string_t args[16];
    for (int i = 0; i < argc; i++) {
        args[i].ptr = argv[i];
        args[i].len = strlen(argv[i]);
    }
    kibi_result_process_t res;
    memset(&res, 0xAA, sizeof res);
    kibi_process_spawn(path, strlen(path), args, (size_t)argc, 0, NULL, 0, cwd != NULL, cwd, cwd ? strlen(cwd) : 0, fds, nfds, &res);
    return res.is_err ? -1 - (int64_t)res.val.err : (int64_t)res.val.ok;
}

static int64_t spawn_self(int argc, const char **argv, const kibi_process_fd_assign_t *fds, size_t nfds) {
    return spawn_full(SELF, argc, argv, fds, nfds, NULL);
}

static int64_t unit(kibi_result_unit_t res) { return res.is_err ? -1 - (int64_t)res.err : 0; }

static int64_t handle_kill(uint32_t h, int32_t sig) {
    kibi_result_unit_t res;
    memset(&res, 0xAA, sizeof res);
    kibi_process_kill(h, sig, &res);
    return unit(res);
}

// 終わり方を文字列にする
static void status_text(uint32_t h, char *out, size_t n) {
    kibi_result_option_exit_status_t res;
    memset(&res, 0xAA, sizeof res);
    kibi_process_status(h, &res);
    if (res.is_err) {
        snprintf(out, n, "err %d", -1 - (int)res.val.err);
    } else if (!res.val.ok.is_some) {
        snprintf(out, n, "none");
    } else {
        kibi_process_exit_status_t s = res.val.ok.val;
        if (s.tag == KIBI_PROCESS_EXITED) snprintf(out, n, "exited %u", s.val.exited);
        else if (s.tag == KIBI_PROCESS_KILLED && s.val.killed.is_some) snprintf(out, n, "killed %u", s.val.killed.sig);
        else if (s.tag == KIBI_PROCESS_KILLED) snprintf(out, n, "killed none");
        else if (s.tag == KIBI_PROCESS_CRASHED) snprintf(out, n, "crashed");
        else snprintf(out, n, "bad %u", s.tag);
    }
}

// 子の終わりを poll（FD_READ だけ）で待つ
static int wait_exit(uint32_t h) {
    struct pollfd p = {(int)h, POLLIN, 0};
    int n = poll(&p, 1, -1);
    return n == 1 && (p.revents & POLLIN) ? 0 : -1;
}

static int run_run(int argc, const char **argv) {
    const char *child[16];
    child[0] = "kp";
    for (int i = 0; i < argc && i < 15; i++) child[i + 1] = argv[i];
    int64_t h = spawn_self(argc + 1, child, NULL, 0);
    if (h < 0) {
        printf("spawn %lld\n", (long long)h);
        return 0;
    }
    uint32_t pid = kibi_process_pid((uint32_t)h);
    printf("spawned %u\n", pid);
    if (wait_exit((uint32_t)h) != 0) return 1;
    char s[64];
    status_text((uint32_t)h, s, sizeof s);
    printf("status1 %s\n", s);
    status_text((uint32_t)h, s, sizeof s);
    printf("status2 %s\n", s);
    printf("pid-after %d\n", kibi_process_pid((uint32_t)h) == pid);
    kibi_process_drop((uint32_t)h);
    return 0;
}

static int run_kill(int sig) {
    const char *child[] = {"kp", "sleep", "60000"};
    int64_t h = spawn_self(3, child, NULL, 0);
    if (h < 0) return 1;
    char s[64];
    status_text((uint32_t)h, s, sizeof s);
    printf("before %s\n", s);
    printf("kill %lld\n", (long long)handle_kill((uint32_t)h, sig));
    if (wait_exit((uint32_t)h) != 0) return 1;
    status_text((uint32_t)h, s, sizeof s);
    printf("status %s\n", s);
    // 刈り取った後に送っても成功（何もしない）
    printf("kill-after %lld\n", (long long)handle_kill((uint32_t)h, sig));
    return 0;
}

// パイプを作り、書く端を子の fd 1 にして子を作る（親の書く端は閉じる）。読む端を返す
static int64_t spawn_with_pipe(const char *mode, uint32_t *read_end) {
    kibi_result_pipe_ends_t p;
    kibi_io_pipe_create(&p);
    if (p.is_err) return -1000;
    kibi_process_fd_assign_t fds[1] = {{1, {KIBI_PROCESS_FD_SOURCE_PARENT, p.val.ok.write}}};
    const char *child[] = {"kp", mode};
    int64_t h = spawn_self(2, child, fds, 1);
    close((int)p.val.ok.write);
    *read_end = p.val.ok.read;
    return h;
}

static int run_ignore(void) {
    uint32_t r;
    int64_t h = spawn_with_pipe("ignore-child", &r);
    if (h < 0) return 1;
    char c;
    if (read((int)r, &c, 1) != 1) return 1;
    printf("int %lld\n", (long long)handle_kill((uint32_t)h, KIBI_PROCESS_SIGNAL_INTERRUPT));
    sleep_ms(50);
    char s[64];
    status_text((uint32_t)h, s, sizeof s);
    printf("after-int %s\n", s);
    printf("kill %lld\n", (long long)handle_kill((uint32_t)h, KIBI_PROCESS_SIGNAL_KILL));
    if (wait_exit((uint32_t)h) != 0) return 1;
    status_text((uint32_t)h, s, sizeof s);
    printf("status %s\n", s);
    return 0;
}

static int run_close(const char *how) {
    uint32_t r;
    int64_t h = spawn_with_pipe("orphan", &r);
    if (h < 0) return 1;
    printf("child %u\n", kibi_process_pid((uint32_t)h));
    if (strcmp(how, "drop") == 0) {
        kibi_process_drop((uint32_t)h);
    } else if (strcmp(how, "close") == 0) {
        if (close((int)h) != 0) return 1;
    } else {
        // 別の fd（パイプの読む端）で上書きする
        kibi_result_pipe_ends_t p;
        kibi_io_pipe_create(&p);
        if (p.is_err) return 1;
        if (strcmp(how, "renumber") == 0) {
            if (__wasi_fd_renumber(p.val.ok.read, (uint32_t)h) != 0) return 1;
        } else {
            kibi_result_unit_t res;
            kibi_io_fd_dup_to(p.val.ok.read, (uint32_t)h, &res);
            if (res.is_err) return 1;
        }
    }
    printf("closed\n");
    char buf[32] = {0};
    size_t got = 0;
    while (got < sizeof buf - 1) {
        ssize_t n = read((int)r, buf + got, sizeof buf - 1 - got);
        if (n <= 0) break;
        got += (size_t)n;
    }
    printf("child said %s\n", buf);
    return 0;
}

static int run_errors(void) {
    kibi_result_process_t res;
    // ない・実行できない（WASM でない）
    printf("noent %lld\n", (long long)spawn_full("/nope", 0, NULL, NULL, 0, NULL));
    printf("noexec %lld\n", (long long)spawn_full("/bin/text", 0, NULL, NULL, 0, NULL));
    // UTF-8 でない path
    kibi_process_spawn("\xff", 1, NULL, 0, 0, NULL, 0, 0, NULL, 0, NULL, 0, &res);
    printf("utf8 %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // args が多すぎる（要素は読まない）・範囲外
    kibi_process_spawn(SELF, strlen(SELF), (const kibi_process_string_t *)0x10, 5000, 0, NULL, 0, 0, NULL, 0, NULL, 0, &res);
    printf("args-count %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    kibi_process_spawn(SELF, strlen(SELF), (const kibi_process_string_t *)0x7ffffff0, 2, 0, NULL, 0, 0, NULL, 0, NULL, 0, &res);
    printf("args-fault %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // option の判別子
    kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 2, NULL, 0, 0, NULL, 0, NULL, 0, &res);
    printf("env-disc %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // fds: 同じ fd を2回・判別子の誤り・親の fd が開いていない
    kibi_process_fd_assign_t dup[2] = {{1, {KIBI_PROCESS_FD_SOURCE_CLOSED, 0}}, {1, {KIBI_PROCESS_FD_SOURCE_CLOSED, 0}}};
    printf("fds-dup %lld\n", (long long)spawn_full(SELF, 0, NULL, dup, 2, NULL));
    kibi_process_fd_assign_t badtag[1] = {{1, {7, 0}}};
    printf("fds-tag %lld\n", (long long)spawn_full(SELF, 0, NULL, badtag, 1, NULL));
    kibi_process_fd_assign_t badfd[1] = {{4, {KIBI_PROCESS_FD_SOURCE_PARENT, 200}}};
    printf("fds-badf %lld\n", (long long)spawn_full(SELF, 0, NULL, badfd, 1, NULL));
    // 環境変数の名前に = がある（読み込みの前に断る）
    kibi_process_env_t env[1] = {{{"A=B", 3}, {"c", 1}}};
    kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 1, env, 1, 0, NULL, 0, NULL, 0, &res);
    printf("env-name %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // cwd がない
    printf("cwd-noent %lld\n", (long long)spawn_full(SELF, 0, NULL, NULL, 0, "/nope"));
    kibi_result_unit_t u;
    kibi_process_kill_pid(99999, KIBI_PROCESS_SIGNAL_TERMINATE, &u);
    printf("kill-srch %lld\n", (long long)unit(u));
    kibi_process_kill_pid(0, KIBI_PROCESS_SIGNAL_TERMINATE, &u);
    printf("kill-zero %lld\n", (long long)unit(u));
    kibi_process_kill_pid(kibi_process_self_pid(), 9, &u);
    printf("kill-sig %lld\n", (long long)unit(u));
    kibi_process_set_signal_ignored(KIBI_PROCESS_SIGNAL_KILL, 1, &u);
    printf("ignore-kill %lld\n", (long long)unit(u));
    kibi_process_set_signal_ignored(KIBI_PROCESS_SIGNAL_QUIT, 1, &u);
    printf("ignore-quit %lld\n", (long long)unit(u));
    // 子のハンドルでない fd の status・kill は bad-descriptor
    char s[64];
    status_text(1, s, sizeof s);
    printf("status-badf %s\n", s);
    printf("kill-badf %lld\n", (long long)handle_kill(1, KIBI_PROCESS_SIGNAL_TERMINATE));
    kibi_process_set_cwd("/nope", 5, &u);
    printf("setcwd-noent %lld\n", (long long)unit(u));
    kibi_process_set_cwd(SELF, strlen(SELF), &u);
    printf("setcwd-notdir %lld\n", (long long)unit(u));
    return 0;
}

// 4 に揃った場所から 2 ずらした ptr（中身は読まれないはず）
static _Alignas(8) char misaligned_area[64];
#define MISALIGNED ((const void *)(misaligned_area + 2))

static int run_misaligned(const char *which) {
    kibi_result_process_t res;
    memset(&res, 0xAA, sizeof res);
    printf("spawning %s\n", which);
    if (strcmp(which, "args") == 0) {
        kibi_process_spawn(SELF, strlen(SELF), MISALIGNED, 0, 0, NULL, 0, 0, NULL, 0, NULL, 0, &res);
    } else if (strcmp(which, "env") == 0) {
        kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 1, MISALIGNED, 0, 0, NULL, 0, NULL, 0, &res);
    } else if (strcmp(which, "env-cwd") == 0) {
        // 揃っていない env の list のトラップが、後の cwd の判別子の誤り（invalid）より先
        kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 1, MISALIGNED, 0, 2, NULL, 0, NULL, 0, &res);
    } else if (strcmp(which, "fds") == 0) {
        kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 0, NULL, 0, 0, NULL, 0, MISALIGNED, 0, &res);
    } else {
        return 2;
    }
    printf("returned %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    return 0;
}

static char long_path[5000];

static int run_misaligned_order(void) {
    kibi_result_process_t res;
    // path が長すぎる（name-too-long）のが、揃っていない args より先
    memset(long_path, 'a', sizeof long_path);
    long_path[0] = '/';
    kibi_process_spawn(long_path, sizeof long_path, MISALIGNED, 0, 0, NULL, 0, 0, NULL, 0, NULL, 0, &res);
    printf("path-first %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // args が多すぎる（too-big。要素は読まない）のが、揃っていない env より先
    kibi_process_spawn(SELF, strlen(SELF), (const kibi_process_string_t *)0x10, 5000, 1, MISALIGNED, 0, 0, NULL, 0, NULL, 0, &res);
    printf("args-first %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // env の option の判別子が不正（invalid）なのが、揃っていない fds より先
    kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 2, NULL, 0, 0, NULL, 0, MISALIGNED, 0, &res);
    printf("disc-first %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // env が none なら env_ptr は揃っていなくても確かめない（標準 OS の process.zig は none でも ptr を渡す）。ない path で no-entry
    kibi_process_spawn("/nope", 5, NULL, 0, 0, MISALIGNED, 3, 0, NULL, 0, NULL, 0, &res);
    printf("env-none %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // cwd が長すぎる（name-too-long）のが、揃っていない fds より先
    kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 0, NULL, 0, 1, long_path, sizeof long_path, MISALIGNED, 0, &res);
    printf("cwd-first %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    // cwd の option の判別子が不正（invalid）なのが、揃っていない fds より先
    kibi_process_spawn(SELF, strlen(SELF), NULL, 0, 0, NULL, 0, 2, NULL, 0, MISALIGNED, 0, &res);
    printf("cwd-disc-first %d\n", res.is_err ? -1 - (int)res.val.err : 0);
    return 0;
}

static int run_mfile(void) {
    int n = 0;
    for (;;) {
        kibi_result_pipe_ends_t p;
        kibi_io_pipe_create(&p);
        if (p.is_err) break;
        n += 2;
    }
    // 1つだけ空いているかもしれないので、dup で埋める
    for (;;) {
        kibi_result_u32_t d;
        kibi_io_fd_dup(3, &d);
        if (d.is_err) break;
        n++;
    }
    const char *child[] = {"kp", "exit", "0"};
    printf("mfile %lld\n", (long long)spawn_self(3, child, NULL, 0));
    return 0;
}

static void print_cwd(const char *label) {
    kibi_process_string_t s;
    kibi_process_get_cwd(&s);
    printf("%s %.*s\n", label, (int)s.len, s.ptr);
}

static int wait_child(int64_t h) {
    if (h < 0) {
        printf("spawn %lld\n", (long long)h);
        return 0;
    }
    if (wait_exit((uint32_t)h) != 0) return 1;
    kibi_process_drop((uint32_t)h);
    return 0;
}

static int run_cwd(void) {
    print_cwd("cwd");
    kibi_result_unit_t u;
    kibi_process_set_cwd("/home", 5, &u);
    printf("set %lld\n", (long long)unit(u));
    print_cwd("cwd");
    const char *child[] = {"kp", "cwd-child"};
    // 相対パスはカーネルの cwd（/home）から。子の cwd は none なら親の今の cwd
    if (wait_child(spawn_full("../bin/kp", 2, child, NULL, 0, NULL)) != 0) return 1;
    if (wait_child(spawn_full(SELF, 2, child, NULL, 0, "/bin")) != 0) return 1;
    // 相対の set-cwd
    kibi_process_set_cwd("../bin", 6, &u);
    printf("set-rel %lld\n", (long long)unit(u));
    print_cwd("cwd");
    return 0;
}

static const char *state_name(uint8_t tag) {
    switch (tag) {
    case KIBI_PROCESS_STATE_STARTING: return "starting";
    case KIBI_PROCESS_STATE_RUNNING: return "running";
    case KIBI_PROCESS_STATE_WAITING: return "waiting";
    case KIBI_PROCESS_STATE_FINISHED: return "finished";
    default: return "bad";
    }
}

// 1行に1つ: p <pid> <parent-pid> <状態> <終わり方> <args の数> <args を | でつないだもの>
static void print_processes(const kibi_process_list_process_info_t *list) {
    for (size_t i = 0; i < list->len; i++) {
        const kibi_process_info_t *p = &list->ptr[i];
        char exit[32] = "-";
        if (p->state.tag == KIBI_PROCESS_STATE_FINISHED) {
            kibi_process_exit_status_t s = p->state.val.finished;
            if (s.tag == KIBI_PROCESS_EXITED) snprintf(exit, sizeof exit, "exited:%u", s.val.exited);
            else if (s.tag == KIBI_PROCESS_KILLED && s.val.killed.is_some) snprintf(exit, sizeof exit, "killed:%u", s.val.killed.sig);
            else if (s.tag == KIBI_PROCESS_KILLED) snprintf(exit, sizeof exit, "killed:none");
            else if (s.tag == KIBI_PROCESS_CRASHED) snprintf(exit, sizeof exit, "crashed");
            else snprintf(exit, sizeof exit, "bad:%u", s.tag);
        }
        printf("p %u %u %s %s %zu ", p->pid, p->parent_pid, state_name(p->state.tag), exit, p->args.len);
        for (size_t j = 0; j < p->args.len; j++) printf("%s%.*s", j ? "|" : "", (int)p->args.ptr[j].len, p->args.ptr[j].ptr);
        printf("\n");
    }
}

// list の中の pid の要素（なければ NULL）
static const kibi_process_info_t *find_process(const kibi_process_list_process_info_t *list, uint32_t pid) {
    for (size_t i = 0; i < list->len; i++) {
        if (list->ptr[i].pid == pid) return &list->ptr[i];
    }
    return NULL;
}

static int run_ps(void) {
    // 眠り続ける子
    const char *sleeper[] = {"kp", "sleep", "60000"};
    int64_t a = spawn_self(3, sleeper, NULL, 0);
    // 終わった子（status を読まないのでゾンビのまま）
    const char *exiter[] = {"kp", "exit", "3"};
    int64_t b = spawn_self(3, exiter, NULL, 0);
    // 親なしで終わる子（ハンドルを閉じる）。親なしになるまで終わらないので、閉じる前に終わって刈り取られることはない
    const char *orphan[] = {"kp", "orphan-quiet"};
    int64_t c = spawn_self(2, orphan, NULL, 0);
    // シバンのスクリプト（argv はインタプリタ・シバンの引数・スクリプトのパス・元の argv[1..]）
    const char *script[] = {"hang.sh", "x"};
    int64_t d = spawn_full("/bin/hang.sh", 2, script, NULL, 0, NULL);
    if (a < 0 || b < 0 || c < 0 || d < 0) {
        printf("spawn %lld %lld %lld %lld\n", (long long)a, (long long)b, (long long)c, (long long)d);
        return 1;
    }
    uint32_t pa = kibi_process_pid((uint32_t)a), pb = kibi_process_pid((uint32_t)b);
    uint32_t pc = kibi_process_pid((uint32_t)c), pd = kibi_process_pid((uint32_t)d);
    printf("children %u %u %u %u\n", pa, pb, pc, pd);
    kibi_process_drop((uint32_t)c);
    if (wait_exit((uint32_t)b) != 0) return 1;
    kibi_process_list_process_info_t list;
    for (int i = 0;; i++) {
        kibi_process_list_processes(&list);
        const kibi_process_info_t *o = find_process(&list, pc);
        if (o != NULL && o->state.tag == KIBI_PROCESS_STATE_FINISHED && o->parent_pid == 0) break;
        if (i > 4000) {
            printf("orphan not finished\n");
            return 1;
        }
        sleep_ms(5);
    }
    print_processes(&list);
    printf("kill %lld %lld\n", (long long)handle_kill((uint32_t)a, KIBI_PROCESS_SIGNAL_KILL),
           (long long)handle_kill((uint32_t)d, KIBI_PROCESS_SIGNAL_KILL));
    if (wait_exit((uint32_t)a) != 0 || wait_exit((uint32_t)d) != 0) return 1;
    // 止めた子は killed:3 と出る（まだ刈り取っていない）
    kibi_process_list_processes(&list);
    print_processes(&list);
    return 0;
}

static int child_ignore(void) {
    kibi_result_unit_t u;
    kibi_process_set_signal_ignored(KIBI_PROCESS_SIGNAL_INTERRUPT, 1, &u);
    if (u.is_err) return 1;
    if (write(1, "r", 1) != 1) return 1;
    for (;;) sleep_ms(1000);
}

static int child_orphan_quiet(void) {
    for (int i = 0; i < 4000; i++) {
        if (kibi_process_parent_pid() == 0) return 0;
        sleep_ms(5);
    }
    return 1;
}

static int child_orphan(void) {
    for (int i = 0; i < 4000; i++) {
        if (kibi_process_parent_pid() == 0) {
            if (write(1, "orphaned", 8) != 8) return 1;
            return 0;
        }
        sleep_ms(5);
    }
    if (write(1, "still", 5) != 5) return 1;
    return 1;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) return 2;
    const char *mode = argv[1];
    // 子
    if (strcmp(mode, "exit") == 0 && argc > 2) __wasi_proc_exit((uint32_t)strtoul(argv[2], NULL, 10));
    if (strcmp(mode, "trap") == 0) __builtin_trap();
    if (strcmp(mode, "sleep") == 0 && argc > 2) {
        sleep_ms(strtol(argv[2], NULL, 10));
        return 0;
    }
    if (strcmp(mode, "ignore-child") == 0) return child_ignore();
    if (strcmp(mode, "orphan") == 0) return child_orphan();
    if (strcmp(mode, "orphan-quiet") == 0) return child_orphan_quiet();
    if (strcmp(mode, "hang") == 0) {
        for (;;) sleep_ms(1000);
    }
    if (strcmp(mode, "cwd-child") == 0) {
        print_cwd("child cwd");
        return 0;
    }
    // 親
    if (strcmp(mode, "run") == 0) return run_run(argc - 2, (const char **)argv + 2);
    if (strcmp(mode, "kill") == 0 && argc > 2) return run_kill(atoi(argv[2]));
    if (strcmp(mode, "ignore") == 0) return run_ignore();
    if (strcmp(mode, "close") == 0 && argc > 2) return run_close(argv[2]);
    if (strcmp(mode, "errors") == 0) return run_errors();
    if (strcmp(mode, "mfile") == 0) return run_mfile();
    if (strcmp(mode, "cwd") == 0) return run_cwd();
    if (strcmp(mode, "realloc-spawn") == 0) {
        realloc_spawn = 1;
        print_cwd("cwd");
        return 0;
    }
    if (strcmp(mode, "nowait") == 0) {
        const char *child[] = {"kp", "exit", "0"};
        printf("nowait %lld\n", (long long)spawn_self(3, child, NULL, 0));
        return 0;
    }
    if (strcmp(mode, "misaligned") == 0 && argc > 2) return run_misaligned(argv[2]);
    if (strcmp(mode, "misaligned-order") == 0) return run_misaligned_order();
    if (strcmp(mode, "nowait-misaligned") == 0) {
        kibi_result_process_t res;
        kibi_process_spawn(SELF, strlen(SELF), MISALIGNED, 0, 0, NULL, 0, 0, NULL, 0, NULL, 0, &res);
        printf("nowait %d\n", res.is_err ? -1 - (int)res.val.err : 0);
        return 0;
    }
    if (strcmp(mode, "ps") == 0) return run_ps();
    if (strcmp(mode, "ps-realloc") == 0) {
        realloc_list = 1;
        kibi_process_list_process_info_t list;
        kibi_process_list_processes(&list);
        printf("inner %ld\n", inner_list_len);
        printf("outer %zu\n", list.len);
        print_processes(&list);
        return 0;
    }
    if (strcmp(mode, "self") == 0) {
        printf("self %u parent %u\n", kibi_process_self_pid(), kibi_process_parent_pid());
        return 0;
    }
    return 2;
}
