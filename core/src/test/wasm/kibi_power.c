// 独自拡張 kibi:computer/power（#306）の p1 の import を確かめる CLI。WasiP1PowerTest が動かす。
//
//   kpw shutdown          "before" を書いて shutdown。戻ったら "after" を書く（書かれないはず）
//   kpw reboot            "before" を書いて reboot。断られたら "refused <is_err> <err>" を書く
//   kpw reboot-oob        書き先を線形メモリの外にして reboot（受け付ければ戻らず、断られたらトラップのはず）
//   kpw realloc-shutdown  get-cwd の cabi_realloc の中で shutdown
//   kpw realloc-reboot    get-cwd の cabi_realloc の中で reboot
//   kpw reboot-once       /kpw-mode を "hold" に書き換えてから reboot（次の起動では hold になる）
//   kpw hold              止められるまで眠る
//
// 引数がなければ（/sbin/init として起動したとき）、/kpw-mode の中身をモードにする（ComputerCaseTest が使う）
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <unistd.h>

#include "kibi_power.h"
#include "kibi_process.h"

static int realloc_power = 0; // 1: shutdown、2: reboot

static void say(const char *s) {
    // 書き出しは fd_write を直接（stdio の溜めに残さない）
    write(1, s, strlen(s));
}

__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    if (realloc_power == 1) kibi_power_shutdown();
    if (realloc_power == 2) {
        kibi_result_unit_t res;
        kibi_power_reboot(&res);
    }
    return aligned_alloc(align < sizeof(void *) ? sizeof(void *) : align, new_size == 0 ? 1 : new_size);
}

static void reboot_and_report(kibi_result_unit_t *res) {
    kibi_power_reboot(res);
    char buf[64];
    char *p = buf;
    memcpy(p, "refused ", 8);
    p += 8;
    *p++ = (char)('0' + res->is_err);
    *p++ = ' ';
    unsigned e = res->err;
    if (e >= 10) *p++ = (char)('0' + e / 10);
    *p++ = (char)('0' + e % 10);
    *p++ = '\n';
    write(1, buf, (size_t)(p - buf));
}

#define MODE_FILE "/kpw-mode"

// /kpw-mode の中身（末尾の改行は除く）。読めなければ空
static const char *mode_from_file(void) {
    static char buf[32];
    int fd = open(MODE_FILE, O_RDONLY);
    if (fd < 0) return "";
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n < 0) n = 0;
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) n--;
    buf[n] = 0;
    return buf;
}

int main(int argc, char **argv) {
    const char *mode = argc >= 2 ? argv[1] : mode_from_file();
    say("before\n");
    if (strcmp(mode, "hold") == 0) {
        for (;;) {
            struct timespec ts = {1, 0};
            nanosleep(&ts, NULL);
        }
    }
    if (strcmp(mode, "reboot-once") == 0) {
        int fd = open(MODE_FILE, O_WRONLY | O_TRUNC);
        if (fd < 0 || write(fd, "hold", 4) != 4) return 3;
        close(fd);
        mode = "reboot";
    }
    if (strcmp(mode, "shutdown") == 0) {
        kibi_power_shutdown();
        // noreturn なので、ここはコンパイラが消してよい
    } else if (strcmp(mode, "reboot") == 0) {
        kibi_result_unit_t res;
        memset(&res, 0xAA, sizeof res);
        reboot_and_report(&res);
    } else if (strcmp(mode, "reboot-oob") == 0) {
        // 線形メモリの外（4 GiB の手前）
        reboot_and_report((kibi_result_unit_t *)(uintptr_t)0xFFFFFFF0u);
    } else if (strcmp(mode, "realloc-shutdown") == 0 || strcmp(mode, "realloc-reboot") == 0) {
        realloc_power = strcmp(mode, "realloc-shutdown") == 0 ? 1 : 2;
        kibi_process_string_t s;
        kibi_process_get_cwd(&s);
        realloc_power = 0;
    } else {
        return 2;
    }
    say("after\n");
    return 0;
}
