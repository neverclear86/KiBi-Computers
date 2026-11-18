/*
 * Lua の term ライブラリ（#724。ADR 0724）。CC: Tweaked の term の振る舞いに合わせて、自分で書いたもの（CC のコードは使わない）。
 *
 * 端末（コンソールの TTY。docs/terminal.md）への ANSI のエスケープで描く。カーネルの口は足さず、使う import は
 * kibi:io/tty の is-tty・get-size（待たない）だけ。
 *
 * - カーソルの位置・色・パレットは、この C の静的変数に持つ（論理カーソル）。端末に位置を問い合わせない（DSR の応答は TTY の入力に混ざるため）。
 *   位置は画面の外（x < 1、x > 幅など）も持てる。最初の setCursorPos より前は位置が分からないので、write は今の端末の位置にそのまま書く
 * - write・blit は、論理カーソルから画面に入る分だけを切り出し、その前に毎回カーソルの位置（CUP）と色（SGR）を書く。右端で折り返さない
 *   （CC と同じ）。端末の状態が保存から戻した後に初めの状態になっていても、次の出力で直る
 * - setCursorPos・setTextColour・setBackgroundColour・setCursorBlink は、その場でエスケープを書く（後の print にも効くように）
 * - 色は CC の 16 色のビットで受ける。既定のパレットのままの色は端末の基本の 16 色で、setPaletteColour で変えた色は RGB
 *   （SGR 38;2・48;2）で書き、端末がティアの色数に丸める。setPaletteColour は、その後に書く分にだけ効く（もう描いたセルの色は変わらない）
 * - 文字は UTF-8 で、全角は 2 セル（core の CharWidth・os/lib/kibi/text.zig と同じ表）。C0・DEL・C1 の制御文字と不正なバイト列は
 *   U+FFFD にして書く（エスケープを注入させない）
 * - 出力は 1 回の呼び出しで 1 回にまとめ、stdout（print と同じバッファ）に書いてすぐ fflush する（print との順番を保つ）
 * - 色かカーソルの表示を変えたら、終わるときに SGR 0 と CSI ?25h を書く（atexit。シグナルで殺されたときは走らない）
 * - term.redirect: 今の出力先がこの端末でなければ、term.* は出力先のテーブルの同じ名前の関数に渡す
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lauxlib.h"
#include "lua.h"

#include "kibi_io.h"
#include "kibi_lua.h"

/* TTY が見つからないときの大きさ（CC のコンピューターの画面と同じ） */
#define DEFAULT_WIDTH 51
#define DEFAULT_HEIGHT 19
/* 位置の上限（足して溢れないように） */
#define POS_LIMIT 0x3FFFFFFF

/* レジストリの key: 今の出力先（nil ならこの端末）と、term.native() のテーブル */
static char CURRENT_KEY;
static char NATIVE_KEY;

static lua_Integer cur_x = 1;
static lua_Integer cur_y = 1;
/* setCursorPos を呼んだら 1（それまでは端末の位置が分からない） */
static int pos_known = 0;
/* 色の番号（2^i の i）。既定は白の文字と黒の背景（CC と同じ） */
static int fg_index = 0;
static int bg_index = 15;
static int blink = 1;
static uint32_t palette[16];
static int palette_ready = 0;
/* 終わるときの後始末を atexit に登録したら 1 */
static int cleanup_registered = 0;
/* 保存から戻した後、まだ何も書いていなければ 1 */
static int restored_pending = 0;
/* 大きさを読む TTY の fd（まだ探していなければ -2、見つからなければ -1） */
static int tty_fd = -2;

void kibi_lua_term_restored(void) {
    restored_pending = 1;
}

static void ensure_palette(void) {
    if (palette_ready) return;
    memcpy(palette, kibi_lua_default_palette, sizeof palette);
    palette_ready = 1;
}

static void reset_at_exit(void) {
    fputs("\x1b[0m\x1b[?25h", stdout);
    fflush(stdout);
}

static void register_cleanup(void) {
    if (cleanup_registered) return;
    if (atexit(reset_at_exit) == 0) cleanup_registered = 1;
}

/* 端末の大きさ。fd 1 → 2 → 0 の順に TTY を探す（見つかった fd は覚える）。なければ 51 x 19 */
static void term_size(lua_Integer *w, lua_Integer *h) {
    if (tty_fd == -2) {
        static const int FDS[] = {1, 2, 0};
        tty_fd = -1;
        for (int i = 0; i < 3; i++) {
            if (kibi_io_tty_is_tty((uint32_t)FDS[i])) {
                tty_fd = FDS[i];
                break;
            }
        }
    }
    if (tty_fd >= 0) {
        kibi_result_tty_size_t r;
        kibi_io_tty_get_size((uint32_t)tty_fd, &r);
        if (!r.is_err && r.val.ok.columns > 0 && r.val.ok.rows > 0) {
            *w = (lua_Integer)r.val.ok.columns;
            *h = (lua_Integer)r.val.ok.rows;
            return;
        }
    }
    *w = DEFAULT_WIDTH;
    *h = DEFAULT_HEIGHT;
}

/* ---- 色の引数 ---- */

/* CC の色（いちばん上のビットを見る）を番号 0〜15 にする。範囲の外は "Colour out of range" */
static int check_colour(lua_State *L, int idx) {
    lua_Integer c = luaL_checkinteger(L, idx);
    if (c <= 0 || c > 0xFFFF) return luaL_error(L, "Colour out of range");
    int i = 15;
    while (!(c & ((lua_Integer)1 << i))) i--;
    return i;
}

/* blit の 1 文字（0〜9・a〜f・A〜F）を番号にする。ほかは -1 */
static int blit_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* ---- 出力 ---- */

typedef struct {
    luaL_Buffer b;
    int begun;
} out_t;

static void out_init(lua_State *L, out_t *o) {
    luaL_buffinit(L, &o->b);
    o->begun = 0;
}

/*
 * 既定のパレットのままの色を書く端末の基本の 16 色の番号（[i] は 2^i の色）。色数の少ない画面（16 色）は RGB をいちばん近い基本の色に
 * 丸めるが、CC の既定の色はくすんでいて、赤・緑・水色などが灰色に丸まる。色の名前どおりに見えるよう、既定のままの色は基本の色で書く
 */
static const unsigned char ANSI_OF[16] = {15, 3, 13, 12, 11, 10, 13, 8, 7, 6, 5, 4, 3, 2, 1, 0};

/* [v]（0〜255）を 10 進で [s] に書き、次の位置を返す */
static char *put_uint(char *s, unsigned v) {
    if (v >= 100) *s++ = (char)('0' + v / 100);
    if (v >= 10) *s++ = (char)('0' + v / 10 % 10);
    *s++ = (char)('0' + v % 10);
    return s;
}

/* 色 [i] の SGR の引数を [s] に書き、次の位置を返す（[base] は前景 30・背景 40）。既定のままなら基本の 16 色、変えたなら RGB */
static char *put_colour(char *s, int i, unsigned base) {
    uint32_t c = palette[i];
    if (c == kibi_lua_default_palette[i]) {
        unsigned a = ANSI_OF[i];
        return put_uint(s, a < 8 ? base + a : base + 60 + a - 8);
    }
    s = put_uint(s, base + 8);
    *s++ = ';';
    *s++ = '2';
    for (int shift = 16; shift >= 0; shift -= 8) {
        *s++ = ';';
        s = put_uint(s, (unsigned)(c >> shift & 0xFF));
    }
    return s;
}

static void add_sgr(out_t *o, int fg, int bg) {
    ensure_palette();
    /* "\x1b[" + 2 色（それぞれ最大 "48;2;255;255;255"）+ ";" + "m" */
    char buf[48];
    char *s = buf;
    *s++ = '\x1b';
    *s++ = '[';
    s = put_colour(s, fg, 30);
    *s++ = ';';
    s = put_colour(s, bg, 40);
    *s++ = 'm';
    luaL_addlstring(&o->b, buf, (size_t)(s - buf));
    register_cleanup();
}

static void add_cup(out_t *o, lua_Integer y, lua_Integer x) {
    char s[32];
    int n = snprintf(s, sizeof s, "\x1b[%d;%dH", (int)y, (int)x);
    luaL_addlstring(&o->b, s, (size_t)n);
}

/* 描く前に 1 度だけ: 保存から戻した後ならカーソルの表示を書き直し、今の色を書く */
static void out_begin(out_t *o) {
    if (o->begun) return;
    o->begun = 1;
    if (restored_pending) {
        restored_pending = 0;
        if (!blink) luaL_addstring(&o->b, "\x1b[?25l");
    }
    add_sgr(o, fg_index, bg_index);
}

/* 溜めた分を stdout に書いて fflush する（stdout のバッファにある print の出力の後ろに続く） */
static void out_flush(lua_State *L, out_t *o) {
    luaL_pushresult(&o->b);
    size_t len;
    const char *s = lua_tolstring(L, -1, &len);
    if (len > 0) {
        fwrite(s, 1, len, stdout);
        fflush(stdout);
    }
    lua_pop(L, 1);
}

/* その場で短いエスケープを書く */
static void write_now(const char *s, size_t len) {
    fwrite(s, 1, len, stdout);
    fflush(stdout);
}

static void add_utf8(out_t *o, uint32_t cp) {
    char s[4];
    size_t n;
    if (cp < 0x80) {
        s[0] = (char)cp;
        n = 1;
    } else if (cp < 0x800) {
        s[0] = (char)(0xC0 | cp >> 6);
        s[1] = (char)(0x80 | (cp & 0x3F));
        n = 2;
    } else if (cp < 0x10000) {
        s[0] = (char)(0xE0 | cp >> 12);
        s[1] = (char)(0x80 | (cp >> 6 & 0x3F));
        s[2] = (char)(0x80 | (cp & 0x3F));
        n = 3;
    } else {
        s[0] = (char)(0xF0 | cp >> 18);
        s[1] = (char)(0x80 | (cp >> 12 & 0x3F));
        s[2] = (char)(0x80 | (cp >> 6 & 0x3F));
        s[3] = (char)(0x80 | (cp & 0x3F));
        n = 4;
    }
    luaL_addlstring(&o->b, s, n);
}

/* ---- 文字 ---- */

#define REPLACEMENT 0xFFFD

/*
 * [s] の [*i] から 1 文字読んで進める。不正なバイト列（過長・サロゲート・U+10FFFF 超え・途中で切れたもの）は 1 バイト進めて U+FFFD、
 * 制御文字（C0・DEL・C1）も U+FFFD にする
 */
static uint32_t next_char(const unsigned char *s, size_t len, size_t *i) {
    unsigned char c = s[*i];
    size_t n;
    uint32_t v;
    if (c < 0x80) {
        (*i)++;
        return c < 0x20 || c == 0x7F ? REPLACEMENT : c;
    } else if (c >= 0xC2 && c <= 0xDF) {
        n = 2;
        v = c & 0x1F;
    } else if (c >= 0xE0 && c <= 0xEF) {
        n = 3;
        v = c & 0x0F;
    } else if (c >= 0xF0 && c <= 0xF4) {
        n = 4;
        v = c & 0x07;
    } else {
        (*i)++;
        return REPLACEMENT;
    }
    if (len - *i < n) {
        (*i)++;
        return REPLACEMENT;
    }
    unsigned char lo = 0x80, hi = 0xBF;
    if (c == 0xE0) lo = 0xA0;
    else if (c == 0xED) hi = 0x9F;
    else if (c == 0xF0) lo = 0x90;
    else if (c == 0xF4) hi = 0x8F;
    if (s[*i + 1] < lo || s[*i + 1] > hi) {
        (*i)++;
        return REPLACEMENT;
    }
    for (size_t k = 1; k < n; k++) {
        if ((s[*i + k] & 0xC0) != 0x80) {
            (*i)++;
            return REPLACEMENT;
        }
        v = v << 6 | (s[*i + k] & 0x3F);
    }
    *i += n;
    return v >= 0x80 && v <= 0x9F ? REPLACEMENT : v;
}

/* 全角（2 セル）の範囲（core の CharWidth・os/lib/kibi/text.zig と同じ） */
static const uint32_t WIDE[][2] = {
    {0x1100, 0x115F},   {0x231A, 0x231B},   {0x2E80, 0x303E},   {0x3041, 0x33FF},   {0x3400, 0x4DBF},   {0x4E00, 0x9FFF},
    {0xA000, 0xA4CF},   {0xA960, 0xA97F},   {0xAC00, 0xD7A3},   {0xF900, 0xFAFF},   {0xFE10, 0xFE19},   {0xFE30, 0xFE6F},
    {0xFF00, 0xFF60},   {0xFFE0, 0xFFE6},   {0x1F300, 0x1F64F}, {0x1F900, 0x1F9FF}, {0x20000, 0x2FFFD}, {0x30000, 0x3FFFD},
};

/* 幅のない文字（結合文字・書式の文字）の主な範囲（os/lib/kibi/text.zig と同じ） */
static const uint32_t ZERO[][2] = {
    {0x0300, 0x036F}, {0x0483, 0x0489}, {0x0591, 0x05BD}, {0x0610, 0x061A}, {0x064B, 0x065F}, {0x200B, 0x200F}, {0x202A, 0x202E},
    {0x2060, 0x2064}, {0x20D0, 0x20FF}, {0x302A, 0x302D}, {0x3099, 0x309A}, {0xFE00, 0xFE0F}, {0xFE20, 0xFE2F}, {0xFEFF, 0xFEFF},
};

static int in_ranges(uint32_t cp, const uint32_t (*r)[2], size_t n) {
    for (size_t i = 0; i < n; i++) {
        if (cp < r[i][0]) return 0;
        if (cp <= r[i][1]) return 1;
    }
    return 0;
}

static int char_width(uint32_t cp) {
    if (in_ranges(cp, ZERO, sizeof ZERO / sizeof ZERO[0])) return 0;
    return in_ranges(cp, WIDE, sizeof WIDE / sizeof WIDE[0]) ? 2 : 1;
}

/*
 * 論理カーソルから [s] を書く（[fgs]・[bgs] があれば blit の色。文字ごとに 1 つ）。画面に入る分だけを書き、カーソルを進める。
 * 全角の文字が画面の端にかかるときは、入る側の 1 セルを空白にする
 */
static void draw(lua_State *L, const unsigned char *s, size_t len, const char *fgs, const char *bgs) {
    lua_Integer w, h;
    term_size(&w, &h);
    out_t o;
    out_init(L, &o);
    lua_Integer x = cur_x;
    int row_visible = pos_known && cur_y >= 1 && cur_y <= h;
    int drawing = 0;  /* 直前の文字を画面に書いた（続けて書けば CUP は要らない）なら 1 */
    int sgr_fg = -1, sgr_bg = -1;
    size_t i = 0, k = 0;
    while (i < len) {
        uint32_t cp = next_char(s, len, &i);
        int f = fg_index, b = bg_index;
        if (fgs != NULL) {
            int df = blit_digit(fgs[k]), db = blit_digit(bgs[k]);
            if (df >= 0) f = df;
            if (db >= 0) b = db;
        }
        k++;
        int cw = char_width(cp);
        if (cw == 0) {
            if (drawing) add_utf8(&o, cp);
            continue;
        }
        int show = !pos_known || (row_visible && x >= 1 && x + cw - 1 <= w);
        int half = !show && row_visible && cw == 2 && (x == 0 || x == w);
        if (show || half) {
            out_begin(&o);
            if (sgr_fg < 0) {
                sgr_fg = fg_index;
                sgr_bg = bg_index;
            }
            if (f != sgr_fg || b != sgr_bg) {
                add_sgr(&o, f, b);
                sgr_fg = f;
                sgr_bg = b;
            }
            lua_Integer at = half && x == 0 ? 1 : x;
            if (pos_known && (!drawing || half)) add_cup(&o, cur_y, at);
            if (half) {
                luaL_addchar(&o.b, ' ');
                drawing = 0;
            } else {
                add_utf8(&o, cp);
                drawing = 1;
            }
        } else {
            drawing = 0;
        }
        if (x < POS_LIMIT) x += cw;
    }
    /* blit の後は、今の色に戻す（後の print が今の色で書かれるように） */
    if (o.begun && (sgr_fg != fg_index || sgr_bg != bg_index)) add_sgr(&o, fg_index, bg_index);
    cur_x = x;
    out_flush(L, &o);
}

/* ---- 端末の関数（term.native() のテーブルと、出力先がこの端末のときの term.*） ---- */

static int n_write(lua_State *L) {
    size_t len;
    const char *s = luaL_tolstring(L, 1, &len);
    draw(L, (const unsigned char *)s, len, NULL, NULL);
    return 0;
}

static int n_blit(lua_State *L) {
    size_t len, flen, blen;
    const char *s = luaL_checklstring(L, 1, &len);
    const char *f = luaL_checklstring(L, 2, &flen);
    const char *b = luaL_checklstring(L, 3, &blen);
    size_t count = 0, i = 0;
    while (i < len) {
        next_char((const unsigned char *)s, len, &i);
        count++;
    }
    if (flen != count || blen != count) return luaL_error(L, "Arguments must be the same length");
    draw(L, (const unsigned char *)s, len, f, b);
    return 0;
}

static lua_Integer check_pos(lua_State *L, int idx) {
    lua_Number v = floor(luaL_checknumber(L, idx));
    if (!(v == v)) luaL_argerror(L, idx, "not a number");
    if (v > POS_LIMIT) return POS_LIMIT;
    if (v < -POS_LIMIT) return -POS_LIMIT;
    return (lua_Integer)v;
}

static int n_setCursorPos(lua_State *L) {
    lua_Integer x = check_pos(L, 1), y = check_pos(L, 2);
    cur_x = x;
    cur_y = y;
    pos_known = 1;
    lua_Integer w, h;
    term_size(&w, &h);
    if (x >= 1 && x <= w && y >= 1 && y <= h) {
        char s[32];
        int n = snprintf(s, sizeof s, "\x1b[%d;%dH", (int)y, (int)x);
        write_now(s, (size_t)n);
    }
    return 0;
}

static int n_getCursorPos(lua_State *L) {
    lua_pushinteger(L, cur_x);
    lua_pushinteger(L, cur_y);
    return 2;
}

static int n_setCursorBlink(lua_State *L) {
    luaL_checktype(L, 1, LUA_TBOOLEAN);
    blink = lua_toboolean(L, 1);
    if (!blink) register_cleanup();
    write_now(blink ? "\x1b[?25h" : "\x1b[?25l", 6);
    return 0;
}

static int n_getCursorBlink(lua_State *L) {
    lua_pushboolean(L, blink);
    return 1;
}

static int n_getSize(lua_State *L) {
    lua_Integer w, h;
    term_size(&w, &h);
    lua_pushinteger(L, w);
    lua_pushinteger(L, h);
    return 2;
}

/* 消す（[what] は "2J" か "2K"）。消す色は今の背景色（端末の bce） */
static void erase(lua_State *L, const char *what, int at_cursor_row) {
    out_t o;
    out_init(L, &o);
    if (at_cursor_row && pos_known) {
        lua_Integer w, h;
        term_size(&w, &h);
        if (cur_y < 1 || cur_y > h) {
            out_flush(L, &o);
            return;
        }
        out_begin(&o);
        add_cup(&o, cur_y, cur_x < 1 ? 1 : cur_x > w ? w : cur_x);
    }
    out_begin(&o);
    luaL_addstring(&o.b, "\x1b[");
    luaL_addstring(&o.b, what);
    out_flush(L, &o);
}

static int n_clear(lua_State *L) {
    erase(L, "2J", 0);
    return 0;
}

static int n_clearLine(lua_State *L) {
    erase(L, "2K", 1);
    return 0;
}

/* scroll(n): 中身を n 行上へ（負なら下へ）ずらし、空いた行を今の背景色で埋める。カーソルは動かさない */
static int n_scroll(lua_State *L) {
    lua_Integer n = luaL_checkinteger(L, 1);
    if (n == 0) return 0;
    lua_Integer w, h;
    term_size(&w, &h);
    lua_Integer m = n < 0 ? -n : n;
    if (m > h) m = h;
    out_t o;
    out_init(L, &o);
    out_begin(&o);
    char s[32];
    int len = snprintf(s, sizeof s, "\x1b[%d%c", (int)m, n > 0 ? 'S' : 'T');
    luaL_addlstring(&o.b, s, (size_t)len);
    out_flush(L, &o);
    return 0;
}

/* 色を変えたら、その場で SGR を書く（後の print にも効くように） */
static void write_sgr_now(lua_State *L) {
    out_t o;
    out_init(L, &o);
    add_sgr(&o, fg_index, bg_index);
    out_flush(L, &o);
}

static int n_setTextColour(lua_State *L) {
    fg_index = check_colour(L, 1);
    write_sgr_now(L);
    return 0;
}

static int n_getTextColour(lua_State *L) {
    lua_pushinteger(L, (lua_Integer)1 << fg_index);
    return 1;
}

static int n_setBackgroundColour(lua_State *L) {
    bg_index = check_colour(L, 1);
    write_sgr_now(L);
    return 0;
}

static int n_getBackgroundColour(lua_State *L) {
    lua_pushinteger(L, (lua_Integer)1 << bg_index);
    return 1;
}

/* isColour: 常に true（単色のティアでも、端末が読める色に丸める） */
static int n_isColour(lua_State *L) {
    lua_pushboolean(L, 1);
    return 1;
}

static lua_Integer rgb_channel(lua_State *L, int idx) {
    lua_Number v = luaL_checknumber(L, idx);
    if (!(v > 0)) return 0;
    if (v >= 1) return 255;
    return (lua_Integer)floor(v * 255);
}

/* setPaletteColour(colour, rgb) か setPaletteColour(colour, r, g, b)（0〜1） */
static int n_setPaletteColour(lua_State *L) {
    int i = check_colour(L, 1);
    uint32_t rgb;
    if (lua_gettop(L) == 2) {
        rgb = (uint32_t)(luaL_checkinteger(L, 2) & 0xFFFFFF);
    } else {
        rgb = (uint32_t)(rgb_channel(L, 2) << 16 | rgb_channel(L, 3) << 8 | rgb_channel(L, 4));
    }
    ensure_palette();
    palette[i] = rgb;
    if (i == fg_index || i == bg_index) write_sgr_now(L);
    return 0;
}

static int push_rgb(lua_State *L, uint32_t rgb) {
    lua_pushnumber(L, (lua_Number)(rgb >> 16 & 0xFF) / 255);
    lua_pushnumber(L, (lua_Number)(rgb >> 8 & 0xFF) / 255);
    lua_pushnumber(L, (lua_Number)(rgb & 0xFF) / 255);
    return 3;
}

static int n_getPaletteColour(lua_State *L) {
    int i = check_colour(L, 1);
    ensure_palette();
    return push_rgb(L, palette[i]);
}

static int l_nativePaletteColour(lua_State *L) {
    return push_rgb(L, kibi_lua_default_palette[check_colour(L, 1)]);
}

/* 端末の関数の表（term.native()）。色の綴りは colour と color の両方 */
#define NATIVE_FUNCS(X)                                                                                                                    \
    X(write, n_write)                                                                                                                      \
    X(blit, n_blit)                                                                                                                        \
    X(scroll, n_scroll)                                                                                                                    \
    X(getCursorPos, n_getCursorPos)                                                                                                        \
    X(setCursorPos, n_setCursorPos)                                                                                                        \
    X(getCursorBlink, n_getCursorBlink)                                                                                                    \
    X(setCursorBlink, n_setCursorBlink)                                                                                                    \
    X(getSize, n_getSize)                                                                                                                  \
    X(clear, n_clear)                                                                                                                      \
    X(clearLine, n_clearLine)                                                                                                              \
    X(getTextColour, n_getTextColour)                                                                                                      \
    X(getTextColor, n_getTextColour)                                                                                                       \
    X(setTextColour, n_setTextColour)                                                                                                      \
    X(setTextColor, n_setTextColour)                                                                                                       \
    X(getBackgroundColour, n_getBackgroundColour)                                                                                          \
    X(getBackgroundColor, n_getBackgroundColour)                                                                                           \
    X(setBackgroundColour, n_setBackgroundColour)                                                                                          \
    X(setBackgroundColor, n_setBackgroundColour)                                                                                           \
    X(isColour, n_isColour)                                                                                                                \
    X(isColor, n_isColour)                                                                                                                 \
    X(setPaletteColour, n_setPaletteColour)                                                                                                \
    X(setPaletteColor, n_setPaletteColour)                                                                                                 \
    X(getPaletteColour, n_getPaletteColour)                                                                                                \
    X(getPaletteColor, n_getPaletteColour)

static const luaL_Reg NATIVE[] = {
#define NATIVE_REG(name, fn) {#name, fn},
    NATIVE_FUNCS(NATIVE_REG)
#undef NATIVE_REG
    {NULL, NULL},
};

/* ---- 出力先 ---- */

static int dispatch_k(lua_State *L, int status, lua_KContext ctx) {
    (void)status;
    (void)ctx;
    return lua_gettop(L);
}

/* 出力先がこの端末なら [native] を呼び、ほかなら出力先の [name] に引数を渡す */
static int dispatch(lua_State *L, const char *name, lua_CFunction native) {
    if (lua_rawgetp(L, LUA_REGISTRYINDEX, &CURRENT_KEY) == LUA_TNIL) {
        lua_pop(L, 1);
        return native(L);
    }
    if (lua_getfield(L, -1, name) == LUA_TNIL) return luaL_error(L, "Redirect object is missing method %s.", name);
    lua_remove(L, -2);
    lua_insert(L, 1);
    lua_callk(L, lua_gettop(L) - 1, LUA_MULTRET, 0, dispatch_k);
    return lua_gettop(L);
}

#define DISPATCHER(name, fn) \
    static int d_##name(lua_State *L) { return dispatch(L, #name, fn); }
NATIVE_FUNCS(DISPATCHER)
#undef DISPATCHER

/* term.native() のテーブルを積む（初めて呼ばれたときに作る） */
static void push_native(lua_State *L) {
    if (lua_rawgetp(L, LUA_REGISTRYINDEX, &NATIVE_KEY) == LUA_TTABLE) return;
    lua_pop(L, 1);
    luaL_newlib(L, NATIVE);
    lua_pushvalue(L, -1);
    lua_rawsetp(L, LUA_REGISTRYINDEX, &NATIVE_KEY);
}

static int l_native(lua_State *L) {
    push_native(L);
    return 1;
}

static int l_current(lua_State *L) {
    if (lua_rawgetp(L, LUA_REGISTRYINDEX, &CURRENT_KEY) != LUA_TNIL) return 1;
    lua_pop(L, 1);
    push_native(L);
    return 1;
}

/* term.redirect(target): 出力先を変え、前の出力先を返す */
static int l_redirect(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_settop(L, 1);
    /* term の表そのもの（package.loaded.term）を出力先にすると、自分を呼び続けるので断る（CC と同じ） */
    luaL_getsubtable(L, LUA_REGISTRYINDEX, LUA_LOADED_TABLE);
    lua_getfield(L, -1, "term");
    if (lua_rawequal(L, 1, -1)) return luaL_error(L, "term is not a recommended redirect target, try term.current() instead");
    lua_settop(L, 1);
    l_current(L);
    push_native(L);
    if (lua_rawequal(L, 1, -1)) lua_pushnil(L);
    else lua_pushvalue(L, 1);
    lua_rawsetp(L, LUA_REGISTRYINDEX, &CURRENT_KEY);
    lua_pop(L, 1);
    return 1;
}

static int fill_term(lua_State *L) {
#define FILL(name, fn) kibi_lua_lazy_set(L, #name, d_##name);
    NATIVE_FUNCS(FILL)
#undef FILL
    kibi_lua_lazy_set(L, "nativePaletteColour", l_nativePaletteColour);
    kibi_lua_lazy_set(L, "nativePaletteColor", l_nativePaletteColour);
    kibi_lua_lazy_set(L, "redirect", l_redirect);
    kibi_lua_lazy_set(L, "current", l_current);
    kibi_lua_lazy_set(L, "native", l_native);
    return 0;
}

int luaopen_term(lua_State *L) {
    kibi_lua_lazy_lib(L, fill_term);
    return 1;
}
