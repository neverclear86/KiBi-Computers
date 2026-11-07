//! シェルの小物（#101）
const kibi = @import("kibi");
const io = kibi.io;

/// シェルの中のエラー
/// - `OutOfMemory`: シェルの領域が足りない
/// - `Syntax`: 構文の誤り（知らせは書いた）
/// - `Incomplete`: 入力が途中で切れている（続きを読んでからもう一度読む）
/// - `Abort`: 今のコマンドをやめる（展開の誤りなど。知らせは書いた）
pub const Error = error{ OutOfMemory, Syntax, Incomplete, Abort };

/// 入れ子（関数の呼び出し・複合コマンド・構文の読み）を1段深くするときに、残っていてほしいスタック（バイト。#514）。
/// 入れ子を深くしない処理（組み込み・外のプログラムを起こす・子を待つ・誤りを知らせる）が、この中に収まる大きさにする
pub const STACK_RESERVE = 32 * 1024;

/// スタックの残りが [STACK_RESERVE] より少ない（これ以上入れ子を深くすると、シェルが範囲外の読み書きで落ちうる）
pub fn stackLow() bool {
    return kibi.stack.remaining() < STACK_RESERVE;
}

pub fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}

pub fn startsWith(s: []const u8, p: []const u8) bool {
    return s.len >= p.len and eql(s[0..p.len], p);
}

pub fn indexOf(s: []const u8, c: u8) ?usize {
    for (s, 0..) |x, i| if (x == c) return i;
    return null;
}

pub fn isDigit(c: u8) bool {
    return c >= '0' and c <= '9';
}

pub fn isAlpha(c: u8) bool {
    return (c >= 'a' and c <= 'z') or (c >= 'A' and c <= 'Z') or c == '_';
}

pub fn isAlnum(c: u8) bool {
    return isAlpha(c) or isDigit(c);
}

/// 変数の名前として正しいか
pub fn isName(s: []const u8) bool {
    if (s.len == 0 or !isAlpha(s[0])) return false;
    for (s) |c| if (!isAlnum(c)) return false;
    return true;
}

/// 符号つき整数を 10 進で書く
pub fn formatSigned(buf: []u8, v: i64) []const u8 {
    if (v >= 0) return io.formatUnsigned(buf, @intCast(v));
    buf[0] = '-';
    const mag: u64 = @as(u64, @bitCast(-%v));
    const rest = io.formatUnsigned(buf[1..], mag);
    return buf[0 .. rest.len + 1];
}

/// 10 進の整数（先頭の `+`・`-` と前後の空白を許す）。読めなければ null
pub fn parseSigned(s0: []const u8) ?i64 {
    var s = trim(s0);
    var neg = false;
    if (s.len > 0 and (s[0] == '-' or s[0] == '+')) {
        neg = s[0] == '-';
        s = s[1..];
    }
    const v = io.parseUnsigned(s) orelse return null;
    if (v > @as(u64, @intCast(std_max_i64)) + @intFromBool(neg)) return null;
    if (neg) return -%@as(i64, @bitCast(v));
    return @intCast(v);
}

const std_max_i64: i64 = 0x7fff_ffff_ffff_ffff;

pub fn trim(s: []const u8) []const u8 {
    var a: usize = 0;
    var b = s.len;
    while (a < b and isBlank(s[a])) a += 1;
    while (b > a and isBlank(s[b - 1])) b -= 1;
    return s[a..b];
}

pub fn isBlank(c: u8) bool {
    return c == ' ' or c == '\t' or c == '\n' or c == '\r';
}

// ---- 知らせ（標準エラー出力） ----

/// 知らせを書く fd（外のコマンドを起こすときは、そのコマンドの `2>` の先。null なら書かない）
pub var err_fd: ?io.Fd = io.STDERR;

/// `sh: ` に続けて書く（最後に改行）
pub fn complain(parts: []const []const u8) void {
    const fd = err_fd orelse return;
    var b: [512]u8 = undefined;
    var n: usize = 0;
    const head = "sh: ";
    @memcpy(b[0..head.len], head);
    n = head.len;
    for (parts) |p| {
        const k = @min(p.len, b.len - 1 - n);
        @memcpy(b[n..][0..k], p[0..k]);
        n += k;
    }
    b[n] = '\n';
    n += 1;
    io.writeAll(fd, b[0..n]) catch {};
}
