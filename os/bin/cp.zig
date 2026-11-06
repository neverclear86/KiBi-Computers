//! `cp [-rR] 元 先`・`cp [-rR] 元... ディレクトリ`: ファイルを写す。`-r`（`-R`）はディレクトリを中身ごと。
//! 先がディレクトリなら、その中に元と同じ名前で写す
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;

const SYNOPSIS = "cp [-rR] source... dest";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var recursive = false;
    while (opts.next()) |c| switch (c) {
        'r', 'R' => recursive = true,
        // 既定で上書きするので -f は受け付けるだけ
        'f' => {},
        else => return cli.badOption(c, SYNOPSIS),
    };
    const ops = opts.rest();
    if (ops.len < 2) return cli.usage("missing operand", SYNOPSIS);
    const dest = ops[ops.len - 1];
    const sources = ops[0 .. ops.len - 1];
    const dest_is_dir = if (fs.stat(dest)) |s| s.kind == .directory else |_| false;
    if (sources.len > 1 and !dest_is_dir) {
        cli.warnErr(dest, error.NOTDIR);
        return 1;
    }
    var status: u8 = 0;
    for (sources) |src| {
        var buf: [fs.MAX_PATH]u8 = undefined;
        const target = if (dest_is_dir) cli.join(&buf, dest, cli.basename(src)) catch |e| {
            cli.warnErr(src, e);
            status = 1;
            continue;
        } else dest;
        if (!kibi.tree.copy(src, target, recursive)) status = 1;
    }
    return status;
}
