// 独自拡張 kibi:fs/mounts（#56）の p1 の import を確かめる CLI。KibiFsTest が動かす。
// 引数の操作を順に行い、1つごとに "<操作> <結果>" を1行出す（結果は 0 が成功、ほかは p1 の errno = error-code の判別子 + 1）。
//
//   mount SRC PT RO     kibi:fs/mounts.mount（RO は 0 か 1）
//   unmount PT          kibi:fs/mounts.unmount
//   write PATH TEXT     PATH を作って TEXT を書く（結果は errno）
//   oob                 範囲外の文字列で mount（fault の判別子 + 1 が出る）
//   long                4,097 バイトの point で mount（name-too-long）、4,097 バイトの source で mount（invalid）
//   badutf8             UTF-8 でない source で mount（invalid）
//   mounts              list-mounts を "m <point> <source> <kind> <ro> <id> <total> <used> <avail> <total-entries|-> <used-entries>" で出す
//                       （usage が none なら数の代わりに "none"）。最後に "mounts <件数>"
//   disks               list-disks を "d <source> <capacity> <max-entries> <mount-point|->" で出す。最後に "disks <件数>"
//   mounts-realloc      list-mounts の cabi_realloc の中で、もう一度 list-mounts を呼ぶ（トラップしないはず。"mounts-realloc <外> <内>"）
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "kibi_fs.h"

static int realloc_list = 0;
static long inner_list_len = -1;

__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    if (realloc_list) {
        // 入れ子は1段だけ。書き出し（fd_write）は cabi_realloc の中では呼ばない
        realloc_list = 0;
        kibi_fs_list_mount_info_t inner;
        kibi_fs_list_mounts(&inner);
        inner_list_len = (long)inner.len;
    }
    return aligned_alloc(align < sizeof(void *) ? sizeof(void *) : align, new_size == 0 ? 1 : new_size);
}

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
        } else if (strcmp(op, "mounts") == 0) {
            kibi_fs_list_mount_info_t list;
            memset(&list, 0xAA, sizeof list);
            kibi_fs_list_mounts(&list);
            for (size_t j = 0; j < list.len; j++) {
                const kibi_fs_mount_info_t *e = &list.ptr[j];
                printf("m %.*s %.*s %u %u %u", (int)e->point.len, e->point.ptr, (int)e->source.len, e->source.ptr,
                       (unsigned)e->kind, (unsigned)e->read_only, (unsigned)e->id);
                if (e->usage_is_some) {
                    printf(" %llu %llu %llu", (unsigned long long)e->usage.total_bytes, (unsigned long long)e->usage.used_bytes,
                           (unsigned long long)e->usage.available_bytes);
                    if (e->usage.total_entries_is_some) {
                        printf(" %u", (unsigned)e->usage.total_entries);
                    } else {
                        printf(" -");
                    }
                    printf(" %u\n", (unsigned)e->usage.used_entries);
                } else {
                    printf(" none\n");
                }
            }
            printf("mounts %zu\n", list.len);
        } else if (strcmp(op, "disks") == 0) {
            kibi_fs_list_disk_info_t list;
            memset(&list, 0xAA, sizeof list);
            kibi_fs_list_disks(&list);
            for (size_t j = 0; j < list.len; j++) {
                const kibi_fs_disk_info_t *e = &list.ptr[j];
                printf("d %.*s %llu %u ", (int)e->source.len, e->source.ptr, (unsigned long long)e->capacity_bytes, (unsigned)e->max_entries);
                if (e->mount_point_is_some) {
                    printf("%.*s\n", (int)e->mount_point.len, e->mount_point.ptr);
                } else {
                    printf("-\n");
                }
            }
            printf("disks %zu\n", list.len);
        } else if (strcmp(op, "mounts-realloc") == 0) {
            realloc_list = 1;
            kibi_fs_list_mount_info_t outer;
            kibi_fs_list_mounts(&outer);
            printf("mounts-realloc %zu %ld\n", outer.len, inner_list_len);
        } else {
            printf("unknown %s\n", op);
            return 2;
        }
        fflush(stdout);
    }
    return 0;
}
