//! `head [-n 行数 | -行数 | -c バイト数] [ファイル...]`: 先頭の行（既定 10 行）か、先頭のバイト（`-c`）を書く。
//! ファイルが 2 つ以上なら、それぞれの前に `==> 名前 <==` を書く（開けなかったファイルには書かない）
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;

const SYNOPSIS = "head [-n lines | -c bytes] [file...]";

var buf: [8192]u8 = undefined;

/// 何を数えるか（後に書いた `-n`・`-c` が勝つ）
const Unit = enum { lines, bytes };

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var count: u64 = 10;
    var unit: Unit = .lines;
    var opts = cli.Args.init(argv);
    // 古い書き方 `head -5`
    if (argv.len > 1 and argv[1].len > 1 and argv[1][0] == '-') {
        if (io.parseUnsigned(argv[1][1..])) |n| {
            count = n;
            opts.i = 2;
        }
    }
    while (opts.next()) |c| switch (c) {
        'n' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- n", SYNOPSIS);
            count = io.parseUnsigned(v) orelse return cli.usage("invalid number of lines", SYNOPSIS);
            unit = .lines;
        },
        'c' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- c", SYNOPSIS);
            count = io.parseUnsigned(v) orelse return cli.usage("invalid number of bytes", SYNOPSIS);
            unit = .bytes;
        },
        else => return cli.badOption(c, SYNOPSIS),
    };
    const files = opts.rest();
    const names: []const []const u8 = if (files.len == 0) &.{"-"} else files;
    var status: u8 = 0;
    var shown: usize = 0;
    for (names) |f| {
        // 開いてから見出しを書く（開けなければ見出しなしでエラーだけ）
        const fd = cli.openInput(f) catch |e| {
            cli.warnErr(f, e);
            status = 1;
            continue;
        };
        defer cli.closeInput(fd);
        if (names.len > 1) {
            if (shown > 0) io.out("\n");
            io.out("==> ");
            io.out(if (cli.eql(f, "-")) "standard input" else f);
            io.out(" <==\n");
        }
        shown += 1;
        const ok = switch (unit) {
            .lines => headLines(fd, f, count),
            .bytes => headBytes(fd, f, count),
        };
        if (!ok) status = 1;
    }
    return status;
}

fn headLines(fd: io.Fd, path: []const u8, lines: u64) bool {
    var left = lines;
    while (left > 0) {
        const n = io.read(fd, &buf) catch |e| {
            cli.warnErr(path, e);
            return false;
        };
        if (n == 0) break;
        var end: usize = 0;
        while (end < n and left > 0) : (end += 1) {
            if (buf[end] == '\n') left -= 1;
        }
        io.writeAll(io.STDOUT, buf[0..end]) catch kibi.process.exit(1);
    }
    return true;
}

/// 先頭の [bytes] バイトを書く。要る分より多くは読まない（`/dev/zero` のような終わらない入力でも止まる）
fn headBytes(fd: io.Fd, path: []const u8, bytes: u64) bool {
    var left = bytes;
    while (left > 0) {
        const want: usize = @intCast(@min(left, buf.len));
        const n = io.read(fd, buf[0..want]) catch |e| {
            cli.warnErr(path, e);
            return false;
        };
        if (n == 0) break;
        io.writeAll(io.STDOUT, buf[0..n]) catch kibi.process.exit(1);
        left -= n;
    }
    return true;
}
