//! `touch [-c] ファイル...`: ファイルの時刻を今にする。なければ空のファイルを作る（`-c` なら作らない）
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;

const SYNOPSIS = "touch [-c] file...";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var no_create = false;
    while (opts.next()) |c| switch (c) {
        'c' => no_create = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const files = opts.rest();
    if (files.len == 0) return cli.usage("missing file operand", SYNOPSIS);
    var status: u8 = 0;
    for (files) |f| {
        touch(f, no_create) catch |e| {
            cli.warnErr(f, e);
            status = 1;
        };
    }
    return status;
}

fn touch(path: []const u8, no_create: bool) kibi.Error!void {
    fs.touchTimes(path) catch |e| {
        if (e != error.NOENT) return e;
        if (no_create) return;
        const fd = try fs.open(path, .{ .read = false, .write = true, .create = true });
        kibi.io.close(fd) catch {};
    };
}
