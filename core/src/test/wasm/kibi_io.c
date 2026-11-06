// 独自拡張 kibi:io/fd・kibi:io/pipe（#75）の p1 の import を確かめる CLI。WasiP1KibiIoTest が動かす。
//
//   kibi-io rw         create → 書く → 読む。dup した fd を閉じても元の fd が使える
//   kibi-io nonblock   NONBLOCK を付けた書く端をいっぱいまで書くと EAGAIN
//   kibi-io eof        書く端を閉じると残りの後に 0 バイト、読む端を閉じると書く側は EPIPE
//   kibi-io redirect   dup-to(書く端, 1) の後の printf がパイプに入り、読む端から読める
//   kibi-io poll       空の読む端を poll で待つ（ホストがパイプに書くと起きる）
//   kibi-io errors     bad-descriptor と mfile
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "kibi_io.h"

// 失敗なら -1 - error-code
static int64_t pipe_create(uint32_t *r, uint32_t *w) {
    kibi_result_pipe_ends_t res;
    memset(&res, 0xAA, sizeof res);
    kibi_io_pipe_create(&res);
    if (res.is_err) return -1 - (int64_t)res.val.err;
    *r = res.val.ok.read;
    *w = res.val.ok.write;
    return 0;
}

static int64_t fd_dup(uint32_t fd) {
    kibi_result_u32_t res;
    memset(&res, 0xAA, sizeof res);
    kibi_io_fd_dup(fd, &res);
    return res.is_err ? -1 - (int64_t)res.val.err : (int64_t)res.val.ok;
}

static int64_t fd_dup_to(uint32_t fd, uint32_t to) {
    kibi_result_unit_t res;
    memset(&res, 0xAA, sizeof res);
    kibi_io_fd_dup_to(fd, to, &res);
    return res.is_err ? -1 - (int64_t)res.err : (res.err == 0 ? 0 : -1000);
}

static int run_rw(void) {
    uint32_t r, w;
    if (pipe_create(&r, &w) != 0) return 1;
    printf("ends %u %u\n", r, w);
    if (write(w, "hello", 5) != 5) return 1;
    char buf[16] = {0};
    ssize_t n = read(r, buf, sizeof buf - 1);
    printf("read %zd %s\n", n, buf);
    // 向きの違う使い方は EBADF
    errno = 0;
    printf("wrong %zd %d %zd %d\n", read(w, buf, 1), errno == EBADF, write(r, "x", 1), errno == EBADF);
    int64_t d = fd_dup(r);
    printf("dup %lld\n", (long long)d);
    if (d < 0 || close((int)d) != 0) return 1;
    if (write(w, "again", 5) != 5) return 1;
    memset(buf, 0, sizeof buf);
    n = read(r, buf, sizeof buf - 1);
    printf("after close dup %zd %s\n", n, buf);
    return 0;
}

static int run_nonblock(void) {
    uint32_t r, w;
    if (pipe_create(&r, &w) != 0) return 1;
    int fl = fcntl(w, F_GETFL);
    if (fl < 0 || fcntl(w, F_SETFL, fl | O_NONBLOCK) != 0) return 1;
    static char chunk[1000];
    memset(chunk, 'z', sizeof chunk);
    long total = 0;
    for (int i = 0; i < 100; i++) {
        ssize_t n = write(w, chunk, sizeof chunk);
        if (n < 0) {
            printf("full %ld %d\n", total, errno == EAGAIN);
            return 0;
        }
        total += n;
    }
    printf("never full %ld\n", total);
    return 0;
}

static int run_eof(void) {
    uint32_t r, w;
    if (pipe_create(&r, &w) != 0) return 1;
    if (write(w, "abc", 3) != 3) return 1;
    if (close(w) != 0) return 1;
    char buf[8] = {0};
    ssize_t a = read(r, buf, sizeof buf - 1);
    ssize_t b = read(r, buf + 3, 1);
    printf("eof %zd %zd %s\n", a, b, buf);
    if (close(r) != 0) return 1;
    if (pipe_create(&r, &w) != 0) return 1;
    if (close(r) != 0) return 1;
    errno = 0;
    ssize_t c = write(w, "x", 1);
    printf("epipe %zd %d\n", c, errno == EPIPE);
    return 0;
}

static int run_redirect(void) {
    uint32_t r, w;
    if (pipe_create(&r, &w) != 0) return 1;
    int64_t saved = fd_dup(1);
    if (saved < 0) return 1;
    if (fd_dup_to(w, 1) != 0) return 1;
    printf("redirected\n");
    if (fd_dup_to((uint32_t)saved, 1) != 0) return 1;
    char buf[32] = {0};
    ssize_t n = read(r, buf, sizeof buf - 1);
    printf("from pipe %zd %s", n, buf);
    return 0;
}

static int run_poll(void) {
    uint32_t r, w;
    if (pipe_create(&r, &w) != 0) return 1;
    printf("ends %u %u\n", r, w);
    struct pollfd pfd = {.fd = (int)r, .events = POLLIN};
    if (poll(&pfd, 1, -1) != 1 || !(pfd.revents & POLLIN)) {
        printf("poll failed\n");
        return 1;
    }
    char buf[32] = {0};
    ssize_t n = read(r, buf, sizeof buf - 1);
    printf("woke %zd %s\n", n, buf);
    return 0;
}

static int run_errors(void) {
    // bad-descriptor は判別子 7（-8）
    printf("badf %lld %lld %lld %lld %lld\n", (long long)fd_dup(200), (long long)fd_dup(0x80000000u),
           (long long)fd_dup_to(200, 5), (long long)fd_dup_to(1, 0x80000000u), (long long)fd_dup_to(1, 100000));
    printf("same %lld %lld\n", (long long)fd_dup_to(1, 1), (long long)fd_dup_to(200, 200));
    // 閉じた fd を dup-to しても、to は閉じない
    printf("kept %lld %d\n", (long long)fd_dup_to(200, 1), fcntl(1, F_GETFL) >= 0);
    // 表を埋める。mfile は判別子 32（-33）
    int64_t last = 0;
    int count = 0;
    while ((last = fd_dup(1)) >= 0) count++;
    uint32_t r, w;
    int64_t full = pipe_create(&r, &w);
    close(3);
    int64_t one = pipe_create(&r, &w);
    close(4);
    int64_t two = pipe_create(&r, &w);
    printf("mfile %lld %lld %lld %lld ends %u %u\n", (long long)last, (long long)full, (long long)one, (long long)two, r, w);
    return count > 0 ? 0 : 1;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) return 2;
    const char *mode = argv[1];
    if (strcmp(mode, "rw") == 0) return run_rw();
    if (strcmp(mode, "nonblock") == 0) return run_nonblock();
    if (strcmp(mode, "eof") == 0) return run_eof();
    if (strcmp(mode, "redirect") == 0) return run_redirect();
    if (strcmp(mode, "poll") == 0) return run_poll();
    if (strcmp(mode, "errors") == 0) return run_errors();
    return 2;
}
