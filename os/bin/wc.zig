//! `wc [-lwc] [ファイル...]`: 行・語・バイトの数を書く（既定は 3 つとも。この順に並べる）。
//!
//! 数は、全部の数のうち一番大きいものの桁にそろえる（数 1 つだけを書くなら桁をそろえない）。ファイルが 2 つ以上なら最後に合計を書く
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;

const SYNOPSIS = "wc [-lwc] [file...]";

const Counts = struct { lines: u64 = 0, words: u64 = 0, bytes: u64 = 0, ok: bool = true };

var buf: [8192]u8 = undefined;
var results: [kibi.process.MAX_ARGS]Counts = undefined;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var show_l = false;
    var show_w = false;
    var show_c = false;
    while (opts.next()) |c| switch (c) {
        'l' => show_l = true,
        'w' => show_w = true,
        'c' => show_c = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    if (!show_l and !show_w and !show_c) {
        show_l = true;
        show_w = true;
        show_c = true;
    }
    const files = opts.rest();
    const names: []const []const u8 = if (files.len == 0) &.{"-"} else files;
    var total = Counts{};
    var status: u8 = 0;
    for (names, 0..) |f, i| {
        results[i] = count(f);
        if (!results[i].ok) status = 1;
        total.lines += results[i].lines;
        total.words += results[i].words;
        total.bytes += results[i].bytes;
    }
    const columns = @as(usize, @intFromBool(show_l)) + @intFromBool(show_w) + @intFromBool(show_c);
    const width = if (columns == 1 and names.len == 1) 0 else cli.digits(@max(total.lines, @max(total.words, total.bytes)));
    for (names, 0..) |f, i| {
        if (!results[i].ok) continue;
        print(results[i], show_l, show_w, show_c, width, if (files.len == 0) null else f);
    }
    if (names.len > 1) print(total, show_l, show_w, show_c, width, "total");
    cli.stdout.flush();
    return status;
}

fn print(c: Counts, l: bool, w: bool, b: bool, width: usize, name: ?[]const u8) void {
    var first = true;
    const vals = [_]struct { on: bool, v: u64 }{ .{ .on = l, .v = c.lines }, .{ .on = w, .v = c.words }, .{ .on = b, .v = c.bytes } };
    for (vals) |x| {
        if (!x.on) continue;
        if (!first) cli.stdout.byte(' ');
        first = false;
        cli.stdout.uint(x.v, width);
    }
    if (name) |n| {
        cli.stdout.byte(' ');
        cli.stdout.write(n);
    }
    cli.stdout.byte('\n');
}

fn count(path: []const u8) Counts {
    var c = Counts{};
    const fd = cli.openInput(path) catch |e| {
        cli.warnErr(path, e);
        return .{ .ok = false };
    };
    defer cli.closeInput(fd);
    var in_word = false;
    while (true) {
        const n = io.read(fd, &buf) catch |e| {
            cli.warnErr(path, e);
            return .{ .ok = false };
        };
        if (n == 0) break;
        c.bytes += n;
        for (buf[0..n]) |ch| {
            if (ch == '\n') c.lines += 1;
            const space = ch == ' ' or ch == '\t' or ch == '\n' or ch == '\r' or ch == 0x0b or ch == 0x0c;
            if (!space and !in_word) c.words += 1;
            in_word = !space;
        }
    }
    return c;
}
