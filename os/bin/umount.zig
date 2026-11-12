//! `umount 位置...`: マウントを外す（`kibi:fs/mounts` の `unmount`。#56・#454）。
//!
//! 位置は cwd につないで畳んでから渡す（末尾の `/` も落ちる）。マウントの位置でなければ `Invalid argument`、
//! `/`・`/tmp`・`/dev`・下にほかのマウントがあれば `Device or resource busy`。開いたファイルがあっても外す（その後の操作は `IO`）
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;

const SYNOPSIS = "umount dir...";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| return cli.badOption(c, SYNOPSIS);
    const operands = opts.rest();
    if (operands.len == 0) return cli.usage("missing operand", SYNOPSIS);
    var status: u8 = 0;
    for (operands) |p| {
        var buf: [fs.MAX_PATH]u8 = undefined;
        const point = fs.absolute(&buf, p) catch |e| {
            cli.warnErr(p, e);
            status = 1;
            continue;
        };
        kibi.mounts.unmount(point) catch |e| {
            cli.warnErr(p, e);
            status = 1;
        };
    }
    return status;
}
