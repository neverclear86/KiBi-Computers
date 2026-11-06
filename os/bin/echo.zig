//! `echo [-n] [文字列...]`: 引数を空白でつないで書く。`-n` は末尾の改行を書かない
const kibi = @import("kibi");

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        kibi.io.err("echo: ");
        kibi.io.err(kibi.errno.describe(e));
        kibi.io.err("\n");
        return 1;
    };
    var words = if (argv.len > 0) argv[1..] else argv;
    var newline = true;
    if (words.len > 0 and eql(words[0], "-n")) {
        newline = false;
        words = words[1..];
    }
    for (words, 0..) |w, i| {
        if (i > 0) kibi.io.writeAll(kibi.io.STDOUT, " ") catch return 1;
        kibi.io.writeAll(kibi.io.STDOUT, w) catch return 1;
    }
    if (newline) kibi.io.writeAll(kibi.io.STDOUT, "\n") catch return 1;
    return 0;
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}
