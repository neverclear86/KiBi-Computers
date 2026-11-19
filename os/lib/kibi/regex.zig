//! 正規表現（#103。`grep` と `vi`（#104）が使う）: POSIX の基本正規表現（BRE）・拡張正規表現（ERE）のうち、よく使う部分だけ。
//!
//! 読むもの: 文字、`.`、`[...]`（`^` の否定・範囲・`[:alpha:]` などの名前）、`*`、`^`（頭だけ）、`$`（末尾だけ）、`\` の引用。
//! ERE（`-E`）は `+`・`?` も、BRE は GNU と同じく `\+`・`\?` も繰り返しになる。
//! 組（`(...)`・`\(...\)`）・選択（`|`・`\|`）・回数（`{m,n}`・`\{m,n\}`）・後方参照はまだない（使うと `Unsupported`）。
//! 照合は後ろに戻る素朴な方式（深さは要素の数まで）。
//!
//! 照合する文字列は、スライスのほかに「`len` と `at(i)` を持つ型」でも渡せる（[find]）。`vi` はギャップバッファの 1 行を写さずに照合する。

pub const MAX_NODES = 128;

pub const CompileError = error{ Unsupported, BadPattern, TooLong };

const Rep = enum { one, opt, star, plus };
const Kind = enum { literal, any, class };

const Node = struct {
    kind: Kind,
    rep: Rep = .one,
    c: u8 = 0,
    /// 文字の集合（256 ビット）
    set: [32]u8 = @splat(0),

    fn matches(self: *const Node, ch: u8, icase: bool) bool {
        return switch (self.kind) {
            .any => ch != '\n',
            .literal => if (icase) lower(ch) == self.c else ch == self.c,
            .class => self.set[ch >> 3] & (@as(u8, 1) << @intCast(ch & 7)) != 0,
        };
    }
};

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
                else => {
                    try self.literal(c);
                    i += 1;
                },
            }
        }
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
        while (true) {
            if (i >= p.len) return error.BadPattern;
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
            if (i + 2 < p.len and p[i + 1] == '-' and p[i + 2] != ']') {
                const hi = p[i + 2];
                if (hi < c) return error.BadPattern;
                var x: u16 = c;
                while (x <= hi) : (x += 1) setBit(&n.set, @intCast(x));
                i += 3;
                continue;
            }
            setBit(&n.set, c);
            i += 1;
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
        while (s <= t.len) : (s += 1) {
            if (self.matchHere(0, t, s)) |e| return .{ .start = s, .end = e };
        }
        return null;
    }

    /// [ni] 番目の要素から [ti] で合わせる。合えば合ったところの終わり
    fn matchHere(self: *const Regex, ni: usize, t: anytype, ti: usize) ?usize {
        if (ni == self.len) return if (!self.eol or ti == t.len) ti else null;
        const n = &self.nodes[ni];
        switch (n.rep) {
            .one => {
                if (ti < t.len and n.matches(t.at(ti), self.icase)) return self.matchHere(ni + 1, t, ti + 1);
                return null;
            },
            .opt => {
                if (ti < t.len and n.matches(t.at(ti), self.icase)) {
                    if (self.matchHere(ni + 1, t, ti + 1)) |e| return e;
                }
                return self.matchHere(ni + 1, t, ti);
            },
            .star, .plus => {
                var k: usize = 0;
                while (ti + k < t.len and n.matches(t.at(ti + k), self.icase)) k += 1;
                const min: usize = if (n.rep == .plus) 1 else 0;
                while (true) {
                    if (k < min) return null;
                    if (self.matchHere(ni + 1, t, ti + k)) |e| return e;
                    if (k == 0) return null;
                    k -= 1;
                }
            },
        }
    }
};

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
