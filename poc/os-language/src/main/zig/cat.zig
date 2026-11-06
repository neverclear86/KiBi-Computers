//! `cat`: 引数のファイル（なければ標準入力）を標準出力へ写す（#32）
const sys = @import("sys.zig");

var buf: [4096]u8 = undefined;

fn copy(fd: i32) bool {
    while (true) {
        const n = sys.read(fd, &buf) orelse return false;
        if (n == 0) return true;
        if (!sys.writeAll(sys.STDOUT, buf[0..n])) return false;
    }
}

pub fn main() void {
    const argv = sys.args();
    if (argv.len <= 1) {
        if (!copy(sys.STDIN)) sys.proc_exit(1);
        return;
    }
    var failed = false;
    for (argv[1..]) |arg| {
        const path = sys.span(arg);
        const fd = sys.open(path, false) orelse {
            _ = sys.writeAll(sys.STDERR, "cat: cannot open ");
            _ = sys.writeAll(sys.STDERR, path);
            _ = sys.writeAll(sys.STDERR, "\n");
            failed = true;
            continue;
        };
        if (!copy(fd)) failed = true;
        _ = sys.fd_close(fd);
    }
    if (failed) sys.proc_exit(1);
}
