//! `shutdown [-h|-r] [now]`: マシンを止める（`-r` なら再起動する。`kibi:computer/power`。#306・#454）。受け付けたら戻らない。
//!
//! 時刻は `now` だけ（予約して止める仕組みはない）。`-h` は受け付けるだけ。`-r` が断られたら `shutdown: Not supported` で 1
const kibi = @import("kibi");
const cli = kibi.cli;

const SYNOPSIS = "shutdown [-h|-r] [now]";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var reboot = false;
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'h' => reboot = false,
        'r' => reboot = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const rest = opts.rest();
    if (rest.len > 1) return cli.usage("extra operand", SYNOPSIS);
    if (rest.len == 1 and !cli.eql(rest[0], "now")) return cli.usage("only 'now' is supported", SYNOPSIS);
    cli.stdout.flush();
    if (!reboot) kibi.power.shutdown();
    cli.warnErr(null, kibi.power.reboot());
    return 1;
}
