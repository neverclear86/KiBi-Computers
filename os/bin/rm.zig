//! `rm [-rRf] パス...`: ファイルを消す。`-r`（`-R`）はディレクトリを中身ごと、`-f` はないものを黙って飛ばす
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;

const SYNOPSIS = "rm [-rRf] file...";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var recursive = false;
    var force = false;
    while (opts.next()) |c| switch (c) {
        'r', 'R' => recursive = true,
        'f' => force = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const paths = opts.rest();
    if (paths.len == 0) return if (force) 0 else cli.usage("missing operand", SYNOPSIS);
    var status: u8 = 0;
    for (paths) |p| {
        const base = cli.basename(p);
        if (cli.eql(base, ".") or cli.eql(base, "..") or cli.eql(base, "/")) {
            cli.warn(p, "refusing to remove '.', '..' or '/'");
            status = 1;
            continue;
        }
        const st = fs.lstat(p) catch |e| {
            if (!(force and e == error.NOENT)) {
                cli.warnErr(p, e);
                status = 1;
            }
            continue;
        };
        if (st.kind == .directory and !recursive) {
            cli.warnErr(p, error.ISDIR);
            status = 1;
            continue;
        }
        if (!kibi.tree.remove(p)) status = 1;
    }
    return status;
}
