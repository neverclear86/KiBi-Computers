// テスト用の /sbin/init（#307 の仮の結線）。同梱イメージ testboot に入れる。
// 標準OS（#98）ができたら置き換える。
//
// コンソールから1行ずつ読み、"echo: <行>" と返す。"exit" で終わる（init が終わるとマシンは止まる）。
// 入力の行編集とエコーは TTY（#60）が行う。
#include <stdio.h>
#include <string.h>

int main(void) {
    char line[512];
    printf("KiBi test init\n");
    printf("type a line and press Enter (exit to stop)\n");
    for (;;) {
        printf("> ");
        fflush(stdout);
        if (fgets(line, sizeof line, stdin) == NULL) break;
        size_t n = strlen(line);
        if (n > 0 && line[n - 1] == '\n') line[--n] = '\0';
        if (strcmp(line, "exit") == 0) break;
        printf("echo: %s\n", line);
    }
    printf("bye\n");
    fflush(stdout);
    return 0;
}
