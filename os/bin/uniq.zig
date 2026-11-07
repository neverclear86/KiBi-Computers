//! `uniq [-cdui] [入力 [出力]]`: 続けて同じ行を 1 行にまとめて書く（並んでいない同じ行はまとめない。先に `sort` する）。
//!
//! - `-c`: 行の前に数を書く（7 桁に右寄せと空白 1 つ）
//! - `-d`: 2 行以上続いた行だけ、`-u`: 続かなかった行だけ書く
//! - `-i`: 英字の大小を区別しない
//! - 入力の `-` は標準入力。1 行は [LINE_MAX] バイトまで（長い行は切って別の行として扱う）
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;
const fs = kibi.fs;

const SYNOPSIS = "uniq [-cdui] [input [output]]";

const LINE_MAX = 64 * 1024;

var read_buf: [LINE_MAX]u8 = undefined;
var prev_buf: [LINE_MAX]u8 = undefined;
var out_buf: [4096]u8 = undefined;

var count = false;
var only_dup = false;
var only_single = false;
var fold = false;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'c' => count = true,
        'd' => only_dup = true,
        'u' => only_single = true,
        'i' => fold = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const rest = opts.rest();
    if (rest.len > 2) return cli.usage("extra operand", SYNOPSIS);
    const input = if (rest.len > 0) rest[0] else "-";
    const fd = cli.openInput(input) catch |e| {
        cli.warnErr(input, e);
        return 1;
    };
    defer cli.closeInput(fd);
    var w = cli.Writer{ .fd = io.STDOUT, .buf = &out_buf };
    if (rest.len == 2) {
        w.fd = fs.open(rest[1], .{ .read = false, .write = true, .create = true, .truncate = true }) catch |e| {
            cli.warnErr(rest[1], e);
            return 1;
        };
    }
    defer if (rest.len == 2) io.close(w.fd) catch {};

    var reader = cli.LineReader.init(fd, &read_buf);
    var prev_len: usize = 0;
    var have_prev = false;
    var n: u64 = 0;
    var status: u8 = 0;
    while (true) {
        const line = reader.next() catch |e| {
            cli.warnErr(input, e);
            status = 1;
            break;
        } orelse break;
        if (have_prev and same(prev_buf[0..prev_len], line.text)) {
            n += 1;
            continue;
        }
        if (have_prev) emit(&w, prev_buf[0..prev_len], n);
        @memcpy(prev_buf[0..line.text.len], line.text);
        prev_len = line.text.len;
        have_prev = true;
        n = 1;
        if (w.failed) break;
    }
    if (have_prev) emit(&w, prev_buf[0..prev_len], n);
    w.flush();
    return status;
}

fn lower(c: u8) u8 {
    return if (c >= 'A' and c <= 'Z') c + 32 else c;
}

fn same(a: []const u8, b: []const u8) bool {
    if (!fold) return cli.eql(a, b);
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (lower(x) != lower(y)) return false;
    return true;
}

fn emit(w: *cli.Writer, line: []const u8, n: u64) void {
    if (only_dup and n < 2) return;
    if (only_single and n > 1) return;
    if (count) {
        w.uint(n, 7);
        w.byte(' ');
    }
    w.write(line);
    w.byte('\n');
}
