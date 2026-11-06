//! 会話的なシェルの行の編集（#102）。決めごとは docs/adr/0102-shell-line-editing.md。
//!
//! 行を読む間だけ TTY を raw（出力の LF → CR LF だけ残す）にし、1 バイトずつ読んで自分で編集・表示する。
//! 確定したら元のモードに戻す（子のプログラムは canonical の TTY を見る）。
//! 1 バイトずつ読むのは、確定した行の後ろの入力（先打ち・貼り付けた残り）を TTY に残し、次に動くプログラムが読めるようにするため。
//!
//! 表示は、プロンプトの始まり（行の頭）からの相対で、折り返した複数の行として描く。端末の解釈（ADR 0061、`docs/terminal.md`）の
//! 「右端に書いた後は折り返しの保留」と、全角の文字が右端の 1 セルに入らないときの折り返しをまねて、カーソルの位置を求める。
const kibi = @import("kibi");
const util = @import("util.zig");
const history = @import("history.zig");
const complete = @import("complete.zig");

const io = kibi.io;
const tty = kibi.tty;

const IN = io.STDIN;
const OUT = io.STDERR;

/// 1 行の長さの上限（バイト）
pub const MAX_LINE = history.MAX_LINE;

pub const Result = union(enum) {
    /// 確定した行（改行を含む）のバイト数。[readLine] の out に書いた
    line: usize,
    /// Ctrl+D（空の行で）・入力の終わり
    eof,
    /// Ctrl+C（行を捨てた）
    interrupt,
};

var line: [MAX_LINE]u8 = undefined;
var len: usize = 0;
var pos: usize = 0;
/// この行に入れられる長さ
var cap: usize = 0;
var prompt: []const u8 = "";
var cols: u32 = 80;
/// カーソルの行（プロンプトの最初の行から数える）
var cur_row: usize = 0;

/// Ctrl+K・Ctrl+U・Ctrl+W などで消した文字（Ctrl+Y で戻す）
var kill_buf: [MAX_LINE]u8 = undefined;
var kill_len: usize = 0;

/// 呼び戻している履歴の位置（history.len() なら編集中の新しい行）
var hist_index: usize = 0;
/// 履歴を呼び戻す前に編集していた行
var scratch: [MAX_LINE]u8 = undefined;
var scratch_len: usize = 0;

/// 直前のキーが Tab だった（2 回目の Tab で候補を並べる）
var last_tab = false;

// ---- 出力（まとめて書く） ----

var obuf: [8192]u8 = undefined;
var olen: usize = 0;

fn put(s: []const u8) void {
    var rest = s;
    while (rest.len > 0) {
        if (olen == obuf.len) flush();
        const k = @min(rest.len, obuf.len - olen);
        @memcpy(obuf[olen..][0..k], rest[0..k]);
        olen += k;
        rest = rest[k..];
    }
}

fn putByte(c: u8) void {
    put(&[_]u8{c});
}

fn putNum(v: usize) void {
    var b: [24]u8 = undefined;
    put(io.formatUnsigned(&b, v));
}

/// `ESC [ n <final>`
fn csi(n: usize, final: u8) void {
    put("\x1b[");
    putNum(n);
    putByte(final);
}

fn flush() void {
    if (olen == 0) return;
    io.writeAll(OUT, obuf[0..olen]) catch {};
    olen = 0;
}

fn bell() void {
    putByte(7);
}

// ---- 文字の幅 ----

/// UTF-8 の 1 文字を読む。[n] はバイト数。不正なら U+FFFD で 1 バイト
fn decode(s: []const u8, n: *usize) u21 {
    const c = s[0];
    n.* = 1;
    if (c < 0x80) return c;
    const need: usize = if (c & 0xe0 == 0xc0) 2 else if (c & 0xf0 == 0xe0) 3 else if (c & 0xf8 == 0xf0) 4 else return 0xfffd;
    if (s.len < need) return 0xfffd;
    var v: u32 = c & (@as(u8, 0x7f) >> @intCast(need));
    for (s[1..need]) |b| {
        if (b & 0xc0 != 0x80) return 0xfffd;
        v = (v << 6) | (b & 0x3f);
    }
    if (v > 0x10ffff) return 0xfffd;
    n.* = need;
    return @intCast(v);
}

// `CharWidth`（core）と同じ全角の範囲
const wide_ranges = [_][2]u21{
    .{ 0x1100, 0x115F },   .{ 0x231A, 0x231B },   .{ 0x2E80, 0x303E },   .{ 0x3041, 0x33FF },
    .{ 0x3400, 0x4DBF },   .{ 0x4E00, 0x9FFF },   .{ 0xA000, 0xA4CF },   .{ 0xA960, 0xA97F },
    .{ 0xAC00, 0xD7A3 },   .{ 0xF900, 0xFAFF },   .{ 0xFE10, 0xFE19 },   .{ 0xFE30, 0xFE6F },
    .{ 0xFF00, 0xFF60 },   .{ 0xFFE0, 0xFFE6 },   .{ 0x1F300, 0x1F64F }, .{ 0x1F900, 0x1F9FF },
    .{ 0x20000, 0x2FFFD }, .{ 0x30000, 0x3FFFD },
};

// 幅のない文字（結合文字・書式の文字）の主な範囲。端末の解釈は Unicode の一般カテゴリ Mn・Me・Cf を幅 0 にする
const zero_ranges = [_][2]u21{
    .{ 0x0300, 0x036F }, .{ 0x0483, 0x0489 }, .{ 0x0591, 0x05BD }, .{ 0x0610, 0x061A },
    .{ 0x064B, 0x065F }, .{ 0x200B, 0x200F }, .{ 0x202A, 0x202E }, .{ 0x2060, 0x2064 },
    .{ 0x20D0, 0x20FF }, .{ 0x3099, 0x309A }, .{ 0xFE00, 0xFE0F }, .{ 0xFE20, 0xFE2F },
    .{ 0xFEFF, 0xFEFF },
};

fn inRanges(cp: u21, ranges: []const [2]u21) bool {
    for (ranges) |r| {
        if (cp < r[0]) return false;
        if (cp <= r[1]) return true;
    }
    return false;
}

fn charWidth(cp: u21) u32 {
    if (inRanges(cp, &zero_ranges)) return 0;
    return if (inRanges(cp, &wide_ranges)) 2 else 1;
}

// ---- 位置を数える ----

/// 端末のカーソルの位置（プロンプトの始まりから）
const Pos = struct {
    row: usize = 0,
    col: u32 = 0,
    /// 右端に書いた後の折り返しの保留
    pending: bool = false,

    /// 幅 [w] の文字を書いた後
    fn advance(p: *Pos, w0: u32) void {
        if (w0 == 0) return;
        var w = w0;
        if (w > cols) w = 1;
        if (p.pending) {
            p.pending = false;
            p.row += 1;
            p.col = 0;
        }
        if (w == 2 and p.col + 1 == cols) {
            p.row += 1;
            p.col = 0;
        }
        if (p.col + w >= cols) {
            p.col = cols - 1;
            p.pending = true;
        } else p.col += w;
    }

    /// 表に見えるカーソルの位置（保留なら次の行の頭）
    fn visible(p: Pos) Pos {
        if (p.pending) return .{ .row = p.row + 1, .col = 0 };
        return p;
    }
};

/// プロンプトを書いたときの位置の動き。エスケープシーケンスは幅 0 と見る
/// （CSI `ESC [ ... 終端`、OSC `ESC ] ... BEL か ST`、DCS などの文字列 `ESC P|X|^|_ ... ST`、ほかの `ESC 中間 終端`）
fn measurePrompt(p: *Pos, s: []const u8) void {
    var i: usize = 0;
    while (i < s.len) {
        const c = s[i];
        if (c == 0x1b) {
            i = skipEscape(s, i);
            continue;
        }
        if (c == '\n') {
            p.* = .{ .row = p.row + 1 };
            i += 1;
            continue;
        }
        if (c == '\r') {
            p.col = 0;
            p.pending = false;
            i += 1;
            continue;
        }
        if (c == '\t') {
            const next = (p.col / 8 + 1) * 8;
            if (!p.pending) p.col = @min(next, cols - 1);
            i += 1;
            continue;
        }
        if (c < 0x20 or c == 0x7f) {
            i += 1;
            continue;
        }
        var n: usize = 1;
        const cp = decode(s[i..], &n);
        p.advance(charWidth(cp));
        i += n;
    }
}

/// [s] の [start] にある ESC から始まるエスケープシーケンスの、次の位置
fn skipEscape(s: []const u8, start: usize) usize {
    var i = start + 1;
    if (i >= s.len) return i;
    const kind = s[i];
    i += 1;
    switch (kind) {
        // CSI: 引数・中間のバイトの後、0x40〜0x7e で終わる
        '[' => {
            while (i < s.len and !(s[i] >= 0x40 and s[i] <= 0x7e)) i += 1;
            return @min(i + 1, s.len);
        },
        // OSC（BEL か ST で終わる）と、DCS・SOS・PM・APC（ST で終わる）
        ']', 'P', 'X', '^', '_' => {
            while (i < s.len) : (i += 1) {
                if (s[i] == 0x07 and kind == ']') return i + 1;
                if (s[i] == 0x1b and i + 1 < s.len and s[i + 1] == '\\') return i + 2;
            }
            return s.len;
        },
        else => {
            // `ESC 中間(0x20〜0x2f)... 終端(0x30〜0x7e)`（`ESC ( B` など）。中間がなければ 2 バイトで終わり
            if (kind >= 0x20 and kind <= 0x2f) {
                while (i < s.len and s[i] >= 0x20 and s[i] <= 0x2f) i += 1;
                return @min(i + 1, s.len);
            }
            return i;
        },
    }
}

/// 行の [s] を書く（制御文字は `^X`）。[p] があれば位置を進める
fn emitLine(s: []const u8, p: *Pos) void {
    var i: usize = 0;
    while (i < s.len) {
        const c = s[i];
        if (c < 0x20 or c == 0x7f) {
            putByte('^');
            putByte(c ^ 0x40);
            p.advance(1);
            p.advance(1);
            i += 1;
            continue;
        }
        var n: usize = 1;
        const cp = decode(s[i..], &n);
        if (cp == 0xfffd and n == 1 and c >= 0x80) {
            // 途中で切れた・不正なバイト。端末と同じく U+FFFD に見せる
            put("\xef\xbf\xbd");
        } else put(s[i..][0..n]);
        p.advance(charWidth(cp));
        i += n;
    }
}

fn measureLine(s: []const u8, p: *Pos) void {
    var i: usize = 0;
    while (i < s.len) {
        const c = s[i];
        if (c < 0x20 or c == 0x7f) {
            p.advance(1);
            p.advance(1);
            i += 1;
            continue;
        }
        var n: usize = 1;
        const cp = decode(s[i..], &n);
        p.advance(charWidth(cp));
        i += n;
    }
}

fn updateSize() void {
    if (tty.getSize(OUT)) |s| {
        cols = if (s.columns >= 1) s.columns else 80;
    } else |_| cols = 80;
}

/// 行の全体を描き直す
fn refresh() void {
    updateSize();
    if (cur_row > 0) csi(cur_row, 'A');
    put("\r\x1b[J");
    var p: Pos = .{};
    put(prompt);
    measurePrompt(&p, prompt);
    emitLine(line[0..pos], &p);
    const target = p.visible();
    emitLine(line[pos..len], &p);
    var phys = p;
    if (p.pending) {
        // 右端で終わった: 次の行に出ておく（カーソルを右端に残さない）
        put("\n");
        phys = .{ .row = p.row + 1 };
    }
    // カーソルが行の後ろにあれば、もう動かさない
    if (phys.row != target.row or phys.col != target.col) {
        if (phys.row > target.row) csi(phys.row - target.row, 'A');
        putByte('\r');
        if (target.col > 0) csi(target.col, 'C');
    }
    cur_row = target.row;
    flush();
}

/// プロンプトを、新しい行の頭から出す。前の出力が改行で終わっていなくても、それを消さずに次の行へ進む
/// （画面の幅だけ空白を書き、CR で戻る。行の頭にいれば同じ行に、いなければ次の行に戻る）
fn freshLine() void {
    updateSize();
    var i: u32 = 0;
    while (i < cols) : (i += 1) putByte(' ');
    put("\r\x1b[K");
    cur_row = 0;
}

// ---- 編集 ----

fn isCont(c: u8) bool {
    return c & 0xc0 == 0x80;
}

fn prevChar(from: usize) usize {
    var i = from;
    if (i == 0) return 0;
    i -= 1;
    while (i > 0 and isCont(line[i])) i -= 1;
    return i;
}

fn nextChar(from: usize) usize {
    var i = from;
    if (i >= len) return len;
    i += 1;
    while (i < len and isCont(line[i])) i += 1;
    return i;
}

fn isWordChar(c: u8) bool {
    return util.isAlnum(c) or c >= 0x80;
}

/// 前の語の始まり（英数字の語。Alt+B）
fn prevWord(from: usize) usize {
    var i = from;
    while (i > 0 and !isWordChar(line[i - 1])) i -= 1;
    while (i > 0 and isWordChar(line[i - 1])) i -= 1;
    return i;
}

/// 次の語の終わり（Alt+F）
fn nextWord(from: usize) usize {
    var i = from;
    while (i < len and !isWordChar(line[i])) i += 1;
    while (i < len and isWordChar(line[i])) i += 1;
    return i;
}

/// [s] をカーソルの位置に入れる。入らなければ false
fn insert(s: []const u8) bool {
    if (len + s.len > cap) return false;
    var k = len;
    while (k > pos) : (k -= 1) line[k - 1 + s.len] = line[k - 1];
    @memcpy(line[pos..][0..s.len], s);
    len += s.len;
    pos += s.len;
    return true;
}

/// line[a..b] を消す。[save] なら Ctrl+Y で戻せるように残す
fn remove(a: usize, b: usize, save: bool) void {
    if (b <= a) return;
    if (save) {
        @memcpy(kill_buf[0 .. b - a], line[a..b]);
        kill_len = b - a;
    }
    const n = b - a;
    var k = a;
    while (k + n < len) : (k += 1) line[k] = line[k + n];
    len -= n;
    if (pos >= b) pos -= n else if (pos > a) pos = a;
}

fn setLine(s: []const u8) void {
    const n = @min(s.len, cap);
    @memcpy(line[0..n], s[0..n]);
    len = n;
    pos = n;
}

/// 履歴を [delta]（-1 で古い方）動かす
fn historyMove(up: bool) void {
    const total = history.len();
    if (up) {
        if (hist_index == 0) return bell();
        if (hist_index == total) {
            @memcpy(scratch[0..len], line[0..len]);
            scratch_len = len;
        }
        hist_index -= 1;
        setLine(history.get(hist_index));
    } else {
        if (hist_index >= total) return bell();
        hist_index += 1;
        if (hist_index == total) setLine(scratch[0..scratch_len]) else setLine(history.get(hist_index));
    }
    refresh();
}

/// 普通の文字を入れたときの表示。行の末尾に足して折り返さないなら、その文字だけ書く
fn typed(s: []const u8) void {
    if (pos == len) {
        var p: Pos = .{};
        measurePrompt(&p, prompt);
        measureLine(line[0 .. len - s.len], &p);
        var n: usize = 1;
        const cp = decode(s, &n);
        const w = charWidth(cp);
        if (!p.pending and n == s.len and s[0] >= 0x20 and p.col + w < cols) {
            put(s);
            flush();
            return;
        }
    }
    refresh();
}

// ---- 補完 ----

fn isBreak(c: u8) bool {
    return switch (c) {
        ' ', '\t', ';', '|', '&', '<', '>', '(', ')' => true,
        else => false,
    };
}

const Quote = enum { none, single, double };

/// カーソルの前の語。始まりの位置と、引用を外した文字列、終わりで開いている引用
const Word = struct { start: usize, text: []const u8, quote: Quote };

var word_buf: [MAX_LINE]u8 = undefined;

fn currentWord() Word {
    var start: usize = 0;
    var n: usize = 0;
    var q: Quote = .none;
    var i: usize = 0;
    while (i < pos) : (i += 1) {
        const c = line[i];
        switch (q) {
            .single => {
                if (c == '\'') q = .none else {
                    word_buf[n] = c;
                    n += 1;
                }
            },
            .double => {
                if (c == '"') {
                    q = .none;
                } else if (c == '\\' and i + 1 < pos and (line[i + 1] == '"' or line[i + 1] == '\\' or line[i + 1] == '$' or line[i + 1] == '`')) {
                    i += 1;
                    word_buf[n] = line[i];
                    n += 1;
                } else {
                    word_buf[n] = c;
                    n += 1;
                }
            },
            .none => {
                if (isBreak(c)) {
                    start = i + 1;
                    n = 0;
                } else if (c == '\'') {
                    q = .single;
                } else if (c == '"') {
                    q = .double;
                } else if (c == '\\' and i + 1 < pos) {
                    i += 1;
                    word_buf[n] = line[i];
                    n += 1;
                } else {
                    word_buf[n] = c;
                    n += 1;
                }
            },
        }
    }
    return .{ .start = start, .text = word_buf[0..n], .quote = q };
}

/// 語の前がコマンドの位置か（行の頭、`;`・`|`・`&`・`(` の後、`then` などの予約語の後）
fn isCommandPosition(start: usize) bool {
    var i = start;
    while (i > 0 and (line[i - 1] == ' ' or line[i - 1] == '\t')) i -= 1;
    if (i == 0) return true;
    switch (line[i - 1]) {
        ';', '|', '&', '(' => return true,
        else => {},
    }
    var j = i;
    while (j > 0 and !isBreak(line[j - 1])) j -= 1;
    const prev = line[j..i];
    const words = [_][]const u8{ "then", "do", "else", "elif", "if", "while", "until", "!", "{", "exec", "command", "time" };
    for (words) |w| if (util.eql(prev, w)) {
        // 予約語は、それ自体がコマンドの位置にあるときだけ
        return isCommandPosition(j);
    };
    // 代入（`FOO=bar cmd`）の後は、代入がコマンドの位置にあればコマンドの位置
    if (isAssignmentWord(prev)) return isCommandPosition(j);
    return false;
}

/// 引用の外で `\` を前に付ける文字
fn needsEscape(c: u8, q: Quote) bool {
    return switch (q) {
        .single => false,
        .double => c == '"' or c == '\\' or c == '$' or c == '`',
        .none => switch (c) {
            ' ', '\t', '\\', '\'', '"', '$', '`', '&', '|', ';', '<', '>', '(', ')', '*', '?', '[', ']', '!', '{', '}', '#' => true,
            else => false,
        },
    };
}

/// [s] を、開いている引用 [q] の中に合う形で入れる
fn insertQuoted(s: []const u8, q: Quote) bool {
    for (s) |c| {
        if (needsEscape(c, q) and !insert("\\")) return false;
        if (!insert(&[_]u8{c})) return false;
    }
    return true;
}

fn completeWord() void {
    var w = currentWord();
    // `NAME=値`・`if=値` は、`=` の後ろだけを補う（値はパスか変数）
    const value = assignmentValue(w.text);
    if (value) |v| w.text = v;
    const command = value == null and w.quote == .none and isCommandPosition(w.start);
    complete.collect(w.text, command);
    if (complete.count == 0) {
        bell();
        flush();
        return;
    }
    const common = complete.commonPrefix();
    const first = complete.items[0];
    var changed = false;
    if (common > w.text.len and util.startsWith(first.text, w.text)) {
        if (!insertQuoted(first.text[w.text.len..common], w.quote)) {
            bell();
        }
        changed = true;
    }
    if (complete.count == 1) {
        if (first.dir) {
            if (common == first.text.len and !util.startsWith(line[pos..len], "/")) _ = insert("/");
        } else {
            switch (w.quote) {
                .single => _ = insert("'"),
                .double => _ = insert("\""),
                .none => {},
            }
            if (pos == len or line[pos] != ' ') _ = insert(" ");
        }
        changed = true;
    }
    if (changed) {
        refresh();
        last_tab = false;
        return;
    }
    if (!last_tab) {
        bell();
        flush();
        last_tab = true;
        return;
    }
    listCandidates();
}

/// 語 [text] が `前=値` の形（`前` が空でなく `/` を含まない）なら、`=` の後ろ。そうでなければ null
fn assignmentValue(text: []const u8) ?[]const u8 {
    const eq = util.indexOf(text, '=') orelse return null;
    if (eq == 0 or util.indexOf(text[0..eq], '/') != null) return null;
    return text[eq + 1 ..];
}

/// [s] がシェルの代入 `NAME=...`（NAME は英字か `_` で始まり、英数字と `_`）か
fn isAssignmentWord(s: []const u8) bool {
    const eq = util.indexOf(s, '=') orelse return false;
    if (eq == 0) return false;
    for (s[0..eq], 0..) |c, k| {
        const alpha = (c >= 'a' and c <= 'z') or (c >= 'A' and c <= 'Z') or c == '_';
        if (!alpha and !(k > 0 and c >= '0' and c <= '9')) return false;
    }
    return true;
}

/// 候補を段に並べて書き、行を出し直す
fn listCandidates() void {
    // カーソルを行の後ろへ
    const saved = pos;
    pos = len;
    refresh();
    put("\n");
    var widest: usize = 1;
    for (complete.items[0..complete.count]) |c| widest = @max(widest, nameWidth(c));
    const cell = widest + 2;
    const ncols = @max(1, @as(usize, cols) / cell);
    const rows = (complete.count + ncols - 1) / ncols;
    var r: usize = 0;
    while (r < rows) : (r += 1) {
        var c: usize = 0;
        while (c < ncols) : (c += 1) {
            const idx = c * rows + r;
            if (idx >= complete.count) break;
            const it = complete.items[idx];
            // 制御文字は入力行と同じく `^X` で見せる（nameWidth の桁と合わせる）
            var shown: Pos = .{};
            emitLine(it.name, &shown);
            if (it.dir) putByte('/');
            const last = (c + 1) * rows + r >= complete.count;
            if (!last) {
                var k = nameWidth(it);
                while (k < cell) : (k += 1) putByte(' ');
            }
        }
        put("\n");
    }
    if (complete.truncated) put("(more candidates were left out)\n");
    cur_row = 0;
    pos = saved;
    refresh();
}

fn nameWidth(c: complete.Candidate) usize {
    var p: Pos = .{};
    const save = cols;
    cols = 0xffff;
    measureLine(c.name, &p);
    cols = save;
    return p.col + @intFromBool(c.dir);
}

// ---- 入力 ----

fn readByte() ?u8 {
    var b: [1]u8 = undefined;
    while (true) {
        const n = io.read(IN, &b) catch |e| {
            if (e == error.INTR) continue;
            return null;
        };
        if (n == 0) return null;
        return b[0];
    }
}

const Key = enum { none, up, down, left, right, home, end, delete, word_left, word_right };

/// `ESC [` の後を読んで、キーにする
fn readCsi() Key {
    var params: [16]u8 = undefined;
    var n: usize = 0;
    while (true) {
        const c = readByte() orelse return .none;
        if (c >= 0x40 and c <= 0x7e) {
            const p = params[0..n];
            // 修飾キー（`1;5D` の 5 など）
            var first: usize = 0;
            var modifier: usize = 0;
            var k: usize = 0;
            var which: usize = 0;
            while (k < p.len) : (k += 1) {
                if (p[k] == ';') {
                    which += 1;
                } else if (util.isDigit(p[k])) {
                    if (which == 0) first = first * 10 + (p[k] - '0') else if (which == 1) modifier = modifier * 10 + (p[k] - '0');
                }
            }
            const ctrl_or_alt = modifier == 3 or modifier == 5;
            return switch (c) {
                'A' => .up,
                'B' => .down,
                'C' => if (ctrl_or_alt) .word_right else .right,
                'D' => if (ctrl_or_alt) .word_left else .left,
                'H' => .home,
                'F' => .end,
                '~' => switch (first) {
                    1, 7 => .home,
                    4, 8 => .end,
                    3 => .delete,
                    else => .none,
                },
                else => .none,
            };
        }
        if (c < 0x20) return .none;
        if (n < params.len) {
            params[n] = c;
            n += 1;
        }
    }
}

/// ESC の後を読む
fn readEscape() Key {
    const c = readByte() orelse return .none;
    switch (c) {
        '[' => return readCsi(),
        'O' => {
            const d = readByte() orelse return .none;
            return switch (d) {
                'A' => .up,
                'B' => .down,
                'C' => .right,
                'D' => .left,
                'H' => .home,
                'F' => .end,
                else => .none,
            };
        },
        'b', 'B' => return .word_left,
        'f', 'F' => return .word_right,
        'd', 'D' => {
            remove(pos, nextWord(pos), true);
            refresh();
            return .none;
        },
        0x7f, 0x08 => {
            remove(prevWord(pos), pos, true);
            refresh();
            return .none;
        },
        else => return .none,
    }
}

fn applyKey(k: Key) void {
    switch (k) {
        .none => {},
        .up => historyMove(true),
        .down => historyMove(false),
        .left => {
            if (pos > 0) {
                pos = prevChar(pos);
                refresh();
            }
        },
        .right => {
            if (pos < len) {
                pos = nextChar(pos);
                refresh();
            }
        },
        .home => {
            pos = 0;
            refresh();
        },
        .end => {
            pos = len;
            refresh();
        },
        .delete => {
            if (pos < len) {
                remove(pos, nextChar(pos), false);
                refresh();
            }
        },
        .word_left => {
            pos = prevWord(pos);
            refresh();
        },
        .word_right => {
            pos = nextWord(pos);
            refresh();
        },
    }
}

/// 行を確定・取り消すときに、カーソルを行の後ろに置いて改行する
fn finishLine(suffix: []const u8) void {
    if (pos != len) {
        pos = len;
        refresh();
    }
    put(suffix);
    put("\n");
    flush();
}

/// プロンプト [p] を出して 1 行を読み、[out] に（改行を付けて）書く
pub fn readLine(p: []const u8, out: []u8) Result {
    const saved = tty.getMode(IN) catch tty.Mode.cooked;
    // 子が canonical を外したまま終わっていたら、既定に戻す
    const restore = if (saved.canonical) saved else tty.Mode.cooked;
    tty.setMode(IN, .{ .nl_to_crnl = true }) catch {};
    defer tty.setMode(IN, restore) catch {};

    prompt = p;
    len = 0;
    pos = 0;
    cap = @min(MAX_LINE, if (out.len > 0) out.len - 1 else 0);
    hist_index = history.len();
    scratch_len = 0;
    last_tab = false;
    freshLine();
    refresh();

    // UTF-8 の 1 文字の続きの残り
    var utf8_rest: usize = 0;
    var utf8_start: usize = 0;
    while (true) {
        const c = readByte() orelse {
            finishLine("");
            return .eof;
        };
        const was_tab = last_tab;
        last_tab = false;
        if (utf8_rest > 0) {
            if (isCont(c)) {
                if (!insert(&[_]u8{c})) {
                    bell();
                    flush();
                    utf8_rest = 0;
                    continue;
                }
                utf8_rest -= 1;
                if (utf8_rest == 0) typed(line[utf8_start..pos]);
                continue;
            }
            // 途中で切れた
            utf8_rest = 0;
            refresh();
        }
        switch (c) {
            '\r', '\n' => {
                finishLine("");
                const n = len;
                @memcpy(out[0..n], line[0..n]);
                out[n] = '\n';
                history.record(line[0..n]);
                return .{ .line = n + 1 };
            },
            3 => {
                // Ctrl+C
                finishLine("^C");
                return .interrupt;
            },
            4 => {
                // Ctrl+D: 空の行なら終わり。ほかはカーソルの文字を消す
                if (len == 0) {
                    flush();
                    return .eof;
                }
                applyKey(.delete);
            },
            1 => applyKey(.home), // Ctrl+A
            5 => applyKey(.end), // Ctrl+E
            2 => applyKey(.left), // Ctrl+B
            6 => applyKey(.right), // Ctrl+F
            16 => applyKey(.up), // Ctrl+P
            14 => applyKey(.down), // Ctrl+N
            0x7f, 8 => {
                // Backspace
                if (pos > 0) {
                    remove(prevChar(pos), pos, false);
                    refresh();
                }
            },
            11 => {
                // Ctrl+K
                remove(pos, len, true);
                refresh();
            },
            21 => {
                // Ctrl+U
                remove(0, pos, true);
                refresh();
            },
            23 => {
                // Ctrl+W: 前の空白で区切った語
                var i = pos;
                while (i > 0 and (line[i - 1] == ' ' or line[i - 1] == '\t')) i -= 1;
                while (i > 0 and line[i - 1] != ' ' and line[i - 1] != '\t') i -= 1;
                remove(i, pos, true);
                refresh();
            },
            25 => {
                // Ctrl+Y
                if (!insert(kill_buf[0..kill_len])) bell();
                refresh();
            },
            20 => {
                // Ctrl+T: 前の 2 文字を入れ替える
                if (pos > 0 and len >= 2) {
                    if (pos == len) pos = prevChar(pos);
                    const a = prevChar(pos);
                    const b = nextChar(pos);
                    var tmp: [8]u8 = undefined;
                    const left = pos - a;
                    const right = b - pos;
                    if (left + right <= tmp.len) {
                        @memcpy(tmp[0..right], line[pos..b]);
                        @memcpy(tmp[right..][0..left], line[a..pos]);
                        @memcpy(line[a..b], tmp[0 .. left + right]);
                        pos = b;
                    }
                    refresh();
                }
            },
            12 => {
                // Ctrl+L: 画面を消す
                put("\x1b[H\x1b[2J");
                cur_row = 0;
                refresh();
            },
            '\t' => {
                last_tab = was_tab;
                completeWord();
            },
            0x1b => applyKey(readEscape()),
            else => {
                if (c < 0x20) continue;
                if (c >= 0x80) {
                    const need: usize = if (c & 0xe0 == 0xc0) 1 else if (c & 0xf0 == 0xe0) 2 else if (c & 0xf8 == 0xf0) 3 else continue;
                    if (!insert(&[_]u8{c})) {
                        bell();
                        flush();
                        continue;
                    }
                    utf8_start = pos - 1;
                    utf8_rest = need;
                    continue;
                }
                if (!insert(&[_]u8{c})) {
                    bell();
                    flush();
                    continue;
                }
                typed(line[pos - 1 .. pos]);
            },
        }
    }
}
