//! `yes [文字列...]`: 引数を空白でつないだ行（既定は `y`）を、書けなくなる（読み手が閉じる）まで書き続ける
const kibi = @import("kibi");
const io = kibi.io;

var line_buf: [4096]u8 = undefined;
var block: [8192]u8 = undefined;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        kibi.cli.warnErr(null, e);
        return 1;
    };
    const words: []const []const u8 = if (argv.len > 1) argv[1..] else &.{"y"};
    var n: usize = 0;
    for (words, 0..) |w, i| {
        if (i > 0) n = put(&line_buf, n, " ");
        n = put(&line_buf, n, w);
    }
    // 長すぎる行は切らずに、そのまま書き続ける
    if (n + 1 > line_buf.len) {
        while (true) {
            for (words, 0..) |w, i| {
                if (i > 0) io.writeAll(io.STDOUT, " ") catch return 0;
                io.writeAll(io.STDOUT, w) catch return 0;
            }
            io.writeAll(io.STDOUT, "\n") catch return 0;
        }
    }
    line_buf[n] = '\n';
    const line = line_buf[0 .. n + 1];
    // 行を何度か並べたものをまとめて書く
    var len: usize = 0;
    while (len + line.len <= block.len) : (len += line.len) @memcpy(block[len..][0..line.len], line);
    while (true) io.writeAll(io.STDOUT, block[0..len]) catch return 0;
}

/// [buf] の [n] から [s] を書く（入らない分は捨て、長さだけ進める）
fn put(buf: []u8, n: usize, s: []const u8) usize {
    if (n < buf.len) {
        const k = @min(s.len, buf.len - n);
        @memcpy(buf[n..][0..k], s[0..k]);
    }
    return n + s.len;
}
