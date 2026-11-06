//! `tail [-n [+]行数 | -行数] [ファイル...]`: 最後の行（既定 10 行）を書く。`+N` なら N 行目から最後まで。
//!
//! 普通のファイルは後ろから読む。パイプなど位置を動かせない入力は、最後の [KEEP] バイトだけを覚えて読み通す
//! （それより長い最後の行々は、覚えた分だけを書く）。`-f` はまだない（ファイルの変更を待つ口がない）
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;
const fs = kibi.fs;

const SYNOPSIS = "tail [-n [+]lines] [file...]";

/// 位置を動かせない入力で覚えるバイト数
const KEEP = 64 * 1024;

var buf: [8192]u8 = undefined;
var keep: [KEEP]u8 = undefined;

const Mode = union(enum) {
    /// 最後の n 行
    last: u64,
    /// n 行目から
    from: u64,
};

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var mode: Mode = .{ .last = 10 };
    var opts = cli.Args.init(argv);
    if (argv.len > 1 and argv[1].len > 1 and argv[1][0] == '-') {
        if (io.parseUnsigned(argv[1][1..])) |n| {
            mode = .{ .last = n };
            opts.i = 2;
        }
    }
    while (opts.next()) |c| switch (c) {
        'n' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- n", SYNOPSIS);
            mode = parseCount(v) orelse return cli.usage("invalid number of lines", SYNOPSIS);
        },
        else => return cli.badOption(c, SYNOPSIS),
    };
    const files = opts.rest();
    const names: []const []const u8 = if (files.len == 0) &.{"-"} else files;
    var status: u8 = 0;
    for (names, 0..) |f, i| {
        if (names.len > 1) {
            if (i > 0) io.out("\n");
            io.out("==> ");
            io.out(if (cli.eql(f, "-")) "standard input" else f);
            io.out(" <==\n");
        }
        tail(f, mode) catch |e| {
            cli.warnErr(f, e);
            status = 1;
        };
    }
    return status;
}

fn parseCount(v: []const u8) ?Mode {
    if (v.len > 0 and v[0] == '+') return .{ .from = io.parseUnsigned(v[1..]) orelse return null };
    if (v.len > 0 and v[0] == '-') return .{ .last = io.parseUnsigned(v[1..]) orelse return null };
    return .{ .last = io.parseUnsigned(v) orelse return null };
}

fn tail(path: []const u8, mode: Mode) kibi.Error!void {
    const fd = try cli.openInput(path);
    defer cli.closeInput(fd);
    switch (mode) {
        .from => |n| try fromLine(fd, n),
        .last => |n| {
            const st = fs.fstat(fd) catch null;
            if (st != null and st.?.kind == .file) {
                seekable(fd, st.?.size, n) catch |e| {
                    if (e != error.SPIPE) return e;
                    try lastLinesStream(fd, n);
                };
                return;
            }
            try lastLinesStream(fd, n);
        },
    }
}

/// [n] 行目（1 から）から最後まで写す
fn fromLine(fd: io.Fd, n: u64) kibi.Error!void {
    var skip = if (n > 0) n - 1 else 0;
    while (true) {
        const got = try io.read(fd, &buf);
        if (got == 0) return;
        var start: usize = 0;
        while (skip > 0 and start < got) : (start += 1) {
            if (buf[start] == '\n') skip -= 1;
        }
        try write(buf[start..got]);
    }
}

/// 普通のファイル（大きさ [size]）の最後の [n] 行を、後ろから読んで書く
fn seekable(fd: io.Fd, size: u64, n: u64) kibi.Error!void {
    var start: u64 = 0;
    if (n == 0) start = size else if (size > 1) {
        // 最後のバイトを除いた中の改行を、後ろから n 個数える
        var pos: u64 = size - 1;
        var found: u64 = 0;
        scan: while (pos > 0) {
            const chunk: usize = @intCast(@min(pos, buf.len));
            pos -= chunk;
            _ = try io.seek(fd, @intCast(pos), .set);
            const got = try io.readAll(fd, buf[0..chunk]);
            var k = got;
            while (k > 0) {
                k -= 1;
                if (buf[k] == '\n') {
                    found += 1;
                    if (found == n) {
                        start = pos + k + 1;
                        break :scan;
                    }
                }
            }
        }
    }
    _ = try io.seek(fd, @intCast(start), .set);
    while (true) {
        const got = try io.read(fd, &buf);
        if (got == 0) return;
        try write(buf[0..got]);
    }
}

/// 位置を動かせない入力の最後の [n] 行（最後の [KEEP] バイトの中から）
fn lastLinesStream(fd: io.Fd, n: u64) kibi.Error!void {
    var len: usize = 0;
    while (true) {
        if (len == keep.len) {
            // 前半を捨てる
            const half = keep.len / 2;
            var i: usize = 0;
            while (i < keep.len - half) : (i += 1) keep[i] = keep[half + i];
            len = keep.len - half;
        }
        const got = try io.read(fd, keep[len..]);
        if (got == 0) break;
        len += got;
    }
    var start: usize = if (n == 0) len else 0;
    if (n > 0 and len > 1) {
        var found: u64 = 0;
        var k = len - 1;
        while (k > 0) {
            k -= 1;
            if (keep[k] == '\n') {
                found += 1;
                if (found == n) {
                    start = k + 1;
                    break;
                }
            }
        }
    }
    try write(keep[start..len]);
}

fn write(bytes: []const u8) kibi.Error!void {
    io.writeAll(io.STDOUT, bytes) catch kibi.process.exit(1);
}
