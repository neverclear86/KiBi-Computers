//! `reboot`: マシンを再起動する（`kibi:computer/power`。#306・#454）。受け付けたら戻らない。
//!
//! 断られたら（起動を通らずに作ったマシン・サーバーを閉じる途中）`reboot: Not supported` で 1
const kibi = @import("kibi");
const cli = kibi.cli;

const SYNOPSIS = "reboot";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| return cli.badOption(c, SYNOPSIS);
    if (opts.rest().len > 0) return cli.usage("extra operand", SYNOPSIS);
    cli.stdout.flush();
    cli.warnErr(null, kibi.power.reboot());
    return 1;
}
