//! 語の展開（#101）: チルダ、パラメーター、コマンド置換、算術、フィールド分割、パス名、引用符の除去。
//!
//! 展開の途中の文字列では、引用された文字のうちパターンに効く文字（`*?[]\` と印そのもの）の前に印 [ESC] を置く。
//! パス名の展開と `case`・`${x#pat}` の照合は、印の付いた文字をそのままの文字として扱う。最後に印を外す。
const kibi = @import("kibi");
const util = @import("util.zig");
const mem = @import("mem.zig");
const parse = @import("parse.zig");
const vars = @import("vars.zig");
const state = @import("state.zig");
const exec = @import("exec.zig");

const fs = kibi.fs;
const Error = util.Error;
const eql = util.eql;

pub const ESC: u8 = 0x01;

fn isPatternChar(c: u8) bool {
    return c == '*' or c == '?' or c == '[' or c == ']' or c == '\\' or c == ESC;
}

const Builder = struct {
    /// できたフィールド（null ならフィールドを 1 つにまとめる）
    out: ?*mem.List([]const u8),
    cur: mem.List(u8),
    /// 今のフィールドがある（中身があるか、空の引用があった）
    has: bool = false,
    /// フィールド分割をする
    split: bool,
    /// 空白の IFS でフィールドを終えたところ
    ws_finished: bool = false,
    /// 足した回数（`"$@"` が空だったかを見る）
    appended: usize = 0,
    /// 引用の中の `$@` が空だった
    empty_at: bool = false,
    /// チルダを展開する
    tilde: bool = true,

    fn init(out: ?*mem.List([]const u8), split: bool) Builder {
        return .{ .out = out, .cur = mem.List(u8).init(&mem.str), .split = split };
    }

    fn finish(self: *Builder) Error!void {
        if (self.out) |o| {
            try o.append(self.cur.items);
            self.cur = mem.List(u8).init(&mem.str);
            self.has = false;
        }
    }

    /// 引用された文字
    fn quoted(self: *Builder, s: []const u8) Error!void {
        for (s) |c| {
            if (isPatternChar(c)) try self.cur.append(ESC);
            try self.cur.append(c);
        }
        self.has = true;
        self.ws_finished = false;
        self.appended += 1;
    }

    /// 引用されていない文字（パターンとして効く）
    fn literal(self: *Builder, s: []const u8) Error!void {
        for (s) |c| {
            if (c == ESC) try self.cur.append(ESC);
            try self.cur.append(c);
        }
        self.has = true;
        self.ws_finished = false;
        self.appended += 1;
    }

    /// 展開の結果。引用の外なら IFS で分ける
    fn expansion(self: *Builder, s: []const u8, in_dq: bool) Error!void {
        if (in_dq) return self.quoted(s);
        if (!self.split or self.out == null) {
            if (s.len > 0) return self.literal(s);
            return;
        }
        const ifs = vars.get("IFS") orelse " \t\n";
        self.appended += 1;
        for (s) |c| {
            if (util.indexOf(ifs, c) != null) {
                const white = c == ' ' or c == '\t' or c == '\n';
                if (white) {
                    if (self.has) {
                        try self.finish();
                        self.ws_finished = true;
                    }
                } else {
                    if (self.has) {
                        try self.finish();
                    } else if (!self.ws_finished) {
                        self.has = true;
                        try self.finish();
                    }
                    self.ws_finished = false;
                }
                continue;
            }
            if (c == ESC) try self.cur.append(ESC);
            try self.cur.append(c);
            self.has = true;
            self.ws_finished = false;
        }
    }
};

// ---- 入り口 ----

/// 語の並びを、フィールド分割・パス名の展開まで行って [out] に足す
pub fn fields(words: ?*parse.Word, out: *mem.List([]const u8)) Error!void {
    var w = words;
    while (w) |word| : (w = word.next) {
        var raw = mem.List([]const u8).init(&mem.ast);
        var b = Builder.init(&raw, true);
        if (eql(word.text, "\"$@\"") and vars.params.len == 0) continue;
        try expandText(&b, word.text, false, false);
        if (b.has) try b.finish();
        for (raw.items) |f| {
            if (!state.noglob and hasGlob(f)) {
                const before = out.items.len;
                try glob(f, out);
                if (out.items.len > before) continue;
            }
            try out.append(try unescape(f));
        }
    }
}

/// 1 つの文字列にする（分割・パス名の展開をしない）。代入の値・リダイレクトの先・case の語
pub fn string(text: []const u8) Error![]const u8 {
    return unescape(try pattern(text));
}

/// パターンとして展開する（引用された文字に印を残す）
pub fn pattern(text: []const u8) Error![]const u8 {
    var b = Builder.init(null, false);
    try expandText(&b, text, false, false);
    return b.cur.items;
}

/// 代入の値（`=` の後と `:` の後のチルダも展開する）
pub fn assignValue(text: []const u8) Error![]const u8 {
    return string(text);
}

/// ヒアドキュメントの中身（`$`・`` ` ``・`\` だけが効く）
pub fn heredoc(body: []const u8) Error![]const u8 {
    var b = Builder.init(null, false);
    b.tilde = false;
    try expandText(&b, body, true, true);
    return unescape(b.cur.items);
}

/// 印を外す
pub fn unescape(s: []const u8) Error![]const u8 {
    if (util.indexOf(s, ESC) == null) return s;
    var out = mem.List(u8).init(&mem.str);
    var i: usize = 0;
    while (i < s.len) : (i += 1) {
        if (s[i] == ESC and i + 1 < s.len) i += 1;
        try out.append(s[i]);
    }
    return out.items;
}

// ---- 本体 ----

fn subParser(text: []const u8) parse.Parser {
    var p = parse.Parser.init(text, true);
    p.quiet = true;
    return p;
}

/// [text] を展開して [b] に足す。[in_dq] は `"` の中。[heredoc_mode] はヒアドキュメントの中身
fn expandText(b: *Builder, text: []const u8, in_dq: bool, heredoc_mode: bool) Error!void {
    var p = subParser(text);
    var i: usize = 0;
    while (i < text.len) {
        const c = text[i];
        if (heredoc_mode) {
            switch (c) {
                '\\' => {
                    if (i + 1 < text.len and (text[i + 1] == '$' or text[i + 1] == '`' or text[i + 1] == '\\')) {
                        try b.quoted(text[i + 1 .. i + 2]);
                        i += 2;
                    } else if (i + 1 < text.len and text[i + 1] == '\n') {
                        i += 2;
                    } else {
                        try b.quoted("\\");
                        i += 1;
                    }
                },
                '$' => i = try dollar(b, &p, text, i, true),
                '`' => i = try backtick(b, &p, text, i, true),
                else => {
                    try b.quoted(text[i .. i + 1]);
                    i += 1;
                },
            }
            continue;
        }
        switch (c) {
            '\'' => {
                if (in_dq) {
                    try b.quoted("'");
                    i += 1;
                    continue;
                }
                const end = p.skipSingle(i + 1) catch return badSubst(text);
                try b.quoted(text[i + 1 .. end - 1]);
                i = end;
            },
            '"' => {
                if (in_dq) {
                    // 入れ子の `${x:-"..."}` の中の引用
                    const end = p.skipDouble(i + 1) catch return badSubst(text);
                    try expandText(b, text[i + 1 .. end - 1], true, false);
                    i = end;
                    continue;
                }
                const end = p.skipDouble(i + 1) catch return badSubst(text);
                const before = b.appended;
                b.empty_at = false;
                try expandText(b, text[i + 1 .. end - 1], true, false);
                if (b.appended == before and !b.empty_at) {
                    b.has = true;
                    b.ws_finished = false;
                }
                i = end;
            },
            '\\' => {
                if (i + 1 == text.len) {
                    try b.quoted("\\");
                    i += 1;
                } else if (text[i + 1] == '\n') {
                    i += 2;
                } else if (in_dq) {
                    const n = text[i + 1];
                    if (n == '$' or n == '`' or n == '"' or n == '\\') {
                        try b.quoted(text[i + 1 .. i + 2]);
                        i += 2;
                    } else {
                        try b.quoted("\\");
                        i += 1;
                    }
                } else {
                    try b.quoted(text[i + 1 .. i + 2]);
                    i += 2;
                }
            },
            '$' => i = try dollar(b, &p, text, i, in_dq),
            '`' => i = try backtick(b, &p, text, i, in_dq),
            '~' => {
                if (i == 0 and b.tilde and !in_dq) {
                    var j: usize = 1;
                    while (j < text.len and text[j] != '/') j += 1;
                    if (j == 1) {
                        try b.quoted(vars.get("HOME") orelse "/");
                        i = 1;
                        continue;
                    }
                }
                try b.literal("~");
                i += 1;
            },
            else => {
                if (in_dq) try b.quoted(text[i .. i + 1]) else try b.literal(text[i .. i + 1]);
                i += 1;
            },
        }
    }
}

fn badSubst(text: []const u8) Error {
    util.complain(&.{ text, ": bad substitution" });
    return error.Abort;
}

/// `$` の位置 [at] から展開する。次の位置を返す
fn dollar(b: *Builder, p: *parse.Parser, text: []const u8, at: usize, in_dq: bool) Error!usize {
    if (at + 1 >= text.len) {
        try b.literal("$");
        return at + 1;
    }
    const n = text[at + 1];
    if (n == '(') {
        const end = p.skipDollar(at) catch return badSubst(text);
        if (at + 2 < text.len and text[at + 2] == '(') {
            const v = try arith(text[at + 3 .. end - 2]);
            var buf: [24]u8 = undefined;
            try b.expansion(util.formatSigned(&buf, v), in_dq);
            return end;
        }
        try commandSub(b, text[at + 2 .. end - 1], in_dq);
        return end;
    }
    if (n == '{') {
        const end = p.skipDollar(at) catch return badSubst(text);
        try braceParam(b, text[at + 2 .. end - 1], in_dq);
        return end;
    }
    if (util.isAlpha(n)) {
        var j = at + 1;
        while (j < text.len and util.isAlnum(text[j])) j += 1;
        try simpleParam(b, text[at + 1 .. j], in_dq);
        return j;
    }
    if (util.isDigit(n) or isSpecial(n)) {
        try simpleParam(b, text[at + 1 .. at + 2], in_dq);
        return at + 2;
    }
    try b.literal("$");
    return at + 1;
}

fn isSpecial(c: u8) bool {
    return c == '@' or c == '*' or c == '#' or c == '?' or c == '$' or c == '!' or c == '-';
}

/// `$name`・`$1`・`$@` など
fn simpleParam(b: *Builder, name: []const u8, in_dq: bool) Error!void {
    if (eql(name, "@") or eql(name, "*")) return allParams(b, eql(name, "*"), in_dq);
    const v = try lookup(name);
    if (v == null) {
        if (state.nounset and !eql(name, "@") and !eql(name, "*")) {
            util.complain(&.{ name, ": parameter not set" });
            return error.Abort;
        }
        return;
    }
    try b.expansion(v.?, in_dq);
}

fn allParams(b: *Builder, star: bool, in_dq: bool) Error!void {
    const ps = vars.params;
    if (ps.len == 0) {
        if (in_dq and !star) b.empty_at = true;
        return;
    }
    if (in_dq and star) {
        const ifs = vars.get("IFS") orelse " \t\n";
        for (ps, 0..) |s, k| {
            if (k > 0 and ifs.len > 0) try b.quoted(ifs[0..1]);
            try b.quoted(s);
        }
        return;
    }
    for (ps, 0..) |s, k| {
        if (in_dq) {
            if (k > 0) try b.finish();
            try b.quoted(s);
        } else {
            if (k > 0 and b.split and b.out != null) {
                if (b.has) try b.finish();
                b.ws_finished = true;
            } else if (k > 0) try b.literal(" ");
            try b.expansion(s, false);
        }
    }
}

/// 特別なパラメーターと変数の値（なければ null）
pub fn lookup(name: []const u8) Error!?[]const u8 {
    var buf: [24]u8 = undefined;
    if (name.len == 1) {
        switch (name[0]) {
            '?' => return try mem.str.dupe(kibi.io.formatUnsigned(&buf, state.status)),
            '$' => return try mem.str.dupe(kibi.io.formatUnsigned(&buf, state.shell_pid)),
            '#' => return try mem.str.dupe(kibi.io.formatUnsigned(&buf, vars.params.len)),
            '!' => return if (state.last_bg) |pid| try mem.str.dupe(kibi.io.formatUnsigned(&buf, pid)) else null,
            '-' => return try optionString(),
            '0' => return vars.arg0,
            '@', '*' => return try joinParams(),
            else => {},
        }
    }
    if (util.isDigit(name[0])) {
        const k = kibi.io.parseUnsigned(name) orelse return null;
        if (k == 0) return vars.arg0;
        if (k > vars.params.len) return null;
        return vars.params[@intCast(k - 1)];
    }
    return vars.get(name);
}

fn joinParams() Error![]const u8 {
    var out = mem.List(u8).init(&mem.str);
    for (vars.params, 0..) |s, k| {
        if (k > 0) try out.append(' ');
        try out.appendSlice(s);
    }
    return out.items;
}

fn optionString() Error![]const u8 {
    var out = mem.List(u8).init(&mem.str);
    if (state.errexit) try out.append('e');
    if (state.noglob) try out.append('f');
    if (state.interactive) try out.append('i');
    if (state.nounset) try out.append('u');
    if (state.verbose) try out.append('v');
    if (state.xtrace) try out.append('x');
    if (state.noclobber) try out.append('C');
    return out.items;
}

/// `${...}` の中身
fn braceParam(b: *Builder, inner: []const u8, in_dq: bool) Error!void {
    if (inner.len == 0) return badSubst("${}");
    // ${#name}
    if (inner[0] == '#' and inner.len > 1) {
        const name = inner[1..];
        if (!validParam(name)) return badSubst(inner);
        const v = (try lookup(name)) orelse {
            if (state.nounset) {
                util.complain(&.{ name, ": parameter not set" });
                return error.Abort;
            }
            return b.expansion("0", in_dq);
        };
        var buf: [24]u8 = undefined;
        const len = if (eql(name, "@") or eql(name, "*")) vars.params.len else v.len;
        return b.expansion(kibi.io.formatUnsigned(&buf, len), in_dq);
    }
    var j: usize = 0;
    if (util.isAlpha(inner[0])) {
        while (j < inner.len and util.isAlnum(inner[j])) j += 1;
    } else if (util.isDigit(inner[0])) {
        while (j < inner.len and util.isDigit(inner[j])) j += 1;
    } else if (isSpecial(inner[0])) {
        j = 1;
    } else return badSubst(inner);
    const name = inner[0..j];
    const rest = inner[j..];
    if (rest.len == 0) return simpleParam(b, name, in_dq);

    var colon = false;
    var k: usize = 0;
    if (rest[0] == ':') {
        colon = true;
        k = 1;
    }
    if (k >= rest.len) return badSubst(inner);
    const op = rest[k];
    var word_start = k + 1;
    var twice = false;
    if (!colon and (op == '#' or op == '%') and word_start < rest.len and rest[word_start] == op) {
        twice = true;
        word_start += 1;
    }
    const word = rest[word_start..];
    const value = try lookup(name);
    const unset_or_null = value == null or (colon and value.?.len == 0);
    switch (op) {
        '-' => {
            if (unset_or_null) return expandText(b, word, in_dq, false);
            return b.expansion(value.?, in_dq);
        },
        '=' => {
            if (unset_or_null) {
                if (!util.isName(name)) {
                    util.complain(&.{ name, ": cannot assign in this way" });
                    return error.Abort;
                }
                const w = try string(word);
                try vars.set(name, w);
                return b.expansion(w, in_dq);
            }
            return b.expansion(value.?, in_dq);
        },
        '?' => {
            if (unset_or_null) {
                const msg = if (word.len > 0) try string(word) else "parameter null or not set";
                util.complain(&.{ name, ": ", msg });
                return error.Abort;
            }
            return b.expansion(value.?, in_dq);
        },
        '+' => {
            if (!unset_or_null) return expandText(b, word, in_dq, false);
            return;
        },
        '#', '%' => {
            if (colon) return badSubst(inner);
            const v = value orelse {
                if (state.nounset) {
                    util.complain(&.{ name, ": parameter not set" });
                    return error.Abort;
                }
                return;
            };
            const pat = try pattern(word);
            return b.expansion(trimPattern(v, pat, op == '#', twice), in_dq);
        },
        else => return badSubst(inner),
    }
}

fn validParam(name: []const u8) bool {
    if (name.len == 1 and (isSpecial(name[0]) or util.isDigit(name[0]))) return true;
    if (util.isName(name)) return true;
    for (name) |c| if (!util.isDigit(c)) return false;
    return true;
}

/// `${v#pat}`・`${v##pat}`・`${v%pat}`・`${v%%pat}`
fn trimPattern(v: []const u8, pat: []const u8, prefix: bool, longest: bool) []const u8 {
    if (prefix) {
        if (longest) {
            var n = v.len + 1;
            while (n > 0) {
                n -= 1;
                if (match(pat, v[0..n])) return v[n..];
            }
        } else {
            var n: usize = 0;
            while (n <= v.len) : (n += 1) {
                if (match(pat, v[0..n])) return v[n..];
            }
        }
    } else {
        if (longest) {
            var n: usize = 0;
            while (n <= v.len) : (n += 1) {
                if (match(pat, v[n..])) return v[0..n];
            }
        } else {
            var n = v.len + 1;
            while (n > 0) {
                n -= 1;
                if (match(pat, v[n..])) return v[0..n];
            }
        }
    }
    return v;
}

/// コマンド置換。出力の最後の改行を落として足す
fn commandSub(b: *Builder, cmd: []const u8, in_dq: bool) Error!void {
    const out = try exec.capture(cmd);
    defer mem.heapFree(out);
    var n = out.len;
    while (n > 0 and out[n - 1] == '\n') n -= 1;
    try b.expansion(out[0..n], in_dq);
}

/// `` `...` `` の位置 [at] から。中の `\$`・`` \` ``・`\\` を戻してからコマンド置換にする
fn backtick(b: *Builder, p: *parse.Parser, text: []const u8, at: usize, in_dq: bool) Error!usize {
    const end = p.skipBacktick(at + 1) catch return badSubst(text);
    const inner = text[at + 1 .. end - 1];
    var cmd = mem.List(u8).init(&mem.ast);
    var i: usize = 0;
    while (i < inner.len) : (i += 1) {
        if (inner[i] == '\\' and i + 1 < inner.len and (inner[i + 1] == '$' or inner[i + 1] == '`' or inner[i + 1] == '\\' or (in_dq and inner[i + 1] == '"'))) i += 1;
        try cmd.append(inner[i]);
    }
    try commandSub(b, cmd.items, in_dq);
    return end;
}

// ---- 算術 ----

const Arith = struct {
    s: []const u8,
    i: usize = 0,
    /// 0 で割ったなどの誤り
    failed: ?[]const u8 = null,
    /// 評価しない（`&&`・`||`・`?:` の飛ばす側）
    skip: u32 = 0,

    fn ws(self: *Arith) void {
        while (self.i < self.s.len and util.isBlank(self.s[self.i])) self.i += 1;
    }

    fn eat(self: *Arith, op: []const u8) bool {
        self.ws();
        if (util.startsWith(self.s[self.i..], op)) {
            // `<` と `<<`・`<=` などを見分ける
            const after = self.i + op.len;
            if (op.len == 1 and after < self.s.len) {
                const n = self.s[after];
                const c = op[0];
                if ((c == '<' or c == '>') and (n == c or n == '=')) return false;
                if ((c == '&' or c == '|') and n == c) return false;
                if ((c == '=' or c == '!') and n == '=') return false;
                if ((c == '*' or c == '/' or c == '%' or c == '+' or c == '-' or c == '^') and n == '=') return false;
                if ((c == '+' or c == '-') and n == c) return false;
            }
            if (op.len == 2 and after < self.s.len and self.s[after] == '=' and (eql(op, "<<") or eql(op, ">>"))) return false;
            self.i = after;
            return true;
        }
        return false;
    }

    fn fail(self: *Arith, why: []const u8) i64 {
        if (self.failed == null) self.failed = why;
        return 0;
    }

    fn expr(self: *Arith) Error!i64 {
        return self.assign();
    }

    fn assign(self: *Arith) Error!i64 {
        self.ws();
        const save = self.i;
        if (self.i < self.s.len and util.isAlpha(self.s[self.i])) {
            var j = self.i;
            while (j < self.s.len and util.isAlnum(self.s[j])) j += 1;
            const name = self.s[self.i..j];
            self.i = j;
            const ops = [_][]const u8{ "=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "|=", "^=" };
            self.ws();
            for (ops) |op| {
                if (util.startsWith(self.s[self.i..], op) and !(op.len == 1 and self.i + 1 < self.s.len and self.s[self.i + 1] == '=')) {
                    self.i += op.len;
                    const rhs = try self.assign();
                    const cur = try self.varValue(name);
                    const v: i64 = switch (op[0]) {
                        '=' => rhs,
                        '+' => cur +% rhs,
                        '-' => cur -% rhs,
                        '*' => cur *% rhs,
                        // 0 での割り算と INT64_MIN / -1 は、2 項の `/`・`%` と同じに扱う（評価しない側では誤りにしない）
                        '/' => self.apply("/", cur, rhs),
                        '%' => self.apply("%", cur, rhs),
                        '<' => cur << @intCast(rhs & 63),
                        '>' => cur >> @intCast(rhs & 63),
                        '&' => cur & rhs,
                        '|' => cur | rhs,
                        else => cur ^ rhs,
                    };
                    if (self.skip == 0 and self.failed == null) {
                        var buf: [24]u8 = undefined;
                        try vars.set(name, util.formatSigned(&buf, v));
                    }
                    return v;
                }
            }
        }
        self.i = save;
        return self.ternary();
    }

    fn ternary(self: *Arith) Error!i64 {
        const c = try self.binary(0);
        if (!self.eat("?")) return c;
        if (c == 0) self.skip += 1;
        const a = try self.assign();
        if (c == 0) self.skip -= 1;
        if (!self.eat(":")) return self.fail("expected ':'");
        if (c != 0) self.skip += 1;
        const b = try self.assign();
        if (c != 0) self.skip -= 1;
        return if (c != 0) a else b;
    }

    const levels = [_][]const []const u8{
        &.{"||"},
        &.{"&&"},
        &.{"|"},
        &.{"^"},
        &.{"&"},
        &.{ "==", "!=" },
        &.{ "<=", ">=", "<", ">" },
        &.{ "<<", ">>" },
        &.{ "+", "-" },
        &.{ "*", "/", "%" },
    };

    fn binary(self: *Arith, level: usize) Error!i64 {
        if (level == levels.len) return self.unary();
        var l = try self.binary(level + 1);
        outer: while (true) {
            for (levels[level]) |op| {
                if (!self.eat(op)) continue;
                const short = (eql(op, "&&") and l == 0) or (eql(op, "||") and l != 0);
                if (short) self.skip += 1;
                const r = try self.binary(level + 1);
                if (short) self.skip -= 1;
                l = self.apply(op, l, r);
                continue :outer;
            }
            break;
        }
        return l;
    }

    fn apply(self: *Arith, op: []const u8, l: i64, r: i64) i64 {
        if (eql(op, "||")) return @intFromBool(l != 0 or r != 0);
        if (eql(op, "&&")) return @intFromBool(l != 0 and r != 0);
        if (eql(op, "|")) return l | r;
        if (eql(op, "^")) return l ^ r;
        if (eql(op, "&")) return l & r;
        if (eql(op, "==")) return @intFromBool(l == r);
        if (eql(op, "!=")) return @intFromBool(l != r);
        if (eql(op, "<=")) return @intFromBool(l <= r);
        if (eql(op, ">=")) return @intFromBool(l >= r);
        if (eql(op, "<")) return @intFromBool(l < r);
        if (eql(op, ">")) return @intFromBool(l > r);
        if (eql(op, "<<")) return l << @intCast(r & 63);
        if (eql(op, ">>")) return l >> @intCast(r & 63);
        if (eql(op, "+")) return l +% r;
        if (eql(op, "-")) return l -% r;
        if (eql(op, "*")) return l *% r;
        if (r == 0) {
            if (self.skip > 0) return 0;
            return self.fail("division by zero");
        }
        if (l == -0x7fff_ffff_ffff_ffff - 1 and r == -1) return if (eql(op, "/")) l else 0;
        if (eql(op, "/")) return @divTrunc(l, r);
        return @rem(l, r);
    }

    fn unary(self: *Arith) Error!i64 {
        // 括弧・単項演算子の入れ子は式の中で再帰するので、スタックが尽きる前に誤りにする（#514）
        if (util.stackLow()) return self.fail("too deeply nested");
        if (self.eat("!")) return @intFromBool((try self.unary()) == 0);
        if (self.eat("~")) return ~(try self.unary());
        if (self.eat("-")) return -%(try self.unary());
        if (self.eat("+")) return self.unary();
        return self.primary();
    }

    fn primary(self: *Arith) Error!i64 {
        self.ws();
        if (self.eat("(")) {
            const v = try self.expr();
            if (!self.eat(")")) return self.fail("expected ')'");
            return v;
        }
        if (self.i >= self.s.len) return self.fail("expected an operand");
        const c = self.s[self.i];
        if (util.isDigit(c)) {
            var j = self.i;
            while (j < self.s.len and util.isAlnum(self.s[j])) j += 1;
            const lit = self.s[self.i..j];
            self.i = j;
            return parseNumber(lit) orelse self.fail("bad number");
        }
        if (util.isAlpha(c)) {
            var j = self.i;
            while (j < self.s.len and util.isAlnum(self.s[j])) j += 1;
            const name = self.s[self.i..j];
            self.i = j;
            return self.varValue(name);
        }
        return self.fail("syntax error");
    }

    fn varValue(self: *Arith, name: []const u8) Error!i64 {
        const v = vars.get(name) orelse {
            if (state.nounset and self.skip == 0) {
                util.complain(&.{ name, ": parameter not set" });
                return error.Abort;
            }
            return 0;
        };
        if (util.trim(v).len == 0) return 0;
        return parseNumber(util.trim(v)) orelse self.fail("bad number");
    }
};

/// 10 進・`0x` の 16 進・`0` で始まる 8 進
fn parseNumber(s: []const u8) ?i64 {
    var t = s;
    var neg = false;
    if (t.len > 0 and (t[0] == '-' or t[0] == '+')) {
        neg = t[0] == '-';
        t = t[1..];
    }
    if (t.len == 0) return null;
    var base: u64 = 10;
    if (t.len > 2 and t[0] == '0' and (t[1] == 'x' or t[1] == 'X')) {
        base = 16;
        t = t[2..];
    } else if (t.len > 1 and t[0] == '0') {
        base = 8;
        t = t[1..];
    }
    var v: u64 = 0;
    for (t) |c| {
        const d: u64 = if (util.isDigit(c)) c - '0' else if (c >= 'a' and c <= 'f') c - 'a' + 10 else if (c >= 'A' and c <= 'F') c - 'A' + 10 else return null;
        if (d >= base) return null;
        v = v *% base +% d;
    }
    const r: i64 = @bitCast(v);
    return if (neg) -%r else r;
}

/// `$((...))` の中身を展開して計算する
pub fn arith(text: []const u8) Error!i64 {
    var b = Builder.init(null, false);
    b.tilde = false;
    try expandText(&b, text, true, false);
    const s = try unescape(b.cur.items);
    var a = Arith{ .s = s };
    const v = try a.expr();
    a.ws();
    if (a.failed == null and a.i < s.len) a.failed = "syntax error";
    if (a.failed) |why| {
        util.complain(&.{ "arithmetic: ", why, ": ", s });
        return error.Abort;
    }
    return v;
}

// ---- パターン ----

/// 印のない `*`・`?`・`[` があるか
pub fn hasGlob(s: []const u8) bool {
    var i: usize = 0;
    while (i < s.len) : (i += 1) {
        if (s[i] == ESC) {
            i += 1;
            continue;
        }
        if (s[i] == '*' or s[i] == '?' or s[i] == '[') return true;
    }
    return false;
}

/// [pat]（印つき）が [s] 全体に合うか
pub fn match(pat: []const u8, s: []const u8) bool {
    var p: usize = 0;
    var i: usize = 0;
    // 最後の `*` の位置と、そのとき合わせた位置（戻って試す）
    var star_p: ?usize = null;
    var star_i: usize = 0;
    while (true) {
        if (p < pat.len) {
            const c = pat[p];
            if (c == '*') {
                star_p = p + 1;
                star_i = i;
                p += 1;
                continue;
            }
            if (i < s.len) {
                if (c == '?') {
                    p += 1;
                    i += 1;
                    continue;
                }
                if (c == '[') {
                    if (bracket(pat, p, s[i])) |r| {
                        if (r.ok) {
                            p = r.next;
                            i += 1;
                            continue;
                        }
                    } else if (s[i] == '[') {
                        p += 1;
                        i += 1;
                        continue;
                    }
                } else {
                    var lit = c;
                    var adv: usize = 1;
                    if (c == ESC and p + 1 < pat.len) {
                        lit = pat[p + 1];
                        adv = 2;
                    }
                    if (lit == s[i]) {
                        p += adv;
                        i += 1;
                        continue;
                    }
                }
            }
        } else if (i == s.len) return true;
        // 合わなかった。最後の `*` を 1 文字伸ばす
        if (star_p) |sp| {
            if (star_i < s.len) {
                star_i += 1;
                p = sp;
                i = star_i;
                continue;
            }
        }
        return false;
    }
}

const BracketResult = struct { ok: bool, next: usize };

/// `[` の位置 [at] の文字の集まり。閉じていなければ null（`[` をそのままの文字にする）
fn bracket(pat: []const u8, at: usize, c: u8) ?BracketResult {
    var p = at + 1;
    var negate = false;
    if (p < pat.len and (pat[p] == '!' or pat[p] == '^')) {
        negate = true;
        p += 1;
    }
    var ok = false;
    var first = true;
    while (p < pat.len) {
        var lo = pat[p];
        if (lo == ']' and !first) return .{ .ok = ok != negate, .next = p + 1 };
        first = false;
        if (lo == ESC and p + 1 < pat.len) {
            p += 1;
            lo = pat[p];
        }
        p += 1;
        var hi = lo;
        if (p + 1 < pat.len and pat[p] == '-' and pat[p + 1] != ']') {
            p += 1;
            hi = pat[p];
            if (hi == ESC and p + 1 < pat.len) {
                p += 1;
                hi = pat[p];
            }
            p += 1;
        }
        if (c >= lo and c <= hi) ok = true;
    }
    return null;
}

// ---- パス名の展開 ----

/// [pat]（印つき）に合うパスを、名前の順で [out] に足す
fn glob(pat: []const u8, out: *mem.List([]const u8)) Error!void {
    const start = out.items.len;
    var prefix = mem.List(u8).init(&mem.str);
    var rest = pat;
    if (rest.len > 0 and rest[0] == '/') {
        try prefix.append('/');
        while (rest.len > 0 and rest[0] == '/') rest = rest[1..];
    }
    try globRest(prefix.items, rest, out);
    sort(out.items[start..]);
}

fn globRest(prefix: []const u8, rest: []const u8, out: *mem.List([]const u8)) Error!void {
    if (rest.len == 0) {
        try out.append(prefix);
        return;
    }
    // 次の `/` までが 1 つの部分
    var j: usize = 0;
    while (j < rest.len and rest[j] != '/') : (j += 1) {
        if (rest[j] == ESC) j += 1;
    }
    const part = rest[0..@min(j, rest.len)];
    var next = rest[@min(j, rest.len)..];
    const slash = next.len > 0;
    while (next.len > 0 and next[0] == '/') next = next[1..];

    if (!hasGlob(part)) {
        const lit = try unescape(part);
        var p = mem.List(u8).init(&mem.str);
        try p.appendSlice(prefix);
        try p.appendSlice(lit);
        if (slash) try p.append('/');
        // 残りに印のない `*` がなければ、あるかだけを見る
        if (next.len == 0 or !hasGlob(next)) {
            const whole = try mem.str.concat(p.items, try unescape(next));
            _ = fs.stat(whole) catch return;
            try out.append(whole);
            return;
        }
        return globRest(p.items, next, out);
    }

    const dir = if (prefix.len == 0) "." else prefix;
    const fd = fs.open(dir, .{ .directory = true }) catch return;
    defer kibi.io.close(fd) catch {};
    const buf = try mem.ast.alloc(2048, 8);
    var d = fs.Dir.init(fd, buf);
    var names = mem.List([]const u8).init(&mem.ast);
    const dot_ok = part.len > 0 and part[0] == '.';
    while (true) {
        const e = (d.next() catch break) orelse break;
        if (e.name.len > 0 and e.name[0] == '.' and !dot_ok) continue;
        if (!match(part, e.name)) continue;
        if (slash and e.kind != .directory and e.kind != .symlink) continue;
        try names.append(try mem.str.dupe(e.name));
    }
    for (names.items) |name| {
        var p = mem.List(u8).init(&mem.str);
        try p.appendSlice(prefix);
        try p.appendSlice(name);
        if (slash) try p.append('/');
        if (next.len == 0) {
            try out.append(p.items);
        } else {
            try globRest(p.items, next, out);
        }
    }
}

fn lessThan(a: []const u8, b: []const u8) bool {
    const n = @min(a.len, b.len);
    for (a[0..n], b[0..n]) |x, y| {
        if (x != y) return x < y;
    }
    return a.len < b.len;
}

pub fn sort(items: [][]const u8) void {
    var i: usize = 1;
    while (i < items.len) : (i += 1) {
        const v = items[i];
        var j = i;
        while (j > 0 and lessThan(v, items[j - 1])) : (j -= 1) items[j] = items[j - 1];
        items[j] = v;
    }
}
