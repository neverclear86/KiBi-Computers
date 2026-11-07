//! `basename パス [後ろ]`・`basename -a [-s 後ろ] パス...`: パスの最後の部分を書く（末尾の `/` は除く）。
//! 後ろ（`.txt` など）が付いていれば除く（名前全体と同じなら除かない）
const kibi = @import("kibi");
const cli = kibi.cli;

const SYNOPSIS = "basename path [suffix] | basename -a [-s suffix] path...";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var multiple = false;
    var suffix: []const u8 = "";
    while (opts.next()) |c| switch (c) {
        'a' => multiple = true,
        's' => {
            suffix = opts.value() orelse return cli.usage("option requires an argument -- s", SYNOPSIS);
            multiple = true;
        },
        else => return cli.badOption(c, SYNOPSIS),
    };
    var paths = opts.rest();
    if (paths.len == 0) return cli.usage("missing operand", SYNOPSIS);
    if (!multiple) {
        if (paths.len > 2) return cli.usage("extra operand", SYNOPSIS);
        if (paths.len == 2) suffix = paths[1];
        paths = paths[0..1];
    }
    for (paths) |p| {
        var name = cli.basename(p);
        if (suffix.len > 0 and name.len > suffix.len and cli.eql(name[name.len - suffix.len ..], suffix)) {
            name = name[0 .. name.len - suffix.len];
        }
        cli.stdout.write(name);
        cli.stdout.byte('\n');
    }
    cli.stdout.flush();
    return 0;
}
