/*
 * Lua の keys ライブラリ（#724。ADR 0724）と、カーネルのキー名（ADR 0326）から CC: Tweaked の keys の番号への表。
 *
 * CC の keys の番号は GLFW のキーコード（CC の文書と keys の名前の一覧で確かめた。CC のコードは使わない）。
 * os.pullEvent の "key"・"key_up" は、カーネルの "key-pressed"・"key-released" のキー名をこの表で番号にしたもの
 * （kibi_lua_os.c の push_kernel_event）。"unknown" と表にない名前は写さない（捨てる）。
 *
 * 表は C の静的な配列（番号の順）で、Lua のテーブルは初めて keys を触ったときに作る（起動の RAM を増やさない）
 */
#include <stdint.h>
#include <string.h>

#include "lauxlib.h"
#include "lua.h"

#include "kibi_lua.h"

typedef struct {
    uint16_t code;   /* GLFW のキーコード（CC の keys の番号） */
    const char *cc;  /* CC の keys の名前 */
    const char *kibi; /* カーネルのキー名（ADR 0326）。CC にしかない（KiBi では届かない）キーは NULL */
} key_entry_t;

/* 番号の順（keys.getName の二分探索のため） */
static const key_entry_t KEYS[] = {
    {32, "space", "space"},
    {39, "apostrophe", "apostrophe"},
    {44, "comma", "comma"},
    {45, "minus", "minus"},
    {46, "period", "period"},
    {47, "slash", "slash"},
    {48, "zero", "digit-0"},
    {49, "one", "digit-1"},
    {50, "two", "digit-2"},
    {51, "three", "digit-3"},
    {52, "four", "digit-4"},
    {53, "five", "digit-5"},
    {54, "six", "digit-6"},
    {55, "seven", "digit-7"},
    {56, "eight", "digit-8"},
    {57, "nine", "digit-9"},
    {59, "semicolon", "semicolon"},
    {61, "equals", "equal"},
    {65, "a", "a"},
    {66, "b", "b"},
    {67, "c", "c"},
    {68, "d", "d"},
    {69, "e", "e"},
    {70, "f", "f"},
    {71, "g", "g"},
    {72, "h", "h"},
    {73, "i", "i"},
    {74, "j", "j"},
    {75, "k", "k"},
    {76, "l", "l"},
    {77, "m", "m"},
    {78, "n", "n"},
    {79, "o", "o"},
    {80, "p", "p"},
    {81, "q", "q"},
    {82, "r", "r"},
    {83, "s", "s"},
    {84, "t", "t"},
    {85, "u", "u"},
    {86, "v", "v"},
    {87, "w", "w"},
    {88, "x", "x"},
    {89, "y", "y"},
    {90, "z", "z"},
    {91, "leftBracket", "left-bracket"},
    {92, "backslash", "backslash"},
    {93, "rightBracket", "right-bracket"},
    {96, "grave", "grave-accent"},
    /* world1・world2・escape・rightSuper は CC の表にない（KiBi が足したもの。ADR 0724） */
    {161, "world1", "world-1"},
    {162, "world2", "world-2"},
    {256, "escape", "escape"},
    {257, "enter", "enter"},
    {258, "tab", "tab"},
    {259, "backspace", "backspace"},
    {260, "insert", "insert"},
    {261, "delete", "delete"},
    {262, "right", "right"},
    {263, "left", "left"},
    {264, "down", "down"},
    {265, "up", "up"},
    {266, "pageUp", "page-up"},
    {267, "pageDown", "page-down"},
    {268, "home", "home"},
    {269, "end", "end"},
    {280, "capsLock", "caps-lock"},
    {281, "scrollLock", "scroll-lock"},
    {282, "numLock", "num-lock"},
    {283, "printScreen", "print-screen"},
    {284, "pause", "pause"},
    {290, "f1", "f1"},
    {291, "f2", "f2"},
    {292, "f3", "f3"},
    {293, "f4", "f4"},
    {294, "f5", "f5"},
    {295, "f6", "f6"},
    {296, "f7", "f7"},
    {297, "f8", "f8"},
    {298, "f9", "f9"},
    {299, "f10", "f10"},
    {300, "f11", "f11"},
    {301, "f12", "f12"},
    {302, "f13", NULL},
    {303, "f14", NULL},
    {304, "f15", NULL},
    {305, "f16", NULL},
    {306, "f17", NULL},
    {307, "f18", NULL},
    {308, "f19", NULL},
    {309, "f20", NULL},
    {310, "f21", NULL},
    {311, "f22", NULL},
    {312, "f23", NULL},
    {313, "f24", NULL},
    {314, "f25", NULL},
    {320, "numPad0", "keypad-0"},
    {321, "numPad1", "keypad-1"},
    {322, "numPad2", "keypad-2"},
    {323, "numPad3", "keypad-3"},
    {324, "numPad4", "keypad-4"},
    {325, "numPad5", "keypad-5"},
    {326, "numPad6", "keypad-6"},
    {327, "numPad7", "keypad-7"},
    {328, "numPad8", "keypad-8"},
    {329, "numPad9", "keypad-9"},
    {330, "numPadDecimal", "keypad-decimal"},
    {331, "numPadDivide", "keypad-divide"},
    {332, "numPadMultiply", "keypad-multiply"},
    {333, "numPadSubtract", "keypad-subtract"},
    {334, "numPadAdd", "keypad-add"},
    {335, "numPadEnter", "keypad-enter"},
    {336, "numPadEqual", "keypad-equal"},
    {340, "leftShift", "left-shift"},
    {341, "leftCtrl", "left-control"},
    {342, "leftAlt", "left-alt"},
    {343, "leftSuper", "left-super"},
    {344, "rightShift", "right-shift"},
    {345, "rightCtrl", "right-control"},
    {346, "rightAlt", "right-alt"},
    {347, "rightSuper", "right-super"},
    {348, "menu", "menu"},
};

#define KEY_COUNT ((int)(sizeof KEYS / sizeof KEYS[0]))

int kibi_lua_key_code(const char *name, size_t len) {
    /* 1 回のキーのイベントに 1 回なので、表をなめるだけでよい */
    for (int i = 0; i < KEY_COUNT; i++) {
        const char *k = KEYS[i].kibi;
        if (k != NULL && strlen(k) == len && memcmp(k, name, len) == 0) return KEYS[i].code;
    }
    return -1;
}

/* keys.getName(code): 番号の名前。なければ nil（別名の "return"・"scollLock" は返さない。CC と同じ） */
static int l_getName(lua_State *L) {
    lua_Integer code = luaL_checkinteger(L, 1);
    int lo = 0, hi = KEY_COUNT - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (code < KEYS[mid].code) hi = mid - 1;
        else if (code > KEYS[mid].code) lo = mid + 1;
        else {
            lua_pushstring(L, KEYS[mid].cc);
            return 1;
        }
    }
    return 0;
}

/* 表 1 の [name] がまだなければ [code] を入れる */
static void set_code(lua_State *L, const char *name, lua_Integer code) {
    lua_pushstring(L, name);
    if (lua_rawget(L, 1) != LUA_TNIL) {
        lua_pop(L, 1);
        return;
    }
    lua_pop(L, 1);
    lua_pushstring(L, name);
    lua_pushinteger(L, code);
    lua_rawset(L, 1);
}

static int fill_keys(lua_State *L) {
    for (int i = 0; i < KEY_COUNT; i++) set_code(L, KEYS[i].cc, KEYS[i].code);
    /* CC の別名（"return" は Lua の予約語なので keys["return"] で引く。"scollLock" は CC の昔の綴り違い） */
    set_code(L, "return", 257);
    set_code(L, "scollLock", 281);
    kibi_lua_lazy_set(L, "getName", l_getName);
    return 0;
}

int luaopen_keys(lua_State *L) {
    kibi_lua_lazy_lib(L, fill_keys);
    return 1;
}
