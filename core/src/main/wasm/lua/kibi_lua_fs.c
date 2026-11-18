/*
 * Lua の fs ライブラリ（#724。ADR 0724）。CC: Tweaked の fs の振る舞いに合わせて、自分で書いたもの（CC のコードは使わない）。
 *
 * - パスは CC と同じく、いつも根から数える（cwd は見ない。"a/b" も "/a/b" も同じ）。標準の io.open は cwd から数えるので、数え方が違う。
 *   "\\" は "/" と同じ、"." は飛ばし、".." は 1 つ上がる。根より上に出る ".." は fs.combine では残り、ファイルの操作では "Invalid Path"
 * - ファイルの操作は wasi-libc の stat・mkdir・rename・unlink・rmdir と、fd_readdir を直に使う（opendir は libc の malloc を使い、
 *   RAM を数える包みを通らないので使わない）。いつも絶対のパスで渡すので、cwd とつなぐ確保もしない
 * - 一覧・再帰は、ディレクトリの fd を読み終えたらすぐ閉じてから中を見る（fd の上限 256 を食わない）。再帰は深さ 128 まで
 * - エラーは CC と同じく Lua の error（"/パス: No such file" の形）。fs.open だけは nil と文を返す
 * - 容量・読み取り専用・ドライブは kibi:fs/mounts の list-mounts（待たない）で、パスがいちばん長く合うマウントを引く
 * - fs.open のハンドル、fs.find、fs.complete は Lua で書き、初めて使ったときに読み込む（起動のたびに Lua のコードを読まない）
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <wasi/api.h>

#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

#include "kibi_fs.h"
#include "kibi_lua.h"

/* 再帰（delete・copy・move）の深さの上限（C のスタックと fd を食いすぎないように） */
#define MAX_DEPTH 128
/* fd_readdir・写しの領域 */
#define IO_BUF 4096
/* list-mounts の結果の見積もり: 32 個 × (72 ＋ 文字列 2 つ × 256) ＋ 余り 1 KiB */
#define MOUNTS_RESULT_BYTES (32 * (72 + 2 * 256) + 1024)

static char IMPL_KEY;

/* ---- パス ---- */

/*
 * [s] を CC の形（先頭と末尾の "/" なし、"." なし、".." は根より上に出る分だけ先頭に残る）にして積む
 */
static void push_sanitized(lua_State *L, const char *s, size_t len) {
    luaL_Buffer b;
    /* 区切りの位置を覚える代わりに、できた文字列を後ろから削る */
    luaL_buffinit(L, &b);
    size_t depth = 0; /* 先頭の ".." を除いた区間の数 */
    size_t i = 0;
    while (i < len) {
        while (i < len && (s[i] == '/' || s[i] == '\\')) i++;
        size_t start = i;
        while (i < len && s[i] != '/' && s[i] != '\\') i++;
        size_t n = i - start;
        if (n == 0 || (n == 1 && s[start] == '.')) continue;
        if (n == 2 && s[start] == '.' && s[start + 1] == '.') {
            if (depth > 0) {
                /* 最後の区間を消す */
                char *p = luaL_buffaddr(&b);
                size_t l = luaL_bufflen(&b);
                while (l > 0 && p[l - 1] != '/') l--;
                if (l > 0) l--;
                luaL_buffsub(&b, luaL_bufflen(&b) - l);
                depth--;
                continue;
            }
            if (luaL_bufflen(&b) > 0) luaL_addchar(&b, '/');
            luaL_addlstring(&b, "..", 2);
            continue;
        }
        if (luaL_bufflen(&b) > 0) luaL_addchar(&b, '/');
        luaL_addlstring(&b, s + start, n);
        depth++;
    }
    luaL_pushresult(&b);
}

/* 引数 [idx] の文字列を CC の形にして積む */
static const char *check_path(lua_State *L, int idx) {
    size_t len;
    const char *s = luaL_checklstring(L, idx, &len);
    push_sanitized(L, s, len);
    return lua_tostring(L, -1);
}

static int outside_root(const char *p) {
    return p[0] == '.' && p[1] == '.' && (p[2] == '\0' || p[2] == '/');
}

/* 引数 [idx] を CC の形にし、絶対のパス（"/" ＋ 形）を積んで返す。根より上なら "Invalid Path" のエラー */
static const char *check_abs(lua_State *L, int idx) {
    const char *p = check_path(L, idx);
    if (outside_root(p)) luaL_error(L, "/%s: Invalid Path", p);
    lua_pushfstring(L, "/%s", p);
    lua_remove(L, -2);
    return lua_tostring(L, -1);
}

static const char *cc_message(int e) {
    switch (e) {
    case ENOENT: return "No such file";
    case ENOTDIR: return "Not a directory";
    case EEXIST: return "File exists";
    case EACCES:
    case EPERM:
    case EROFS: return "Access denied";
    case EISDIR: return "Is a directory";
    case ENOTEMPTY: return "Directory not empty";
    case ENOSPC:
    case EDQUOT: return "Out of space";
    case EMFILE:
    case ENFILE: return "Too many files already open";
    case ENAMETOOLONG: return "Path too long";
    case ENOMEM: return "not enough memory";
    default: return strerror(e);
    }
}

/* "/パス: 文" の Lua のエラー（[abs] は絶対のパス） */
static int fs_error(lua_State *L, const char *abs, int e) {
    return luaL_error(L, "%s: %s", abs, cc_message(e));
}

/* ---- 調べる ---- */

static int l_combine(lua_State *L) {
    int n = lua_gettop(L);
    luaL_Buffer b;
    for (int i = 1; i <= n; i++) luaL_checkstring(L, i);
    luaL_buffinit(L, &b);
    for (int i = 1; i <= n; i++) {
        size_t len;
        const char *s = lua_tolstring(L, i, &len);
        if (i > 1) luaL_addchar(&b, '/');
        luaL_addlstring(&b, s, len);
    }
    luaL_pushresult(&b);
    size_t len;
    const char *s = lua_tolstring(L, -1, &len);
    push_sanitized(L, s, len);
    return 1;
}

/* getName: 最後の区間（根は "root"。CC と同じ） */
static int l_getName(lua_State *L) {
    const char *p = check_path(L, 1);
    if (p[0] == '\0') {
        lua_pushliteral(L, "root");
        return 1;
    }
    const char *slash = strrchr(p, '/');
    lua_pushstring(L, slash ? slash + 1 : p);
    return 1;
}

/* getDir: 最後の区間を除いたもの（根は ".."。CC と同じ） */
static int l_getDir(lua_State *L) {
    const char *p = check_path(L, 1);
    if (p[0] == '\0') {
        lua_pushliteral(L, "..");
        return 1;
    }
    const char *slash = strrchr(p, '/');
    lua_pushlstring(L, p, slash ? (size_t)(slash - p) : 0);
    return 1;
}

static int l_exists(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    lua_pushboolean(L, stat(abs, &st) == 0);
    return 1;
}

static int l_isDir(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    lua_pushboolean(L, stat(abs, &st) == 0 && S_ISDIR(st.st_mode));
    return 1;
}

static int l_getSize(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    if (stat(abs, &st) != 0) return fs_error(L, abs, errno);
    lua_pushinteger(L, S_ISDIR(st.st_mode) ? 0 : (lua_Integer)st.st_size);
    return 1;
}

/* ---- マウント ---- */

typedef struct {
    int found;
    uint8_t kind;
    uint8_t read_only;
    int is_point;
    int has_usage;
    uint64_t total;
    uint64_t available;
} mount_t;

/* [abs] がいちばん長く合うマウントを引く。結果を読む間は Lua の確保をしない */
static void find_mount(lua_State *L, const char *abs, mount_t *m) {
    if (kibi_lua_arena_held()) luaL_error(L, "cannot use fs while a result is being copied");
    memset(m, 0, sizeof *m);
    size_t alen = strlen(abs);
    kibi_lua_arena_reset();
    kibi_lua_check_memory(L, MOUNTS_RESULT_BYTES);
    kibi_fs_list_mount_info_t r;
    kibi_fs_list_mounts(&r);
    size_t best = 0;
    for (size_t i = 0; i < r.len; i++) {
        const kibi_fs_mount_info_t *mi = &r.ptr[i];
        const char *p = mi->point.ptr;
        size_t plen = mi->point.len;
        int match = (plen == 1 && p[0] == '/') ||
                    (plen <= alen && memcmp(p, abs, plen) == 0 && (alen == plen || abs[plen] == '/'));
        if (!match || (m->found && plen <= best)) continue;
        best = plen;
        m->found = 1;
        m->kind = mi->kind;
        m->read_only = mi->read_only;
        m->is_point = plen == alen;
        m->has_usage = mi->usage_is_some;
        m->total = mi->usage.total_bytes;
        m->available = mi->usage.available_bytes;
    }
    kibi_lua_arena_reset();
}

/* [abs] 自身か、その下にマウントの位置があれば 1（消すと、挿したディスクの中身まで消えるパス）。結果を読む間は Lua の確保をしない */
static int has_mount_within(lua_State *L, const char *abs) {
    if (kibi_lua_arena_held()) luaL_error(L, "cannot use fs while a result is being copied");
    size_t alen = strlen(abs);
    kibi_lua_arena_reset();
    kibi_lua_check_memory(L, MOUNTS_RESULT_BYTES);
    kibi_fs_list_mount_info_t r;
    kibi_fs_list_mounts(&r);
    int found = 0;
    for (size_t i = 0; i < r.len && !found; i++) {
        const char *p = (const char *)r.ptr[i].point.ptr;
        size_t plen = r.ptr[i].point.len;
        found = plen >= alen && memcmp(p, abs, alen) == 0 && (plen == alen || p[alen] == '/');
    }
    kibi_lua_arena_reset();
    return found;
}

static int l_isReadOnly(lua_State *L) {
    const char *abs = check_abs(L, 1);
    mount_t m;
    find_mount(L, abs, &m);
    lua_pushboolean(L, !m.found || m.read_only);
    return 1;
}

static const char *const KIND_NAMES[] = {"disk", "image", "tmp", "dev"};

/* getDrive: マウントの種類（"disk"・"image"・"tmp"・"dev"）。ないパスは nil */
static int l_getDrive(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    mount_t m;
    m.found = 0;
    if (stat(abs, &st) == 0) find_mount(L, abs, &m);
    if (!m.found) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushstring(L, m.kind < 4 ? KIND_NAMES[m.kind] : "unknown");
    return 1;
}

static int l_isDriveRoot(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    if (stat(abs, &st) != 0) return fs_error(L, abs, errno);
    mount_t m;
    find_mount(L, abs, &m);
    lua_pushboolean(L, m.found && m.is_point);
    return 1;
}

/* getFreeSpace: 空きのバイト数。量を持たないマウントは "unlimited"（tmp の空きは目安。ADR 0453） */
static int l_getFreeSpace(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    if (stat(abs, &st) != 0) return fs_error(L, abs, errno);
    mount_t m;
    find_mount(L, abs, &m);
    if (m.found && m.read_only) lua_pushinteger(L, 0);
    else if (!m.found || !m.has_usage) lua_pushliteral(L, "unlimited");
    else lua_pushinteger(L, m.available > (uint64_t)INT64_MAX ? INT64_MAX : (lua_Integer)m.available);
    return 1;
}

/* getCapacity: 全体のバイト数。読み取り専用か量を持たないマウントは nil */
static int l_getCapacity(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    if (stat(abs, &st) != 0) return fs_error(L, abs, errno);
    mount_t m;
    find_mount(L, abs, &m);
    if (!m.found || m.read_only || !m.has_usage) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushinteger(L, m.total > (uint64_t)INT64_MAX ? INT64_MAX : (lua_Integer)m.total);
    return 1;
}

static int l_attributes(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    if (stat(abs, &st) != 0) return fs_error(L, abs, errno);
    mount_t m;
    find_mount(L, abs, &m);
    lua_createtable(L, 0, 5);
    lua_pushinteger(L, S_ISDIR(st.st_mode) ? 0 : (lua_Integer)st.st_size);
    lua_setfield(L, -2, "size");
    lua_pushboolean(L, S_ISDIR(st.st_mode));
    lua_setfield(L, -2, "isDir");
    lua_pushboolean(L, !m.found || m.read_only);
    lua_setfield(L, -2, "isReadOnly");
    /* WASI に作った時刻はないので、created は状態の変わった時刻（ctim）で代える */
    lua_pushinteger(L, (lua_Integer)st.st_ctim.tv_sec * 1000 + st.st_ctim.tv_nsec / 1000000);
    lua_setfield(L, -2, "created");
    lua_pushinteger(L, (lua_Integer)st.st_mtim.tv_sec * 1000 + st.st_mtim.tv_nsec / 1000000);
    lua_setfield(L, -2, "modified");
    return 1;
}

/* ---- 一覧 ---- */

/* lua_pcall で呼ぶ: (fd, 領域, 結果の表) に、fd のディレクトリの名前を足す（"."・".." は除く） */
static int read_dir(lua_State *L) {
    int fd = (int)lua_tointeger(L, 1);
    uint8_t *buf = (uint8_t *)lua_touserdata(L, 2);
    lua_Integer count = 0;
    __wasi_dircookie_t cookie = 0;
    for (;;) {
        __wasi_size_t used = 0;
        __wasi_errno_t err = __wasi_fd_readdir((__wasi_fd_t)fd, buf, IO_BUF, cookie, &used);
        if (err != 0) {
            lua_pushinteger(L, err);
            return 1;
        }
        size_t off = 0;
        int progressed = 0;
        while (off + sizeof(__wasi_dirent_t) <= used) {
            __wasi_dirent_t d;
            memcpy(&d, buf + off, sizeof d);
            if (off + sizeof d + d.d_namlen > used) break;
            const char *name = (const char *)buf + off + sizeof d;
            if (!(d.d_namlen == 1 && name[0] == '.') && !(d.d_namlen == 2 && name[0] == '.' && name[1] == '.')) {
                lua_pushlstring(L, name, d.d_namlen);
                lua_rawseti(L, 3, ++count);
            }
            cookie = d.d_next;
            off += sizeof d + d.d_namlen;
            progressed = 1;
        }
        if (used < IO_BUF) break;
        if (!progressed) {
            lua_pushinteger(L, ENAMETOOLONG);
            return 1;
        }
    }
    lua_pushinteger(L, 0);
    return 1;
}

/* 名前の順に並べる（標準の table.sort。ユーザーが差し替えたグローバルは使わない） */
static void sort_table(lua_State *L, int idx) {
    idx = lua_absindex(L, idx);
    luaL_getsubtable(L, LUA_REGISTRYINDEX, LUA_LOADED_TABLE);
    lua_getfield(L, -1, LUA_TABLIBNAME);
    lua_getfield(L, -1, "sort");
    lua_pushvalue(L, idx);
    lua_call(L, 1, 0);
    lua_pop(L, 2);
}

/* ディレクトリ [abs] の名前の表を、名前の順で積む。fd は読み終えたら（エラーでも）閉じる */
static void push_list(lua_State *L, const char *abs) {
    struct stat st;
    if (stat(abs, &st) != 0) fs_error(L, abs, errno);
    if (!S_ISDIR(st.st_mode)) fs_error(L, abs, ENOTDIR);
    /* 確保は fd を開く前に済ませる（確保の失敗で fd を残さない） */
    lua_newtable(L);
    int result = lua_gettop(L);
    lua_pushcfunction(L, read_dir);
    lua_pushnil(L);
    lua_newuserdatauv(L, IO_BUF, 0);
    lua_pushvalue(L, result);
    int fd = open(abs, O_RDONLY | O_DIRECTORY);
    if (fd < 0) fs_error(L, abs, errno);
    lua_pushinteger(L, fd);
    lua_replace(L, result + 2);
    int status = lua_pcall(L, 3, 1, 0);
    close(fd);
    if (status != LUA_OK) lua_error(L);
    int err = (int)lua_tointeger(L, -1);
    lua_pop(L, 1);
    if (err != 0) fs_error(L, abs, err);
    sort_table(L, result);
}

static int l_list(lua_State *L) {
    const char *abs = check_abs(L, 1);
    push_list(L, abs);
    return 1;
}

/* ---- 作る・消す・写す ---- */

/* [abs] のディレクトリを、親も含めて作る（もうあるディレクトリはそのまま） */
static void make_dirs(lua_State *L, const char *abs) {
    size_t len = strlen(abs);
    /* 写し（区切りを一時的に NUL にする。Lua の文字列は書き換えられないので userdata に写す） */
    char *p = (char *)lua_newuserdatauv(L, len + 1, 0);
    memcpy(p, abs, len + 1);
    for (size_t i = 1; i <= len; i++) {
        if (i < len && p[i] != '/') continue;
        char saved = p[i];
        p[i] = '\0';
        if (mkdir(p, 0777) != 0) {
            int e = errno;
            struct stat st;
            if (!(e == EEXIST && stat(p, &st) == 0 && S_ISDIR(st.st_mode))) {
                lua_pushstring(L, p);
                fs_error(L, lua_tostring(L, -1), e == EEXIST ? EEXIST : e);
            }
        }
        p[i] = saved;
    }
    lua_pop(L, 1);
}

static int l_makeDir(lua_State *L) {
    const char *abs = check_abs(L, 1);
    if (abs[1] == '\0') return 0;
    make_dirs(L, abs);
    return 0;
}

/* [abs] を中身ごと消す（[depth] は深さ） */
static void delete_tree(lua_State *L, const char *abs, int depth) {
    struct stat st;
    if (lstat(abs, &st) != 0) fs_error(L, abs, errno);
    if (S_ISDIR(st.st_mode)) {
        if (depth >= MAX_DEPTH) luaL_error(L, "%s: Too many nested directories", abs);
        luaL_checkstack(L, 4, "too many nested directories");
        push_list(L, abs);
        lua_Integer n = (lua_Integer)lua_rawlen(L, -1);
        for (lua_Integer i = 1; i <= n; i++) {
            lua_rawgeti(L, -1, i);
            lua_pushfstring(L, "%s/%s", abs[1] == '\0' ? "" : abs, lua_tostring(L, -1));
            delete_tree(L, lua_tostring(L, -1), depth + 1);
            lua_pop(L, 2);
        }
        lua_pop(L, 1);
        if (abs[1] != '\0' && rmdir(abs) != 0) fs_error(L, abs, errno);
    } else if (unlink(abs) != 0) {
        fs_error(L, abs, errno);
    }
}

/* delete: ファイルかディレクトリ（中身ごと）を消す。ないパスは何もしない（CC と同じ） */
static int l_delete(lua_State *L) {
    const char *abs = check_abs(L, 1);
    struct stat st;
    if (lstat(abs, &st) != 0) {
        if (errno == ENOENT) return 0;
        return fs_error(L, abs, errno);
    }
    if (abs[1] == '\0') return fs_error(L, abs, EACCES);
    /* マウントの位置か、その上のディレクトリは、中身（挿したディスク）を先に消さないよう、何も消さずに断る（CC のドライブの根と同じ） */
    if (S_ISDIR(st.st_mode) && has_mount_within(L, abs)) return fs_error(L, abs, EACCES);
    delete_tree(L, abs, 0);
    return 0;
}

/* ファイル [src] を [dst]（まだない）に写す。開いている間は Lua の確保をしない */
static void copy_file(lua_State *L, const char *src, const char *dst) {
    unsigned char *buf = (unsigned char *)lua_newuserdatauv(L, IO_BUF, 0);
    int in = open(src, O_RDONLY);
    if (in < 0) fs_error(L, src, errno);
    int out = open(dst, O_WRONLY | O_CREAT | O_EXCL, 0666);
    if (out < 0) {
        int e = errno;
        close(in);
        fs_error(L, dst, e);
    }
    int err = 0;
    const char *where = src;
    for (;;) {
        ssize_t n = read(in, buf, IO_BUF);
        if (n < 0) {
            err = errno;
            break;
        }
        if (n == 0) break;
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(out, buf + off, (size_t)(n - off));
            if (w < 0) {
                err = errno;
                where = dst;
                break;
            }
            off += w;
        }
        if (err != 0) break;
    }
    close(in);
    if (close(out) != 0 && err == 0) {
        err = errno;
        where = dst;
    }
    lua_pop(L, 1);
    if (err != 0) fs_error(L, where, err);
}

static void copy_tree(lua_State *L, const char *src, const char *dst, int depth) {
    struct stat st;
    if (stat(src, &st) != 0) fs_error(L, src, errno);
    if (!S_ISDIR(st.st_mode)) {
        copy_file(L, src, dst);
        return;
    }
    if (depth >= MAX_DEPTH) luaL_error(L, "%s: Too many nested directories", src);
    luaL_checkstack(L, 5, "too many nested directories");
    if (mkdir(dst, 0777) != 0) fs_error(L, dst, errno);
    push_list(L, src);
    lua_Integer n = (lua_Integer)lua_rawlen(L, -1);
    for (lua_Integer i = 1; i <= n; i++) {
        lua_rawgeti(L, -1, i);
        const char *name = lua_tostring(L, -1);
        lua_pushfstring(L, "%s/%s", src[1] == '\0' ? "" : src, name);
        lua_pushfstring(L, "%s/%s", dst, name);
        copy_tree(L, lua_tostring(L, -2), lua_tostring(L, -1), depth + 1);
        lua_pop(L, 3);
    }
    lua_pop(L, 1);
}

/* copy・move の前の確かめ。[src]・[dst] は絶対のパス。[what] は "copy" か "move" */
static void check_transfer(lua_State *L, const char *src, const char *dst, const char *what) {
    struct stat st;
    if (stat(src, &st) != 0) fs_error(L, src, errno);
    if (stat(dst, &st) == 0) fs_error(L, dst, EEXIST);
    size_t sl = strlen(src);
    if (sl == 1 || (strncmp(dst, src, sl) == 0 && (dst[sl] == '/' || dst[sl] == '\0')))
        luaL_error(L, "Can't %s a directory inside itself", what);
    /* 行き先の親を作る（CC と同じ） */
    const char *slash = strrchr(dst, '/');
    if (slash != NULL && slash != dst) {
        lua_pushlstring(L, dst, (size_t)(slash - dst));
        make_dirs(L, lua_tostring(L, -1));
        lua_pop(L, 1);
    }
}

static int l_copy(lua_State *L) {
    const char *src = check_abs(L, 1);
    const char *dst = check_abs(L, 2);
    check_transfer(L, src, dst, "copy");
    copy_tree(L, src, dst, 0);
    return 0;
}

static int l_move(lua_State *L) {
    const char *src = check_abs(L, 1);
    const char *dst = check_abs(L, 2);
    check_transfer(L, src, dst, "move");
    if (rename(src, dst) == 0) return 0;
    int e = errno;
    /* マウントをまたぐときは、写してから消す */
    if (e != EXDEV) return fs_error(L, src, e);
    copy_tree(L, src, dst, 0);
    delete_tree(L, src, 0);
    return 0;
}

/* ---- Lua で書いた部分 ---- */

/*
 * 引数は (list, isDir, exists, combine, sort, io.open の結果を包むときに使う何もなし)。{handle, find, complete} を返す。
 * handle(file, binary, canRead, canWrite) は標準の io のファイルを CC のハンドルに包む（fd の後始末は io の __gc・__close に任せる）
 */
static const char IMPL_SOURCE[] =
    "local list, isDir, exists, combine, sort = ...\n"
    "local function handle(f, binary, canRead, canWrite)\n"
    "  local h, closed = {}, false\n"
    "  local function check() if closed then error('attempt to use a closed file', 3) end end\n"
    "  if canRead then\n"
    "    function h.read(n)\n"
    "      check()\n"
    "      if n == nil then\n"
    "        local c = f:read(1)\n"
    "        if c ~= nil and binary then return c:byte() end\n"
    "        return c\n"
    "      end\n"
    "      if type(n) ~= 'number' then error('bad argument #1 (number expected, got ' .. type(n) .. ')', 2) end\n"
    "      if n < 0 then error('Cannot read a negative number of bytes', 2) end\n"
    "      return f:read(math.floor(n))\n"
    "    end\n"
    "    function h.readAll() check() return f:read('a') end\n"
    "    function h.readLine(withTrailing)\n"
    "      check()\n"
    "      local l = f:read(withTrailing and 'L' or 'l')\n"
    "      if l ~= nil and not withTrailing then l = (l:gsub('\\r$', '')) end\n"
    "      return l\n"
    "    end\n"
    "  end\n"
    "  if canWrite then\n"
    "    function h.write(v)\n"
    "      check()\n"
    "      if binary and type(v) == 'number' then f:write(string.char(math.floor(v) % 256))\n"
    "      else f:write(tostring(v)) end\n"
    "    end\n"
    "    function h.writeLine(v) check() f:write(tostring(v), '\\n') end\n"
    "    function h.flush() check() f:flush() end\n"
    "  end\n"
    "  function h.seek(whence, offset)\n"
    "    check()\n"
    "    return f:seek(whence or 'cur', offset or 0)\n"
    "  end\n"
    "  function h.close() check() closed = true f:close() end\n"
    "  return h\n"
    "end\n"
    /* ワイルドカード（* と ?）の 1 区間を Lua のパターンにする */
    "local function glob(seg)\n"
    "  local p = seg:gsub('[%^%$%(%)%%%.%[%]%+%-]', '%%%0'):gsub('%*', '.*'):gsub('%?', '.')\n"
    "  return '^' .. p .. '$'\n"
    "end\n"
    "local function find(pattern)\n"
    "  pattern = combine(pattern)\n"
    "  if pattern == '..' or pattern:sub(1, 3) == '../' then error('/' .. pattern .. ': Invalid Path', 2) end\n"
    "  local out = {}\n"
    "  if not pattern:find('[*?]') then\n"
    "    if exists(pattern) then out[1] = pattern end\n"
    "    return out\n"
    "  end\n"
    "  local parts = {}\n"
    "  for p in pattern:gmatch('[^/]+') do parts[#parts + 1] = p end\n"
    "  local function walk(base, i)\n"
    "    if i > #parts then out[#out + 1] = base return end\n"
    "    local part = parts[i]\n"
    "    if part:find('[*?]') then\n"
    "      if not isDir(base) then return end\n"
    "      local pat = glob(part)\n"
    "      local names = list(base)\n"
    "      for k = 1, #names do\n"
    "        local name = names[k]\n"
    "        if name:find(pat) then walk(base == '' and name or base .. '/' .. name, i + 1) end\n"
    "      end\n"
    "    else\n"
    "      local path = base == '' and part or base .. '/' .. part\n"
    "      if exists(path) then walk(path, i + 1) end\n"
    "    end\n"
    "  end\n"
    "  walk('', 1)\n"
    "  sort(out)\n"
    "  return out\n"
    "end\n"
    "local function complete(path, location, a, b)\n"
    "  if type(path) ~= 'string' then error('bad argument #1 (string expected, got ' .. type(path) .. ')', 2) end\n"
    "  if type(location) ~= 'string' then error('bad argument #2 (string expected, got ' .. type(location) .. ')', 2) end\n"
    "  local files, dirs, hidden = true, true, false\n"
    "  if type(a) == 'table' then\n"
    "    if a.include_files ~= nil then files = a.include_files end\n"
    "    if a.include_dirs ~= nil then dirs = a.include_dirs end\n"
    "    hidden = a.include_hidden and true or false\n"
    "  else\n"
    "    if a ~= nil then files = a end\n"
    "    if b ~= nil then dirs = b end\n"
    "  end\n"
    "  if path:sub(1, 1) == '/' then location = '' end\n"
    "  local dirPart, name = path:match('^(.*)/([^/]*)$')\n"
    "  if dirPart == nil then dirPart, name = '', path end\n"
    "  local dir = combine(location, dirPart)\n"
    "  local out = {}\n"
    "  if dir == '..' or dir:sub(1, 3) == '../' or not isDir(dir) then return out end\n"
    "  local names = list(dir)\n"
    "  for k = 1, #names do\n"
    "    local file = names[k]\n"
    "    if #file >= #name and file:sub(1, #name) == name and (hidden or file:sub(1, 1) ~= '.' or name:sub(1, 1) == '.') then\n"
    "      local rest = file:sub(#name + 1)\n"
    "      if isDir(combine(dir, file)) then\n"
    "        out[#out + 1] = rest .. '/'\n"
    "        if dirs and #rest > 0 then out[#out + 1] = rest end\n"
    "      elseif files and #rest > 0 then\n"
    "        out[#out + 1] = rest\n"
    "      end\n"
    "    end\n"
    "  end\n"
    "  return out\n"
    "end\n"
    "return {handle = handle, find = find, complete = complete}\n";

/* Lua で書いた部分の表を積む（初めて呼ばれたときに読み込む） */
static void push_impl(lua_State *L) {
    if (lua_rawgetp(L, LUA_REGISTRYINDEX, &IMPL_KEY) == LUA_TTABLE) return;
    lua_pop(L, 1);
    if (luaL_loadbufferx(L, IMPL_SOURCE, sizeof IMPL_SOURCE - 1, "=fs", "t") != LUA_OK) lua_error(L);
    lua_pushcfunction(L, l_list);
    lua_pushcfunction(L, l_isDir);
    lua_pushcfunction(L, l_exists);
    lua_pushcfunction(L, l_combine);
    luaL_getsubtable(L, LUA_REGISTRYINDEX, LUA_LOADED_TABLE);
    lua_getfield(L, -1, LUA_TABLIBNAME);
    lua_getfield(L, -1, "sort");
    lua_remove(L, -2);
    lua_remove(L, -2);
    lua_call(L, 5, 1);
    lua_pushvalue(L, -1);
    lua_rawsetp(L, LUA_REGISTRYINDEX, &IMPL_KEY);
}

/* Lua で書いた部分の [name] に、引数をそのまま渡す */
static int call_impl(lua_State *L, const char *name) {
    int n = lua_gettop(L);
    push_impl(L);
    lua_getfield(L, -1, name);
    lua_remove(L, -2);
    lua_insert(L, 1);
    lua_call(L, n, LUA_MULTRET);
    return lua_gettop(L);
}

static int l_find(lua_State *L) {
    return call_impl(L, "find");
}

static int l_complete(lua_State *L) {
    return call_impl(L, "complete");
}

/* fs.open(path, mode): ハンドルか、nil と文 */
static int l_open(lua_State *L) {
    const char *abs = check_abs(L, 1);
    int pathIdx = lua_gettop(L);
    const char *mode = luaL_checkstring(L, 2);
    size_t ml = strlen(mode);
    int binary = ml > 0 && mode[ml - 1] == 'b';
    if (binary) ml--;
    const char *iomode;
    int canRead, canWrite;
    if (ml == 1 && mode[0] == 'r') iomode = "rb", canRead = 1, canWrite = 0;
    else if (ml == 1 && mode[0] == 'w') iomode = "wb", canRead = 0, canWrite = 1;
    else if (ml == 1 && mode[0] == 'a') iomode = "ab", canRead = 0, canWrite = 1;
    else if (ml == 2 && mode[0] == 'r' && mode[1] == '+') iomode = "r+b", canRead = 1, canWrite = 1;
    else if (ml == 2 && mode[0] == 'w' && mode[1] == '+') iomode = "w+b", canRead = 1, canWrite = 1;
    else return luaL_error(L, "Unsupported mode");
    struct stat st;
    if (stat(abs, &st) == 0 && S_ISDIR(st.st_mode)) {
        lua_pushnil(L);
        lua_pushfstring(L, "%s: %s", abs, canWrite && mode[0] != 'r' ? "Cannot write to directory" : "Is a directory");
        return 2;
    }
    /* 書くときは親のディレクトリを作る（CC と同じ。作れなければ、開くときのエラーになる） */
    if (mode[0] != 'r') {
        const char *slash = strrchr(abs, '/');
        if (slash != NULL && slash != abs) {
            lua_pushcfunction(L, l_makeDir);
            lua_pushlstring(L, abs, (size_t)(slash - abs));
            (void)lua_pcall(L, 1, 0, 0);
            lua_settop(L, pathIdx);
        }
    }
    luaL_getsubtable(L, LUA_REGISTRYINDEX, LUA_LOADED_TABLE);
    lua_getfield(L, -1, LUA_IOLIBNAME);
    lua_getfield(L, -1, "open");
    lua_pushvalue(L, pathIdx);
    lua_pushstring(L, iomode);
    lua_call(L, 2, 3);
    if (lua_isnil(L, -3)) {
        int e = (int)lua_tointeger(L, -1);
        lua_pushnil(L);
        lua_pushfstring(L, "%s: %s", abs, cc_message(e));
        return 2;
    }
    lua_pop(L, 2);
    int file = lua_gettop(L);
    push_impl(L);
    lua_getfield(L, -1, "handle");
    lua_pushvalue(L, file);
    lua_pushboolean(L, binary);
    lua_pushboolean(L, canRead);
    lua_pushboolean(L, canWrite);
    lua_call(L, 4, 1);
    return 1;
}

static int fill_fs(lua_State *L) {
    static const luaL_Reg FUNCS[] = {
        {"combine", l_combine},
        {"getName", l_getName},
        {"getDir", l_getDir},
        {"exists", l_exists},
        {"isDir", l_isDir},
        {"isReadOnly", l_isReadOnly},
        {"getSize", l_getSize},
        {"attributes", l_attributes},
        {"list", l_list},
        {"makeDir", l_makeDir},
        {"delete", l_delete},
        {"copy", l_copy},
        {"move", l_move},
        {"open", l_open},
        {"find", l_find},
        {"complete", l_complete},
        {"getDrive", l_getDrive},
        {"isDriveRoot", l_isDriveRoot},
        {"getFreeSpace", l_getFreeSpace},
        {"getCapacity", l_getCapacity},
        {NULL, NULL},
    };
    for (const luaL_Reg *f = FUNCS; f->name; f++) kibi_lua_lazy_set(L, f->name, f->func);
    return 0;
}

int luaopen_fs(lua_State *L) {
    kibi_lua_lazy_lib(L, fill_fs);
    return 1;
}
