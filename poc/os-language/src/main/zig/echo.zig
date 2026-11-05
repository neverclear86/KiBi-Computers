//! `echo`: 引数を空白でつないで1行で書く（#32）
const sys = @import("sys.zig");

var line: [4096]u8 = undefined;

pub fn main() void {
    const argv = sys.args();
    var n: usize = 0;
    for (argv, 0..) |arg, i| {
        if (i == 0) continue;
        const s = sys.span(arg);
        if (i > 1 and n < line.len) {
            line[n] = ' ';
            n += 1;
        }
        const k = @min(s.len, line.len - n);
        @memcpy(line[n..][0..k], s[0..k]);
        n += k;
    }
    if (n < line.len) {
        line[n] = '\n';
        n += 1;
    }
    if (!sys.writeAll(sys.STDOUT, line[0..n])) sys.proc_exit(1);
}
