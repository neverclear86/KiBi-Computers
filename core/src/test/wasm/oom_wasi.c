// RAM を書き換えた 4 KiB の区画で数える（#407）ことを、カーネルの上で確かめるプログラム。
// 使い方:
//   oom-wasi write <KiB>     : <KiB> を malloc して書く（RAM を超えればトラップ）
//   oom-wasi grow <終了コード> : 1 ページずつ memory.grow し、-1 になったら（RAM の上限で断られたら）その終了コードで終わる
//   oom-wasi grow-trap       : 同じく -1 になったら、abort でトラップする
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void grow_until_refused(void) {
    while (__builtin_wasm_memory_grow(0, 1) != (unsigned long)-1) {
    }
}

int main(int argc, char **argv) {
    if (argc < 2) return 64;
    if (strcmp(argv[1], "write") == 0 && argc >= 3) {
        size_t n = (size_t)atoi(argv[2]) * 1024;
        unsigned char *p = malloc(n);
        if (p == NULL) {
            printf("malloc returned NULL\n");
            return 70;
        }
        memset(p, 1, n);
        printf("wrote %u\n", (unsigned)p[n - 1]);
        return 0;
    }
    if (strcmp(argv[1], "grow") == 0 && argc >= 3) {
        // 断られた後は RAM がほとんど残らないので、先に出力と終了コードを用意しておき、後では何も書かない
        int code = atoi(argv[2]);
        printf("growing\n");
        fflush(stdout);
        grow_until_refused();
        return code;
    }
    if (strcmp(argv[1], "grow-trap") == 0) {
        printf("growing\n");
        fflush(stdout);
        grow_until_refused();
        abort();
    }
    return 64;
}
