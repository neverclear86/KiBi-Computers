// 独自拡張 kibi:device/bus（#77）の p1 の import を確かめる CLI。WasiP1KibiDeviceTest が動かす。
// <a> はテストがつないだデバイスの address（種類 test:dev）。結果は 1 行ずつ「<見出し> <結果>」で書く（describe の形）。
//
//   kd list                 list-devices の一覧
//   kd basic <a>            open・info・methods と、直接のメソッド echo を全種類の値で invoke
//   kd main <a>             メインスレッドのメソッド main-echo を invoke（待ち方ごとに結果が変わる）
//   kd once <a> <method>    <method> を1回 invoke して結果を書く（保存・kill の確かめ）
//   kd start <a>            main-echo を start し、終わる前の take（none）→ poll → take を2回 → drop
//   kd swap <a>             swap（同じ address に別の種類をつなぎ直す）の後の invoke・start・methods・info
//   kd unplug <a>           unplug（外す）の後の invoke・methods
//   kd errors <a>           引数とハンドルの誤り、上限の前後
//   kd misaligned <a>       揃っていない args の ptr（長さ 0）で invoke（トラップになるはず）
//   kd realloc-invoke <a> <method>  cabi_realloc の中で invoke（トラップになるはず）
//   kd realloc-ok <a>       cabi_realloc の中で list-devices・info・methods・start・take（トラップしないはず）
//   kd drop-type <a>        device の fd を [resource-drop]pending-call で閉じる（トラップになるはず）
//   kd info-bad             不正なハンドルで info（トラップになるはず）
//   kd limit <a>            start を 16 個積んだまま invoke、17 個目の start は again、invoke の後に fd が残らない
//   kd mfile <a>            fd 表を満杯にしてから invoke（直接は通り、メインスレッドは mfile）
//   kd devfs <a>            /dev/device/<a> を path_open で開いた fd で invoke
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <wasi/api.h>

#include "kibi_device.h"

static const char *realloc_invoke = NULL; // 立っていたら cabi_realloc の中でこのメソッドを invoke する
static uint32_t realloc_handle = 0;
static int realloc_ok = 0;
static int realloc_ok_done = 0;

static void nested_ok(void);

__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    if (realloc_invoke) {
        const char *m = realloc_invoke;
        realloc_invoke = NULL;
        kibi_device_result_values_t r;
        kibi_device_invoke(realloc_handle, m, strlen(m), NULL, 0, &r);
    }
    if (realloc_ok && !realloc_ok_done) {
        realloc_ok_done = 1;
        nested_ok();
    }
    return aligned_alloc(align < sizeof(void *) ? sizeof(void *) : align, new_size == 0 ? 1 : new_size);
}

static void print_error(const kibi_device_call_error_t *e) {
    switch (e->tag) {
    case KIBI_DEVICE_CALL_ERROR_KERNEL: printf("err kernel %d", (int)e->val.kernel + 1); break; // p1 の errno
    case KIBI_DEVICE_CALL_ERROR_DETACHED: printf("err detached"); break;
    case KIBI_DEVICE_CALL_ERROR_NO_SUCH_METHOD: printf("err no-such-method"); break;
    case KIBI_DEVICE_CALL_ERROR_BAD_ARGUMENTS: printf("err bad-arguments %.*s", (int)e->val.message.len, e->val.message.ptr); break;
    case KIBI_DEVICE_CALL_ERROR_FAILED: printf("err failed %.*s", (int)e->val.message.len, e->val.message.ptr); break;
    default: printf("err ? %u", e->tag);
    }
}

static void print_values(const kibi_device_list_value_t *l) {
    printf("ok %zu", l->len);
    for (size_t i = 0; i < l->len; i++) {
        const kibi_device_value_t *v = &l->ptr[i];
        switch (v->tag) {
        case KIBI_DEVICE_VALUE_NONE: printf(" none"); break;
        case KIBI_DEVICE_VALUE_INTEGER: printf(" i:%lld", (long long)v->val.integer); break;
        case KIBI_DEVICE_VALUE_FLOAT: printf(" f:%g", v->val.floating); break;
        case KIBI_DEVICE_VALUE_BOOLEAN: printf(" b:%u", v->val.boolean); break;
        case KIBI_DEVICE_VALUE_TEXT:
            printf(" t:");
            for (size_t j = 0; j < v->val.text.len; j++) printf("%02x", (unsigned char)v->val.text.ptr[j]);
            break;
        case KIBI_DEVICE_VALUE_BYTES: printf(" y:%zu", v->val.bytes.len); break;
        default: printf(" ?%u", v->tag);
        }
    }
}

static void print_result(const char *label, const kibi_device_result_values_t *r) {
    printf("%s ", label);
    if (r->is_err) print_error(&r->val.err);
    else print_values(&r->val.ok);
    printf("\n");
}

static int64_t open_dev(const char *a) {
    kibi_device_result_handle_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_device_open(a, strlen(a), &r);
    if (r.is_err) {
        printf("open ");
        print_error(&r.val.err);
        printf("\n");
        return -1;
    }
    return r.val.ok;
}

static void invoke(const char *label, uint32_t h, const char *m, const kibi_device_value_t *args, size_t n) {
    kibi_device_result_values_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_device_invoke(h, m, strlen(m), args, n, &r);
    print_result(label, &r);
}

// start の結果のハンドル。失敗なら書いて -1
static int64_t start(const char *label, uint32_t h, const char *m, const kibi_device_value_t *args, size_t n) {
    kibi_device_result_handle_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_device_start(h, m, strlen(m), args, n, &r);
    if (r.is_err) {
        printf("%s ", label);
        print_error(&r.val.err);
        printf("\n");
        return -1;
    }
    return r.val.ok;
}

static void take(const char *label, uint32_t c) {
    kibi_device_option_result_values_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_device_take(c, &r);
    if (!r.is_some) printf("%s none\n", label);
    else print_result(label, &r.val);
}

static kibi_device_value_t integer(int64_t v) {
    kibi_device_value_t x;
    memset(&x, 0, sizeof x);
    x.tag = KIBI_DEVICE_VALUE_INTEGER;
    x.val.integer = v;
    return x;
}

static kibi_device_value_t text(const char *s, size_t n) {
    kibi_device_value_t x;
    memset(&x, 0, sizeof x);
    x.tag = KIBI_DEVICE_VALUE_TEXT;
    x.val.text.ptr = s;
    x.val.text.len = n;
    return x;
}

static kibi_device_value_t bytes(const uint8_t *p, size_t n) {
    kibi_device_value_t x;
    memset(&x, 0, sizeof x);
    x.tag = KIBI_DEVICE_VALUE_BYTES;
    x.val.bytes.ptr = p;
    x.val.bytes.len = n;
    return x;
}

static int run_list(void) {
    kibi_device_list_info_t l;
    kibi_device_list_devices(&l);
    printf("count %zu\n", l.len);
    for (size_t i = 0; i < l.len; i++) {
        printf("dev %.*s %.*s\n", (int)l.ptr[i].address.len, l.ptr[i].address.ptr, (int)l.ptr[i].kind.len, l.ptr[i].kind.ptr);
    }
    return 0;
}

static void print_info(const char *label, uint32_t h) {
    kibi_device_info_t info;
    kibi_device_info(h, &info);
    printf("%s %.*s %.*s\n", label, (int)info.address.len, info.address.ptr, (int)info.kind.len, info.kind.ptr);
}

static void print_methods(const char *label, uint32_t h) {
    kibi_device_list_method_info_t l;
    kibi_device_methods(h, &l);
    printf("%s %zu", label, l.len);
    for (size_t i = 0; i < l.len; i++) printf(" %.*s:%u", (int)l.ptr[i].name.len, l.ptr[i].name.ptr, l.ptr[i].main_thread);
    printf("\n");
}

static int run_basic(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    print_info("info", (uint32_t)h);
    print_methods("methods", (uint32_t)h);
    static const uint8_t raw[3] = {1, 2, 3};
    kibi_device_value_t args[7];
    memset(args, 0, sizeof args);
    args[0].tag = KIBI_DEVICE_VALUE_NONE;
    args[1] = integer(-5);
    args[2].tag = KIBI_DEVICE_VALUE_FLOAT;
    args[2].val.floating = 1.5;
    args[3].tag = KIBI_DEVICE_VALUE_BOOLEAN;
    args[3].val.boolean = 7; // 0 以外は true
    args[4] = text("hi", 2);
    args[5] = bytes(raw, 3);
    args[6].tag = KIBI_DEVICE_VALUE_BOOLEAN;
    args[6].val.boolean = 0;
    invoke("echo", (uint32_t)h, "echo", args, 7);
    invoke("empty", (uint32_t)h, "echo", NULL, 0);
    invoke("surrogate", (uint32_t)h, "surrogate", NULL, 0);
    invoke("fail", (uint32_t)h, "fail", NULL, 0);
    invoke("bad", (uint32_t)h, "needs-int", NULL, 0);
    invoke("nsm", (uint32_t)h, "nothing", NULL, 0);
    kibi_device_drop((uint32_t)h);
    printf("dropped\n");
    return 0;
}

static int run_main(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    kibi_device_value_t v = integer(7);
    invoke("main", (uint32_t)h, "main-echo", &v, 1);
    invoke("direct", (uint32_t)h, "echo", &v, 1);
    return 0;
}

static int run_once(const char *a, const char *m) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    printf("calling\n");
    fflush(stdout);
    invoke("once", (uint32_t)h, m, NULL, 0);
    return 0;
}

static int run_start(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    kibi_device_value_t v = integer(9);
    int64_t c = start("start", (uint32_t)h, "main-echo", &v, 1);
    if (c < 0) return 1;
    take("take0", (uint32_t)c);
    struct pollfd p = {(int)c, POLLIN, 0};
    int n = poll(&p, 1, -1);
    printf("poll %d %d\n", n, (p.revents & POLLIN) != 0);
    take("take1", (uint32_t)c);
    take("take2", (uint32_t)c);
    kibi_device_pending_call_drop((uint32_t)c);
    // 直接のメソッドの start は、返る前に終わっている
    int64_t d = start("start-direct", (uint32_t)h, "echo", &v, 1);
    if (d >= 0) {
        take("take-direct", (uint32_t)d);
        kibi_device_pending_call_drop((uint32_t)d);
    }
    printf("done\n");
    return 0;
}

static int run_swap(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    invoke("swap", (uint32_t)h, "swap", NULL, 0);
    invoke("after", (uint32_t)h, "echo", NULL, 0);
    int64_t c = start("start", (uint32_t)h, "echo", NULL, 0);
    if (c >= 0) take("take", (uint32_t)c);
    print_methods("methods", (uint32_t)h);
    print_info("info", (uint32_t)h);
    return 0;
}

static int run_unplug(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    invoke("unplug", (uint32_t)h, "unplug", NULL, 0);
    invoke("after", (uint32_t)h, "main-echo", NULL, 0);
    print_methods("methods", (uint32_t)h);
    open_dev(a);
    return 0;
}

static char big[64 * 1024 + 1];

static int run_errors(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    char name[66];
    memset(name, 'm', sizeof name);
    // メソッド名: 64 バイトは通り（ないメソッド）、65 バイトは name-too-long
    kibi_device_result_values_t r;
    kibi_device_invoke((uint32_t)h, name, 64, NULL, 0, &r);
    print_result("name64", &r);
    kibi_device_invoke((uint32_t)h, name, 65, NULL, 0, &r);
    print_result("name65", &r);
    // 範囲外の名前の長さ（読まずに name-too-long）
    kibi_device_invoke((uint32_t)h, name, 0x7fffffff, NULL, 0, &r);
    print_result("name-huge", &r);
    static const char bad_utf8[2] = {(char)0xff, 'x'};
    kibi_device_invoke((uint32_t)h, bad_utf8, 2, NULL, 0, &r);
    print_result("name-utf8", &r);
    kibi_device_invoke((uint32_t)h, (const char *)0x7ffffff0, 8, NULL, 0, &r);
    print_result("name-fault", &r);
    // 引数の数: 32 は通り、33 は too-big
    static kibi_device_value_t many[33];
    for (int i = 0; i < 33; i++) many[i] = integer(i);
    kibi_device_invoke((uint32_t)h, "echo", 4, many, 32, &r);
    printf("args32 %s %zu\n", r.is_err ? "err" : "ok", r.is_err ? 0 : r.val.ok.len);
    kibi_device_invoke((uint32_t)h, "echo", 4, many, 33, &r);
    print_result("args33", &r);
    // text・bytes の合計: 64 KiB は通り、1 バイト超えたら too-big（中身は読まない: ptr が範囲外でも too-big）
    memset(big, 'x', sizeof big);
    kibi_device_value_t two[2];
    two[0] = text(big, 32 * 1024);
    two[1] = bytes((const uint8_t *)big, 32 * 1024);
    kibi_device_invoke((uint32_t)h, "count", 5, two, 2, &r);
    print_result("payload64k", &r);
    two[1] = bytes((const uint8_t *)0x7ffffff0, 32 * 1024 + 1);
    kibi_device_invoke((uint32_t)h, "count", 5, two, 2, &r);
    print_result("payload-over", &r);
    // 判別子の誤り
    kibi_device_value_t odd = integer(1);
    odd.tag = 6;
    kibi_device_invoke((uint32_t)h, "echo", 4, &odd, 1, &r);
    print_result("tag", &r);
    // text の UTF-8 の誤り・範囲外
    kibi_device_value_t t = text(bad_utf8, 2);
    kibi_device_invoke((uint32_t)h, "echo", 4, &t, 1, &r);
    print_result("text-utf8", &r);
    t = text((const char *)0x7ffffff0, 32);
    kibi_device_invoke((uint32_t)h, "echo", 4, &t, 1, &r);
    print_result("text-fault", &r);
    // 引数の配列が範囲外
    kibi_device_invoke((uint32_t)h, "echo", 4, (const kibi_device_value_t *)0x7ffffff0, 2, &r);
    print_result("args-fault", &r);
    // method の誤りは args の誤りより先
    kibi_device_invoke((uint32_t)h, name, 65, many, 33, &r);
    print_result("order", &r);
    // ハンドルの誤り: invoke・start は bad-descriptor、take は some(err(bad-descriptor))。引数の誤りが先
    kibi_device_invoke(999, "echo", 4, NULL, 0, &r);
    print_result("invoke-badf", &r);
    kibi_device_invoke(999, name, 65, NULL, 0, &r);
    print_result("invoke-badf-order", &r);
    start("start-badf", 999, "echo", NULL, 0);
    take("take-badf", 999);
    // device の fd で take（pending-call でない）
    take("take-device", (uint32_t)h);
    // start の引数の誤り
    kibi_device_result_handle_t sr;
    kibi_device_start((uint32_t)h, name, 65, NULL, 0, &sr);
    printf("start-name ");
    if (sr.is_err) print_error(&sr.val.err);
    printf("\n");
    // open: 36 バイトでない・大文字・つないでいない address は detached
    kibi_device_result_handle_t o;
    kibi_device_open(a, 35, &o);
    printf("open35 %s\n", o.is_err && o.val.err.tag == KIBI_DEVICE_CALL_ERROR_DETACHED ? "detached" : "?");
    kibi_device_open((const char *)0x7ffffff0, 0x7fffffff, &o);
    printf("open-huge %s\n", o.is_err && o.val.err.tag == KIBI_DEVICE_CALL_ERROR_DETACHED ? "detached" : "?");
    char upper[37];
    memcpy(upper, a, 36);
    upper[36] = 0;
    for (int i = 0; i < 36; i++) if (upper[i] >= 'a' && upper[i] <= 'f') upper[i] -= 32;
    kibi_device_open(upper, 36, &o);
    printf("open-upper %s\n", o.is_err && o.val.err.tag == KIBI_DEVICE_CALL_ERROR_DETACHED ? "detached" : "?");
    kibi_device_open("ffffffff-ffff-4fff-bfff-ffffffffffff", 36, &o);
    printf("open-none %s\n", o.is_err && o.val.err.tag == KIBI_DEVICE_CALL_ERROR_DETACHED ? "detached" : "?");
    kibi_device_open((const char *)0x7ffffff0, 36, &o);
    printf("open-fault ");
    if (o.is_err) print_error(&o.val.err);
    printf("\n");
    return 0;
}

static int run_misaligned(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    static uint8_t buf[64] __attribute__((aligned(8)));
    printf("invoking\n");
    fflush(stdout);
    kibi_device_result_values_t r;
    kibi_device_invoke((uint32_t)h, "echo", 4, (const kibi_device_value_t *)(buf + 4), 0, &r);
    printf("returned\n");
    return 0;
}

static int run_realloc_invoke(const char *a, const char *m) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    realloc_handle = (uint32_t)h;
    realloc_invoke = m;
    kibi_device_list_info_t l;
    kibi_device_list_devices(&l);
    printf("returned\n");
    return 0;
}

static void nested_ok(void) {
    // 書き出し（fd_write）は止まりうる import なので、cabi_realloc の中では呼ばない
    kibi_device_list_info_t l;
    kibi_device_list_devices(&l);
    kibi_device_info_t info;
    kibi_device_info(realloc_handle, &info);
    kibi_device_list_method_info_t ms;
    kibi_device_methods(realloc_handle, &ms);
    kibi_device_result_handle_t s;
    kibi_device_start(realloc_handle, "echo", 4, NULL, 0, &s);
    if (!s.is_err) {
        kibi_device_option_result_values_t t;
        kibi_device_take(s.val.ok, &t);
        kibi_device_pending_call_drop(s.val.ok);
    }
}

static int run_realloc_ok(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    realloc_handle = (uint32_t)h;
    realloc_ok = 1;
    kibi_device_list_info_t l;
    kibi_device_list_devices(&l);
    printf("inner-done %d\n", realloc_ok_done);
    return 0;
}

static int run_drop_type(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    printf("dropping\n");
    fflush(stdout);
    kibi_device_pending_call_drop((uint32_t)h);
    printf("returned\n");
    return 0;
}

static int run_info_bad(void) {
    printf("calling\n");
    fflush(stdout);
    kibi_device_info_t info;
    kibi_device_info(999, &info);
    printf("returned\n");
    return 0;
}

static int run_limit(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    // 基準の fd 番号（いちばん小さい空き）
    int64_t first = start("probe", (uint32_t)h, "echo", NULL, 0);
    printf("probe %lld\n", (long long)first);
    kibi_device_pending_call_drop((uint32_t)first);
    int64_t calls[16];
    for (int i = 0; i < 16; i++) calls[i] = start("start", (uint32_t)h, "defer", NULL, 0);
    start("start17", (uint32_t)h, "defer", NULL, 0);
    kibi_device_value_t v = integer(3);
    invoke("main", (uint32_t)h, "main-echo", &v, 1);
    for (int i = 0; i < 16; i++) kibi_device_pending_call_drop((uint32_t)calls[i]);
    invoke("main2", (uint32_t)h, "main-echo", &v, 1);
    int64_t again = start("probe2", (uint32_t)h, "echo", NULL, 0);
    printf("probe2 %lld\n", (long long)again);
    return 0;
}

static int run_mfile(const char *a) {
    int64_t h = open_dev(a);
    if (h < 0) return 1;
    // fd 表を満杯にする
    int n = 0;
    while (open_dev(a) >= 0) n++;
    printf("filled %d\n", n > 0);
    kibi_device_value_t v = integer(1);
    invoke("direct", (uint32_t)h, "echo", &v, 1);
    invoke("main", (uint32_t)h, "main-echo", &v, 1);
    return 0;
}

static int run_devfs(const char *a) {
    char path[64];
    snprintf(path, sizeof path, "/dev/device/%s", a);
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        printf("open-path %d\n", errno);
        return 1;
    }
    kibi_device_value_t v = integer(4);
    invoke("devfs", (uint32_t)fd, "main-echo", &v, 1);
    print_info("info", (uint32_t)fd);
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc < 2) return 2;
    const char *cmd = argv[1];
    const char *a = argc > 2 ? argv[2] : "";
    if (!strcmp(cmd, "list")) return run_list();
    if (!strcmp(cmd, "basic")) return run_basic(a);
    if (!strcmp(cmd, "main")) return run_main(a);
    if (!strcmp(cmd, "once") && argc > 3) return run_once(a, argv[3]);
    if (!strcmp(cmd, "start")) return run_start(a);
    if (!strcmp(cmd, "swap")) return run_swap(a);
    if (!strcmp(cmd, "unplug")) return run_unplug(a);
    if (!strcmp(cmd, "errors")) return run_errors(a);
    if (!strcmp(cmd, "misaligned")) return run_misaligned(a);
    if (!strcmp(cmd, "realloc-invoke") && argc > 3) return run_realloc_invoke(a, argv[3]);
    if (!strcmp(cmd, "realloc-ok")) return run_realloc_ok(a);
    if (!strcmp(cmd, "drop-type")) return run_drop_type(a);
    if (!strcmp(cmd, "info-bad")) return run_info_bad();
    if (!strcmp(cmd, "limit")) return run_limit(a);
    if (!strcmp(cmd, "mfile")) return run_mfile(a);
    if (!strcmp(cmd, "devfs")) return run_devfs(a);
    return 2;
}
