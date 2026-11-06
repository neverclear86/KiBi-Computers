//! `mv 元 先`・`mv 元... ディレクトリ`: 名前を変える・動かす。先がディレクトリなら、その中に元と同じ名前で動かす。
//! 別のファイルシステムへ（名前の変更が `XDEV`）なら、中身ごと写してから元を消す
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;

const SYNOPSIS = "mv source... dest";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    // 既定で上書きするので -f は受け付けるだけ
    while (opts.next()) |c| if (c != 'f') return cli.badOption(c, SYNOPSIS);
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
        fs.rename(src, target) catch |e| {
            if (e != error.XDEV) {
                cli.warnErr(src, e);
                status = 1;
                continue;
            }
            if (!kibi.tree.copy(src, target, true) or !kibi.tree.remove(src)) status = 1;
        };
    }
    return status;
}
