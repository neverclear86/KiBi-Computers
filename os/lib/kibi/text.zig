//! UTF-8 の読み方と、文字画面での文字の幅（#105）。
//!
//! 読み方は厳しめにする（overlong・サロゲート・U+10FFFF より上・途中で切れたものは不正）。
//! 不正なバイト列をそのまま端末に送ると、端末の側で別の文字（制御文字を含む）に読まれるおそれがあるため。
//! 幅の表は core の `CharWidth`（全角）と、端末の解釈が幅 0 にする文字（Unicode の一般カテゴリ Mn・Me・Cf）の主な範囲。
//! 全角の表が `CharWidth` と同じことは、core の `StandardOsTest` が確かめる。

/// 置き換えの文字（U+FFFD）の UTF-8
pub const REPLACEMENT = "\xef\xbf\xbd";

/// 先頭のバイト [lead] から始まる 1 文字のバイト数。続きのバイト・不正な先頭なら 0
pub fn sequenceLength(lead: u8) u3 {
    if (lead < 0x80) return 1;
    if (lead >= 0xc2 and lead <= 0xdf) return 2;
    if (lead >= 0xe0 and lead <= 0xef) return 3;
    if (lead >= 0xf0 and lead <= 0xf4) return 4;
    return 0;
}

/// 続きのバイト（10xxxxxx）か
pub fn isContinuation(c: u8) bool {
    return c & 0xc0 == 0x80;
}

/// [s] の頭の 1 文字（バイト数は `sequenceLength(s[0])`）。不正・途中で切れていれば null
pub fn decode(s: []const u8) ?u21 {
    if (s.len == 0) return null;
    const c = s[0];
    const n = sequenceLength(c);
    if (n == 0 or s.len < n) return null;
    if (n == 1) return c;
    // 2 バイト目の範囲（overlong・サロゲート・範囲外を断る）
    const lo: u8 = switch (c) {
        0xe0 => 0xa0,
        0xf0 => 0x90,
        else => 0x80,
    };
    const hi: u8 = switch (c) {
        0xed => 0x9f,
        0xf4 => 0x8f,
        else => 0xbf,
    };
    if (s[1] < lo or s[1] > hi) return null;
    var v: u21 = c & (@as(u8, 0x7f) >> n);
    for (s[1..n]) |b| {
        if (!isContinuation(b)) return null;
        v = (v << 6) | (b & 0x3f);
    }
    return v;
}

/// 全角（2 セル）の範囲（`CharWidth`（core）と同じ）
pub const wide_ranges = [_][2]u21{
    .{ 0x1100, 0x115F },   .{ 0x231A, 0x231B },   .{ 0x2E80, 0x303E },   .{ 0x3041, 0x33FF },
    .{ 0x3400, 0x4DBF },   .{ 0x4E00, 0x9FFF },   .{ 0xA000, 0xA4CF },   .{ 0xA960, 0xA97F },
    .{ 0xAC00, 0xD7A3 },   .{ 0xF900, 0xFAFF },   .{ 0xFE10, 0xFE19 },   .{ 0xFE30, 0xFE6F },
    .{ 0xFF00, 0xFF60 },   .{ 0xFFE0, 0xFFE6 },   .{ 0x1F300, 0x1F64F }, .{ 0x1F900, 0x1F9FF },
    .{ 0x20000, 0x2FFFD }, .{ 0x30000, 0x3FFFD },
};

/// 幅のない文字（結合文字・書式の文字）の主な範囲
pub const zero_ranges = [_][2]u21{
    .{ 0x0300, 0x036F }, .{ 0x0483, 0x0489 }, .{ 0x0591, 0x05BD }, .{ 0x0610, 0x061A },
    .{ 0x064B, 0x065F }, .{ 0x200B, 0x200F }, .{ 0x202A, 0x202E }, .{ 0x2060, 0x2064 },
    .{ 0x20D0, 0x20FF }, .{ 0x302A, 0x302D }, .{ 0x3099, 0x309A }, .{ 0xFE00, 0xFE0F },
    .{ 0xFE20, 0xFE2F }, .{ 0xFEFF, 0xFEFF },
};

fn inRanges(cp: u21, ranges: []const [2]u21) bool {
    for (ranges) |r| {
        if (cp < r[0]) return false;
        if (cp <= r[1]) return true;
    }
    return false;
}

/// 印字できる文字 [cp] の幅（0・1・2）。制御文字は呼ぶ側で扱う
pub fn width(cp: u21) u2 {
    if (inRanges(cp, &zero_ranges)) return 0;
    return if (inRanges(cp, &wide_ranges)) 2 else 1;
}
