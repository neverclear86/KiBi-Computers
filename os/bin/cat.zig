//! `cat [ファイル...]`: ファイルを順に標準出力に書く。ファイルがないか `-` なら標準入力
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;

const SYNOPSIS = "cat [-u] [file...]";

var buf: [8192]u8 = undefined;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    // POSIX の -u（バッファしない）は、もともとまとめないので受け付けるだけ
    while (opts.next()) |c| if (c != 'u') return cli.badOption(c, SYNOPSIS);
    const files = opts.rest();
    var status: u8 = 0;
    if (files.len == 0) {
        if (!copy("-")) status = 1;
    } else for (files) |f| {
        if (!copy(f)) status = 1;
    }
    return status;
}

/// [path] を写す。失敗したら知らせて false
fn copy(path: []const u8) bool {
    const fd = cli.openInput(path) catch |e| {
        cli.warnErr(path, e);
        return false;
    };
    defer cli.closeInput(fd);
    while (true) {
        const n = io.read(fd, &buf) catch |e| {
            cli.warnErr(path, e);
            return false;
        };
        if (n == 0) return true;
        io.writeAll(io.STDOUT, buf[0..n]) catch |e| {
            // 読み手が閉じたパイプ（`cat f | head`）は黙って終わる
            if (e != error.PIPE) cli.warnErr("write error", e);
            kibi.process.exit(1);
        };
    }
}
