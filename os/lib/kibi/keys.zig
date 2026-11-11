//! raw の TTY から読んだバイト列を、キーに分ける（#105）。並びは xterm 形式（core の `TerminalKeyEncoder`）。
//!
//! 1 つのキーのバイト列は、1 回の入力としてまとめて TTY に入る。そのため、読んだかたまりの最後が ESC なら、
//! 素の Esc と見る（続きを待たない。時計で待つと、待っているだけの間も起きることになるため）。
//! かたまりがエスケープシーケンスや UTF-8 の途中で切れていたら（読む領域が埋まったとき）、続きを読んでからつなぐ。
const io = @import("io.zig");
const text = @import("text.zig");

pub const Kind = enum {
    /// 文字（`bytes` に UTF-8 の 1 文字か、不正な 1 バイト）
    text,
    /// 制御文字（`c` に 0x00〜0x1F か 0x7F）
    ctrl,
    /// Alt+文字（`c` に ESC の後のバイト）
    alt,
    up,
    down,
    left,
    right,
    home,
    end,
    page_up,
    page_down,
    insert,
    delete,
    /// F1〜F12（`c` に番号）
    function,
    escape,
    /// 知らないシーケンス（読み捨てる）
    unknown,
};

pub const Mods = packed struct(u8) {
    shift: bool = false,
    alt: bool = false,
    ctrl: bool = false,
    _pad: u5 = 0,
};

pub const Key = struct {
    kind: Kind,
    c: u8 = 0,
    mods: Mods = .{},
    bytes: [4]u8 = .{ 0, 0, 0, 0 },
    len: u3 = 0,

    pub fn slice(self: *const Key) []const u8 {
        return self.bytes[0..self.len];
    }

    /// Ctrl+[letter]（`'a'` など小文字で渡す）か
    pub fn isCtrl(self: Key, letter: u8) bool {
        return self.kind == .ctrl and self.c == letter & 0x1f;
    }

    /// Alt+[letter]（大文字・小文字を区別しない）か
    pub fn isAlt(self: Key, letter: u8) bool {
        return self.kind == .alt and (self.c | 0x20) == (letter | 0x20);
    }
};

/// CSI の長さの上限（これより長いものは読み捨てる）
const MAX_CSI = 32;

pub const Reader = struct {
    fd: io.Fd,
    buf: [4096]u8 = undefined,
    start: usize = 0,
    end: usize = 0,
    /// 前の読み込みで領域が埋まった（続きが TTY に残っているかもしれない）
    filled: bool = false,

    pub fn init(fd: io.Fd) Reader {
        return .{ .fd = fd };
    }

    /// 読んだまま、まだキーにできるバイトがあるか（なければ次の [next] は読むのを待つ）。
    /// 領域が埋まって、後ろが UTF-8 やエスケープシーケンスの途中で切れているだけなら、キーにできるバイトはないと見る
    /// （続きが来るまで待つので、待つ前に今ある分を描けるようにする）
    pub fn pending(self: *const Reader) bool {
        if (self.start >= self.end) return false;
        return parse(self.buf[self.start..self.end], !self.filled).used > 0;
    }

    /// 次のキー。入力の終わり・読めないときは null
    pub fn next(self: *Reader) ?Key {
        while (true) {
            if (self.start == self.end) {
                self.start = 0;
                self.end = 0;
                if (!self.fill()) return null;
            }
            const r = parse(self.buf[self.start..self.end], !self.filled);
            if (r.used > 0) {
                self.start += r.used;
                return r.key;
            }
            // 続きが要る: 頭に寄せて読み足す（読めなければ、今あるものを終わりと見る）
            const rest = self.end - self.start;
            if (self.start > 0) {
                @memmove(self.buf[0..rest], self.buf[self.start..self.end]);
                self.start = 0;
                self.end = rest;
            }
            if (self.end == self.buf.len or !self.fill()) {
                const f = parse(self.buf[self.start..self.end], true);
                self.start += f.used;
                return f.key;
            }
        }
    }

    fn fill(self: *Reader) bool {
        while (true) {
            const n = io.read(self.fd, self.buf[self.end..]) catch |e| {
                if (e == error.INTR) continue;
                return false;
            };
            if (n == 0) return false;
            self.filled = self.end + n == self.buf.len;
            self.end += n;
            return true;
        }
    }
};

const Parsed = struct { key: Key = .{ .kind = .unknown }, used: usize = 0 };

fn one(key: Key, used: usize) Parsed {
    return .{ .key = key, .used = used };
}

/// [s] の頭の 1 キー。[final] なら [s] の後に続きはないと見る（続きが要るなら used = 0）
pub fn parse(s: []const u8, final: bool) Parsed {
    const c = s[0];
    if (c == 0x1b) {
        if (s.len == 1) return if (final) one(.{ .kind = .escape }, 1) else .{};
        const d = s[1];
        switch (d) {
            '[' => return parseCsi(s, final),
            'O' => {
                if (s.len < 3) return if (final) one(.{ .kind = .alt, .c = 'O' }, 2) else .{};
                return one(ss3(s[2], .{}), 3);
            },
            0x1b => return one(.{ .kind = .escape }, 1),
            else => {
                if (d >= 0x20) return one(.{ .kind = .alt, .c = d }, 2);
                // ESC の後の制御文字は、Esc と次のキー
                return one(.{ .kind = .escape }, 1);
            },
        }
    }
    if (c < 0x20 or c == 0x7f) return one(.{ .kind = .ctrl, .c = c }, 1);
    var k: Key = .{ .kind = .text };
    const n = text.sequenceLength(c);
    if (n > 1 and s.len < n) {
        // 途中で切れている: 今あるものが続きのバイトの並びなら、続きを待つ
        var ok = true;
        for (s[1..]) |b| ok = ok and text.isContinuation(b);
        if (ok and !final) return .{};
    }
    if (n > 1 and text.decode(s[0..@min(s.len, n)]) != null) {
        @memcpy(k.bytes[0..n], s[0..n]);
        k.len = n;
        return one(k, n);
    }
    // ASCII か、不正な 1 バイト
    k.bytes[0] = c;
    k.len = 1;
    return one(k, 1);
}

fn ss3(final: u8, mods: Mods) Key {
    return switch (final) {
        'A' => .{ .kind = .up, .mods = mods },
        'B' => .{ .kind = .down, .mods = mods },
        'C' => .{ .kind = .right, .mods = mods },
        'D' => .{ .kind = .left, .mods = mods },
        'H' => .{ .kind = .home, .mods = mods },
        'F' => .{ .kind = .end, .mods = mods },
        'P'...'S' => .{ .kind = .function, .c = final - 'P' + 1, .mods = mods },
        else => .{ .kind = .unknown },
    };
}

fn parseCsi(s: []const u8, final: bool) Parsed {
    var p: [2]u32 = .{ 0, 0 };
    var which: usize = 0;
    var i: usize = 2;
    while (i < s.len) : (i += 1) {
        const b = s[i];
        if (b >= 0x40 and b <= 0x7e) {
            const mods: Mods = if (which >= 1 and p[1] >= 2) @bitCast(@as(u8, @truncate(p[1] - 1))) else .{};
            const m: Mods = .{ .shift = mods.shift, .alt = mods.alt, .ctrl = mods.ctrl };
            const key: Key = switch (b) {
                'A', 'B', 'C', 'D', 'H', 'F', 'P', 'Q', 'R', 'S' => ss3(b, m),
                '~' => tilde(p[0], m),
                else => .{ .kind = .unknown },
            };
            return one(key, i + 1);
        }
        if (b < 0x20 or b > 0x7e or i >= MAX_CSI) return one(.{ .kind = .unknown }, i);
        if (b == ';') {
            which += 1;
        } else if (b >= '0' and b <= '9' and which < 2) {
            p[which] = p[which] *| 10 +| (b - '0');
        }
    }
    return if (final) one(.{ .kind = .unknown }, s.len) else .{};
}

fn tilde(code: u32, m: Mods) Key {
    return switch (code) {
        1, 7 => .{ .kind = .home, .mods = m },
        4, 8 => .{ .kind = .end, .mods = m },
        2 => .{ .kind = .insert, .mods = m },
        3 => .{ .kind = .delete, .mods = m },
        5 => .{ .kind = .page_up, .mods = m },
        6 => .{ .kind = .page_down, .mods = m },
        15 => .{ .kind = .function, .c = 5, .mods = m },
        17...21 => .{ .kind = .function, .c = @intCast(code - 11), .mods = m },
        23, 24 => .{ .kind = .function, .c = @intCast(code - 12), .mods = m },
        else => .{ .kind = .unknown },
    };
}
