//! シェル `/bin/sh`（#101）を確かめるための外のプログラム（core のテスト `ShellTest` が置く）。
//! 名前（argv[0] の最後の部分）で振る舞いを変える。基本コマンド（#103）がまだないので、その代わり:
//!
//! - `cat [ファイル...]`: ファイル（なければ標準入力）を標準出力に写す
//! - `args 引数...`: 引数を `<...>` で囲んで 1 行に書く
//! - `envget 名前...`: 環境変数を `名前=値`（なければ `名前 unset`）で書く
//! - `exitwith n`: 終了コード n で終わる
//! - `errout 文字列`: 標準エラー出力に書く
const kibi = @import("kibi");
const io = kibi.io;
const process = kibi.process;

pub fn main() u8 {
    const argv = process.args() catch return 100;
    if (argv.len == 0) return 101;
    const name = basename(argv[0]);
    const rest = argv[1..];
    if (eql(name, "cat")) return cat(rest);
    if (eql(name, "args")) {
        for (rest, 0..) |a, i| {
            if (i > 0) io.out(" ");
            io.out("<");
            io.out(a);
            io.out(">");
        }
        io.out("\n");
        return 0;
    }
    if (eql(name, "envget")) {
        for (rest) |n| {
            io.out(n);
            if (process.getenv(n)) |v| {
                io.out("=");
                io.out(v);
            } else io.out(" unset");
            io.out("\n");
        }
        return 0;
    }
    if (eql(name, "exitwith")) {
        if (rest.len == 0) return 0;
        return @intCast((io.parseUnsigned(rest[0]) orelse 0) & 0xff);
    }
    if (eql(name, "errout")) {
        for (rest, 0..) |a, i| {
            if (i > 0) io.err(" ");
            io.err(a);
        }
        io.err("\n");
        return 0;
    }
    return 102;
}

var buf: [4096]u8 = undefined;

fn cat(files: []const []const u8) u8 {
    if (files.len == 0) return copy(io.STDIN);
    var status: u8 = 0;
    for (files) |f| {
        const fd = kibi.fs.open(f, .{}) catch {
            io.err("cat: ");
            io.err(f);
            io.err(": cannot open\n");
            status = 1;
            continue;
        };
        status |= copy(fd);
        io.close(fd) catch {};
    }
    return status;
}

fn copy(fd: io.Fd) u8 {
    while (true) {
        const n = io.read(fd, &buf) catch return 1;
        if (n == 0) return 0;
        io.writeAll(io.STDOUT, buf[0..n]) catch return 1;
    }
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}

fn basename(path: []const u8) []const u8 {
    var i = path.len;
    while (i > 0 and path[i - 1] != '/') i -= 1;
    return path[i..];
}
