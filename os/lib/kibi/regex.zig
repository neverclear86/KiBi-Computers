//! 正規表現（#103。`grep` と `vi`（#104）が使う）: POSIX の基本正規表現（BRE）・拡張正規表現（ERE）のうち、よく使う部分だけ。
//!
//! 読むもの: 文字、`.`、`[...]`（`^` の否定・範囲・`[:alpha:]` などの名前）、`*`、`^`（頭だけ）、`$`（末尾だけ）、`\` の引用。
//! ERE（`-E`）は `+`・`?` も、BRE は GNU と同じく `\+`・`\?` も繰り返しになる。
//! 組（`(...)`・`\(...\)`）・選択（`|`・`\|`）・回数（`{m,n}`・`\{m,n\}`）・後方参照はまだない（使うと `Unsupported`）。
//! 照合は後ろに戻る素朴な方式（深さは要素の数まで）。
//! 文字は UTF-8 の 1 文字（コードポイント）が単位: `.`・`[...]`・`[^...]` は多バイトの文字を丸ごと 1 つとして合わせ、文字の途中のバイトには合わせない。
//! 多バイトの文字への `*` なども 1 文字にかかる。不正なバイト列のバイトは、1 バイトで 1 文字として合わせる。大文字小文字を無視するのは ASCII だけ。
//!
//! 照合する文字列は、スライスのほかに「`len` と `at(i)` を持つ型」でも渡せる（[find]）。`vi` はギャップバッファの 1 行を写さずに照合する。

const utf8 = @import("text.zig");

pub const MAX_NODES = 128;
/// 1 つの `[...]` に書ける、ASCII でない文字の範囲の数（超えたら `TooLong`）
const MAX_WIDE = 8;

pub const CompileError = error{ Unsupported, BadPattern, TooLong };

const Rep = enum { one, opt, star, plus };
const Kind = enum { literal, any, class };

/// ASCII でない文字の範囲（コードポイント）
const Wide = struct { lo: u21, hi: u21 };

const Node = struct {
    kind: Kind,
    rep: Rep = .one,
    c: u8 = 0,
    /// バイトの集合（256 ビット）。ASCII と、不正なバイト列のバイトを見る
    set: [32]u8 = @splat(0),
    /// ASCII でない文字の範囲（`class` だけ）
    wide: [MAX_WIDE]Wide = undefined,
    nwide: u8 = 0,
    /// `[^...]`（ASCII でない文字の判定を逆にする。[set] は読むときに反転済み）
    negate: bool = false,

    /// [t] の [ti] の 1 文字がこの要素に合うなら、そのバイト数。合わなければ 0
    fn width(self: *const Node, t: anytype, ti: usize, icase: bool) usize {
        if (ti >= t.len) return 0;
        const ch = t.at(ti);
        switch (self.kind) {
            .literal => {
                const ok = if (icase) lower(ch) == self.c else ch == self.c;
                return if (ok) 1 else 0;
            },
            .any => {
                if (ch == '\n') return 0;
                return decode(t, ti).len;
            },
            .class => {
                const d = decode(t, ti);
                if (d.len == 1) return if (getBit(&self.set, ch)) 1 else 0;
                var in = false;
                for (self.wide[0..self.nwide]) |w| {
                    if (d.cp >= w.lo and d.cp <= w.hi) in = true;
                }
                return if (in != self.negate) d.len else 0;
            },
        }
    }
};

/// [decode] の結果
const Dec = struct { cp: u21, len: usize, valid: bool };

/// [t] の [i] の 1 文字を読む。ASCII か、正しい UTF-8 の 1 文字。不正なら 1 バイト（`valid` が偽）
fn decode(t: anytype, i: usize) Dec {
    const c = t.at(i);
    if (c < 0x80) return .{ .cp = c, .len = 1, .valid = true };
    const n: usize = utf8.sequenceLength(c);
    if (n < 2 or i + n > t.len) return .{ .cp = c, .len = 1, .valid = false };
    var b: [4]u8 = undefined;
    var k: usize = 0;
    while (k < n) : (k += 1) b[k] = t.at(i + k);
    const cp = utf8.decode(b[0..n]) orelse return .{ .cp = c, .len = 1, .valid = false };
    return .{ .cp = cp, .len = n, .valid = true };
}

pub const Regex = struct {
    nodes: [MAX_NODES]Node = undefined,
    len: usize = 0,
    bol: bool = false,
    eol: bool = false,
    icase: bool = false,

    /// [pattern] を読む。[extended] は ERE
    pub fn compile(self: *Regex, pattern: []const u8, extended: bool, icase: bool) CompileError!void {
        self.* = .{ .icase = icase };
        var i: usize = 0;
        if (pattern.len > 0 and pattern[0] == '^') {
            self.bol = true;
            i = 1;
        }
        while (i < pattern.len) {
            const c = pattern[i];
            if (c == '$' and i == pattern.len - 1) {
                self.eol = true;
                break;
            }
            switch (c) {
                '.' => {
                    try self.add(.{ .kind = .any });
                    i += 1;
                },
                '[' => i = try self.class(pattern, i + 1),
                '\\' => {
                    if (i + 1 >= pattern.len) return error.BadPattern;
                    const e = pattern[i + 1];
                    i += 2;
                    if (!extended and (e == '+' or e == '?')) {
                        self.quantify(if (e == '+') .plus else .opt) catch try self.literal(e);
                        continue;
                    }
                    if (!extended and (e == '(' or e == ')' or e == '{' or e == '}' or e == '|')) return error.Unsupported;
                    if (e >= '1' and e <= '9') return error.Unsupported;
                    if (e >= 0x80) {
                        i = try self.literalAt(pattern, i - 1);
                        continue;
                    }
                    try self.literal(e);
                },
                '*' => {
                    self.quantify(.star) catch try self.literal('*');
                    i += 1;
                },
                '+', '?' => {
                    i += 1;
                    if (extended) {
                        self.quantify(if (c == '+') .plus else .opt) catch try self.literal(c);
                    } else try self.literal(c);
                },
                '(', ')', '|', '{' => {
                    if (extended) return error.Unsupported;
                    try self.literal(c);
                    i += 1;
                },
                else => i = try self.literalAt(pattern, i),
            }
        }
    }

    /// [pattern] の [i] の 1 文字を、そのまま合わせる要素にして、次の位置を返す。ASCII でない文字は 1 つの要素にする（`*` なども 1 文字にかかる）
    fn literalAt(self: *Regex, pattern: []const u8, i: usize) CompileError!usize {
        const d = decode(Slice.of(pattern), i);
        if (d.valid and d.len > 1) {
            var n = Node{ .kind = .class };
            try addWide(&n, d.cp, d.cp);
            try self.add(n);
        } else try self.literal(pattern[i]);
        return i + d.len;
    }

    fn add(self: *Regex, n: Node) CompileError!void {
        if (self.len == MAX_NODES) return error.TooLong;
        self.nodes[self.len] = n;
        self.len += 1;
    }

    fn literal(self: *Regex, c: u8) CompileError!void {
        if (self.icase and isAlpha(c)) {
            var n = Node{ .kind = .class };
            setBit(&n.set, lower(c));
            setBit(&n.set, upper(c));
            return self.add(n);
        }
        try self.add(.{ .kind = .literal, .c = c });
    }

    /// 前の要素を繰り返しにする。前がなければ error（呼ぶ側が文字として扱う）
    fn quantify(self: *Regex, rep: Rep) error{NoAtom}!void {
        if (self.len == 0) return error.NoAtom;
        const prev = &self.nodes[self.len - 1];
        // `a*?` などの重ねは、広い方にまとめる
        prev.rep = if (prev.rep == .one) rep else if (prev.rep == .plus and rep == .plus) .plus else .star;
    }

    /// `[` の後ろから `]` までを読む。次の位置を返す
    fn class(self: *Regex, p: []const u8, start: usize) CompileError!usize {
        var n = Node{ .kind = .class };
        var i = start;
        var negate = false;
        if (i < p.len and p[i] == '^') {
            negate = true;
            i += 1;
        }
        var first = true;
        const sl = Slice.of(p);
        while (true) {
            if (i >= p.len) return error.BadPattern;
            const d = decode(sl, i);
            const c = p[i];
            if (c == ']' and !first) {
                i += 1;
                break;
            }
            first = false;
            if (c == '[' and i + 1 < p.len and p[i + 1] == ':') {
                const end = indexFrom(p, i + 2, ':') orelse return error.BadPattern;
                if (end + 1 >= p.len or p[end + 1] != ']') return error.BadPattern;
                try named(&n.set, p[i + 2 .. end]);
                i = end + 2;
                continue;
            }
            const next = i + d.len;
            if (next + 1 < p.len and p[next] == '-' and p[next + 1] != ']') {
                const dh = decode(sl, next + 1);
                try addRange(&n, d, dh);
                i = next + 1 + dh.len;
                continue;
            }
            try addRange(&n, d, d);
            i = next;
        }
        if (self.icase) {
            var x: u16 = 'a';
            while (x <= 'z') : (x += 1) {
                const l: u8 = @intCast(x);
                if (getBit(&n.set, l) or getBit(&n.set, upper(l))) {
                    setBit(&n.set, l);
                    setBit(&n.set, upper(l));
                }
            }
        }
        if (negate) {
            n.negate = true;
            for (&n.set) |*b| b.* = ~b.*;
            // 改行には合わせない
            n.set['\n' >> 3] &= ~(@as(u8, 1) << ('\n' & 7));
        }
        try self.add(n);
        return i;
    }

    /// [text] のどこかに合うか
    pub fn search(self: *const Regex, text: []const u8) bool {
        return self.find(Slice.of(text), 0) != null;
    }

    /// [t]（`len: usize` と `fn at(usize) u8` を持つもの）の [from] 以降で、最初に合うところ。`^` は [t] の頭にだけ合う
    pub fn find(self: *const Regex, t: anytype, from: usize) ?Match {
        if (self.bol) {
            if (from != 0) return null;
            const e = self.matchHere(0, t, 0) orelse return null;
            return .{ .start = 0, .end = e };
        }
        var s: usize = from;
        while (true) {
            if (self.matchHere(0, t, s)) |e| return .{ .start = s, .end = e };
            if (s >= t.len) return null;
            s += decode(t, s).len;
        }
    }

    /// [ni] 番目の要素から [ti] で合わせる。合えば合ったところの終わり
    fn matchHere(self: *const Regex, ni: usize, t: anytype, ti: usize) ?usize {
        if (ni == self.len) return if (!self.eol or ti == t.len) ti else null;
        const n = &self.nodes[ni];
        switch (n.rep) {
            .one => {
                const w = n.width(t, ti, self.icase);
                if (w > 0) return self.matchHere(ni + 1, t, ti + w);
                return null;
            },
            .opt => {
                const w = n.width(t, ti, self.icase);
                if (w > 0) {
                    if (self.matchHere(ni + 1, t, ti + w)) |e| return e;
                }
                return self.matchHere(ni + 1, t, ti);
            },
            .star, .plus => {
                var lo = ti;
                if (n.rep == .plus) {
                    const w = n.width(t, ti, self.icase);
                    if (w == 0) return null;
                    lo = ti + w;
                }
                var p = ti;
                while (true) {
                    const w = n.width(t, p, self.icase);
                    if (w == 0) break;
                    p += w;
                }
                // 長い方から順に、1 文字ずつ戻す
                while (true) {
                    if (self.matchHere(ni + 1, t, p)) |e| return e;
                    if (p <= lo) return null;
                    p = stepBack(t, ti, p);
                }
            },
        }
    }
};

/// [p] の 1 文字前（[lo] 以降）の位置
fn stepBack(t: anytype, lo: usize, p: usize) usize {
    var q = p - 1;
    var back: usize = 0;
    while (q > lo and back < 3 and utf8.isContinuation(t.at(q))) : (back += 1) q -= 1;
    if (q < p - 1) {
        const d = decode(t, q);
        if (d.valid and q + d.len == p) return q;
    }
    return p - 1;
}

/// 合ったところ（[start, end)）
pub const Match = struct { start: usize, end: usize };

/// スライスを [Regex.find] に渡す形
pub const Slice = struct {
    s: []const u8,
    len: usize,

    pub fn of(s: []const u8) Slice {
        return .{ .s = s, .len = s.len };
    }

    pub fn at(self: Slice, i: usize) u8 {
        return self.s[i];
    }
};

/// [n] に ASCII でない文字の範囲 [lo, hi] を足す
fn addWide(n: *Node, lo: u21, hi: u21) CompileError!void {
    if (n.nwide == MAX_WIDE) return error.TooLong;
    n.wide[n.nwide] = .{ .lo = lo, .hi = hi };
    n.nwide += 1;
}

/// `[...]` の 1 つの文字（[lo]）か範囲（[lo]-[hi]）を [n] に足す。ASCII と不正なバイトはバイトの集合、ほかはコードポイントの範囲に入れる
fn addRange(n: *Node, lo: Dec, hi: Dec) CompileError!void {
    const lo_byte = !lo.valid or lo.cp < 0x80;
    const hi_byte = !hi.valid or hi.cp < 0x80;
    if (lo_byte and hi_byte) {
        if (hi.cp < lo.cp) return error.BadPattern;
        var x: u16 = @intCast(lo.cp);
        while (x <= hi.cp) : (x += 1) setBit(&n.set, @intCast(x));
        return;
    }
    // 片方だけ不正なバイトの範囲は読めない
    if (!lo.valid or !hi.valid) return error.BadPattern;
    if (hi.cp < lo.cp) return error.BadPattern;
    if (lo.cp < 0x80) {
        var x: u16 = @intCast(lo.cp);
        while (x < 0x80) : (x += 1) setBit(&n.set, @intCast(x));
        try addWide(n, 0x80, hi.cp);
    } else try addWide(n, lo.cp, hi.cp);
}

fn named(set: *[32]u8, name: []const u8) CompileError!void {
    var x: u16 = 0;
    while (x < 256) : (x += 1) {
        const c: u8 = @intCast(x);
        const in = if (eql(name, "alpha")) isAlpha(c) else if (eql(name, "digit")) isDigit(c) else if (eql(name, "alnum"))
            isAlpha(c) or isDigit(c)
        else if (eql(name, "upper")) c >= 'A' and c <= 'Z' else if (eql(name, "lower")) c >= 'a' and c <= 'z' else if (eql(name, "space"))
            c == ' ' or (c >= '\t' and c <= '\r')
        else if (eql(name, "blank")) c == ' ' or c == '\t' else if (eql(name, "punct"))
            (c >= '!' and c <= '/') or (c >= ':' and c <= '@') or (c >= '[' and c <= '`') or (c >= '{' and c <= '~')
        else if (eql(name, "xdigit")) isDigit(c) or (c >= 'a' and c <= 'f') or (c >= 'A' and c <= 'F') else return error.BadPattern;
        if (in) setBit(set, c);
    }
}

fn setBit(set: *[32]u8, c: u8) void {
    set[c >> 3] |= @as(u8, 1) << @intCast(c & 7);
}

fn getBit(set: *const [32]u8, c: u8) bool {
    return set[c >> 3] & (@as(u8, 1) << @intCast(c & 7)) != 0;
}

fn indexFrom(s: []const u8, from: usize, c: u8) ?usize {
    var i = from;
    while (i < s.len) : (i += 1) if (s[i] == c) return i;
    return null;
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}

fn isAlpha(c: u8) bool {
    return (c >= 'a' and c <= 'z') or (c >= 'A' and c <= 'Z');
}

fn isDigit(c: u8) bool {
    return c >= '0' and c <= '9';
}

pub fn lower(c: u8) u8 {
    return if (c >= 'A' and c <= 'Z') c + 32 else c;
}

fn upper(c: u8) u8 {
    return if (c >= 'a' and c <= 'z') c - 32 else c;
}
