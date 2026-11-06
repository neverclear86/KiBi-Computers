//! `head [-n 行数 | -行数] [ファイル...]`: 先頭の行（既定 10 行）を書く。
//! ファイルが 2 つ以上なら、それぞれの前に `==> 名前 <==` を書く（開けなかったファイルには書かない）
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;

const SYNOPSIS = "head [-n lines] [file...]";

var buf: [8192]u8 = undefined;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var lines: u64 = 10;
    var opts = cli.Args.init(argv);
    // 古い書き方 `head -5`
    if (argv.len > 1 and argv[1].len > 1 and argv[1][0] == '-') {
        if (io.parseUnsigned(argv[1][1..])) |n| {
            lines = n;
            opts.i = 2;
        }
    }
    while (opts.next()) |c| switch (c) {
        'n' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- n", SYNOPSIS);
            lines = io.parseUnsigned(v) orelse return cli.usage("invalid number of lines", SYNOPSIS);
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
        if (!head(fd, f, lines)) status = 1;
    }
    return status;
}

fn head(fd: io.Fd, path: []const u8, lines: u64) bool {
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
