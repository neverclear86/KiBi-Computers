//! `/bin/vi`（#104）: 小さな vi（POSIX vi のよく使う部分）。
//!
//! ```
//! vi [+LINE | +] [FILE]
//! ```
//!
//! 決めごと（コマンドの表・画面・上限・保存の手順・Esc の見分け方・今の制限）は docs/adr/0104-vi.md。ソースは `os/bin/vi/` に分けている:
//! `buffer.zig`（テキスト）、`view.zig`（表示）、`state.zig`（状態）、`undo.zig`（元に戻す）。
//!
//! 待っている間は標準入力の読み込み（待機点）か、標準入力と「保存から戻った」のキューの `poll_oneoff`（#87）で止まるだけにする（時計で起きない）。
//! キーは読んだ分を全部処理してから、まとめて 1 回描く（貼り付けで 1 文字ごとに描かない）。
const kibi = @import("kibi");
const buffer = @import("vi/buffer.zig");
const view = @import("vi/view.zig");
const st = @import("vi/state.zig");
const undo = @import("vi/undo.zig");

const io = kibi.io;
const fs = kibi.fs;
const cli = kibi.cli;
const tty = kibi.tty;
const keys = kibi.keys;
const Key = keys.Key;

const SYNOPSIS = "vi [+LINE | +] [FILE]";

/// ESC の後の文字を Alt+文字にせず、素の Esc と次のキーに分けて読む（ADR 0104「Esc の見分け方」）
var reader: keys.Reader = .{ .fd = io.STDIN, .split_escape = true };
/// 入力が終わった（TTY が閉じた）
var eof = false;
var quit = false;

// ---- レジスター（無名の 1 つだけ） ----

var reg_mem: [buffer.MAX_LEN]u8 = undefined;
var reg_len: usize = 0;
var reg_committed: usize = 0;
/// 行単位で入れたか（`dd`・`yy` など。中身は必ず改行で終わる）
var reg_linewise = false;

// ---- 繰り返し（`.`） ----

/// 1 つのコマンドで覚えるキーの数の上限（超えたら `.` で繰り返せない）
const MAX_REC = 4096;
/// 今のコマンドのキー（数の後ろから）
var tent: [MAX_REC]Key = undefined;
var tent_len: usize = 0;
var tent_over = false;
var recording = false;
/// 今のコマンドがテキストを変えるコマンドか（`.` で繰り返すものとして覚える）
var change_cmd = false;
/// 前に変えたコマンド
var dot: [MAX_REC]Key = undefined;
var dot_len: usize = 0;
var dot_count: usize = 0;
/// 繰り返しの途中（キーを [dot] から読む）
var replaying = false;
var replay_pos: usize = 0;

// ---- 探す ----

var search_buf: [256]u8 = undefined;
var search_len: usize = 0;
var search_backward = false;
var re: kibi.regex.Regex = undefined;

/// 前の f・F・t・T
const FindKind = enum { f, F, t, T };
var last_find: ?struct { kind: FindKind, bytes: [4]u8, len: usize } = null;

// ---- 待つ ----

/// 「保存から戻った」を受け取るキュー（#87。起動中ずっと開く。開けなければ null で、入力だけを待つ）
var restored: ?kibi.events.Queue = null;

fn closeRestored() void {
    if (restored) |q| q.close();
    restored = null;
}

/// 待ち・取り出しに失敗したキューを、閉じずに手放す（#87、ADR 0087）。不正なハンドルを drop するとトラップするので閉じない
fn abandonRestored() void {
    restored = null;
}

/// 読んだバイトが残っていなければ、入力か「保存から戻った」を待つ（#87、ADR 0087）。戻ったら、端末の設定（代替画面・折り返しなし）
/// から出し直して全体を描き直し、また待つ
fn awaitInput() void {
    while (restored) |q| {
        if (!reader.idle()) return;
        const ready = kibi.events.waitInputOrQueue(io.STDIN, q) catch {
            abandonRestored();
            return;
        };
        if (ready.queue) {
            const redraw = q.drainRestored() catch {
                abandonRestored();
                return;
            };
            if (redraw) {
                view.enter();
                view.render();
            }
        }
        if (ready.input) return;
    }
}

/// 次のキー。読んだ分を処理し終えていたら、描いてから待つ。繰り返しの間は覚えたキーを返す
fn nextKey() ?Key {
    if (replaying) {
        if (replay_pos < dot_len) {
            replay_pos += 1;
            return dot[replay_pos - 1];
        }
        // 覚えたキーが途中で終わった（ふつうは起きない）: Esc で抜ける
        return .{ .kind = .escape };
    }
    if (eof) return null;
    if (!reader.pending()) {
        view.render();
        awaitInput();
    }
    const k = reader.next() orelse {
        eof = true;
        return null;
    };
    if (recording) {
        if (tent_len < tent.len) {
            tent[tent_len] = k;
            tent_len += 1;
        } else tent_over = true;
    }
    return k;
}

// ---- キーの読み方 ----

/// ASCII の 1 文字 [c] のキーか
fn isChar(k: Key, c: u8) bool {
    return k.kind == .text and k.len == 1 and k.bytes[0] == c;
}

/// ASCII の 1 文字のキーなら、その文字
fn charOf(k: Key) ?u8 {
    if (k.kind == .text and k.len == 1) return k.bytes[0];
    return null;
}

fn digitOf(k: Key) ?u8 {
    const c = charOf(k) orelse return null;
    return if (c >= '0' and c <= '9') c - '0' else null;
}

fn isEnter(k: Key) bool {
    return k.kind == .ctrl and (k.c == '\r' or k.c == '\n');
}

fn isBackspace(k: Key) bool {
    return k.kind == .ctrl and (k.c == 0x7f or k.c == 0x08);
}

/// 打ち切るキー（Esc・Ctrl+C）
fn isCancel(k: Key) bool {
    return k.kind == .escape or (k.kind == .ctrl and k.c == 0x03);
}

// ---- 位置 ----

fn countNl(s: []const u8) usize {
    var n: usize = 0;
    for (s) |c| n += @intFromBool(c == '\n');
    return n;
}

/// カーソルを [pos] へ（行の番号は、今の位置から数える）。[center] なら画面の外のとき真ん中に置く
fn setCursor(pos: usize, center: bool) void {
    const p = @min(pos, buffer.len);
    if (p > st.cur) {
        st.cur_line += buffer.newlinesIn(st.cur, p);
    } else {
        st.cur_line -= buffer.newlinesIn(p, st.cur);
    }
    st.cur = p;
    st.want_dirty = true;
    st.want_end = false;
    if (center) st.center = true;
}

/// 上下に動いたときのカーソル（目指す桁を変えない）
fn setCursorVertical(pos: usize) void {
    const want = st.want;
    const end = st.want_end;
    setCursor(pos, false);
    st.want = want;
    st.want_end = end;
    st.want_dirty = false;
}

fn ensureWant() void {
    if (st.want_dirty) {
        st.want = view.columnOf(buffer.lineStart(st.cur), st.cur);
        st.want_dirty = false;
    }
}

/// normal のカーソルを、行の最後の文字より後ろに置かない（空の行では頭）。テキストの終わりの改行の後ろにも置かない
fn clampNormal() void {
    var p = st.cur;
    if (buffer.len > 0 and p >= buffer.len and buffer.at(buffer.len - 1) == '\n') p = buffer.len - 1;
    const s = buffer.lineStart(p);
    const e = buffer.lineEnd(p);
    if (p >= e and e > s) p = view.prevGlyph(s, e);
    if (p != st.cur) {
        const want = st.want;
        const dirty = st.want_dirty;
        const end = st.want_end;
        setCursor(p, false);
        st.want = want;
        st.want_dirty = dirty;
        st.want_end = end;
    }
}

fn isBlank(c: u8) bool {
    return c == ' ' or c == '\t';
}

/// 行の頭 [s] から、空白でない最初の文字（なければ行の終わり）
fn firstNonBlank(s: usize) usize {
    var i = s;
    while (i < buffer.len and isBlank(buffer.at(i))) i += 1;
    return i;
}

/// 今の行から [delta] 行動いた行の頭（端で止まる）と、動いた行数
fn lineFrom(start: usize, up: bool, n: usize) struct { start: usize, moved: usize } {
    var s = start;
    var moved: usize = 0;
    while (moved < n) : (moved += 1) {
        s = (if (up) buffer.prevLine(s) else buffer.nextLine(s)) orelse break;
    }
    return .{ .start = s, .moved = moved };
}

/// [idx] 行目（0 から）の頭
fn lineStartOf(idx: usize) usize {
    const here = buffer.lineStart(st.cur);
    if (idx >= st.cur_line) return lineFrom(here, false, idx - st.cur_line).start;
    return lineFrom(here, true, st.cur_line - idx).start;
}

/// 位置 [p] の文字の頭（UTF-8 の続きのバイトなら前へ戻る）
fn glyphStart(p: usize) usize {
    var i = p;
    while (i > 0 and i < buffer.len and kibi.text.isContinuation(buffer.at(i))) i -= 1;
    return i;
}

// ---- 語 ----

/// 0 = 空白（改行を含む）、1 = 語の文字（英数字・`_`・ASCII でない文字）、2 = 記号。[big] なら空白でないものは全部 1
fn cls(c: u8, big: bool) u8 {
    if (c == ' ' or c == '\t' or c == '\n') return 0;
    if (big) return 1;
    if ((c >= 'a' and c <= 'z') or (c >= 'A' and c <= 'Z') or (c >= '0' and c <= '9') or c == '_' or c >= 0x80) return 1;
    return 2;
}

/// 次の語の頭（空の行も語に数える）
fn wordForward(p: usize, big: bool) usize {
    if (p >= buffer.len) return p;
    var i = p;
    const c0 = cls(buffer.at(i), big);
    if (c0 != 0) {
        while (i < buffer.len and cls(buffer.at(i), big) == c0) i += 1;
    }
    while (i < buffer.len) {
        const c = buffer.at(i);
        if (c == '\n') {
            i += 1;
            if (i < buffer.len and buffer.at(i) == '\n') return i;
            continue;
        }
        if (cls(c, big) != 0) break;
        i += 1;
    }
    return i;
}

/// 前の語の頭
fn wordBackward(p: usize, big: bool) usize {
    if (p == 0) return 0;
    var i = p - 1;
    while (i > 0 and cls(buffer.at(i), big) == 0) {
        // 空の行（改行の前も改行。その位置が空の行の頭）で止まる
        if (buffer.at(i) == '\n' and buffer.at(i - 1) == '\n') return i;
        i -= 1;
    }
    if (cls(buffer.at(i), big) == 0) return i;
    const c = cls(buffer.at(i), big);
    while (i > 0 and cls(buffer.at(i - 1), big) == c) i -= 1;
    return i;
}

/// 語の終わり（最後の文字の頭）
fn wordEnd(p: usize, big: bool) usize {
    if (buffer.len == 0) return 0;
    var i = p + 1;
    while (i < buffer.len and kibi.text.isContinuation(buffer.at(i))) i += 1;
    while (i < buffer.len and cls(buffer.at(i), big) == 0) i += 1;
    if (i >= buffer.len) return glyphStart(buffer.len - 1);
    const c = cls(buffer.at(i), big);
    while (i + 1 < buffer.len and cls(buffer.at(i + 1), big) == c) i += 1;
    return glyphStart(i);
}

// ---- 動き ----

const Motion = struct {
    pos: usize,
    /// 行単位（j・k・G など）
    linewise: bool = false,
    /// 着いた文字を含む（e・$・f など）
    inclusive: bool = false,
    /// 上下の動き（目指す桁を変えない）
    vertical: bool = false,
    /// `$`（上下に動いても行の終わりを目指す）
    to_end: bool = false,
};

/// 次のキーから 1 文字を読む（f・t・r の文字）。Esc などなら null
fn readChar() ?Key {
    const k = nextKey() orelse return null;
    if (k.kind == .text) return k;
    if (k.kind == .ctrl and k.c == '\t') {
        var t: Key = .{ .kind = .text };
        t.bytes[0] = '\t';
        t.len = 1;
        return t;
    }
    return null;
}

/// 今の行で [bytes] を探す（f・F・t・T）
fn findChar(kind: FindKind, bytes: []const u8, n: usize, again: bool) ?usize {
    const s = buffer.lineStart(st.cur);
    const e = buffer.lineEnd(st.cur);
    var p = st.cur;
    var k: usize = 0;
    const forward = kind == .f or kind == .t;
    // 繰り返しの t・T は、すぐ隣の文字で止まったままにならないよう、1 文字先から探す
    if (again and kind == .t) p = view.nextGlyph(s, p);
    if (again and kind == .T) p = view.prevGlyph(s, p);
    while (k < n) : (k += 1) {
        var found: ?usize = null;
        if (forward) {
            var i = view.nextGlyph(s, p);
            while (i < e) : (i = view.nextGlyph(s, i)) {
                if (matchAt(i, bytes)) {
                    found = i;
                    break;
                }
            }
        } else {
            var i = p;
            while (i > s) {
                i = view.prevGlyph(s, i);
                if (matchAt(i, bytes)) {
                    found = i;
                    break;
                }
            }
        }
        p = found orelse return null;
    }
    return switch (kind) {
        .f, .F => p,
        .t => view.prevGlyph(s, p),
        .T => view.nextGlyph(s, p),
    };
}

fn matchAt(i: usize, bytes: []const u8) bool {
    if (i + bytes.len > buffer.len) return false;
    for (bytes, 0..) |b, j| if (buffer.at(i + j) != b) return false;
    return true;
}

/// 対になる括弧（%）
fn matchPair() ?usize {
    const e = buffer.lineEnd(st.cur);
    var i = st.cur;
    while (i < e) : (i += 1) {
        const c = buffer.at(i);
        if (c == '(' or c == ')' or c == '[' or c == ']' or c == '{' or c == '}') break;
    } else return null;
    const c = buffer.at(i);
    const pair: [2]u8 = switch (c) {
        '(', ')' => .{ '(', ')' },
        '[', ']' => .{ '[', ']' },
        else => .{ '{', '}' },
    };
    var depth: usize = 0;
    if (c == pair[0]) {
        var j = i + 1;
        while (j < buffer.len) : (j += 1) {
            const d = buffer.at(j);
            if (d == pair[0]) depth += 1 else if (d == pair[1]) {
                if (depth == 0) return j;
                depth -= 1;
            }
        }
    } else {
        var j = i;
        while (j > 0) {
            j -= 1;
            const d = buffer.at(j);
            if (d == pair[1]) depth += 1 else if (d == pair[0]) {
                if (depth == 0) return j;
                depth -= 1;
            }
        }
    }
    return null;
}

/// 見えている本文の行の数
fn visibleLines() usize {
    return @min(view.textRows(), buffer.lineCount() -| st.top_line);
}

/// 動きのキー [k] の行き先。[op] は d・c・y の後（行の終わりまで動ける）。動けなければ null
fn motion(k: Key, count: usize, op: bool) ?Motion {
    const n = @max(count, 1);
    const s = buffer.lineStart(st.cur);
    const e = buffer.lineEnd(st.cur);
    switch (k.kind) {
        .left => return motion(textKey('h'), count, op),
        .right => return motion(textKey('l'), count, op),
        .up => return motion(textKey('k'), count, op),
        .down => return motion(textKey('j'), count, op),
        .home => return .{ .pos = s },
        .end => return motion(textKey('$'), count, op),
        .ctrl => switch (k.c) {
            0x08, 0x7f => return motion(textKey('h'), count, op),
            0x0e => return motion(textKey('j'), count, op),
            0x10 => return motion(textKey('k'), count, op),
            '\r', '\n' => return motion(textKey('+'), count, op),
            else => return null,
        },
        .text => {},
        else => return null,
    }
    const c = charOf(k) orelse return null;
    switch (c) {
        'h' => {
            var p = st.cur;
            var i: usize = 0;
            while (i < n and p > s) : (i += 1) p = view.prevGlyph(s, p);
            if (p == st.cur) return null;
            return .{ .pos = p };
        },
        'l', ' ' => {
            var p = st.cur;
            var i: usize = 0;
            while (i < n and p < e) : (i += 1) p = view.nextGlyph(s, p);
            // normal では最後の文字の後ろへ出ない（d・c・y では行の終わりまで）
            if (!op and p >= e and e > s) p = view.prevGlyph(s, e);
            if (p == st.cur and !(op and p < e)) return null;
            return .{ .pos = p };
        },
        'j', 'k' => {
            const r = lineFrom(s, c == 'k', n);
            if (r.moved == 0) return null;
            ensureWant();
            const col = if (st.want_end) ~@as(usize, 0) else st.want;
            return .{ .pos = view.positionAt(r.start, col), .linewise = true, .vertical = true };
        },
        '+', '-' => {
            const r = lineFrom(s, c == '-', n);
            if (r.moved == 0) return null;
            return .{ .pos = firstNonBlank(r.start), .linewise = true };
        },
        '0' => return .{ .pos = s },
        '^' => return .{ .pos = firstNonBlank(s) },
        '$' => {
            const r = lineFrom(s, false, n - 1);
            const le = buffer.lineEnd(r.start);
            const p = if (le > r.start) view.prevGlyph(r.start, le) else le;
            return .{ .pos = p, .inclusive = true, .to_end = true };
        },
        'w', 'W' => {
            const big = c == 'W';
            var p = st.cur;
            var i: usize = 0;
            while (i < n) : (i += 1) {
                const before = p;
                p = wordForward(p, big);
                // d・c・y で最後の語が行の終わりにあれば、次の行へ出ない
                if (op and i + 1 == n) {
                    const le = buffer.lineEnd(before);
                    if (le > before and p > le) p = le;
                }
            }
            if (p == st.cur) return null;
            return .{ .pos = p };
        },
        'b', 'B' => {
            var p = st.cur;
            var i: usize = 0;
            while (i < n) : (i += 1) p = wordBackward(p, c == 'B');
            if (p == st.cur) return null;
            return .{ .pos = p };
        },
        'e', 'E' => {
            var p = st.cur;
            var i: usize = 0;
            while (i < n) : (i += 1) p = wordEnd(p, c == 'E');
            if (p == st.cur and !op) return null;
            return .{ .pos = p, .inclusive = true };
        },
        'G' => {
            const line = if (count > 0) @min(count, buffer.lineCount()) - 1 else buffer.lineCount() - 1;
            return .{ .pos = firstNonBlank(lineStartOf(line)), .linewise = true };
        },
        'g' => {
            const k2 = nextKey() orelse return null;
            if (!isChar(k2, 'g')) return null;
            const line = if (count > 0) @min(count, buffer.lineCount()) - 1 else 0;
            return .{ .pos = firstNonBlank(lineStartOf(line)), .linewise = true };
        },
        'H', 'L', 'M' => {
            const vis = @max(visibleLines(), 1);
            const off: usize = switch (c) {
                'H' => @min(n - 1, vis - 1),
                'L' => (vis - 1) -| (n - 1),
                else => (vis - 1) / 2,
            };
            const r = lineFrom(st.top, false, off);
            return .{ .pos = firstNonBlank(r.start), .linewise = true };
        },
        'f', 'F', 't', 'T' => {
            const ck = readChar() orelse return null;
            const kind: FindKind = switch (c) {
                'f' => .f,
                'F' => .F,
                't' => .t,
                else => .T,
            };
            last_find = .{ .kind = kind, .bytes = ck.bytes, .len = ck.len };
            const p = findChar(kind, ck.bytes[0..ck.len], n, false) orelse return null;
            return .{ .pos = p, .inclusive = kind == .f or kind == .t };
        },
        ';', ',' => {
            const lf = last_find orelse return null;
            var kind = lf.kind;
            if (c == ',') kind = switch (kind) {
                .f => .F,
                .F => .f,
                .t => .T,
                .T => .t,
            };
            const p = findChar(kind, lf.bytes[0..lf.len], n, true) orelse return null;
            return .{ .pos = p, .inclusive = kind == .f or kind == .t };
        },
        '%' => {
            const p = matchPair() orelse return null;
            return .{ .pos = p, .inclusive = true };
        },
        'n', 'N' => {
            const p = searchRepeat(c == 'N', n) orelse return null;
            return .{ .pos = p };
        },
        '/', '?' => {
            if (!searchPrompt(c)) return null;
            const p = searchRepeat(false, n) orelse return null;
            return .{ .pos = p };
        },
        else => return null,
    }
}

fn textKey(c: u8) Key {
    var k: Key = .{ .kind = .text };
    k.bytes[0] = c;
    k.len = 1;
    return k;
}

/// 動きをカーソルに当てる
fn applyMotion(m: Motion) void {
    if (m.vertical) {
        setCursorVertical(m.pos);
    } else {
        const before = st.cur_line;
        setCursor(m.pos, false);
        // 1 画面より遠くへ飛んだら、カーソルの行を真ん中に置く
        const d = if (st.cur_line > before) st.cur_line - before else before - st.cur_line;
        if (d >= view.textRows()) st.center = true;
        if (m.to_end) st.want_end = true;
    }
}

// ---- 変える ----

fn reportFull(e: buffer.Error) void {
    st.setError(switch (e) {
        error.TooBig => "The buffer is full (4 MiB)",
        error.OutOfMemory => "Not enough memory",
    });
}

/// 位置を、[pos] に [n] バイト（改行 [nl] 個）入ったぶんずらす
fn shiftInserted(pos: usize, n: usize, nl: usize) void {
    if (pos <= st.cur) {
        st.cur += n;
        st.cur_line += nl;
    }
    if (pos < st.top) {
        st.top += n;
        st.top_line += nl;
    }
}

/// [pos] に [bytes] を入れる（カーソルが [pos] 以降なら後ろへずれる）。入れられなければ知らせて false
fn edInsert(pos: usize, bytes: []const u8) bool {
    if (bytes.len == 0) return true;
    buffer.insert(pos, bytes) catch |e| {
        reportFull(e);
        return false;
    };
    undo.recordInsert(pos, bytes.len);
    shiftInserted(pos, bytes.len, countNl(bytes));
    st.modified = true;
    st.want_dirty = true;
    return true;
}

/// [a, b) を消す（カーソルが中にあれば [a] へ）
fn edRemove(a: usize, b: usize) void {
    if (b <= a) return;
    undo.recordDelete(a, b);
    if (st.cur >= b) {
        st.cur_line -= buffer.newlinesIn(a, b);
        st.cur -= b - a;
    } else if (st.cur > a) {
        st.cur_line -= buffer.newlinesIn(a, st.cur);
        st.cur = a;
    }
    if (st.top >= b) {
        st.top_line -= buffer.newlinesIn(a, b);
        st.top -= b - a;
    } else if (st.top > a) {
        st.top_line -= buffer.newlinesIn(a, st.top);
        st.top = buffer.lineStart(a);
    }
    buffer.remove(a, b);
    st.modified = true;
    st.want_dirty = true;
}

fn ensureReg(n: usize) bool {
    while (reg_committed < n) {
        const end = @min(reg_committed + buffer.CHUNK, reg_mem.len);
        if (!kibi.memory.commit(reg_mem[reg_committed..end], 0)) return false;
        reg_committed = end;
    }
    return true;
}

/// [a, b) をレジスターに写す（[add_nl] なら後ろに改行を足す）。入らなければ知らせて false
fn yankRange(a: usize, b: usize, linewise: bool, add_nl: bool) bool {
    const n = b - a + @intFromBool(add_nl);
    if (n > reg_mem.len or !ensureReg(n)) {
        st.setError("Not enough memory to yank");
        return false;
    }
    _ = buffer.copy(a, reg_mem[0 .. b - a]);
    if (add_nl) reg_mem[n - 1] = '\n';
    reg_len = n;
    reg_linewise = linewise;
    return true;
}

/// 行の頭 [a] の行から、[b] のある行までの、消す範囲（[yank_*] はレジスターに入れる範囲）。
/// 最後の行が改行で終わらないときは、前の行の改行を消して、レジスターには改行を足す（テキストの終わりに改行を足さない）
const LineRange = struct { del_a: usize, del_b: usize, yank_a: usize, yank_b: usize, add_nl: bool };

fn lineRange(a: usize, b: usize) LineRange {
    const ls = buffer.lineStart(a);
    const le = buffer.lineEnd(b);
    if (le < buffer.len) return .{ .del_a = ls, .del_b = le + 1, .yank_a = ls, .yank_b = le + 1, .add_nl = false };
    if (ls > 0) return .{ .del_a = ls - 1, .del_b = le, .yank_a = ls, .yank_b = le, .add_nl = true };
    return .{ .del_a = 0, .del_b = le, .yank_a = 0, .yank_b = le, .add_nl = le > 0 };
}

const Op = enum { delete, change, yank };

/// 演算子 [op] を、カーソルから動き [m] までに当てる
fn operate(op: Op, m: Motion) void {
    var a = @min(st.cur, m.pos);
    var b = @max(st.cur, m.pos);
    if (m.linewise) {
        const r = lineRange(a, b);
        if (!yankRange(r.yank_a, r.yank_b, true, r.add_nl)) return;
        switch (op) {
            .yank => {
                if (r.yank_a < buffer.lineStart(st.cur)) setCursor(r.yank_a, false);
            },
            .delete => {
                edRemove(r.del_a, r.del_b);
                // 消した行の次の行（最後の行を消したときは、その前の行）の、空白でない最初の文字へ
                var p = r.del_a;
                if (p >= buffer.len and buffer.len > 0) p = buffer.len - 1;
                setCursor(firstNonBlank(buffer.lineStart(p)), false);
            },
            .change => {
                // 行の中身だけを消して、空の行で挿入に入る
                const ls = buffer.lineStart(a);
                const le = buffer.lineEnd(b);
                if (!yankRange(ls, le, true, true)) return;
                edRemove(ls, le);
                setCursor(ls, false);
                insertMode(1, .plain);
            },
        }
        return;
    }
    if (m.inclusive and b < buffer.len and buffer.at(b) != '\n') b = view.nextGlyph(buffer.lineStart(b), b);
    // 行の頭で終わる、含まない動きは、前の行の終わりまでにする
    if (!m.inclusive and b > a and buffer.at(b - 1) == '\n' and buffer.lineStart(b) == b) {
        b -= 1;
    }
    if (a > b) a = b;
    if (!yankRange(a, b, false, false)) return;
    switch (op) {
        .yank => setCursor(a, false),
        .delete => {
            edRemove(a, b);
            setCursor(a, false);
        },
        .change => {
            edRemove(a, b);
            setCursor(a, false);
            insertMode(1, .plain);
        },
    }
}

/// d・c・y の後ろのキーを読んで当てる
fn operator(op: Op, opc: u8, count: usize) void {
    var k = nextKey() orelse return;
    var count2: usize = 0;
    while (digitOf(k)) |d| {
        if (d == 0 and count2 == 0) break;
        count2 = count2 *| 10 +| d;
        k = nextKey() orelse return;
    }
    if (isCancel(k)) return;
    const total: usize = if (count == 0 and count2 == 0) 0 else @max(count, 1) *| @max(count2, 1);
    const n = @max(total, 1);
    if (isChar(k, opc)) {
        // dd・cc・yy: n 行
        const r = lineFrom(buffer.lineStart(st.cur), false, n - 1);
        operate(op, .{ .pos = r.start, .linewise = true });
        return;
    }
    // cw は、語の上なら語の終わりまで（空白を含めない）
    if (op == .change and (isChar(k, 'w') or isChar(k, 'W')) and st.cur < buffer.len and cls(buffer.at(st.cur), false) != 0) {
        const big = isChar(k, 'W');
        var p = st.cur;
        var i: usize = 0;
        while (i < n) : (i += 1) {
            // 語の最後の文字に居るときは、その文字だけ（1 回目）
            const nx = view.nextGlyph(buffer.lineStart(p), p);
            if (i == 0 and (nx >= buffer.len or cls(buffer.at(nx), big) != cls(buffer.at(p), big))) continue;
            p = wordEnd(p, big);
        }
        operate(op, .{ .pos = p, .inclusive = true });
        return;
    }
    const m = motion(k, total, true) orelse return;
    operate(op, m);
}

const InsertKind = enum { plain, open_below, open_above };

/// 挿入（Esc・Ctrl+C で終わる）。[count] 回分入れる
fn insertMode(count: usize, kind: InsertKind) void {
    st.mode = .insert;
    defer st.mode = .normal;
    const start = st.cur;
    var moved = false;
    while (nextKey()) |k| {
        if (isCancel(k)) break;
        switch (k.kind) {
            .text => _ = edInsert(st.cur, k.slice()),
            .ctrl => switch (k.c) {
                '\r', '\n' => _ = edInsert(st.cur, "\n"),
                '\t' => _ = edInsert(st.cur, "\t"),
                0x7f, 0x08 => {
                    const s = buffer.lineStart(st.cur);
                    if (st.cur > s) {
                        edRemove(view.prevGlyph(s, st.cur), st.cur);
                    } else if (st.cur > 0) {
                        edRemove(st.cur - 1, st.cur);
                    }
                    if (st.cur < start) moved = true;
                },
                else => {},
            },
            .delete => {
                const s = buffer.lineStart(st.cur);
                const e = buffer.lineEnd(st.cur);
                if (st.cur < e) edRemove(st.cur, view.nextGlyph(s, st.cur)) else if (st.cur < buffer.len) edRemove(st.cur, st.cur + 1);
                moved = true;
            },
            .left, .right, .up, .down, .home, .end, .page_up, .page_down => {
                moved = true;
                insertMove(k);
            },
            else => {},
        }
    }
    // 回数: 打った分を、続けて [count] - 1 回入れる（途中で動いたら繰り返さない）
    if (count > 1 and !moved and st.cur >= start) {
        var a = start;
        var b = st.cur;
        switch (kind) {
            .plain => {},
            .open_below => a -|= 1,
            .open_above => b += 1,
        }
        if (b > a and b <= buffer.len) {
            const n = b - a;
            const times = count - 1;
            if (buffer.repeatRange(a, b, times)) {
                const total = n * times;
                undo.recordInsert(b, total);
                shiftInserted(b, total, buffer.newlinesIn(b, b + total));
                st.modified = true;
                if (kind == .open_above) setCursor(b + total - 1, false);
            } else |e| reportFull(e);
        }
    }
    // Esc で 1 文字左へ（行の頭では動かない）
    const s = buffer.lineStart(st.cur);
    if (st.cur > s) setCursor(view.prevGlyph(s, st.cur), false);
}

/// 挿入の中のカーソルの移動（矢印など）
fn insertMove(k: Key) void {
    const s = buffer.lineStart(st.cur);
    const e = buffer.lineEnd(st.cur);
    switch (k.kind) {
        .left => if (st.cur > s) setCursor(view.prevGlyph(s, st.cur), false),
        .right => if (st.cur < e) setCursor(view.nextGlyph(s, st.cur), false),
        .home => setCursor(s, false),
        .end => setCursor(e, false),
        .up, .down, .page_up, .page_down => {
            const up = k.kind == .up or k.kind == .page_up;
            const n: usize = if (k.kind == .up or k.kind == .down) 1 else @max(1, view.textRows() -| 2);
            const r = lineFrom(s, up, n);
            if (r.moved == 0) return;
            ensureWant();
            setCursorVertical(view.positionAt(r.start, st.want));
        },
        else => {},
    }
}

/// p・P
fn put(after: bool, count: usize) void {
    if (reg_len == 0) {
        st.setError("Nothing in register");
        return;
    }
    const n = @max(count, 1);
    const total = reg_len *| n;
    buffer.reserve(total +| 1) catch |e| return reportFull(e);
    const reg = reg_mem[0..reg_len];
    if (reg_linewise) {
        const s = buffer.lineStart(st.cur);
        var at: usize = s;
        if (after) {
            const e = buffer.lineEnd(st.cur);
            if (e < buffer.len) {
                at = e + 1;
            } else {
                // 最後の行が改行で終わらない: 前に改行を入れ、最後の改行を取る（テキストの終わりに改行を足さない）
                if (!edInsert(e, "\n")) return;
                var i: usize = 0;
                while (i < n) : (i += 1) if (!edInsert(e + 1 + i * reg_len, reg)) break;
                edRemove(buffer.len - 1, buffer.len);
                setCursor(firstNonBlank(e + 1), false);
                return;
            }
        }
        var i: usize = 0;
        while (i < n) : (i += 1) if (!edInsert(at + i * reg_len, reg)) break;
        setCursor(firstNonBlank(at), false);
        return;
    }
    var at = st.cur;
    if (after) {
        const s = buffer.lineStart(st.cur);
        if (st.cur < buffer.lineEnd(st.cur)) at = view.nextGlyph(s, st.cur);
    }
    var i: usize = 0;
    while (i < n) : (i += 1) if (!edInsert(at + i * reg_len, reg)) break;
    const end = at + i * reg_len;
    if (end > at) setCursor(glyphStart(end - 1), false);
}

/// x・X（[before] なら X）
fn deleteChars(before: bool, count: usize) void {
    const n = @max(count, 1);
    const s = buffer.lineStart(st.cur);
    const e = buffer.lineEnd(st.cur);
    var a = st.cur;
    var b = st.cur;
    var i: usize = 0;
    if (before) {
        while (i < n and a > s) : (i += 1) a = view.prevGlyph(s, a);
    } else {
        while (i < n and b < e) : (i += 1) b = view.nextGlyph(s, b);
    }
    if (a == b) return;
    if (!yankRange(a, b, false, false)) return;
    edRemove(a, b);
    setCursor(a, false);
}

/// r
fn replaceChars(count: usize) void {
    const k = nextKey() orelse return;
    var bytes: []const u8 = undefined;
    var tmp = k;
    if (isEnter(k)) {
        bytes = "\n";
    } else if (k.kind == .ctrl and k.c == '\t') {
        bytes = "\t";
    } else if (k.kind == .text) {
        bytes = tmp.slice();
    } else return;
    const n = @max(count, 1);
    const s = buffer.lineStart(st.cur);
    const e = buffer.lineEnd(st.cur);
    var b = st.cur;
    var i: usize = 0;
    while (i < n and b < e) : (i += 1) b = view.nextGlyph(s, b);
    if (i < n) return;
    const a = st.cur;
    if (bytes[0] == '\n') {
        // 改行は何文字置き換えても 1 つ
        edRemove(a, b);
        if (edInsert(a, "\n")) setCursor(a + 1, false);
        return;
    }
    buffer.reserve(bytes.len *| n) catch |err| return reportFull(err);
    edRemove(a, b);
    i = 0;
    while (i < n) : (i += 1) _ = edInsert(a + i * bytes.len, bytes);
    setCursor(a + (n - 1) * bytes.len, false);
}

/// J
fn joinLines(count: usize) void {
    const times = @max(count, 2) - 1;
    var i: usize = 0;
    var at: ?usize = null;
    while (i < times) : (i += 1) {
        const ls = buffer.lineStart(st.cur);
        const e = buffer.lineEnd(ls);
        if (e + 1 >= buffer.len) break;
        var j = e + 1;
        while (j < buffer.len and isBlank(buffer.at(j))) j += 1;
        const sep: []const u8 = if (j >= buffer.len or buffer.at(j) == '\n' or buffer.at(j) == ')' or (e > ls and isBlank(buffer.at(e - 1)))) "" else " ";
        edRemove(e, j);
        _ = edInsert(e, sep);
        setCursor(e, false);
        at = e;
    }
    if (at) |p| setCursor(p, false);
}

/// ~
fn toggleCase(count: usize) void {
    const n = @max(count, 1);
    const s = buffer.lineStart(st.cur);
    const e = buffer.lineEnd(st.cur);
    var p = st.cur;
    var i: usize = 0;
    while (i < n and p < e) : (i += 1) {
        const c = buffer.at(p);
        const t: u8 = if (c >= 'a' and c <= 'z') c - 32 else if (c >= 'A' and c <= 'Z') c + 32 else c;
        if (t != c) {
            edRemove(p, p + 1);
            _ = edInsert(p, &[_]u8{t});
        }
        p = view.nextGlyph(s, p);
    }
    setCursor(p, false);
}

// ---- 元に戻す ----

fn applyUndo() bool {
    if (undo.undo_end == 0) {
        st.setError("Already at oldest change");
        return false;
    }
    // 戻すのに要る分を、先に取る（途中まで戻して止まらないように）。消した分を入れ、入れた分を取り除く順に足し引きして、途中でいちばん増えたところまで
    var need: usize = 0;
    var grown: isize = 0;
    var off = undo.undo_end;
    while (off > 0) {
        const r = undo.recordBefore(off);
        if (r.kind == .group) break;
        switch (r.kind) {
            .delete => {
                grown += @intCast(r.data.len);
                if (grown > 0) need = @max(need, @as(usize, @intCast(grown)));
            },
            .insert => grown -= @intCast(r.data.len),
            .group => {},
        }
        off = r.start;
    }
    buffer.reserve(need) catch |e| {
        reportFull(e);
        return false;
    };
    undo.suspended = true;
    defer undo.suspended = false;
    off = undo.undo_end;
    var target: usize = 0;
    while (off > 0) {
        const r = undo.recordBefore(off);
        off = r.start;
        switch (r.kind) {
            .group => {
                target = r.pos;
                break;
            },
            .insert => edRemove(r.pos, r.pos + r.data.len),
            .delete => _ = edInsert(r.pos, r.data),
        }
    }
    undo.undo_end = off;
    setCursor(target, true);
    return true;
}

fn applyRedo() bool {
    if (undo.undo_end >= undo.log_end) {
        st.setError("Already at newest change");
        return false;
    }
    const g = undo.recordAt(undo.undo_end);
    // 順に入れ・取り除いて、途中でいちばん増えたところまで要る（[applyUndo] と同じ）
    var need: usize = 0;
    var grown: isize = 0;
    var off = g.end;
    while (off < undo.log_end) {
        const r = undo.recordAt(off);
        if (r.kind == .group) break;
        switch (r.kind) {
            .insert => {
                grown += @intCast(r.data.len);
                if (grown > 0) need = @max(need, @as(usize, @intCast(grown)));
            },
            .delete => grown -= @intCast(r.data.len),
            .group => {},
        }
        off = r.end;
    }
    buffer.reserve(need) catch |e| {
        reportFull(e);
        return false;
    };
    undo.suspended = true;
    defer undo.suspended = false;
    off = g.end;
    var first: ?usize = null;
    while (off < undo.log_end) {
        const r = undo.recordAt(off);
        if (r.kind == .group) break;
        switch (r.kind) {
            .insert => _ = edInsert(r.pos, r.data),
            .delete => edRemove(r.pos, r.pos + r.data.len),
            .group => {},
        }
        if (first == null) first = r.pos;
        off = r.end;
    }
    undo.undo_end = off;
    setCursor(first orelse g.pos, true);
    return true;
}

// ---- いちばん下の行の入力 ----

/// `:`・`/`・`?` の入力。Enter で入力、Esc・Ctrl+C・空のときの Backspace で null
fn readCmdline(prefix: u8) ?[]const u8 {
    const saved = st.mode;
    st.mode = .command;
    st.cmd_prefix = prefix;
    st.cmd_len = 0;
    defer st.mode = saved;
    while (nextKey()) |k| {
        if (isCancel(k)) return null;
        switch (k.kind) {
            .text => {
                const s = k.slice();
                if (st.cmd_len + s.len <= st.cmd_buf.len) {
                    @memcpy(st.cmd_buf[st.cmd_len..][0..s.len], s);
                    st.cmd_len += s.len;
                }
            },
            .ctrl => switch (k.c) {
                '\r', '\n' => return st.cmdText(),
                0x7f, 0x08 => {
                    if (st.cmd_len == 0) return null;
                    while (st.cmd_len > 0) {
                        st.cmd_len -= 1;
                        if (!kibi.text.isContinuation(st.cmd_buf[st.cmd_len])) break;
                    }
                },
                0x15 => st.cmd_len = 0,
                '\t' => {
                    if (st.cmd_len < st.cmd_buf.len) {
                        st.cmd_buf[st.cmd_len] = '\t';
                        st.cmd_len += 1;
                    }
                },
                else => {},
            },
            else => {},
        }
    }
    return null;
}

// ---- 探す ----

/// `/`・`?` で語を読む。空なら前の語。読めたら true
fn searchPrompt(prefix: u8) bool {
    const input = readCmdline(prefix) orelse return false;
    // 切り詰めて探さない（文字や `\` の途中で切れて、別の語になるため）。`:s` と同じく断る
    if (input.len > search_buf.len) {
        st.setError("Pattern is too long");
        return false;
    }
    search_backward = prefix == '?';
    if (input.len > 0) {
        search_len = input.len;
        @memcpy(search_buf[0..search_len], input);
    }
    return true;
}

fn compileSearch() bool {
    if (search_len == 0) {
        st.setError("No previous regular expression");
        return false;
    }
    re.compile(search_buf[0..search_len], false, st.ignorecase) catch {
        st.setError("Invalid pattern");
        return false;
    };
    return true;
}

fn patternNotFound() void {
    st.setError("Pattern not found: ");
    st.msgAdd(search_buf[0..search_len]);
}

/// 前の語で探す（[reverse] なら逆の向き）。[n] 回目の一致の位置
fn searchRepeat(reverse: bool, n: usize) ?usize {
    if (!compileSearch()) return null;
    const backward = search_backward != reverse;
    var p = st.cur;
    var wrapped = false;
    var i: usize = 0;
    while (i < n) : (i += 1) {
        const r = (if (backward) findBackward(p) else findForward(p)) orelse {
            patternNotFound();
            return null;
        };
        p = r.pos;
        wrapped = wrapped or r.wrapped;
    }
    if (wrapped) st.setError(if (backward) "search hit TOP, continuing at BOTTOM" else "search hit BOTTOM, continuing at TOP");
    return p;
}

const Found = struct { pos: usize, wrapped: bool };

/// [p] より後ろで最初の一致（終わりまでなければ頭から）
fn findForward(p: usize) ?Found {
    const lines = buffer.lineCount();
    var ls = buffer.lineStart(p);
    // 今の文字の次の文字から探す
    var from = @max(view.nextGlyph(ls, p), p + 1) - ls;
    var k: usize = 0;
    var wrapped = false;
    while (k <= lines) : (k += 1) {
        const le = buffer.lineEnd(ls);
        if (from <= le - ls) {
            if (re.find(buffer.line(ls, le), from)) |m| {
                return .{ .pos = glyphStart(ls + m.start), .wrapped = wrapped };
            }
        }
        if (buffer.nextLine(ls)) |nl| {
            ls = nl;
        } else {
            ls = 0;
            wrapped = true;
        }
        from = 0;
    }
    return null;
}

/// [p] より前で最後の一致（頭までなければ終わりから）
fn findBackward(p: usize) ?Found {
    const lines = buffer.lineCount();
    var ls = buffer.lineStart(p);
    var limit: ?usize = p - ls;
    var k: usize = 0;
    var wrapped = false;
    while (k <= lines) : (k += 1) {
        const le = buffer.lineEnd(ls);
        const l = buffer.line(ls, le);
        var last: ?usize = null;
        var from: usize = 0;
        while (re.find(l, from)) |m| {
            if (limit) |lim| if (m.start >= lim) break;
            last = m.start;
            if (ls + m.start >= le) break;
            from = view.nextGlyph(ls, ls + m.start) - ls;
        }
        if (last) |s| return .{ .pos = glyphStart(ls + s), .wrapped = wrapped };
        if (buffer.prevLine(ls)) |pl| {
            ls = pl;
        } else {
            ls = buffer.lineStart(lastLineStart());
            wrapped = true;
        }
        limit = null;
    }
    return null;
}

fn lastLineStart() usize {
    if (buffer.len == 0) return 0;
    const end = if (buffer.at(buffer.len - 1) == '\n') buffer.len - 1 else buffer.len;
    return buffer.lineStart(end);
}

// ---- 保存 ----

/// [path] を、置き換えずにそのまま書き換える（ADR 0105「保存」。追記 #837）。
/// 切り詰めずに先頭から上書きし、書き終えてから新しい長さに切る。途中で失敗しても、ファイルは空にならない。
/// [allow_grow] が false なら、開いたファイルが今のバッファより短いときは何も書かずに false を返す
/// （書いたら true。書く途中で出たエラーは、短かったときと区別できるよう、そのままエラーで返す）。
/// [allow_grow] が false のとき（一時ファイルに書けなかったときの代わり）は、ファイルがある前提なので作らない。
/// `lstat` の後に消されていたら `NOENT` で返し、元のファイルがなかった所に空のファイルを残さない
fn writeDirect(path: []const u8, allow_grow: bool) kibi.Error!bool {
    const fd = try fs.open(path, .{ .read = false, .write = true, .create = allow_grow });
    const wrote = overwrite(fd, allow_grow) catch |e| {
        io.close(fd) catch {};
        return e;
    };
    try io.close(fd);
    return wrote;
}

fn overwrite(fd: io.Fd, allow_grow: bool) kibi.Error!bool {
    // 大きさは lstat の値でなく、開いたもので確かめ直す（その間に縮められた・置き換えられた、リンクの先）
    const old = (try fs.fstat(fd)).size;
    const len: u64 = buffer.len;
    if (len > old) {
        if (!allow_grow) return false;
        // 先に伸ばす（容量が足りなければ、上書きする前に断られる）
        try fs.setSize(fd, len);
    }
    buffer.writeTo(fd) catch |e| {
        // 伸ばした分（0 で埋まった後ろ）は残さず、元の長さに戻す（戻せなくても、もとのエラーを知らせる）
        if (len > old) fs.setSize(fd, old) catch {};
        return e;
    };
    if (len < old) try fs.setSize(fd, len);
    return true;
}

/// 同じディレクトリの一時ファイルに書いてから、名前を変えて置き換える（nano と同じ手順。ADR 0105「保存」）
fn writeReplace(path: []const u8) kibi.Error!void {
    var abs_buf: [fs.MAX_PATH]u8 = undefined;
    const abs = try fs.absolute(&abs_buf, path);
    var tmp_buf: [fs.MAX_PATH]u8 = undefined;
    const dir = cli.dirname(abs);
    const base = cli.basename(abs);
    var attempt: u8 = 0;
    while (true) : (attempt += 1) {
        // `<dir>/.<名前>.vi<n>`
        var name_buf: [fs.MAX_PATH]u8 = undefined;
        const suffix = ".vi";
        const need = 1 + base.len + suffix.len + 1;
        if (need > name_buf.len) return error.NAMETOOLONG;
        name_buf[0] = '.';
        @memcpy(name_buf[1..][0..base.len], base);
        @memcpy(name_buf[1 + base.len ..][0..suffix.len], suffix);
        name_buf[need - 1] = '0' + attempt;
        const tmp = try cli.join(&tmp_buf, dir, name_buf[0..need]);
        const fd = fs.open(tmp, .{ .read = false, .write = true, .create = true, .exclusive = true }) catch |e| {
            if (e == error.EXIST and attempt < 9) continue;
            return e;
        };
        buffer.writeTo(fd) catch |e| {
            io.close(fd) catch {};
            fs.unlink(tmp) catch {};
            return e;
        };
        io.close(fd) catch |e| {
            fs.unlink(tmp) catch {};
            return e;
        };
        fs.rename(tmp, abs) catch |e| {
            fs.unlink(tmp) catch {};
            return e;
        };
        return;
    }
}

/// `"名前" NL, NB` を知らせに足す
fn msgFileInfo(path: []const u8) void {
    st.msgAdd("\"");
    st.msgAdd(path);
    st.msgAdd("\" ");
    st.msgNum(if (buffer.len == 0) 0 else buffer.lineCount());
    st.msgAdd("L, ");
    st.msgNum(buffer.len);
    st.msgAdd("B");
}

/// [path] に書く。書けたら true（書けなければ知らせを出し、バッファはそのまま）
fn save(path: []const u8) bool {
    var link = false;
    var old_size: ?u64 = null;
    if (fs.lstat(path)) |s| {
        if (s.kind == .directory) return saveFailed(path, error.ISDIR);
        link = s.kind == .symlink;
        old_size = s.size;
    } else |e| {
        if (e != error.NOENT) return saveFailed(path, e);
    }
    if (link) {
        // シンボリックリンクは置き換えると切れるので、リンクの先にそのまま書く
        _ = writeDirect(path, true) catch |e| return saveFailed(path, e);
    } else {
        writeReplace(path) catch |e| {
            // 一時ファイルの分の容量・名前がないとき、元より大きくならないなら上書きで書く
            const fallback = (e == error.NOSPC or e == error.DQUOT or e == error.NAMETOOLONG) and
                old_size != null and buffer.len <= old_size.?;
            if (!fallback) return saveFailed(path, e);
            // 開いたものが短くて何も書かなかったときだけ、一時ファイルで出たエラーを知らせる。
            // 書く途中で出たエラー（`NOSPC` を含む）は、ファイルが書き換わっているので、そのまま知らせる
            const wrote = writeDirect(path, false) catch |e2| return saveFailed(path, e2);
            if (!wrote) return saveFailed(path, e);
        };
    }
    st.clearMsg();
    msgFileInfo(path);
    st.msgAdd(" written");
    return true;
}

fn saveFailed(path: []const u8, e: kibi.Error) bool {
    st.setError("Error writing ");
    st.msgAdd(path);
    st.msgAdd(": ");
    st.msgAdd(kibi.errno.describe(e));
    return false;
}

/// `:w [名前]`。[force] は `!`。書けたら true
fn writeCommand(arg: []const u8, force: bool) bool {
    var path_buf: [fs.MAX_PATH]u8 = undefined;
    var path: []const u8 = undefined;
    if (arg.len > 0) {
        @memcpy(path_buf[0..arg.len], arg);
        path = path_buf[0..arg.len];
        if (!cli.eql(path, st.name()) and !force) {
            if (fs.stat(path)) |_| {
                st.setError("File exists (add ! to override)");
                return false;
            } else |_| {}
        }
    } else {
        if (st.name_len == 0) {
            st.setError("No file name");
            return false;
        }
        @memcpy(path_buf[0..st.name_len], st.name());
        path = path_buf[0..st.name_len];
    }
    if (!save(path)) return false;
    // 名前のないバッファは、書いた名前にする。別の名前に書いたときは、今の名前のまま
    if (st.name_len == 0) st.setName(path);
    if (cli.eql(path, st.name())) st.modified = false;
    return true;
}

/// 入力が終わったとき、保存していない編集を `<名前>.save` に残す（nano と同じ）
fn emergencySave() void {
    if (!st.modified) return;
    var buf: [fs.MAX_PATH]u8 = undefined;
    const base: []const u8 = if (st.name_len > 0) st.name() else "vi";
    const suffix = ".save";
    if (base.len + suffix.len > buf.len) return;
    @memcpy(buf[0..base.len], base);
    @memcpy(buf[base.len..][0..suffix.len], suffix);
    const stem = base.len + suffix.len;
    var n: u32 = 0;
    while (n <= 999) : (n += 1) {
        var len = stem;
        if (n > 0) {
            var digits: [4]u8 = undefined;
            var d: usize = digits.len;
            var v = n;
            while (v > 0) : (v /= 10) {
                d -= 1;
                digits[d] = '0' + @as(u8, @intCast(v % 10));
            }
            const dl = digits.len - d;
            if (stem + 1 + dl > buf.len) return;
            buf[stem] = '.';
            @memcpy(buf[stem + 1 ..][0..dl], digits[d..]);
            len = stem + 1 + dl;
        }
        const fd = fs.open(buf[0..len], .{ .read = false, .write = true, .create = true, .exclusive = true }) catch |e| {
            if (e == error.EXIST) continue;
            return;
        };
        defer io.close(fd) catch {};
        buffer.writeTo(fd) catch {};
        return;
    }
}

// ---- ex のコマンド ----

fn trim(s: []const u8) []const u8 {
    var a: usize = 0;
    var b = s.len;
    while (a < b and (s[a] == ' ' or s[a] == '\t')) a += 1;
    while (b > a and (s[b - 1] == ' ' or s[b - 1] == '\t')) b -= 1;
    return s[a..b];
}

/// 行の番号（`N`・`.`・`$`。0 から）を読む
fn parseAddress(s: []const u8, i: *usize) ?usize {
    if (i.* >= s.len) return null;
    const c = s[i.*];
    if (c == '.') {
        i.* += 1;
        return st.cur_line;
    }
    if (c == '$') {
        i.* += 1;
        return buffer.lineCount() - 1;
    }
    if (c >= '0' and c <= '9') {
        var v: usize = 0;
        while (i.* < s.len and s[i.*] >= '0' and s[i.*] <= '9') : (i.* += 1) v = v *| 10 +| (s[i.*] - '0');
        return @min(v, buffer.lineCount()) -| 1;
    }
    return null;
}

const Range = struct { first: usize, last: usize, given: bool };

fn exCommand(input: []const u8) void {
    var s = trim(input);
    while (s.len > 0 and s[0] == ':') s = trim(s[1..]);
    if (s.len == 0) return;
    var i: usize = 0;
    var range: Range = .{ .first = st.cur_line, .last = st.cur_line, .given = false };
    if (s[0] == '%') {
        range = .{ .first = 0, .last = buffer.lineCount() - 1, .given = true };
        i = 1;
    } else if (parseAddress(s, &i)) |a| {
        range = .{ .first = a, .last = a, .given = true };
        if (i < s.len and s[i] == ',') {
            i += 1;
            const b = parseAddress(s, &i) orelse {
                st.setError("Invalid range");
                return;
            };
            range.last = b;
        }
    }
    if (range.first > range.last) {
        const t = range.first;
        range.first = range.last;
        range.last = t;
    }
    const rest = s[i..];
    if (rest.len == 0) {
        // 行の番号だけ: その行へ
        if (range.given) setCursor(firstNonBlank(lineStartOf(range.last)), true);
        return;
    }
    // コマンドの名前（英字）と `!`、引数
    var j: usize = 0;
    while (j < rest.len and ((rest[j] >= 'a' and rest[j] <= 'z') or (rest[j] >= 'A' and rest[j] <= 'Z'))) j += 1;
    const name = rest[0..j];
    var force = false;
    if (j < rest.len and rest[j] == '!') {
        force = true;
        j += 1;
    }
    const arg = trim(rest[j..]);
    if (cli.eql(name, "s") or cli.eql(name, "substitute")) {
        if (force) return notCommand(rest);
        return substitute(range, rest[j..]);
    }
    if (range.given and !(cli.eql(name, "d") or cli.eql(name, "delete"))) {
        st.setError("No range allowed");
        return;
    }
    if (cli.eql(name, "w") or cli.eql(name, "write")) {
        _ = writeCommand(arg, force);
    } else if (cli.eql(name, "wq") or cli.eql(name, "x") or cli.eql(name, "xit") or cli.eql(name, "exit")) {
        const always = name[0] == 'w';
        if (always or st.modified or arg.len > 0) {
            if (!writeCommand(arg, force)) return;
        }
        quit = true;
    } else if (cli.eql(name, "q") or cli.eql(name, "quit")) {
        if (st.modified and !force) {
            st.setError("No write since last change (add ! to override)");
            return;
        }
        quit = true;
    } else if (cli.eql(name, "e") or cli.eql(name, "edit")) {
        if (!force or arg.len > 0) {
            st.setError("Only :e! (reload the file) is supported");
            return;
        }
        reload();
    } else if (cli.eql(name, "d") or cli.eql(name, "delete")) {
        if (arg.len > 0 or force) return notCommand(rest);
        const a = lineStartOf(range.first);
        const b = lineStartOf(range.last);
        setCursor(a, false);
        operate(.delete, .{ .pos = b, .linewise = true });
    } else if (cli.eql(name, "set") or cli.eql(name, "se")) {
        setOption(arg);
    } else if (cli.eql(name, "help") or cli.eql(name, "h")) {
        st.setMsg("Type :q to quit. See \"man vi\" for the commands");
    } else {
        notCommand(rest);
    }
}

fn notCommand(s: []const u8) void {
    st.setError("Not an editor command: ");
    st.msgAdd(s);
}

fn setOption(arg: []const u8) void {
    if (arg.len == 0) {
        st.setMsg(if (st.number) "number" else "nonumber");
        st.msgAdd(if (st.ignorecase) " ignorecase" else " noignorecase");
        return;
    }
    if (cli.eql(arg, "nu") or cli.eql(arg, "number")) {
        st.number = true;
    } else if (cli.eql(arg, "nonu") or cli.eql(arg, "nonumber")) {
        st.number = false;
    } else if (cli.eql(arg, "ic") or cli.eql(arg, "ignorecase")) {
        st.ignorecase = true;
    } else if (cli.eql(arg, "noic") or cli.eql(arg, "noignorecase")) {
        st.ignorecase = false;
    } else {
        st.setError("Unknown option: ");
        st.msgAdd(arg);
        return;
    }
    view.invalidate();
}

/// `:e!`: ファイルを読み直す（変えたものを捨てる）
fn reload() void {
    if (st.name_len == 0) {
        st.setError("No file name");
        return;
    }
    const path = st.name();
    const line = st.cur_line;
    if (fs.stat(path)) |s| {
        if (s.kind == .directory or s.size > buffer.MAX_LEN) {
            st.setError("Cannot reload the file");
            return;
        }
        const fd = fs.open(path, .{}) catch |e| {
            st.setError(kibi.errno.describe(e));
            return;
        };
        defer io.close(fd) catch {};
        buffer.load(fd) catch {
            st.setError("Cannot reload the file; the buffer is now empty");
            resetPosition();
            undo.reset();
            st.modified = true;
            return;
        };
    } else |e| {
        // ないときだけ空の新しいファイルにする（起動と保存と同じ）。読めないときはバッファに触らない
        if (e != error.NOENT) {
            st.setError(kibi.errno.describe(e));
            return;
        }
        buffer.clear();
    }
    undo.reset();
    resetPosition();
    st.modified = false;
    setCursor(firstNonBlank(lineStartOf(@min(line, buffer.lineCount() - 1))), true);
    st.clearMsg();
    msgFileInfo(path);
    view.invalidate();
}

fn resetPosition() void {
    st.cur = 0;
    st.cur_line = 0;
    st.top = 0;
    st.top_line = 0;
    st.want_dirty = true;
}

/// 置き換えの文字列（`&` は一致したもの）
var rep_buf: [4096]u8 = undefined;

/// `:s/語/置き換え/[g]`
fn substitute(range: Range, spec: []const u8) void {
    if (spec.len == 0) {
        st.setError("Only :s/pattern/replacement/[g] is supported");
        return;
    }
    const delim = spec[0];
    if ((delim >= 'a' and delim <= 'z') or (delim >= 'A' and delim <= 'Z') or (delim >= '0' and delim <= '9') or delim == ' ' or delim == '\\') {
        st.setError("Invalid delimiter");
        return;
    }
    // 語（`\` + 区切りは区切りの文字）
    var pat_buf: [256]u8 = undefined;
    var pl: usize = 0;
    var i: usize = 1;
    while (i < spec.len and spec[i] != delim) : (i += 1) {
        var c = spec[i];
        if (c == '\\' and i + 1 < spec.len) {
            i += 1;
            if (spec[i] != delim) {
                if (pl < pat_buf.len) pat_buf[pl] = '\\';
                pl += 1;
            }
            c = spec[i];
        }
        if (pl < pat_buf.len) pat_buf[pl] = c;
        pl += 1;
    }
    if (pl > pat_buf.len) {
        st.setError("Pattern is too long");
        return;
    }
    i += 1;
    const rep_start = i;
    while (i < spec.len and spec[i] != delim) : (i += 1) {
        if (spec[i] == '\\' and i + 1 < spec.len) i += 1;
    }
    const rep = spec[@min(rep_start, spec.len)..@min(i, spec.len)];
    var global = false;
    if (i < spec.len) {
        for (spec[i + 1 ..]) |f| switch (f) {
            'g' => global = true,
            ' ' => {},
            else => {
                st.setError("Unsupported flag");
                return;
            },
        };
    }
    if (pl > 0) {
        search_len = pl;
        @memcpy(search_buf[0..pl], pat_buf[0..pl]);
    }
    if (!compileSearch()) return;

    var subs: usize = 0;
    var aborted = false;
    var lines_changed: usize = 0;
    var ls = lineStartOf(range.first);
    var line_no = range.first;
    var last_line_start: usize = ls;
    outer: while (line_no <= range.last) : (line_no += 1) {
        var le = buffer.lineEnd(ls);
        var from: usize = 0;
        var changed = false;
        // 直前の一致（空でないもの）の終わり。そこにつながる空の一致は置き換えない（vim・sed と同じ）
        var prev_end: ?usize = null;
        while (from <= le - ls) {
            const m = re.find(buffer.line(ls, le), from) orelse break;
            const empty = m.end == m.start;
            if (empty and prev_end != null and prev_end.? == m.start) {
                if (ls + m.start >= le) break;
                from = view.nextGlyph(ls, ls + m.start) - ls;
                prev_end = null;
                continue;
            }
            const ma = ls + m.start;
            const mb = ls + m.end;
            // 置き換えを作る
            var rl: usize = 0;
            var k: usize = 0;
            while (k < rep.len) : (k += 1) {
                var c = rep[k];
                if (c == '\\' and k + 1 < rep.len) {
                    k += 1;
                    c = rep[k];
                    if (c == 'r' or c == 'n') c = '\n';
                } else if (c == '&') {
                    const ml = mb - ma;
                    if (rl + ml > rep_buf.len) {
                        st.setError("Replacement is too long");
                        aborted = true;
                        break :outer;
                    }
                    _ = buffer.copy(ma, rep_buf[rl..][0..ml]);
                    rl += ml;
                    continue;
                }
                if (rl >= rep_buf.len) {
                    st.setError("Replacement is too long");
                    aborted = true;
                    break :outer;
                }
                rep_buf[rl] = c;
                rl += 1;
            }
            if (rl > mb - ma) {
                buffer.reserve(rl - (mb - ma)) catch |e| {
                    reportFull(e);
                    aborted = true;
                    break :outer;
                };
            }
            edRemove(ma, mb);
            if (!edInsert(ma, rep_buf[0..rl])) {
                aborted = true;
                break :outer;
            }
            le = le - (mb - ma) + rl;
            subs += 1;
            changed = true;
            from = m.start + rl;
            prev_end = if (empty) null else from;
            if (empty) {
                // 空の一致: 1 文字進める
                if (ls + from >= le) break;
                from = view.nextGlyph(ls, ls + from) - ls;
            }
            if (!global) break;
        }
        // 置き換えに改行が入っても、元の行の終わり（[le]）の次の行へ進む
        const end_start = buffer.lineStart(le);
        if (changed) {
            lines_changed += 1;
            last_line_start = end_start;
        }
        ls = buffer.nextLine(end_start) orelse break;
    }
    if (subs == 0) {
        if (st.msg_len == 0) patternNotFound();
        return;
    }
    setCursor(firstNonBlank(buffer.lineStart(last_line_start)), true);
    // 途中でやめたときは、その理由の知らせを残す
    if (aborted) return;
    if (lines_changed > 1 or subs > 1) {
        st.clearMsg();
        st.msgNum(subs);
        st.msgAdd(if (subs == 1) " substitution on " else " substitutions on ");
        st.msgNum(lines_changed);
        st.msgAdd(if (lines_changed == 1) " line" else " lines");
    }
}

// ---- 画面を送る ----

/// 画面のいちばん上の行を [n] 行動かす（[down] なら後ろへ）。カーソルが外に出たら中へ入れる
fn scrollLines(down: bool, n: usize) void {
    // 前のキーで動いたカーソルに、まず画面を合わせる（同じ入力の中で gg の後に Ctrl+F など）
    view.adjustTop();
    const r = lineFrom(st.top, !down, n);
    if (down) {
        // 最後の行より後ろには送らない
        st.top = r.start;
        st.top_line += r.moved;
    } else {
        st.top = r.start;
        st.top_line -= r.moved;
    }
    const tr = view.textRows();
    if (st.cur_line < st.top_line) {
        setCursor(firstNonBlank(st.top), false);
    } else if (st.cur_line >= st.top_line + tr) {
        setCursor(firstNonBlank(lineFrom(st.top, false, tr - 1).start), false);
    }
}

/// Ctrl+D・Ctrl+U: 半画面、画面とカーソルを一緒に動かす
fn scrollHalf(down: bool, count: usize) void {
    view.adjustTop();
    const n = if (count > 0) count else @max(1, view.textRows() / 2);
    const r = lineFrom(buffer.lineStart(st.cur), !down, n);
    if (r.moved == 0) return;
    const t = lineFrom(st.top, !down, r.moved);
    st.top = t.start;
    if (down) st.top_line += t.moved else st.top_line -= t.moved;
    setCursor(firstNonBlank(r.start), false);
}

/// Ctrl+F・Ctrl+B: 1 画面（2 行を残す）
fn scrollPage(down: bool, count: usize) void {
    const n = @max(count, 1) *| @max(1, view.textRows() -| 2);
    scrollLines(down, n);
}

// ---- normal ----

fn showInfo() void {
    st.clearMsg();
    st.msgAdd("\"");
    st.msgAdd(if (st.name_len > 0) st.name() else "[No Name]");
    st.msgAdd("\"");
    if (st.modified) st.msgAdd(" [Modified]");
    const lines = buffer.lineCount();
    st.msgAdd(" line ");
    st.msgNum(st.cur_line + 1);
    st.msgAdd(" of ");
    st.msgNum(lines);
    st.msgAdd(" --");
    st.msgNum((st.cur_line + 1) * 100 / lines);
    st.msgAdd("%-- col ");
    st.msgNum(view.columnOf(buffer.lineStart(st.cur), st.cur) + 1);
}

/// normal の 1 コマンド（数の後ろのキー [k] から）
fn command(k: Key, count: usize) void {
    const n = @max(count, 1);
    switch (k.kind) {
        .page_down => return scrollPage(true, count),
        .page_up => return scrollPage(false, count),
        .delete => {
            change_cmd = true;
            return deleteChars(false, count);
        },
        .insert => {
            change_cmd = true;
            return insertMode(n, .plain);
        },
        .ctrl => switch (k.c) {
            0x06 => return scrollPage(true, count),
            0x02 => return scrollPage(false, count),
            0x04 => return scrollHalf(true, count),
            0x15 => return scrollHalf(false, count),
            0x05 => return scrollLines(true, n),
            0x19 => return scrollLines(false, n),
            0x07 => return showInfo(),
            0x0c => return view.invalidate(),
            0x12 => {
                var i: usize = 0;
                while (i < n) : (i += 1) if (!applyRedo()) break;
                return;
            },
            0x03 => {
                st.setMsg(if (st.modified) "Type :q! and press Enter to quit without saving" else "Type :q and press Enter to quit");
                return;
            },
            else => {},
        },
        else => {},
    }
    if (charOf(k)) |c| switch (c) {
        'i', 'a', 'I', 'A', 'o', 'O' => {
            change_cmd = true;
            const s = buffer.lineStart(st.cur);
            const e = buffer.lineEnd(st.cur);
            switch (c) {
                'a' => if (st.cur < e) setCursor(view.nextGlyph(s, st.cur), false),
                'A' => setCursor(e, false),
                'I' => setCursor(firstNonBlank(s), false),
                'o' => {
                    if (!edInsert(e, "\n")) return;
                    setCursor(e + 1, false);
                    return insertMode(n, .open_below);
                },
                'O' => {
                    if (!edInsert(s, "\n")) return;
                    setCursor(s, false);
                    return insertMode(n, .open_above);
                },
                else => {},
            }
            return insertMode(n, .plain);
        },
        'd', 'c', 'y' => {
            if (c != 'y') change_cmd = true;
            return operator(if (c == 'd') .delete else if (c == 'c') .change else .yank, c, count);
        },
        'D', 'C' => {
            change_cmd = true;
            const m = motion(textKey('$'), count, true) orelse return;
            if (buffer.lineEnd(st.cur) == buffer.lineStart(st.cur)) {
                if (c == 'C') insertMode(1, .plain);
                return;
            }
            return operate(if (c == 'D') .delete else .change, m);
        },
        'Y' => {
            const r = lineFrom(buffer.lineStart(st.cur), false, n - 1);
            const saved = st.cur;
            operate(.yank, .{ .pos = r.start, .linewise = true });
            setCursor(saved, false);
            return;
        },
        's' => {
            change_cmd = true;
            const s = buffer.lineStart(st.cur);
            const e = buffer.lineEnd(st.cur);
            var b = st.cur;
            var i: usize = 0;
            while (i < n and b < e) : (i += 1) b = view.nextGlyph(s, b);
            if (b > st.cur) {
                if (!yankRange(st.cur, b, false, false)) return;
                edRemove(st.cur, b);
            }
            return insertMode(1, .plain);
        },
        'S' => {
            change_cmd = true;
            const r = lineFrom(buffer.lineStart(st.cur), false, n - 1);
            return operate(.change, .{ .pos = r.start, .linewise = true });
        },
        'x' => {
            change_cmd = true;
            return deleteChars(false, count);
        },
        'X' => {
            change_cmd = true;
            return deleteChars(true, count);
        },
        'p', 'P' => {
            change_cmd = true;
            return put(c == 'p', count);
        },
        'r' => {
            change_cmd = true;
            return replaceChars(count);
        },
        'J' => {
            change_cmd = true;
            return joinLines(count);
        },
        '~' => {
            change_cmd = true;
            return toggleCase(count);
        },
        'u' => {
            var i: usize = 0;
            while (i < n) : (i += 1) if (!applyUndo()) break;
            return;
        },
        '.' => return repeatLast(count),
        ':' => {
            const line = readCmdline(':') orelse return;
            var copy_buf: [fs.MAX_PATH]u8 = undefined;
            @memcpy(copy_buf[0..line.len], line);
            return exCommand(copy_buf[0..line.len]);
        },
        'Z' => {
            const k2 = nextKey() orelse return;
            if (isChar(k2, 'Z')) return exCommand("x");
            if (isChar(k2, 'Q')) return exCommand("q!");
            return;
        },
        else => {},
    };
    if (motion(k, count, false)) |m| applyMotion(m);
}

/// `.`: 前に変えたコマンドを、覚えたキーで繰り返す
fn repeatLast(count: usize) void {
    if (dot_len == 0) {
        st.setError("No previous command to repeat");
        return;
    }
    replaying = true;
    replay_pos = 1;
    defer {
        replaying = false;
        change_cmd = false;
    }
    command(dot[0], if (count > 0) count else dot_count);
}

/// normal のキー [first] から 1 コマンドを読んで当てる
fn normal(first: Key) void {
    var k = first;
    var count: usize = 0;
    while (digitOf(k)) |d| {
        if (d == 0 and count == 0) break;
        count = count *| 10 +| d;
        k = nextKey() orelse return;
    }
    undo.begin(st.cur);
    tent[0] = k;
    tent_len = 1;
    tent_over = false;
    recording = true;
    change_cmd = false;
    command(k, count);
    recording = false;
    if (change_cmd) {
        if (tent_over) {
            dot_len = 0;
        } else {
            @memcpy(dot[0..tent_len], tent[0..tent_len]);
            dot_len = tent_len;
            dot_count = count;
        }
    }
}

// ---- 始まり ----

fn openFile(path: []const u8) u8 {
    const s = fs.stat(path) catch |e| {
        if (e == error.NOENT) {
            st.clearMsg();
            st.msgAdd("\"");
            st.msgAdd(path);
            st.msgAdd("\" [New]");
            return 0;
        }
        cli.warnErr(path, e);
        return 1;
    };
    if (s.kind == .directory) {
        cli.warnErr(path, error.ISDIR);
        return 1;
    }
    const fd = fs.open(path, .{}) catch |e| {
        cli.warnErr(path, e);
        return 1;
    };
    defer io.close(fd) catch {};
    buffer.load(fd) catch |e| {
        switch (e) {
            error.TooBig => cli.warn(path, "file is too large (the limit is 4 MiB)"),
            error.OutOfMemory => cli.warn(path, "not enough memory to open the file"),
            else => |x| cli.warnErr(path, x),
        }
        return 1;
    };
    st.clearMsg();
    msgFileInfo(path);
    return 0;
}

pub fn main() u8 {
    cli.setName("vi");
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var file: ?[]const u8 = null;
    var start_line: ?u64 = null;
    var options = true;
    for (argv[1..]) |a| {
        if (options and cli.eql(a, "--")) {
            options = false;
        } else if (options and a.len >= 1 and a[0] == '+') {
            start_line = if (a.len == 1) ~@as(u64, 0) else io.parseUnsigned(a[1..]) orelse return cli.usage("invalid line number", SYNOPSIS);
        } else if (options and a.len > 1 and a[0] == '-') {
            if (cli.eql(a, "-h") or cli.eql(a, "--help")) {
                io.out("usage: " ++ SYNOPSIS ++ "\n");
                return 0;
            }
            return cli.badOption(if (a[1] == '-') '-' else a[1], SYNOPSIS);
        } else {
            if (file != null) return cli.usage("too many files", SYNOPSIS);
            file = a;
        }
    }
    if (!tty.isTty(io.STDIN) or !tty.isTty(io.STDOUT)) {
        cli.warn(null, "standard input and output must be a terminal");
        return 1;
    }
    if (file) |path| {
        const r = openFile(path);
        if (r != 0) return r;
        st.setName(path);
    }
    if (start_line) |l| {
        const idx = @min(@max(l, 1), buffer.lineCount()) - 1;
        setCursor(firstNonBlank(lineStartOf(@intCast(idx))), true);
    }

    const saved = tty.getMode(io.STDIN) catch tty.Mode.cooked;
    tty.setMode(io.STDIN, .{ .nl_to_crnl = true }) catch {};
    defer tty.setMode(io.STDIN, saved) catch {};
    view.enter();
    defer view.leave();
    restored = kibi.events.openRestored();
    defer closeRestored();

    while (!quit) {
        const k = nextKey() orelse break;
        st.clearMsg();
        normal(k);
        if (undo.lost) |m| {
            st.setError(m);
            undo.lost = null;
        }
        clampNormal();
    }
    if (eof) emergencySave();
    return 0;
}
