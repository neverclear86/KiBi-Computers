//! シェルの字句と構文（#101）。POSIX sh の文法のうち、よく使う部分を読む。
//!
//! 語（word）は、元の文字列（引用符を含む）のまま持つ。展開（`expand.zig`）が元の文字列を読み直す。
//! 入力が途中で切れているとき（引用符・`if` などが閉じていない）は、続きがありうるなら `Incomplete` を返す。
//! 呼んだ側は続きを読んで、同じところから読み直す（途中の状態は持たない）。
const kibi = @import("kibi");
const util = @import("util.zig");
const mem = @import("mem.zig");

const Error = util.Error;
const eql = util.eql;

pub const Word = struct {
    text: []const u8,
    next: ?*Word = null,
};

pub const RedirOp = enum { in, out, append, clobber, rw, dup_in, dup_out, heredoc, heredoc_strip };

pub const Redir = struct {
    /// 書かれた fd（`2>` の 2）。null なら op の既定（入力は 0、出力は 1）
    fd: ?u32,
    op: RedirOp,
    /// 先（元の文字列）。ヒアドキュメントでは区切りの語
    target: []const u8,
    /// ヒアドキュメントの中身（区切りの行を除く）
    body: []const u8 = "",
    /// ヒアドキュメントの中身と区切りの行（子のシェルに渡す文字列を作るときに使う）
    tail: []const u8 = "",
    /// 区切りの語に引用符があった（中身を展開しない）
    quoted: bool = false,
    /// 引用符を外した区切りの語
    delim: []const u8 = "",
    next: ?*Redir = null,
    /// 中身を待っているヒアドキュメントの並び
    pend_next: ?*Redir = null,
    /// 読んだ順のヒアドキュメントの並び
    hd_next: ?*Redir = null,
};

pub const Item = struct {
    node: *Node,
    background: bool,
    next: ?*Item = null,
};

pub const CaseItem = struct {
    patterns: *Word,
    body: ?*Node,
    next: ?*CaseItem = null,
};

pub const Kind = union(enum) {
    simple: struct { assigns: ?*Word, words: ?*Word },
    /// パイプライン（`first` から `next` でつなぐ）
    pipeline: struct { first: *Node, count: usize, bang: bool },
    and_or: struct { left: *Node, right: *Node, is_and: bool },
    list: struct { first: ?*Item },
    subshell: *Node,
    group: *Node,
    if_: struct { cond: *Node, then: *Node, else_: ?*Node },
    loop: struct { cond: *Node, body: *Node, until: bool },
    for_: struct { name: []const u8, words: ?*Word, has_in: bool, body: *Node },
    case_: struct { word: []const u8, items: ?*CaseItem },
    func: struct { name: []const u8, body: *Node },
};

pub const Node = struct {
    kind: Kind,
    redirs: ?*Redir = null,
    /// 元の文字列
    src: []const u8 = "",
    /// この中で読んだヒアドキュメント（読んだ順）
    hd_first: ?*Redir = null,
    hd_count: usize = 0,
    /// パイプラインの次のコマンド
    next: ?*Node = null,
};

pub const Tok = enum {
    word,
    io_number,
    newline,
    eof,
    and_and,
    or_or,
    semi_semi,
    semi,
    amp,
    pipe,
    lparen,
    rparen,
    less,
    great,
    dless,
    dless_dash,
    dgreat,
    less_and,
    great_and,
    less_great,
    clobber,
};

pub const Token = struct { kind: Tok, text: []const u8, start: usize, end: usize };

/// 入れ子の深さの上限（スタックを使い切らないため）
const MAX_DEPTH = 100;

pub const Parser = struct {
    src: []const u8,
    pos: usize = 0,
    /// 入力がこれ以上増えない（途中で切れていたら構文の誤り）
    at_eof: bool,
    peeked: ?Token = null,
    /// 最後に受け取った字句の終わり
    last_end: usize = 0,
    /// 中身を待っているヒアドキュメント
    pending: ?*Redir = null,
    pending_tail: ?*Redir = null,
    /// 読んだヒアドキュメントの並びと数
    hd_head: ?*Redir = null,
    hd_tail: ?*Redir = null,
    hd_total: usize = 0,
    depth: u32 = 0,
    /// 知らせに書く名前（スクリプトの名前など）と、src の頭の行番号
    name: []const u8 = "",
    line_base: usize = 1,
    /// 誤りを知らせない（`$(` の終わりを探す入れ子の読み）
    quiet: bool = false,

    pub fn init(src: []const u8, at_eof: bool) Parser {
        return .{ .src = src, .at_eof = at_eof };
    }

    // ---- 字句 ----

    fn more(self: *Parser) Error {
        if (self.at_eof) {
            self.syntax(self.src.len, "unexpected end of file");
            return error.Syntax;
        }
        return error.Incomplete;
    }

    pub fn peek(self: *Parser) Error!Token {
        if (self.peeked) |t| return t;
        const t = try self.lex();
        self.peeked = t;
        return t;
    }

    pub fn take(self: *Parser) Error!Token {
        const t = try self.peek();
        self.peeked = null;
        self.last_end = t.end;
        return t;
    }

    fn lex(self: *Parser) Error!Token {
        const s = self.src;
        var i = self.pos;
        while (true) {
            while (i < s.len and (s[i] == ' ' or s[i] == '\t' or s[i] == '\r')) i += 1;
            if (i < s.len and s[i] == '\\') {
                if (i + 1 == s.len) {
                    self.pos = i;
                    if (!self.at_eof) return error.Incomplete;
                } else if (s[i + 1] == '\n') {
                    i += 2;
                    continue;
                }
            }
            if (i < s.len and s[i] == '#') {
                while (i < s.len and s[i] != '\n') i += 1;
            }
            break;
        }
        self.pos = i;
        if (i == s.len) {
            if (!self.at_eof) return error.Incomplete;
            return .{ .kind = .eof, .text = "", .start = i, .end = i };
        }
        const c = s[i];
        if (c == '\n') {
            self.pos = i + 1;
            try self.readHeredocs();
            return .{ .kind = .newline, .text = "\n", .start = i, .end = i + 1 };
        }
        const ops = [_]struct { t: []const u8, k: Tok }{
            .{ .t = "<<-", .k = .dless_dash },
            .{ .t = "&&", .k = .and_and },
            .{ .t = "||", .k = .or_or },
            .{ .t = ";;", .k = .semi_semi },
            .{ .t = "<<", .k = .dless },
            .{ .t = ">>", .k = .dgreat },
            .{ .t = "<&", .k = .less_and },
            .{ .t = ">&", .k = .great_and },
            .{ .t = "<>", .k = .less_great },
            .{ .t = ">|", .k = .clobber },
            .{ .t = ";", .k = .semi },
            .{ .t = "&", .k = .amp },
            .{ .t = "|", .k = .pipe },
            .{ .t = "(", .k = .lparen },
            .{ .t = ")", .k = .rparen },
            .{ .t = "<", .k = .less },
            .{ .t = ">", .k = .great },
        };
        for (ops) |op| {
            if (util.startsWith(s[i..], op.t)) {
                // 演算子が入力の終わりで切れているかもしれない（`&` の後に `&` が来るなど）
                if (i + op.t.len == s.len and !self.at_eof and op.t.len < 3 and (c == '&' or c == '|' or c == ';' or c == '<' or c == '>')) return error.Incomplete;
                self.pos = i + op.t.len;
                return .{ .kind = op.k, .text = s[i..self.pos], .start = i, .end = self.pos };
            }
        }
        const end = try self.scanWord(i);
        self.pos = end;
        const text = s[i..end];
        var kind: Tok = .word;
        if (end < s.len and (s[end] == '<' or s[end] == '>')) {
            var digits = true;
            for (text) |d| digits = digits and util.isDigit(d);
            if (digits) kind = .io_number;
        }
        return .{ .kind = kind, .text = text, .start = i, .end = end };
    }

    /// [start] から語の終わりを探す
    fn scanWord(self: *Parser, start: usize) Error!usize {
        const s = self.src;
        var i = start;
        while (i < s.len) {
            const c = s[i];
            switch (c) {
                ' ', '\t', '\r', '\n', ';', '&', '|', '<', '>', '(', ')' => break,
                '\\' => {
                    if (i + 1 == s.len) {
                        if (!self.at_eof) return error.Incomplete;
                        i += 1;
                    } else i += 2;
                },
                '\'' => i = try self.skipSingle(i + 1),
                '"' => i = try self.skipDouble(i + 1),
                '$' => i = try self.skipDollar(i),
                '`' => i = try self.skipBacktick(i + 1),
                else => i += 1,
            }
        }
        if (i == s.len and !self.at_eof) return error.Incomplete;
        return i;
    }

    /// `'` の次から、閉じる `'` の次まで
    pub fn skipSingle(self: *Parser, from: usize) Error!usize {
        var i = from;
        while (i < self.src.len) : (i += 1) {
            if (self.src[i] == '\'') return i + 1;
        }
        return self.more();
    }

    /// `"` の次から、閉じる `"` の次まで
    pub fn skipDouble(self: *Parser, from: usize) Error!usize {
        const s = self.src;
        var i = from;
        while (i < s.len) {
            switch (s[i]) {
                '"' => return i + 1,
                '\\' => i += 2,
                '$' => i = try self.skipDollar(i),
                '`' => i = try self.skipBacktick(i + 1),
                else => i += 1,
            }
        }
        return self.more();
    }

    /// `` ` `` の次から、閉じる `` ` `` の次まで
    pub fn skipBacktick(self: *Parser, from: usize) Error!usize {
        const s = self.src;
        var i = from;
        while (i < s.len) {
            if (s[i] == '\\') {
                i += 2;
                continue;
            }
            if (s[i] == '`') return i + 1;
            i += 1;
        }
        return self.more();
    }

    /// `$` の位置から、その展開の終わりまで（`$` だけ・`$name` も）
    pub fn skipDollar(self: *Parser, at: usize) Error!usize {
        const s = self.src;
        var i = at + 1;
        if (i == s.len) {
            if (!self.at_eof) return error.Incomplete;
            return i;
        }
        switch (s[i]) {
            '(' => {
                if (i + 1 < s.len and s[i + 1] == '(') return self.skipArith(i + 2);
                if (i + 1 == s.len and !self.at_eof) return error.Incomplete;
                return self.skipCommandSub(i + 1);
            },
            '{' => return self.skipBrace(i + 1),
            else => {
                if (util.isAlpha(s[i])) {
                    while (i < s.len and util.isAlnum(s[i])) i += 1;
                    if (i == s.len and !self.at_eof) return error.Incomplete;
                    return i;
                }
                return i + 1;
            },
        }
    }

    /// `$((` の次から `))` の次まで
    fn skipArith(self: *Parser, from: usize) Error!usize {
        const s = self.src;
        var i = from;
        var depth: usize = 2;
        while (i < s.len) {
            switch (s[i]) {
                '(' => {
                    depth += 1;
                    i += 1;
                },
                ')' => {
                    depth -= 1;
                    i += 1;
                    if (depth == 0) return i;
                },
                '\'' => i = try self.skipSingle(i + 1),
                '"' => i = try self.skipDouble(i + 1),
                '$' => i = try self.skipDollar(i),
                '`' => i = try self.skipBacktick(i + 1),
                '\\' => i += 2,
                else => i += 1,
            }
        }
        return self.more();
    }

    /// `${` の次から `}` の次まで
    fn skipBrace(self: *Parser, from: usize) Error!usize {
        const s = self.src;
        var i = from;
        while (i < s.len) {
            switch (s[i]) {
                '}' => return i + 1,
                '\'' => i = try self.skipSingle(i + 1),
                '"' => i = try self.skipDouble(i + 1),
                '$' => i = try self.skipDollar(i),
                '`' => i = try self.skipBacktick(i + 1),
                '\\' => i += 2,
                else => i += 1,
            }
        }
        return self.more();
    }

    /// `$(` の次から `)` の次まで。中をコマンドとして読んで終わりを見つける（`case` の `)` を数え違えないため）
    fn skipCommandSub(self: *Parser, from: usize) Error!usize {
        if (self.depth >= MAX_DEPTH) {
            self.syntax(from, "too deeply nested");
            return error.Syntax;
        }
        const m = mem.mark();
        defer mem.release(m);
        var sub = Parser{ .src = self.src, .pos = from, .at_eof = self.at_eof, .depth = self.depth + 1, .quiet = true, .name = self.name, .line_base = self.line_base };
        _ = sub.compoundList() catch |e| {
            if (e == error.Syntax and !self.quiet) self.syntax(sub.pos, "bad command substitution");
            return e;
        };
        const t = sub.take() catch |e| return e;
        if (t.kind != .rparen) {
            if (t.kind == .eof) return self.more();
            if (!self.quiet) self.syntax(t.start, "missing ')' in command substitution");
            return error.Syntax;
        }
        return t.end;
    }

    /// 改行の後で、待っているヒアドキュメントの中身を読む
    fn readHeredocs(self: *Parser) Error!void {
        while (self.pending) |r| {
            const s = self.src;
            const body_start = self.pos;
            var i = self.pos;
            while (true) {
                const line_start = i;
                while (i < s.len and s[i] != '\n') i += 1;
                if (i == s.len and !self.at_eof) return error.Incomplete;
                var line = s[line_start..i];
                if (line.len > 0 and line[line.len - 1] == '\r') line = line[0 .. line.len - 1];
                if (r.op == .heredoc_strip) {
                    while (line.len > 0 and line[0] == '\t') line = line[1..];
                }
                const after = if (i < s.len) i + 1 else i;
                if (eql(line, r.delim) or (i == s.len and line_start == i)) {
                    // 区切りの行（入力の終わりで区切りがなければ、そこまでを中身にする）
                    r.body = s[body_start..line_start];
                    r.tail = s[body_start..after];
                    self.pos = after;
                    break;
                }
                i = after;
            }
            self.pending = r.pend_next;
        }
        self.pending_tail = null;
    }

    // ---- 知らせ ----

    pub fn lineAt(self: *Parser, pos: usize) usize {
        var n = self.line_base;
        for (self.src[0..@min(pos, self.src.len)]) |c| {
            if (c == '\n') n += 1;
        }
        return n;
    }

    fn syntax(self: *Parser, pos: usize, what: []const u8) void {
        if (self.quiet) return;
        var b: [20]u8 = undefined;
        const line = kibi.io.formatUnsigned(&b, self.lineAt(pos));
        if (self.name.len > 0) {
            util.complain(&.{ self.name, ": line ", line, ": syntax error: ", what });
        } else {
            util.complain(&.{ "line ", line, ": syntax error: ", what });
        }
    }

    fn unexpected(self: *Parser, t: Token) Error {
        if (!self.quiet) {
            var b: [80]u8 = undefined;
            const name = switch (t.kind) {
                .eof => "end of file",
                .newline => "newline",
                else => t.text,
            };
            const head = "unexpected '";
            var n: usize = head.len;
            @memcpy(b[0..n], head);
            const k = @min(name.len, b.len - n - 1);
            @memcpy(b[n..][0..k], name[0..k]);
            n += k;
            b[n] = '\'';
            n += 1;
            self.syntax(t.start, b[0..n]);
        }
        return error.Syntax;
    }

    // ---- 構文 ----

    fn newNode(self: *Parser, kind: Kind, start: usize, hd_start: usize) Error!*Node {
        const n = try mem.ast.create(Node);
        n.* = .{ .kind = kind, .src = self.src[start..@max(start, self.last_end)] };
        // この中で読んだヒアドキュメント
        if (self.hd_total > hd_start) {
            var r = self.hd_head;
            var k: usize = 0;
            while (k < hd_start) : (k += 1) r = r.?.hd_next;
            n.hd_first = r;
            n.hd_count = self.hd_total - hd_start;
        }
        return n;
    }

    fn isWord(t: Token, w: []const u8) bool {
        return t.kind == .word and eql(t.text, w);
    }

    /// 並びを終える予約語
    fn isTerminator(t: Token) bool {
        if (t.kind == .eof or t.kind == .rparen or t.kind == .semi_semi) return true;
        if (t.kind != .word) return false;
        const words = [_][]const u8{ "then", "else", "elif", "fi", "do", "done", "esac", "}" };
        for (words) |w| if (eql(t.text, w)) return true;
        return false;
    }

    fn skipNewlines(self: *Parser) Error!void {
        while ((try self.peek()).kind == .newline) _ = try self.take();
    }

    fn expectWord(self: *Parser, w: []const u8) Error!void {
        const t = try self.peek();
        if (!isWord(t, w)) {
            if (t.kind == .eof) return self.more();
            return self.unexpected(t);
        }
        _ = try self.take();
    }

    /// 入力の頭の、完結したコマンド 1 つ（改行まで）。入力が尽きたら null。空の行は中身のない並び
    pub fn top(self: *Parser) Error!?*Node {
        // 前のコマンドのヒアドキュメントは数えない（領域はもう返している）
        self.hd_head = null;
        self.hd_tail = null;
        self.hd_total = 0;
        const t0 = try self.peek();
        if (t0.kind == .eof) return null;
        const start = t0.start;
        const hd_start = self.hd_total;
        var first: ?*Item = null;
        var last: ?*Item = null;
        if (t0.kind == .newline) {
            _ = try self.take();
            return try self.newNode(.{ .list = .{ .first = null } }, start, hd_start);
        }
        while (true) {
            const node = try self.andOr();
            const item = try mem.ast.create(Item);
            item.* = .{ .node = node, .background = false };
            if (last) |l| l.next = item else first = item;
            last = item;
            const t = try self.peek();
            if (t.kind == .semi or t.kind == .amp) {
                _ = try self.take();
                item.background = t.kind == .amp;
                const t2 = try self.peek();
                if (t2.kind == .newline) {
                    _ = try self.take();
                    break;
                }
                if (t2.kind == .eof) break;
                continue;
            }
            if (t.kind == .newline) {
                _ = try self.take();
                break;
            }
            if (t.kind == .eof) break;
            return self.unexpected(t);
        }
        return try self.newNode(.{ .list = .{ .first = first } }, start, hd_start);
    }

    /// 並び（`if` の中など）。終わりの予約語・`)`・`;;` の前で止まる
    pub fn compoundList(self: *Parser) Error!*Node {
        try self.skipNewlines();
        const t0 = try self.peek();
        const start = t0.start;
        const hd_start = self.hd_total;
        var first: ?*Item = null;
        var last: ?*Item = null;
        while (true) {
            const t = try self.peek();
            if (isTerminator(t)) break;
            const node = try self.andOr();
            const item = try mem.ast.create(Item);
            item.* = .{ .node = node, .background = false };
            if (last) |l| l.next = item else first = item;
            last = item;
            const sep = try self.peek();
            if (sep.kind == .semi or sep.kind == .amp) {
                _ = try self.take();
                item.background = sep.kind == .amp;
                try self.skipNewlines();
            } else if (sep.kind == .newline) {
                try self.skipNewlines();
            } else break;
        }
        return try self.newNode(.{ .list = .{ .first = first } }, start, hd_start);
    }

    fn andOr(self: *Parser) Error!*Node {
        const t0 = try self.peek();
        const start = t0.start;
        const hd_start = self.hd_total;
        var left = try self.pipeline();
        while (true) {
            const t = try self.peek();
            if (t.kind != .and_and and t.kind != .or_or) break;
            _ = try self.take();
            try self.skipNewlines();
            const right = try self.pipeline();
            left = try self.newNode(.{ .and_or = .{ .left = left, .right = right, .is_and = t.kind == .and_and } }, start, hd_start);
        }
        return left;
    }

    fn pipeline(self: *Parser) Error!*Node {
        var t = try self.peek();
        const start = t.start;
        const hd_start = self.hd_total;
        var bang = false;
        if (isWord(t, "!")) {
            _ = try self.take();
            bang = true;
            t = try self.peek();
        }
        const first = try self.command();
        var last = first;
        var count: usize = 1;
        while ((try self.peek()).kind == .pipe) {
            _ = try self.take();
            try self.skipNewlines();
            const c = try self.command();
            last.next = c;
            last = c;
            count += 1;
        }
        if (count == 1 and !bang) return first;
        return try self.newNode(.{ .pipeline = .{ .first = first, .count = count, .bang = bang } }, start, hd_start);
    }

    fn command(self: *Parser) Error!*Node {
        const t = try self.peek();
        if (self.depth >= MAX_DEPTH) {
            self.syntax(t.start, "too deeply nested");
            return error.Syntax;
        }
        self.depth += 1;
        defer self.depth -= 1;
        switch (t.kind) {
            .lparen => return self.compound(),
            .word => {
                const compounds = [_][]const u8{ "{", "if", "while", "until", "for", "case" };
                for (compounds) |w| if (eql(t.text, w)) return self.compound();
                if (isTerminator(t) or isWord(t, "!")) return self.unexpected(t);
                return self.simple();
            },
            .io_number, .less, .great, .dless, .dless_dash, .dgreat, .less_and, .great_and, .less_great, .clobber => return self.simple(),
            .eof => return self.more(),
            else => return self.unexpected(t),
        }
    }

    /// 複合コマンドと、その後のリダイレクト
    fn compound(self: *Parser) Error!*Node {
        const t = try self.peek();
        const start = t.start;
        const hd_start = self.hd_total;
        var kind: Kind = undefined;
        if (t.kind == .lparen) {
            _ = try self.take();
            const body = try self.compoundList();
            const close = try self.peek();
            if (close.kind != .rparen) {
                if (close.kind == .eof) return self.more();
                return self.unexpected(close);
            }
            _ = try self.take();
            kind = .{ .subshell = body };
        } else if (eql(t.text, "{")) {
            _ = try self.take();
            const body = try self.compoundList();
            try self.expectWord("}");
            kind = .{ .group = body };
        } else if (eql(t.text, "if")) {
            kind = try self.ifRest();
        } else if (eql(t.text, "while") or eql(t.text, "until")) {
            _ = try self.take();
            const cond = try self.compoundList();
            try self.expectWord("do");
            const body = try self.compoundList();
            try self.expectWord("done");
            kind = .{ .loop = .{ .cond = cond, .body = body, .until = eql(t.text, "until") } };
        } else if (eql(t.text, "for")) {
            kind = try self.forRest();
        } else {
            kind = try self.caseRest();
        }
        const n = try self.newNode(kind, start, hd_start);
        n.redirs = try self.redirects();
        n.src = self.src[start..self.last_end];
        return n;
    }

    /// `if` から `fi` まで（`elif` は入れ子の if にする）
    fn ifRest(self: *Parser) Error!Kind {
        _ = try self.take(); // if・elif
        const cond = try self.compoundList();
        try self.expectWord("then");
        const then = try self.compoundList();
        const t = try self.peek();
        var else_: ?*Node = null;
        if (isWord(t, "elif")) {
            const start = t.start;
            const hd_start = self.hd_total;
            const k = try self.ifRest();
            else_ = try self.newNode(k, start, hd_start);
            return .{ .if_ = .{ .cond = cond, .then = then, .else_ = else_ } };
        }
        if (isWord(t, "else")) {
            _ = try self.take();
            else_ = try self.compoundList();
        }
        try self.expectWord("fi");
        return .{ .if_ = .{ .cond = cond, .then = then, .else_ = else_ } };
    }

    fn forRest(self: *Parser) Error!Kind {
        _ = try self.take(); // for
        const nt = try self.take();
        if (nt.kind != .word or !util.isName(nt.text)) {
            if (nt.kind == .eof) return self.more();
            return self.unexpected(nt);
        }
        try self.skipNewlines();
        var t = try self.peek();
        var words: ?*Word = null;
        var has_in = false;
        if (isWord(t, "in")) {
            _ = try self.take();
            has_in = true;
            var last: ?*Word = null;
            while (true) {
                t = try self.peek();
                if (t.kind != .word) break;
                _ = try self.take();
                const w = try mem.ast.create(Word);
                w.* = .{ .text = t.text };
                if (last) |l| l.next = w else words = w;
                last = w;
            }
            if (t.kind != .semi and t.kind != .newline) {
                if (t.kind == .eof) return self.more();
                return self.unexpected(t);
            }
            _ = try self.take();
            try self.skipNewlines();
        } else if (t.kind == .semi) {
            _ = try self.take();
            try self.skipNewlines();
        }
        try self.expectWord("do");
        const body = try self.compoundList();
        try self.expectWord("done");
        return .{ .for_ = .{ .name = nt.text, .words = words, .has_in = has_in, .body = body } };
    }

    fn caseRest(self: *Parser) Error!Kind {
        _ = try self.take(); // case
        const wt = try self.take();
        if (wt.kind != .word) {
            if (wt.kind == .eof) return self.more();
            return self.unexpected(wt);
        }
        try self.skipNewlines();
        try self.expectWord("in");
        try self.skipNewlines();
        var items: ?*CaseItem = null;
        var last: ?*CaseItem = null;
        while (true) {
            var t = try self.peek();
            if (isWord(t, "esac")) {
                _ = try self.take();
                break;
            }
            if (t.kind == .eof) return self.more();
            if (t.kind == .lparen) {
                _ = try self.take();
                t = try self.peek();
            }
            var pats: ?*Word = null;
            var plast: ?*Word = null;
            while (true) {
                const p = try self.take();
                if (p.kind != .word) {
                    if (p.kind == .eof) return self.more();
                    return self.unexpected(p);
                }
                const w = try mem.ast.create(Word);
                w.* = .{ .text = p.text };
                if (plast) |l| l.next = w else pats = w;
                plast = w;
                const sep = try self.take();
                if (sep.kind == .rparen) break;
                if (sep.kind == .pipe) continue;
                if (sep.kind == .eof) return self.more();
                return self.unexpected(sep);
            }
            try self.skipNewlines();
            var body: ?*Node = null;
            const bt = try self.peek();
            if (bt.kind != .semi_semi and !isWord(bt, "esac")) body = try self.compoundList();
            const item = try mem.ast.create(CaseItem);
            item.* = .{ .patterns = pats.?, .body = body };
            if (last) |l| l.next = item else items = item;
            last = item;
            const end = try self.peek();
            if (end.kind == .semi_semi) {
                _ = try self.take();
                try self.skipNewlines();
            } else if (!isWord(end, "esac")) {
                if (end.kind == .eof) return self.more();
                return self.unexpected(end);
            }
        }
        return .{ .case_ = .{ .word = wt.text, .items = items } };
    }

    fn isRedirOp(k: Tok) bool {
        return switch (k) {
            .less, .great, .dless, .dless_dash, .dgreat, .less_and, .great_and, .less_great, .clobber => true,
            else => false,
        };
    }

    fn redirects(self: *Parser) Error!?*Redir {
        var first: ?*Redir = null;
        var last: ?*Redir = null;
        while (true) {
            const t = try self.peek();
            if (t.kind != .io_number and !isRedirOp(t.kind)) break;
            const r = try self.redirect();
            if (last) |l| l.next = r else first = r;
            last = r;
        }
        return first;
    }

    fn redirect(self: *Parser) Error!*Redir {
        var t = try self.take();
        var fd: ?u32 = null;
        if (t.kind == .io_number) {
            const v = kibi.io.parseUnsigned(t.text) orelse 1000;
            if (v > 255) {
                self.syntax(t.start, "bad file descriptor number");
                return error.Syntax;
            }
            fd = @intCast(v);
            t = try self.take();
        }
        const op: RedirOp = switch (t.kind) {
            .less => .in,
            .great => .out,
            .dgreat => .append,
            .clobber => .clobber,
            .less_great => .rw,
            .less_and => .dup_in,
            .great_and => .dup_out,
            .dless => .heredoc,
            .dless_dash => .heredoc_strip,
            else => return self.unexpected(t),
        };
        const w = try self.take();
        if (w.kind != .word) {
            if (w.kind == .eof) return self.more();
            return self.unexpected(w);
        }
        const r = try mem.ast.create(Redir);
        r.* = .{ .fd = fd, .op = op, .target = w.text };
        if (op == .heredoc or op == .heredoc_strip) {
            r.delim = try unquoteDelim(w.text, &r.quoted);
            if (self.pending_tail) |pt| pt.pend_next = r else self.pending = r;
            self.pending_tail = r;
            if (self.hd_tail) |ht| ht.hd_next = r else self.hd_head = r;
            self.hd_tail = r;
            self.hd_total += 1;
        }
        return r;
    }

    /// 単純なコマンド（関数の定義もここで見分ける）
    fn simple(self: *Parser) Error!*Node {
        const t0 = try self.peek();
        const start = t0.start;
        const hd_start = self.hd_total;
        var assigns: ?*Word = null;
        var alast: ?*Word = null;
        var words: ?*Word = null;
        var wlast: ?*Word = null;
        var redirs: ?*Redir = null;
        var rlast: ?*Redir = null;
        var nwords: usize = 0;
        while (true) {
            const t = try self.peek();
            if (t.kind == .io_number or isRedirOp(t.kind)) {
                const r = try self.redirect();
                if (rlast) |l| l.next = r else redirs = r;
                rlast = r;
                continue;
            }
            if (t.kind != .word) break;
            _ = try self.take();
            if (words == null and isAssignment(t.text)) {
                const w = try mem.ast.create(Word);
                w.* = .{ .text = t.text };
                if (alast) |l| l.next = w else assigns = w;
                alast = w;
                continue;
            }
            if (nwords == 0 and assigns == null and redirs == null and util.isName(t.text)) {
                // `name ( )` なら関数の定義
                const n = try self.peek();
                if (n.kind == .lparen) {
                    _ = try self.take();
                    const close = try self.take();
                    if (close.kind != .rparen) {
                        if (close.kind == .eof) return self.more();
                        return self.unexpected(close);
                    }
                    try self.skipNewlines();
                    const bt = try self.peek();
                    const ok = bt.kind == .lparen or (bt.kind == .word and (eql(bt.text, "{") or eql(bt.text, "if") or eql(bt.text, "while") or eql(bt.text, "until") or eql(bt.text, "for") or eql(bt.text, "case")));
                    if (!ok) {
                        if (bt.kind == .eof) return self.more();
                        return self.unexpected(bt);
                    }
                    self.depth += 1;
                    defer self.depth -= 1;
                    const body = try self.compound();
                    return try self.newNode(.{ .func = .{ .name = t.text, .body = body } }, start, hd_start);
                }
            }
            const w = try mem.ast.create(Word);
            w.* = .{ .text = t.text };
            if (wlast) |l| l.next = w else words = w;
            wlast = w;
            nwords += 1;
        }
        if (assigns == null and words == null and redirs == null) return self.unexpected(try self.peek());
        const n = try self.newNode(.{ .simple = .{ .assigns = assigns, .words = words } }, start, hd_start);
        n.redirs = redirs;
        return n;
    }
};

/// `NAME=...` の形か
pub fn isAssignment(text: []const u8) bool {
    const eq = util.indexOf(text, '=') orelse return false;
    return eq > 0 and util.isName(text[0..eq]);
}

/// ヒアドキュメントの区切りの語から引用符を外す
fn unquoteDelim(text: []const u8, quoted: *bool) Error![]const u8 {
    var out = mem.List(u8).init(&mem.ast);
    var i: usize = 0;
    while (i < text.len) {
        const c = text[i];
        if (c == '\'' or c == '"') {
            quoted.* = true;
            i += 1;
            continue;
        }
        if (c == '\\' and i + 1 < text.len) {
            quoted.* = true;
            try out.append(text[i + 1]);
            i += 2;
            continue;
        }
        try out.append(c);
        i += 1;
    }
    return out.items;
}

/// [node] を子のシェルで動かすための文字列。外に置かれたヒアドキュメントの中身を後ろに足す
pub fn nodeText(node: *const Node, arena: *mem.Arena) Error![]const u8 {
    var out = mem.List(u8).init(arena);
    try out.appendSlice(node.src);
    var r = node.hd_first;
    var k: usize = 0;
    // 中身は `<<EOF` の行の次の行から読まれるので、[node] の文字列が改行で終わっていれば、そのまま後ろに足す
    // （もう1つ改行を足すと、中身の頭に空行が入る。#478）
    var newline_done = node.src.len > 0 and node.src[node.src.len - 1] == '\n';
    const lo = @intFromPtr(node.src.ptr);
    const hi = lo + node.src.len;
    while (k < node.hd_count) : (k += 1) {
        const h = r orelse break;
        const p = @intFromPtr(h.tail.ptr);
        if (h.tail.len > 0 and (p < lo or p >= hi)) {
            if (!newline_done) {
                try out.append('\n');
                newline_done = true;
            }
            try out.appendSlice(h.tail);
            if (h.tail[h.tail.len - 1] != '\n') try out.append('\n');
        } else if (h.tail.len == 0 and (p < lo or p >= hi)) {
            // 中身が空で区切りの行もない（入力の終わり）
            if (!newline_done) {
                try out.append('\n');
                newline_done = true;
            }
            try out.appendSlice(h.delim);
            try out.append('\n');
        }
        r = h.hd_next;
    }
    return out.items;
}
