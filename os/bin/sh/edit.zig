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
const utf8 = kibi.text;

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
    /// 上限（[MAX_LINE]、または out の残り）を超えて入力の一部を捨てた行で Enter を押した。
    /// 行は out に書かず、履歴にも残さない（切れた行を実行しないため。#665）
    too_long,
};

var line: [MAX_LINE]u8 = undefined;
var len: usize = 0;
var pos: usize = 0;
/// この行に入れられる長さ
var cap: usize = 0;
/// 上限を超えて、利用者の入力の一部を捨てた（Enter で実行しない）。
/// Backspace で短くしても、捨てた中身は戻らないので、長さではなくこの印で見る
var truncated = false;
var prompt: []const u8 = "";
var cols: u32 = 80;
/// カーソルの行（プロンプトの最初の行から数える）
var cur_row: usize = 0;

/// Ctrl+K・Ctrl+U・Ctrl+W などで消した文字（Ctrl+Y で戻す）
var kill_buf: [MAX_LINE]u8 = undefined;
var kill_len: usize = 0;
/// kill_buf が、上限を超えて切れた行を全部消したときの中身（Ctrl+Y で戻すと、切れた行がまた実行できてしまうため、戻したら印を付ける）
var kill_truncated = false;

/// 呼び戻している履歴の位置（history.len() なら編集中の新しい行）
var hist_index: usize = 0;
/// 履歴を呼び戻す前に編集していた行
var scratch: [MAX_LINE]u8 = undefined;
var scratch_len: usize = 0;
/// scratch の行の [truncated]
var scratch_truncated = false;

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

/// 行・プロンプトの 1 文字（制御文字の後ろ）
const Glyph = struct {
    /// バイト数
    n: usize,
    /// 幅
    w: u2,
    /// 原文のまま送れる。偽なら U+FFFD（1 セル）で見せる
    ok: bool,
};

/// [s] の頭の 1 文字。制御文字（0x00〜0x1f・0x7f）は呼ぶ側で扱う。読み方と幅は `kibi.text`（ADR 0105）。
/// 不正な UTF-8 は 1 バイトごとに、C1 の制御文字（U+0080〜U+009F。端末が読み捨てる）は 1 文字を U+FFFD で見せる
fn glyph(s: []const u8) Glyph {
    const cp = utf8.decode(s) orelse return .{ .n = 1, .w = 1, .ok = false };
    const n = utf8.sequenceLength(s[0]);
    if (cp >= 0x80 and cp < 0xa0) return .{ .n = n, .w = 1, .ok = false };
    return .{ .n = n, .w = utf8.width(cp), .ok = true };
}

/// [g]（[s] の頭）を書く
fn putGlyph(s: []const u8, g: Glyph) void {
    if (g.ok) put(s[0..g.n]) else put(utf8.REPLACEMENT);
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

/// プロンプト [s] を書いたときの位置の動き。[emit] なら書きもする（数えるだけのときも同じ規則で数える）。
/// エスケープシーケンスは幅 0 と見て、そのまま送る
/// （CSI `ESC [ ... 終端`、OSC `ESC ] ... BEL か ST`、DCS などの文字列 `ESC P|X|^|_ ... ST`、ほかの `ESC 中間 終端`）。
/// 改行・CR・タブとほかの制御文字もそのまま送る。文字は行と同じく [glyph] で読み、不正なバイトは U+FFFD にして送る
fn walkPrompt(p: *Pos, s: []const u8, emit: bool) void {
    var i: usize = 0;
    while (i < s.len) {
        const c = s[i];
        if (c == 0x1b) {
            const next = skipEscape(s, i);
            if (emit) put(s[i..next]);
            i = next;
            continue;
        }
        if (c < 0x20 or c == 0x7f) {
            if (emit) putByte(c);
            i += 1;
            switch (c) {
                '\n' => p.* = .{ .row = p.row + 1 },
                '\r' => {
                    p.col = 0;
                    p.pending = false;
                },
                '\t' => {
                    const next = (p.col / 8 + 1) * 8;
                    if (!p.pending) p.col = @min(next, cols - 1);
                },
                else => {},
            }
            continue;
        }
        const g = glyph(s[i..]);
        if (emit) putGlyph(s[i..], g);
        p.advance(g.w);
        i += g.n;
    }
}

fn measurePrompt(p: *Pos, s: []const u8) void {
    walkPrompt(p, s, false);
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
        const g = glyph(s[i..]);
        putGlyph(s[i..], g);
        p.advance(g.w);
        i += g.n;
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
        const g = glyph(s[i..]);
        p.advance(g.w);
        i += g.n;
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
    walkPrompt(&p, prompt, true);
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

/// [s] の頭の 1 文字のバイト数（描画と同じ区切り。制御文字は 1 バイト、不正な UTF-8 は 1 バイトごと）
fn charLen(s: []const u8) usize {
    const c = s[0];
    if (c < 0x20 or c == 0x7f) return 1;
    return glyph(s).n;
}

/// [from] の 1 文字前。描画（[emitLine]）が行の頭から [from] までを読むのと同じ区切りで数える
fn prevChar(from: usize) usize {
    var i: usize = 0;
    var last: usize = 0;
    while (i < from) {
        last = i;
        i += charLen(line[i..from]);
    }
    return last;
}

/// [from] の 1 文字後（描画と同じ区切り）
fn nextChar(from: usize) usize {
    if (from >= len) return len;
    return from + charLen(line[from..len]);
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

/// line[a..b] を消すと行が空になる
fn removesAll(a: usize, b: usize) bool {
    return a == 0 and b - a == len;
}

/// line[a..b] を消す。[save] なら Ctrl+Y で戻せるように残す
fn remove(a: usize, b: usize, save: bool) void {
    if (b <= a) return;
    if (save) {
        @memcpy(kill_buf[0 .. b - a], line[a..b]);
        kill_len = b - a;
        kill_truncated = truncated and removesAll(a, b);
    }
    const n = b - a;
    var k = a;
    while (k + n < len) : (k += 1) line[k] = line[k + n];
    len -= n;
    if (pos >= b) pos -= n else if (pos > a) pos = a;
    // 全部消したら、もう捨てた入力に頼った行ではない（打ち直せば実行できる）
    if (len == 0) truncated = false;
}

/// 入力が上限を超えて入らなかった。最初の 1 回だけベルを鳴らし、行に印を付ける
fn lost() void {
    if (truncated) return;
    truncated = true;
    bell();
    flush();
}

/// 行を [s] に置き換える。入らなければ（続きの行で残りが少ないとき）false で、行はそのまま
fn setLine(s: []const u8) bool {
    if (s.len > cap) return false;
    @memcpy(line[0..s.len], s);
    len = s.len;
    pos = s.len;
    return true;
}

/// 履歴を [delta]（-1 で古い方）動かす
fn historyMove(up: bool) void {
    const total = history.len();
    if (up) {
        if (hist_index == 0) return bell();
        if (!setLineFrom(hist_index - 1, total)) return bell();
        hist_index -= 1;
    } else {
        if (hist_index >= total) return bell();
        if (!setLineFrom(hist_index + 1, total)) return bell();
        hist_index += 1;
    }
    refresh();
}

/// 行を履歴の [index] の項目（[total] なら履歴を呼び戻す前の行）に置き換える。
/// 入らなければ（ベルは呼ぶ側）false で、行はそのまま
fn setLineFrom(index: usize, total: usize) bool {
    const from_scratch = index == total;
    const s = if (from_scratch) scratch[0..scratch_len] else history.get(index);
    if (s.len > cap) return false;
    // 編集中の新しい行を離れるときは、印ごと退避する（↓で戻ったときに印も戻す）
    if (hist_index == total) {
        @memcpy(scratch[0..len], line[0..len]);
        scratch_len = len;
        scratch_truncated = truncated;
    }
    _ = setLine(s);
    truncated = from_scratch and scratch_truncated;
    return true;
}

/// 普通の文字を入れたときの表示。行の末尾に足して折り返さないなら、その文字だけ書く
fn typed(s: []const u8) void {
    if (pos == len) {
        var p: Pos = .{};
        measurePrompt(&p, prompt);
        measureLine(line[0 .. len - s.len], &p);
        const g = glyph(s);
        // 原文のまま送れる 1 文字だけ（不正なバイト・C1 は描き直して U+FFFD で見せる）
        if (!p.pending and g.ok and g.n == s.len and s[0] >= 0x20 and p.col + g.w < cols) {
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
/// 全部入るか、何も入らないか（入らなければ行は元のまま）
fn insertQuoted(s: []const u8, q: Quote) bool {
    const start = pos;
    for (s) |c| {
        if ((needsEscape(c, q) and !insert("\\")) or !insert(&[_]u8{c})) {
            remove(start, pos, false);
            return false;
        }
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
            // 入らなかった。何も入れていないので、閉じる引用符や空白も足さない
            bell();
            flush();
            return;
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

/// 「保存から戻った」を受け取るキュー（#87。[readLine] の間だけ開く。開けなければ null で、入力だけを待つ）
var restored: ?kibi.events.Queue = null;

fn closeRestored() void {
    if (restored) |q| q.close();
    restored = null;
}

/// 待ち・取り出しに失敗したキューを、閉じずに手放す（#87、ADR 0087）。失敗するのはハンドルが不正なとき（取り出しの BADF・
/// poll のキュー側のエラー）だけで、不正なハンドルを drop するとトラップするので閉じない
fn abandonRestored() void {
    restored = null;
}

/// キーの境目で 1 バイト読む。読む前に、入力か「保存から戻った」を待ち、戻ったら行を描き直してまた待つ（#87、ADR 0087）。
/// 戻した直後の画面は空のことが多い（表示のデバイスは保存しない。#630）ので、プロンプトと入力中の行を出し直す。
/// 描き直しは [refresh] だけで行う（画面が残っていても、相対の描き直しなら崩れない）
fn readKeyByte() ?u8 {
    while (restored) |q| {
        const ready = kibi.events.waitInputOrQueue(IN, q) catch {
            abandonRestored();
            break;
        };
        if (ready.queue) {
            const redraw = q.drainRestored() catch {
                abandonRestored();
                break;
            };
            if (redraw) refresh();
        }
        if (ready.input) break;
    }
    return readByte();
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
    // ちょうど右端で終わった行は、refresh がもう次の行の頭に出ている（そこでさらに改行すると空行が入る）
    var p: Pos = .{};
    measurePrompt(&p, prompt);
    measureLine(line[0..len], &p);
    if (suffix.len == 0 and p.pending) {
        flush();
        return;
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
    restored = kibi.events.openRestored();
    defer closeRestored();

    prompt = p;
    len = 0;
    pos = 0;
    cap = @min(MAX_LINE, if (out.len > 0) out.len - 1 else 0);
    hist_index = history.len();
    scratch_len = 0;
    truncated = false;
    scratch_truncated = false;
    last_tab = false;
    freshLine();
    refresh();

    // UTF-8 の 1 文字の続きの残り
    var utf8_rest: usize = 0;
    var utf8_start: usize = 0;
    while (true) {
        // UTF-8 の続き・エスケープシーケンスの途中（readEscape・readCsi）は、従来どおり入力だけを読む
        const c = (if (utf8_rest > 0) readByte() else readKeyByte()) orelse {
            finishLine("");
            return .eof;
        };
        const was_tab = last_tab;
        last_tab = false;
        if (utf8_rest > 0) {
            if (utf8.isContinuation(c)) {
                // 先頭のバイトで 1 文字が丸ごと入ることを確かめてあるので、普通はここで入る
                if (!insert(&[_]u8{c})) {
                    lost();
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
                if (truncated) return .too_long;
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
                if (!insert(kill_buf[0..kill_len])) bell() else if (kill_truncated) lost();
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
                    // 1 文字が丸ごと入らなければ、先頭のバイトも入れない（続きのバイトは下の continue で捨てる）
                    if (len + 1 + need > cap or !insert(&[_]u8{c})) {
                        lost();
                        continue;
                    }
                    utf8_start = pos - 1;
                    utf8_rest = need;
                    continue;
                }
                if (!insert(&[_]u8{c})) {
                    lost();
                    continue;
                }
                typed(line[pos - 1 .. pos]);
            },
        }
    }
}
