//! `mkdir [-p] ディレクトリ...`: ディレクトリを作る。`-p` は途中のディレクトリも作り、あってもエラーにしない
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;

const SYNOPSIS = "mkdir [-p] directory...";

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
        const r = if (parents) mkdirParents(d) else fs.mkdir(d);
        r catch |e| {
            cli.warnErr(d, e);
            status = 1;
        };
    }
    return status;
}

/// [path] とその上のディレクトリを、なければ作る
fn mkdirParents(path: []const u8) kibi.Error!void {
    var buf: [fs.MAX_PATH]u8 = undefined;
    const abs = try fs.absolute(&buf, path);
    var i: usize = 2;
    while (i <= abs.len) : (i += 1) {
        if (i < abs.len and abs[i] != '/') continue;
        const part = abs[0..i];
        fs.mkdir(part) catch |e| {
            if (e != error.EXIST) return e;
            const st = try fs.stat(part);
            if (st.kind != .directory) return error.NOTDIR;
        };
    }
}
