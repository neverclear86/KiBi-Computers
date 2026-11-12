// 独自拡張 kibi:fs/mounts（#56）の p1 の import を確かめる CLI。KibiFsTest が動かす。
// 引数の操作を順に行い、1つごとに "<操作> <結果>" を1行出す（結果は 0 が成功、ほかは p1 の errno = error-code の判別子 + 1）。
//
//   mount SRC PT RO     kibi:fs/mounts.mount（RO は 0 か 1）
//   unmount PT          kibi:fs/mounts.unmount
//   write PATH TEXT     PATH を作って TEXT を書く（結果は errno）
//   oob                 範囲外の文字列で mount（fault の判別子 + 1 が出る）
//   long                4,097 バイトの point で mount（name-too-long）、4,097 バイトの source で mount（invalid）
//   badutf8             UTF-8 でない source で mount（invalid）
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "kibi_fs.h"

static int result_of(const kibi_result_unit_t *r) {
    return r->is_err ? (int)r->err + 1 : 0;
}

static int do_mount(const char *src, size_t src_len, const char *pt, size_t pt_len, int ro) {
    kibi_result_unit_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_fs_mount(src, src_len, pt, pt_len, ro, &r);
    return result_of(&r);
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        const char *op = argv[i];
        if (strcmp(op, "mount") == 0 && i + 3 < argc) {
            const char *src = argv[i + 1], *pt = argv[i + 2];
            int ro = atoi(argv[i + 3]);
            printf("mount %d\n", do_mount(src, strlen(src), pt, strlen(pt), ro));
            i += 3;
        } else if (strcmp(op, "unmount") == 0 && i + 1 < argc) {
            kibi_result_unit_t r;
            memset(&r, 0xAA, sizeof r);
            kibi_fs_unmount(argv[i + 1], strlen(argv[i + 1]), &r);
            printf("unmount %d\n", result_of(&r));
            i += 1;
        } else if (strcmp(op, "write") == 0 && i + 2 < argc) {
            int fd = open(argv[i + 1], O_WRONLY | O_CREAT | O_TRUNC, 0644);
            int e = 0;
            if (fd < 0) {
                e = errno;
            } else {
                if (write(fd, argv[i + 2], strlen(argv[i + 2])) < 0) e = errno;
                close(fd);
            }
            printf("write %d\n", e);
            i += 2;
        } else if (strcmp(op, "oob") == 0) {
            printf("oob %d\n", do_mount((const char *)0x7ffffff0, 64, "/mnt/x", 6, 0));
        } else if (strcmp(op, "long") == 0) {
            static char big[4097];
            memset(big, 'a', sizeof big);
            big[0] = '/';
            printf("longpoint %d\n", do_mount("disk:x", 6, big, sizeof big, 0));
            printf("longsource %d\n", do_mount(big, sizeof big, "/mnt/x", 6, 0));
        } else if (strcmp(op, "badutf8") == 0) {
            static const char bad[] = {'d', 'i', 's', 'k', ':', (char)0xff};
            printf("badutf8 %d\n", do_mount(bad, sizeof bad, "/mnt/x", 6, 0));
        } else {
            printf("unknown %s\n", op);
            return 2;
        }
        fflush(stdout);
    }
    return 0;
}
