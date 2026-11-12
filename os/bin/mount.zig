//! `mount`・`mount -l`・`mount [-r|-w] ディスク 位置`: マウントを書く・ディスクを書く・ディスクをマウントする（`kibi:fs/mounts`。#56・#453・#454）。
//!
//! - 引数なし: マウントを足した順に `source on 位置 type 種類 (ro|rw)`（種類は `disk`・`image`・`tmp`・`dev`）
//! - `-l`: 挿さっているディスクを `Disk 1K-blocks Entries Mounted on` の表で書く（マウントしていなければ位置は `-`。
//!   開いている途中のディスクも `-` に見えるが、そのときのマウントは `Device or resource busy`）。同梱イメージは出ない
//! - `ディスク 位置`: ディスク（`disk:<識別子>`。`disk:` を付けなければ足す）を位置にマウントする。`-r` は読み取り専用。
//!   位置は cwd につないで畳んでから渡す。位置がなくても、合成のディレクトリとしてマウントできる
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;
const mounts = kibi.mounts;

const SYNOPSIS = "mount [-l] | mount [-r|-w] disk dir";

const DISK_PREFIX = "disk:";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var read_only = false;
    var disks = false;
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'r' => read_only = true,
        'w' => read_only = false,
        'l' => disks = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const operands = opts.rest();
    if (operands.len == 0) {
        if (disks) listDisks() else listMounts();
        cli.stdout.flush();
        return @intFromBool(cli.stdout.failed);
    }
    if (disks) return cli.usage("-l takes no operands", SYNOPSIS);
    if (operands.len != 2) return cli.usage(if (operands.len < 2) "missing operand" else "extra operand", SYNOPSIS);

    var src_buf: [fs.MAX_PATH]u8 = undefined;
    const src = if (cli.startsWith(operands[0], DISK_PREFIX)) operands[0] else prefixed(&src_buf, operands[0]) orelse {
        cli.warnErr(operands[0], error.NAMETOOLONG);
        return 1;
    };
    var buf: [fs.MAX_PATH]u8 = undefined;
    const point = fs.absolute(&buf, operands[1]) catch |e| {
        cli.warnErr(operands[1], e);
        return 1;
    };
    mounts.mount(src, point, read_only) catch |e| {
        // ディスクが見つからないときはディスクを、ほか（使用中・位置の誤り）は位置を対象に書く
        cli.warnErr(if (e == error.NOENT) src else point, e);
        return 1;
    };
    return 0;
}

/// `disk:` を前に付ける
fn prefixed(buf: []u8, id: []const u8) ?[]const u8 {
    if (DISK_PREFIX.len + id.len > buf.len) return null;
    @memcpy(buf[0..DISK_PREFIX.len], DISK_PREFIX);
    @memcpy(buf[DISK_PREFIX.len..][0..id.len], id);
    return buf[0 .. DISK_PREFIX.len + id.len];
}

fn listMounts() void {
    const out = &cli.stdout;
    for (mounts.listMounts()) |*m| {
        out.printable(m.source.slice());
        out.write(" on ");
        out.printable(m.point.slice());
        out.write(" type ");
        out.write(switch (m.kind) {
            .disk => "disk",
            .image => "image",
            .tmp => "tmp",
            .dev => "dev",
            _ => "?",
        });
        out.write(if (m.readOnly()) " (ro)\n" else " (rw)\n");
        if (out.failed) return;
    }
}

fn listDisks() void {
    const list = mounts.listDisks();
    const header = [_][]const u8{ "Disk", "1K-blocks", "Entries" };
    var w = [_]usize{ header[0].len, header[1].len, header[2].len };
    for (list) |*d| {
        w[0] = @max(w[0], cli.charCount(d.source.slice()));
        w[1] = @max(w[1], cli.digits(kib(d.capacity_bytes)));
        w[2] = @max(w[2], cli.digits(d.max_entries));
    }
    const out = &cli.stdout;
    out.write(header[0]);
    out.pad(header[0].len, w[0]);
    out.byte(' ');
    out.pad(header[1].len, w[1]);
    out.write(header[1]);
    out.byte(' ');
    out.pad(header[2].len, w[2]);
    out.write(header[2]);
    out.write(" Mounted on\n");
    for (list) |*d| {
        out.printable(d.source.slice());
        out.pad(cli.charCount(d.source.slice()), w[0]);
        out.byte(' ');
        out.uint(kib(d.capacity_bytes), w[1]);
        out.byte(' ');
        out.uint(d.max_entries, w[2]);
        out.byte(' ');
        if (d.mountPoint()) |p| out.printable(p) else out.byte('-');
        out.byte('\n');
        if (out.failed) return;
    }
}

fn kib(bytes: u64) u64 {
    return bytes / 1024 + @intFromBool(bytes % 1024 != 0);
}
