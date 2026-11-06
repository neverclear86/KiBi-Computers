//! `du [-as] [-b|-k] [パス...]`: ファイルの大きさの合計を書く（パスがなければ `.`）。
//!
//! 既定はディレクトリごとに `KiB（切り上げ）<TAB>パス`。`-a` はファイルも、`-s` は引数ごとの合計だけ、`-b` はバイトで書く。
//! 数えるのはファイルの大きさ（ディスクの区画の数ではない）。中のシンボリックリンクはたどらない
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;
const io = kibi.io;

const SYNOPSIS = "du [-as] [-b|-k] [file...]";

const MAX_DEPTH = kibi.tree.MAX_DEPTH;

var dir_bufs: [MAX_DEPTH][1024]u8 = undefined;
var path: cli.PathBuf = .{};
var all = false;
var summary = false;
var bytes = false;
var ok = true;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'a' => all = true,
        's' => summary = true,
        'b' => bytes = true,
        'k' => bytes = false,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const operands = opts.rest();
    const paths: []const []const u8 = if (operands.len == 0) &.{"."} else operands;
    for (paths) |p| {
        const st = fs.stat(p) catch |e| {
            cli.warnErr(p, e);
            ok = false;
            continue;
        };
        path.set(p) catch |e| {
            cli.warnErr(p, e);
            ok = false;
            continue;
        };
        const total = walk(st, 0, true);
        // ファイルの引数は、-a でなくても書く
        if (summary or st.kind != .directory) print(total);
        if (cli.stdout.failed) break;
    }
    cli.stdout.flush();
    return @intFromBool(!ok or cli.stdout.failed);
}

/// [path] の大きさの合計。ディレクトリなら中を数え、-s でなければ書く
fn walk(st: fs.Stat, depth: usize, top: bool) u64 {
    if (st.kind != .directory) {
        if (all and !top and !summary) print(st.size);
        return st.size;
    }
    if (depth >= MAX_DEPTH) {
        cli.warnErr(path.slice(), error.LOOP);
        ok = false;
        return 0;
    }
    var total: u64 = 0;
    const fd = fs.open(path.slice(), .{ .directory = true }) catch |e| {
        cli.warnErr(path.slice(), e);
        ok = false;
        return 0;
    };
    defer io.close(fd) catch {};
    var dir = fs.Dir.init(fd, &dir_bufs[depth]);
    while (true) {
        const entry = (dir.next() catch |e| {
            cli.warnErr(path.slice(), e);
            ok = false;
            break;
        }) orelse break;
        const old = path.push(entry.name) catch |e| {
            cli.warnErr(path.slice(), e);
            ok = false;
            continue;
        };
        defer path.truncate(old);
        const child = fs.lstat(path.slice()) catch |e| {
            cli.warnErr(path.slice(), e);
            ok = false;
            continue;
        };
        total += walk(child, depth + 1, false);
    }
    if (!summary) print(total);
    return total;
}

fn print(size: u64) void {
    cli.stdout.uint(if (bytes) size else (size + 1023) / 1024, 0);
    cli.stdout.byte('\t');
    cli.stdout.write(path.slice());
    cli.stdout.byte('\n');
}
