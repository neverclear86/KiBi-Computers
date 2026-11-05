//! `ls`: ディレクトリ（引数。なければ `/`）の名前を1行ずつ書く（#32）。`.`・`..` は書かない。並びは fd_readdir の順
const sys = @import("sys.zig");

var buf: [4096]u8 = undefined;

/// dirent: next(u64) ino(u64) namlen(u32) type(u8) と 3 バイトの詰め物 = 24 バイト
const DIRENT = 24;

fn u32At(b: []const u8, i: usize) u32 {
    return @as(u32, b[i]) | @as(u32, b[i + 1]) << 8 | @as(u32, b[i + 2]) << 16 | @as(u32, b[i + 3]) << 24;
}

fn u64At(b: []const u8, i: usize) u64 {
    return @as(u64, u32At(b, i)) | @as(u64, u32At(b, i + 4)) << 32;
}

pub fn main() void {
    const argv = sys.args();
    const path = if (argv.len > 1) sys.span(argv[1]) else "/";
    const fd = sys.open(path, true) orelse {
        _ = sys.writeAll(sys.STDERR, "ls: cannot open ");
        _ = sys.writeAll(sys.STDERR, path);
        _ = sys.writeAll(sys.STDERR, "\n");
        sys.proc_exit(1);
    };
    var cookie: u64 = 0;
    while (true) {
        var used: u32 = 0;
        if (sys.fd_readdir(fd, &buf, buf.len, cookie, &used) != 0) sys.proc_exit(1);
        var i: usize = 0;
        var progressed = false;
        while (i + DIRENT <= used) {
            const namlen = u32At(&buf, i + 16);
            // 名前が切れていたら、この項目の cookie から読み直す
            if (i + DIRENT + namlen > used) break;
            const name = buf[i + DIRENT ..][0..namlen];
            cookie = u64At(&buf, i);
            progressed = true;
            const dot = namlen == 1 and name[0] == '.';
            const dotdot = namlen == 2 and name[0] == '.' and name[1] == '.';
            if (!dot and !dotdot) {
                _ = sys.writeAll(sys.STDOUT, name);
                _ = sys.writeAll(sys.STDOUT, "\n");
            }
            i += DIRENT + namlen;
        }
        // バッファが埋まらなければ終わり
        if (used < buf.len or !progressed) break;
    }
    _ = sys.fd_close(fd);
}
