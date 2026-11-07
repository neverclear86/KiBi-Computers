//! `dirname パス...`: パスの親の部分を書く（`/` を含まなければ `.`、根の直下なら `/`）
const kibi = @import("kibi");
const cli = kibi.cli;

const SYNOPSIS = "dirname path...";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    if (opts.next()) |c| return cli.badOption(c, SYNOPSIS);
    const paths = opts.rest();
    if (paths.len == 0) return cli.usage("missing operand", SYNOPSIS);
    for (paths) |p| {
        cli.stdout.write(cli.dirname(p));
        cli.stdout.byte('\n');
    }
    cli.stdout.flush();
    return 0;
}
