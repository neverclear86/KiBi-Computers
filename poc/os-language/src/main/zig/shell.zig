//! シェルの読み取りループ（#32）: `> ` を出して1行読み、空白で区切った語を `[語]` の形で1行に書く。EOF で終わる。
//!
//! 語はヒープに写さず、行のバッファを指すスライスで持つ（Zig らしい書き方）。行は 4 KiB まで（超えた分は次の行として扱う）。
const sys = @import("sys.zig");

var input: [4096]u8 = undefined;
var start: usize = 0;
var end: usize = 0;
var words: [128][]const u8 = undefined;
var out: [8192]u8 = undefined;

fn indexOf(s: []const u8, c: u8) ?usize {
    for (s, 0..) |x, i| if (x == c) return i;
    return null;
}

/// 次の1行（改行は含まない）。EOF で残りがなければ null
fn readLine() ?[]const u8 {
    while (true) {
        if (indexOf(input[start..end], '\n')) |k| {
            const line = input[start .. start + k];
            start += k + 1;
            return line;
        }
        if (start > 0) {
            const rest = end - start;
            for (0..rest) |i| input[i] = input[start + i];
            start = 0;
            end = rest;
        }
        if (end == input.len) {
            // 改行のない長い行: そのまま1行として返す
            start = end;
            return input[0..end];
        }
        const n = sys.read(sys.STDIN, input[end..]) orelse return null;
        if (n == 0) {
            if (end == start) return null;
            const line = input[start..end];
            start = end;
            return line;
        }
        end += n;
    }
}

fn split(line: []const u8) []const []const u8 {
    var n: usize = 0;
    var i: usize = 0;
    while (i < line.len) {
        while (i < line.len and (line[i] == ' ' or line[i] == '\t')) i += 1;
        const s = i;
        while (i < line.len and line[i] != ' ' and line[i] != '\t') i += 1;
        if (i > s and n < words.len) {
            words[n] = line[s..i];
            n += 1;
        }
    }
    return words[0..n];
}

pub fn main() void {
    while (true) {
        if (!sys.writeAll(sys.STDOUT, "> ")) return;
        const line = readLine() orelse return;
        var n: usize = 0;
        for (split(line)) |w| {
            if (n + w.len + 3 > out.len) break;
            out[n] = '[';
            @memcpy(out[n + 1 ..][0..w.len], w);
            out[n + 1 + w.len] = ']';
            n += w.len + 2;
        }
        out[n] = '\n';
        n += 1;
        if (!sys.writeAll(sys.STDOUT, out[0..n])) return;
    }
}
