//! `tee [-ai] [ファイル...]`: 標準入力を、標準出力とファイルに写す。
//!
//! - `-a`: ファイルを空にせず、後ろに足す。`-i`: Ctrl+C（INT）を無視する
//! - 書けなくなったファイルは知らせて以後は書かない（終了コード 1）。標準出力が書けなくなった（読み手が閉じた）ときは、
//!   知らせずに標準出力だけやめ、ファイルには書き続ける
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;
const fs = kibi.fs;

const SYNOPSIS = "tee [-ai] [file...]";

const Out = struct { fd: io.Fd, name: []const u8, ok: bool };

var outs: [kibi.process.MAX_ARGS]Out = undefined;
var buf: [8192]u8 = undefined;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var append = false;
    while (opts.next()) |c| switch (c) {
        'a' => append = true,
        'i' => kibi.process.setSignalIgnored(.interrupt, true) catch {},
        else => return cli.badOption(c, SYNOPSIS),
    };
    var status: u8 = 0;
    var n: usize = 0;
    outs[0] = .{ .fd = io.STDOUT, .name = "standard output", .ok = true };
    n = 1;
    for (opts.rest()) |path| {
        const fd = fs.open(path, .{ .read = false, .write = true, .create = true, .truncate = !append, .append = append }) catch |e| {
            cli.warnErr(path, e);
            status = 1;
            continue;
        };
        outs[n] = .{ .fd = fd, .name = path, .ok = true };
        n += 1;
    }
    while (true) {
        const got = io.read(io.STDIN, &buf) catch |e| {
            cli.warnErr("standard input", e);
            status = 1;
            break;
        };
        if (got == 0) break;
        var live: usize = 0;
        for (outs[0..n], 0..) |*o, i| {
            if (!o.ok) continue;
            io.writeAll(o.fd, buf[0..got]) catch |e| {
                o.ok = false;
                if (i > 0) {
                    cli.warnErr(o.name, e);
                    status = 1;
                }
                continue;
            };
            live += 1;
        }
        if (live == 0) break;
    }
    for (outs[1..n]) |o| io.close(o.fd) catch {};
    return status;
}
