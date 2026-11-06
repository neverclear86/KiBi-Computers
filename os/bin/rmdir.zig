//! `rmdir [-p] ディレクトリ...`: 空のディレクトリを消す。`-p` は書いたパスの上のディレクトリも順に消す
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;

const SYNOPSIS = "rmdir [-p] directory...";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var parents = false;
    while (opts.next()) |c| switch (c) {
        'p' => parents = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const dirs = opts.rest();
    if (dirs.len == 0) return cli.usage("missing operand", SYNOPSIS);
    var status: u8 = 0;
    for (dirs) |d| {
        fs.rmdir(d) catch |e| {
            cli.warnErr(d, e);
            status = 1;
            continue;
        };
        if (!parents) continue;
        // 書いたパスの上へ順に（`a/b/c` なら `a/b`・`a`）
        var end = d.len;
        while (true) {
            while (end > 0 and d[end - 1] == '/') end -= 1;
            while (end > 0 and d[end - 1] != '/') end -= 1;
            while (end > 1 and d[end - 1] == '/') end -= 1;
            if (end == 0 or (end == 1 and d[0] == '/')) break;
            fs.rmdir(d[0..end]) catch |e| {
                cli.warnErr(d[0..end], e);
                status = 1;
                break;
            };
        }
    }
    return status;
}
