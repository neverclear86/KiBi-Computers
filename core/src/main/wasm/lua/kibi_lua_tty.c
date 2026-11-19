/*
 * キー待ちの間の端末のモード（#798。ADR 0798）。
 *
 * メインスレッドの os.pullEvent・pullEventRaw が nil・"key"・"key_up" を待つとき、標準入力が端末で、モードが既定（cooked）なら、
 * echo と canonical を切る（signals と nl-to-crnl は残す）。待つ間に打ったキーはエコーされず、TTY の入力に溜まったものは、
 * 待つ前に毎回、待たずに読み捨てる。
 *
 * - 一度切ったら、標準入力を読むとき（io.read などの g_read、REPL の lua_readline、引数なしの dofile・loadfile の getc）か、終わるとき（atexit）まで戻さない。
 *   キーを受けてすぐ戻すと、英字のキーは押下（KeyDown）の後から文字（Text）が届くので、その文字が cooked の TTY に入って漏れる
 * - 戻すときは、切ったままで読み捨ててから元のモードに戻し、戻したことで残った塊があればもう一度読み捨てる
 *   （先に戻すと、待ちの列の続きが canonical の編集中の行に入り、次の io.read の行に混ざる）
 * - 戻すのは、今のモードがまだ自分の切ったモードのときだけ（ほかのプロセスが変えたものを上書きしない）
 * - 読み捨ては poll_oneoff（標準入力の FD_READ と相対 0 の時計）で今読めるかを見てから fd_read する。待機点にならず、タイマーも作らない
 * - 使う import は kibi:io/tty の is-tty・get-mode・set-mode だけ（新しい口は足さない）。切った後と、切っていないときの読み取りでは
 *   import を呼ばない（静的な状態を見るだけ）
 * - シグナルで殺されたときは atexit が走らない。対話型の sh は、子が canonical を外したまま終わったら cooked に戻す（ADR 0102）
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wasi/api.h>

#include "kibi_io.h"
#include "kibi_lua.h"

/* kibi_wasi.h の clearerr のマクロを外して、本物を呼ぶ（stdio.h の宣言はマクロで名前が変わっているので、ここで宣言し直す） */
#undef clearerr
void clearerr(FILE *f);
#undef getc
int getc(FILE *f);

/* kibi:io/tty の mode のビット（ADR 0427） */
#define MODE_COOKED 0x1f
/* 切った後のモード: signals | nl-to-crnl */
#define MODE_KEYS 0x14

/* 1回の読み捨ての大きさと、回数の上限（入力 4 KiB ＋待ちの列 8 KiB を読み切れる回数。押し続けても終わるように上限を付ける） */
#define DRAIN_CHUNK 256
#define DRAIN_MAX_READS 96

enum {
    /* まだ標準入力を調べていない */
    STATE_UNKNOWN,
    /* 標準入力が端末でない（パイプ・閉じた fd）。何もしない */
    STATE_NOT_TTY,
    /* 端末だが、今は切っていない */
    STATE_IDLE,
    /* 切っている */
    STATE_SWITCHED,
};

static int state = STATE_UNKNOWN;
/* 切る前のモード */
static int32_t saved_mode = MODE_COOKED;
/* 終わるときに戻すのを atexit に登録したら 1 */
static int exit_registered = 0;

/* 標準入力が今すぐ読めるなら 1（待たない。os/bin/sh/edit.zig の inputReadyNow と同じ形） */
static int ready_now(void) {
    __wasi_subscription_t subs[2];
    __wasi_event_t events[2];
    memset(subs, 0, sizeof subs);
    subs[0].userdata = 1;
    subs[0].u.tag = __WASI_EVENTTYPE_FD_READ;
    subs[0].u.u.fd_read.file_descriptor = 0;
    subs[1].userdata = 2;
    subs[1].u.tag = __WASI_EVENTTYPE_CLOCK;
    subs[1].u.u.clock.id = __WASI_CLOCKID_MONOTONIC;
    subs[1].u.u.clock.timeout = 0;
    __wasi_size_t got = 0;
    if (__wasi_poll_oneoff(subs, events, 2, &got) != 0) return 0;
    for (__wasi_size_t i = 0; i < got; i++) {
        if (events[i].userdata == 1 && events[i].error == 0) return 1;
    }
    return 0;
}

/* 今読める標準入力を読み捨てる（待たない）。領域はスタックに置く（Lua の確保を通さない） */
static void drain(void) {
    uint8_t buf[DRAIN_CHUNK];
    for (int i = 0; i < DRAIN_MAX_READS && ready_now(); i++) {
        __wasi_iovec_t iov = {buf, sizeof buf};
        __wasi_size_t n = 0;
        if (__wasi_fd_read(0, &iov, 1, &n) != 0 || n == 0) return;
    }
}

/* 今のモード。読めなければ -1 */
static int32_t current_mode(void) {
    kibi_result_tty_mode_t r;
    memset(&r, 0, sizeof r);
    kibi_io_tty_get_mode(0, &r);
    return r.is_err ? -1 : (int32_t)r.val.ok;
}

static void restore_at_exit(void) {
    kibi_lua_tty_restore();
}

void kibi_lua_tty_keys(void) {
    if (state == STATE_SWITCHED || state == STATE_NOT_TTY) return;
    if (state == STATE_UNKNOWN) {
        state = kibi_io_tty_is_tty(0) ? STATE_IDLE : STATE_NOT_TTY;
        if (state == STATE_NOT_TTY) return;
    }
    /* 既定のモードのときだけ切る（REPL・sh の行編集の上で、ほかのモードにしたプログラムの設定を壊さない） */
    int32_t mode = current_mode();
    if (mode != MODE_COOKED) return;
    /* 戻す手段がないまま切らない */
    if (!exit_registered) {
        if (atexit(restore_at_exit) != 0) return;
        exit_registered = 1;
    }
    kibi_result_unit_t r;
    memset(&r, 0, sizeof r);
    kibi_io_tty_set_mode(0, MODE_KEYS, &r);
    if (r.is_err) return;
    saved_mode = mode;
    state = STATE_SWITCHED;
}

void kibi_lua_tty_before_wait(void) {
    if (state == STATE_SWITCHED) drain();
}

void kibi_lua_tty_restore(void) {
    if (state != STATE_SWITCHED) return;
    state = STATE_IDLE;
    drain();
    if (current_mode() != MODE_KEYS) return;
    kibi_result_unit_t r;
    memset(&r, 0, sizeof r);
    kibi_io_tty_set_mode(0, saved_mode, &r);
    /* canonical に戻すと、残っていた入力は確定した1つの塊になる。読める塊があれば捨てる */
    drain();
}

void kibi_lua_before_read(FILE *f) {
    if (f == stdin) kibi_lua_tty_restore();
    clearerr(f);
}

/* 引数なしの dofile()・loadfile() の読み取り（lauxlib.c の luaL_loadfilex。最初の getc が標準入力を読む） */
int kibi_lua_getc(FILE *f) {
    if (f == stdin) kibi_lua_tty_restore();
    return getc(f);
}
