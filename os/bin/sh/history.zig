//! 会話的なシェルの履歴（#102）。決めごとは docs/adr/0102-shell-line-editing.md。
//!
//! 1 つの項目は、行の編集で確定した 1 行（改行を含まない）。古い順に静的な領域に並べる。
//! ファイル（`HISTFILE`、既定 `$HOME/.sh_history`）には、1 行に 1 項目を書く。確定するたびに後ろに足し、
//! 起動したときに読む（多すぎれば、残す分だけで書き直す）。
const kibi = @import("kibi");
const util = @import("util.zig");
const vars = @import("vars.zig");

const io = kibi.io;
const fs = kibi.fs;
const eql = util.eql;

/// 項目の数の上限（`HISTSIZE` はこれより大きくできない）
pub const MAX_ENTRIES = 1000;
/// `HISTSIZE` がないとき・読めないときの数（仮の値）
pub const DEFAULT_SIZE = 500;
/// 項目の文字の合計の上限
const TEXT_SIZE = 64 * 1024;
/// 1 項目の長さの上限（これより長い行は残さない）
pub const MAX_LINE = 4096;

var text: [TEXT_SIZE]u8 = undefined;
var text_len: usize = 0;
/// 項目 i は text[starts[i]..starts[i + 1]]
var starts: [MAX_ENTRIES + 1]u32 = undefined;
var count: usize = 0;
/// 一番古い項目の通し番号（`history` の表示用）
var first_number: u64 = 1;

pub fn len() usize {
    return count;
}

/// [i] 番目（0 が一番古い）の項目
pub fn get(i: usize) []const u8 {
    return text[starts[i]..starts[i + 1]];
}

/// 残す数（`HISTSIZE`。0〜MAX_ENTRIES）
pub fn limit() usize {
    const v = vars.get("HISTSIZE") orelse return DEFAULT_SIZE;
    const n = io.parseUnsigned(util.trim(v)) orelse return DEFAULT_SIZE;
    return @intCast(@min(n, MAX_ENTRIES));
}

/// 古いものから [n] 個捨てる
fn dropOldest(n0: usize) void {
    const n = @min(n0, count);
    if (n == 0) return;
    const cut = starts[n];
    const rest = text_len - cut;
    var k: usize = 0;
    while (k < rest) : (k += 1) text[k] = text[cut + k];
    text_len = rest;
    var i: usize = 0;
    while (i + n <= count) : (i += 1) starts[i] = starts[i + n] - cut;
    count -= n;
    first_number += n;
}

/// 項目を足す（ファイルには書かない）。空の行・長すぎる行・直前と同じ行は足さない。足したら true
pub fn add(line: []const u8) bool {
    if (util.trim(line).len == 0 or line.len > MAX_LINE) return false;
    if (count > 0 and eql(get(count - 1), line)) return false;
    const max = limit();
    if (max == 0) return false;
    if (count >= max) dropOldest(count - max + 1);
    // 文字の領域が足りなければ、入るまで古いものを捨てる
    while (count > 0 and text_len + line.len > TEXT_SIZE) dropOldest(1);
    if (count == 0) starts[0] = 0;
    @memcpy(text[text_len..][0..line.len], line);
    text_len += line.len;
    count += 1;
    starts[count] = @intCast(text_len);
    return true;
}

/// 確定した行を残す。足したら、ファイルの後ろにも書く
pub fn record(line: []const u8) void {
    if (!add(line)) return;
    var buf: [fs.MAX_PATH]u8 = undefined;
    const path = filePath(&buf) orelse return;
    const fd = fs.open(path, .{ .read = false, .write = true, .create = true, .append = true }) catch return;
    defer io.close(fd) catch {};
    io.writeAll(fd, line) catch return;
    io.writeAll(fd, "\n") catch {};
}

/// 全部消す（ファイルはそのまま）
pub fn clear() void {
    first_number += count;
    count = 0;
    text_len = 0;
}

/// [i] 番目の項目の通し番号
pub fn number(i: usize) u64 {
    return first_number + i;
}

/// 履歴のファイル。`HISTFILE`（空ならファイルを使わない）、なければ `$HOME/.sh_history`
pub fn filePath(buf: []u8) ?[]const u8 {
    if (vars.get("HISTFILE")) |p| {
        if (p.len == 0) return null;
        return fs.absolute(buf, p) catch null;
    }
    const home = vars.get("HOME") orelse return null;
    if (home.len == 0) return null;
    var tmp: [fs.MAX_PATH]u8 = undefined;
    const name = "/.sh_history";
    if (home.len + name.len > tmp.len) return null;
    @memcpy(tmp[0..home.len], home);
    @memcpy(tmp[home.len..][0..name.len], name);
    return fs.absolute(buf, tmp[0 .. home.len + name.len]) catch null;
}

var read_buf: [4096]u8 = undefined;
var line_buf: [MAX_LINE]u8 = undefined;

/// ファイルから読む（会話的なシェルの起動時）。残す数より多く書いてあれば、残す分だけで書き直す
pub fn load() void {
    var pbuf: [fs.MAX_PATH]u8 = undefined;
    const path = filePath(&pbuf) orelse return;
    const fd = fs.open(path, .{}) catch return;
    var lines: usize = 0;
    var n: usize = 0;
    var too_long = false;
    while (true) {
        const got = io.read(fd, &read_buf) catch break;
        if (got == 0) break;
        for (read_buf[0..got]) |c| {
            if (c == '\n') {
                if (!too_long) _ = add(line_buf[0..n]);
                lines += 1;
                n = 0;
                too_long = false;
                continue;
            }
            if (n < line_buf.len) {
                line_buf[n] = c;
                n += 1;
            } else too_long = true;
        }
    }
    if (n > 0 and !too_long) {
        _ = add(line_buf[0..n]);
        lines += 1;
    }
    io.close(fd) catch {};
    if (lines > count) rewrite(path);
}

/// ファイルを今の項目だけで書き直す
fn rewrite(path: []const u8) void {
    const fd = fs.open(path, .{ .read = false, .write = true, .create = true, .truncate = true }) catch return;
    defer io.close(fd) catch {};
    var i: usize = 0;
    while (i < count) : (i += 1) {
        io.writeAll(fd, get(i)) catch return;
        io.writeAll(fd, "\n") catch return;
    }
}
