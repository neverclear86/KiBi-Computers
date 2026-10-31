/*
 * Lua を WASI（wasm32-wasi）向けにビルドするための設定（#91）。
 * zig cc に -include で渡し、Lua のソースは書き換えない。
 *
 * WASI にはプロセスの生成も一時ファイルの名前づけもないので、
 * それを使う関数は「使えない」と返すようにする。
 */
#ifndef KIBI_WASI_H
#define KIBI_WASI_H

/* os.execute: シェルはない。引数なしなら false、コマンドを渡すと失敗を返す */
#define l_system(cmd) ((cmd) == NULL ? 0 : -1)

/* os.tmpname: 一時ファイルの名前は作れない（"unable to generate a unique filename" になる） */
#define LUA_TMPNAMBUFSIZE 32
#define lua_tmpnam(b, e) { (void)(b); e = -1; }

/*
 * io.popen は LUA_USE_POSIX を定義しないので、Lua 標準の "'popen' not supported" になる。
 * package.loadlib・C のモジュールは LUA_USE_DLOPEN を定義しないので使えない。
 * io.tmpfile は kibi_wasi.c で失敗を返す。
 */

#endif
