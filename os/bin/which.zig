//! `which [-a] 名前...`: `PATH`（なければ `/bin:/sbin`）から、名前のプログラムのパスを書く。
//!
//! - `-a`: 見つかったものを全部書く（既定は最初の 1 つ）
//! - 見つからない名前が 1 つでもあれば終了コード 1（知らせは書かない）。シェルの組み込み・関数は探さない（それは `command -v`）
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;

const SYNOPSIS = "which [-a] name...";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var all = false;
    while (opts.next()) |c| switch (c) {
        'a' => all = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const names = opts.rest();
    if (names.len == 0) return cli.usage("missing operand", SYNOPSIS);
    const search = kibi.process.getenv("PATH") orelse cli.DEFAULT_PATH;
    var status: u8 = 0;
    for (names) |name| {
        if (!find(name, search, all)) status = 1;
    }
    cli.stdout.flush();
    return status;
}

fn find(name: []const u8, search: []const u8, all: bool) bool {
    var buf: [fs.MAX_PATH]u8 = undefined;
    var slash = name.len == 0;
    for (name) |c| slash = slash or c == '/';
    if (slash) {
        const p = cli.findProgram(&buf, name, "") orelse return false;
        const st = fs.stat(p) catch return false;
        if (st.kind != .file) return false;
        cli.stdout.write(name);
        cli.stdout.byte('\n');
        return true;
    }
    var found = false;
    var rest = search;
    while (true) {
        var end: usize = 0;
        while (end < rest.len and rest[end] != ':') end += 1;
        const dir = if (end == 0) "." else rest[0..end];
        if (cli.join(&buf, dir, name)) |joined| {
            if (fs.stat(joined)) |st| {
                if (st.kind == .file) {
                    cli.stdout.write(joined);
                    cli.stdout.byte('\n');
                    found = true;
                    if (!all) return true;
                }
            } else |_| {}
        } else |_| {}
        if (end == rest.len) break;
        rest = rest[end + 1 ..];
    }
    return found;
}
