//! 画面に描く（docs/adr/0105-nano.md の「表示」）。
//!
//! 画面は、1 行目がタイトル、その下が本文、状態の行、いちばん下の 2 行がキーの案内。
//! 行ごとに中身を作ってハッシュを取り、前に描いたものと違う行だけを書く（キーごとに全画面を書かない）。
//! 本文が縦に動いたときは、スクロール領域と `CSI S`・`CSI T` で動かしてから、違う行だけを書く。
//! 右端での折り返しは切る（`CSI ?7l`）。ファイルの中の制御文字は `^X` の形にして、端末に生のまま送らない。
const kibi = @import("kibi");
const buffer = @import("buffer.zig");
const st = @import("state.zig");

const io = kibi.io;
const text = kibi.text;
const tty = kibi.tty;

/// タブの幅
pub const TAB = 8;

/// 扱う画面の大きさの上限（これより大きい画面では、左上のこの範囲だけを使う）
const MAX_COLS = 256;
const MAX_ROWS = 128;

pub var cols: usize = 80;
pub var rows: usize = 25;
var generation: ?u64 = null;

/// 各行に今出ているもののハッシュ（0 = わからない）
var shown: [MAX_ROWS]u64 = @splat(0);
/// 前に描いたときの本文のいちばん上の行（スクロールで動かすため）
var shown_top: ?usize = null;
var full: bool = true;

// ---- 出力 ----

var obuf: [16384]u8 = undefined;
var olen: usize = 0;

fn out(s: []const u8) void {
    var rest = s;
    while (rest.len > 0) {
        if (olen == obuf.len) flush();
        const k = @min(rest.len, obuf.len - olen);
        @memcpy(obuf[olen..][0..k], rest[0..k]);
        olen += k;
        rest = rest[k..];
    }
}

fn outNum(v: usize) void {
    var b: [20]u8 = undefined;
    out(io.formatUnsigned(&b, v));
}

pub fn flush() void {
    if (olen == 0) return;
    io.writeAll(io.STDOUT, obuf[0..olen]) catch {};
    olen = 0;
}

fn moveTo(y: usize, x: usize) void {
    out("\x1b[");
    outNum(y + 1);
    out(";");
    outNum(x + 1);
    out("H");
}

// ---- 1 行分の中身 ----

var row: [MAX_COLS * 8 + 64]u8 = undefined;
var rlen: usize = 0;
/// この行で使ったセルの数
var used: usize = 0;
/// 入力欄の入力の後ろの桁（[drawStatus] が行を埋める前に控える）
var prompt_end: usize = 0;

fn put(s: []const u8) void {
    const n = @min(s.len, row.len - rlen);
    @memcpy(row[rlen..][0..n], s[0..n]);
    rlen += n;
}

fn putByte(c: u8) void {
    put(&[_]u8{c});
}

fn spaces(n: usize) void {
    var i: usize = 0;
    while (i < n) : (i += 1) putByte(' ');
    used += n;
}

fn hash(s: []const u8) u64 {
    var h: u64 = 0xcbf29ce484222325;
    for (s) |c| h = (h ^ c) *% 0x100000001b3;
    return h | 1;
}

/// [y] 行目を、作った中身にする（前と同じなら書かない）
fn commitRow(y: usize) void {
    if (y >= rows) {
        // 画面の外の行は捨てる（作りかけの中身を次の行へ持ち越さない）
        rlen = 0;
        used = 0;
        return;
    }
    const h = hash(row[0..rlen]) ^ @as(u64, used);
    if (shown[y] != h) {
        moveTo(y, 0);
        out(row[0..rlen]);
        if (used < cols) out("\x1b[K");
        shown[y] = h;
    }
    rlen = 0;
    used = 0;
}

// ---- テキストの文字（グリフ） ----

pub const Glyph = struct {
    kind: enum { end, text, ctrl, tab, bad },
    /// テキストのバイト数
    len: usize,
    width: usize,
};

/// [pos] の文字（[col] は表示の桁。タブの幅に使う）。行の終わり（改行・テキストの終わり）なら end。
/// 結合文字など幅のない文字は、前の文字にまとめる
pub fn glyph(pos: usize, col: usize) Glyph {
    if (pos >= buffer.len) return .{ .kind = .end, .len = 0, .width = 0 };
    const c = buffer.at(pos);
    if (c == '\n') return .{ .kind = .end, .len = 0, .width = 0 };
    if (c == '\t') return .{ .kind = .tab, .len = 1, .width = TAB - col % TAB };
    if (c < 0x20 or c == 0x7f) return .{ .kind = .ctrl, .len = 1, .width = 2 };
    if (c < 0x80) return .{ .kind = .text, .len = 1, .width = 1 };
    var b: [4]u8 = undefined;
    const n = buffer.copy(pos, &b);
    const cp = text.decode(b[0..n]) orelse return .{ .kind = .bad, .len = 1, .width = 1 };
    const sl = text.sequenceLength(c);
    // C1 の制御文字は端末が読み捨てるので、置き換えの文字で見せる
    if (cp < 0xa0) return .{ .kind = .bad, .len = sl, .width = 1 };
    var w: usize = text.width(cp);
    // 前に文字のない、幅のない文字は 1 セルで見せる
    if (w == 0) return .{ .kind = .bad, .len = sl, .width = 1 };
    var len: usize = sl;
    while (pos + len < buffer.len) {
        const m = buffer.copy(pos + len, &b);
        const next = text.decode(b[0..m]) orelse break;
        if (next < 0xa0 or text.width(next) != 0) break;
        len += text.sequenceLength(b[0]);
    }
    if (w > 2) w = 2;
    return .{ .kind = .text, .len = len, .width = w };
}

fn putGlyph(pos: usize, g: Glyph) void {
    switch (g.kind) {
        .end => {},
        .text => {
            var i: usize = 0;
            while (i < g.len) : (i += 1) putByte(buffer.at(pos + i));
            used += g.width;
        },
        .ctrl => {
            const c = buffer.at(pos);
            putByte('^');
            putByte(if (c == 0x7f) '?' else c + 0x40);
            used += 2;
        },
        .tab => spaces(g.width),
        .bad => {
            put(text.REPLACEMENT);
            used += 1;
        },
    }
}

/// 行の頭 [start] から [pos] までの表示の幅
pub fn columnOf(start: usize, pos: usize) usize {
    var p = start;
    var col: usize = 0;
    while (p < pos) {
        const g = glyph(p, col);
        if (g.kind == .end) break;
        p += g.len;
        col += g.width;
    }
    return col;
}

/// 行の頭 [start] から、表示の桁 [want] にある文字の位置（行が短ければ行の終わり）
pub fn positionAt(start: usize, want: usize) usize {
    var p = start;
    var col: usize = 0;
    while (true) {
        const g = glyph(p, col);
        if (g.kind == .end or col + g.width > want) return p;
        p += g.len;
        col += g.width;
    }
}

/// [pos] の前の文字の位置（行の頭なら [pos]）
pub fn prevGlyph(start: usize, pos: usize) usize {
    var p = start;
    var col: usize = 0;
    var last = start;
    while (p < pos) {
        const g = glyph(p, col);
        if (g.kind == .end) break;
        last = p;
        p += g.len;
        col += g.width;
    }
    return last;
}

/// 横に送る量（カーソルの桁 [cc] が見えるように。nano と同じく、送った行の頭には `$` を置く）
fn hscrollFor(cc: usize) usize {
    if (cols < 4 or cc + 1 < cols) return 0;
    const chunk = if (cols > 16) cols - 8 else cols / 2;
    return ((cc + 1 - cols) / chunk + 1) * chunk;
}

/// 本文の 1 行を描く。[h] は横に送った量
fn drawLine(start: usize, h: usize) void {
    var p = start;
    var col: usize = 0;
    const vis0: usize = if (h > 0) h + 1 else 0;
    if (h > 0) {
        putByte('$');
        used += 1;
    }
    while (true) {
        const g = glyph(p, col);
        if (g.kind == .end) return;
        if (col + g.width <= vis0) {
            // 左に隠れる
        } else if (col < vis0) {
            // 全角の文字が左の境目にかかる
            spaces(col + g.width - vis0);
        } else {
            const x = col - h;
            if (x + g.width > cols - 1) {
                // 右端に入らない: これが最後の文字でちょうど入るなら書き、そうでなければ右端に `$`
                const last = glyph(p + g.len, col + g.width).kind == .end;
                if (last and x + g.width <= cols) {
                    putGlyph(p, g);
                } else {
                    spaces(cols -| (used + 1));
                    putByte('$');
                    used += 1;
                }
                return;
            }
            putGlyph(p, g);
        }
        p += g.len;
        col += g.width;
    }
}

/// どんな文字列でも、端末を動かさない形で最大 [max] セル書く（ファイルの名前・検索の語など）。書いたセル数
fn putSafe(s: []const u8, max: usize) usize {
    var i: usize = 0;
    var w: usize = 0;
    while (i < s.len) {
        const c = s[i];
        var n: usize = 1;
        var cw: usize = 1;
        var bytes: []const u8 = s[i .. i + 1];
        var ctrl = false;
        if (c < 0x20 or c == 0x7f) {
            ctrl = true;
            cw = 2;
        } else if (c >= 0x80) {
            if (text.decode(s[i..@min(s.len, i + 4)])) |cp| {
                n = text.sequenceLength(c);
                bytes = s[i .. i + n];
                cw = text.width(cp);
                if (cp < 0xa0 or cw == 0) {
                    bytes = text.REPLACEMENT;
                    cw = 1;
                }
            } else {
                bytes = text.REPLACEMENT;
            }
        }
        if (w + cw > max) break;
        if (ctrl) {
            putByte('^');
            putByte(if (c == 0x7f) '?' else c + 0x40);
        } else put(bytes);
        w += cw;
        i += n;
    }
    used += w;
    return w;
}

/// [s] を [putSafe] で書いたときのセル数
fn safeWidth(s: []const u8) usize {
    const save_r = rlen;
    const save_u = used;
    const w = putSafe(s, MAX_COLS * 4);
    rlen = save_r;
    used = save_u;
    return w;
}

// ---- 画面の割り付け ----

fn helpRows() usize {
    return if (rows >= 8) 2 else 0;
}

/// 状態の行
fn statusRow() usize {
    return if (rows >= 2) rows - 1 - helpRows() else 0;
}

/// 本文の行の数
pub fn textRows() usize {
    const reserved = 2 + helpRows();
    return if (rows > reserved) rows - reserved else 1;
}

/// 大きさを読み直す（変わっていれば全体を描き直す）
fn updateSize() void {
    const s = tty.getSize(io.STDOUT) catch return;
    if (generation != null and generation.? == s.generation) return;
    generation = s.generation;
    cols = @max(1, @min(s.columns, MAX_COLS));
    rows = @max(1, @min(s.rows, MAX_ROWS));
    full = true;
}

/// 画面に入る（代替画面・折り返しなし）
pub fn enter() void {
    out("\x1b[?1049h\x1b[?7l");
    full = true;
}

/// 画面から出る（変えた設定を全部戻す。1049 はカーソルしか戻さないため）
pub fn leave() void {
    out("\x1b[r\x1b[0m\x1b[?7h\x1b[?25h\x1b[?1049l");
    flush();
}

/// 次に描くとき、全体を描き直す
pub fn invalidate() void {
    full = true;
}

/// カーソルが本文の中に見えるよう、いちばん上の行を決める
fn adjustTop() void {
    const tr = textRows();
    if (st.cur_line >= st.top_line and st.cur_line < st.top_line + tr) {
        st.center = false;
        return;
    }
    const back: usize = if (st.center) tr / 2 else if (st.cur_line < st.top_line) 0 else tr - 1;
    st.center = false;
    var s = buffer.lineStart(st.cur);
    var l = st.cur_line;
    var k: usize = 0;
    while (k < back) : (k += 1) {
        s = buffer.prevLine(s) orelse break;
        l -= 1;
    }
    st.top = s;
    st.top_line = l;
}

/// 本文を [d] 行（正なら上へ）動かす
fn scrollText(d: isize) void {
    const tr = textRows();
    const first: usize = 1;
    const n: usize = @abs(d);
    out("\x1b[");
    outNum(first + 1);
    out(";");
    outNum(first + tr);
    out("r\x1b[");
    outNum(n);
    out(if (d > 0) "S" else "T");
    out("\x1b[r");
    if (d > 0) {
        var i: usize = 0;
        while (i < tr) : (i += 1) shown[first + i] = if (i + n < tr) shown[first + i + n] else 0;
    } else {
        var i: usize = tr;
        while (i > 0) {
            i -= 1;
            shown[first + i] = if (i >= n) shown[first + i - n] else 0;
        }
    }
}

fn drawTitle() void {
    put("\x1b[7m");
    const left = " nano ";
    const right: []const u8 = if (st.modified) "Modified " else "";
    const nm: []const u8 = if (st.name_len > 0) st.name() else "New Buffer";
    const lw: usize = if (cols >= left.len + right.len + 8) left.len else 0;
    if (lw > 0) {
        put(left);
        used += lw;
    }
    const room = cols -| (lw + right.len);
    const nw = @min(safeWidth(nm), room);
    const at = @max(lw, (cols -| nw) / 2);
    spaces(at -| used);
    _ = putSafe(nm, room -| (used - lw));
    if (right.len > 0 and used + right.len <= cols) {
        spaces(cols - right.len - used);
        put(right);
        used += right.len;
    }
    spaces(cols -| used);
    put("\x1b[0m");
}

fn drawStatus() void {
    switch (st.mode) {
        .prompt => {
            put("\x1b[7m");
            const lw = putSafe(st.prompt_label, cols -| 1);
            // 入力が入りきらなければ、後ろの方を見せる
            var s = st.promptText();
            const room = cols -| (lw + 1);
            while (s.len > 0 and safeWidth(s) > room) {
                var k: usize = 1;
                while (k < s.len and text.isContinuation(s[k])) k += 1;
                s = s[k..];
            }
            _ = putSafe(s, room);
            prompt_end = used;
            spaces(cols -| used);
            put("\x1b[0m");
        },
        else => {
            const m = st.msg();
            if (m.len == 0) return;
            const w = @min(safeWidth(m) + 4, cols);
            spaces((cols - w) / 2);
            put("\x1b[7m[ ");
            used += 2;
            _ = putSafe(m, w -| 4);
            put(" ]\x1b[0m");
            used += 2;
        },
    }
}

/// 案内の項目 [s] を切らずに書くのに要る幅（後ろの空白 1 つを含む）
fn shortcutWidth(s: st.Shortcut) usize {
    return s.key.len + 1 + s.label.len + 1;
}

/// 並べる組の数（どの項目も切らずに入る、いちばん多い数。1 組も入らなければ 1）
fn shortcutColumns() usize {
    const list = st.shortcuts;
    const pairs = (list.len + 1) / 2;
    var per = pairs;
    while (per > 1) : (per -= 1) {
        var need: usize = 0;
        for (list[0..@min(list.len, per * 2)]) |s| need = @max(need, shortcutWidth(s));
        if (cols / per >= need) break;
    }
    return per;
}

/// キーの案内の [line]（0 か 1）行目。案内は縦に 2 つずつ組にして、前から幅に入るだけの組を並べる
fn drawShortcuts(line: usize) void {
    const list = st.shortcuts;
    if (list.len == 0) return;
    const per = shortcutColumns();
    const width = cols / per;
    var i: usize = 0;
    while (i < per) : (i += 1) {
        const idx = i * 2 + line;
        const start = used;
        if (idx < list.len) {
            const s = list[idx];
            put("\x1b[7m");
            _ = putSafe(s.key, width);
            put("\x1b[0m");
            if (used - start + 1 < width) {
                spaces(1);
                _ = putSafe(s.label, width -| (used - start + 1));
            }
        }
        if (i + 1 < per) spaces(width -| (used - start));
    }
}

// ---- ヘルプ ----

pub const help_lines = [_][]const u8{
    "KiBi nano help",
    "",
    "Type to insert text. ^ means Ctrl and M- means Alt",
    "(hold Alt; Esc then a key does not work).",
    "^V does not reach the editor, so use PgDn for",
    "the next page.",
    "",
    "^G  F1       Show this help (^X to leave it)",
    "^X           Exit (asks to save changes)",
    "^O           Write out under a file name",
    "^S           Save to the current file",
    "^W           Search forward (empty: the last one)",
    "M-W          Repeat the last search",
    "^K           Cut the current line",
    "^U           Paste the cut lines",
    "^_  M-G      Go to line [,column]",
    "^C           Show the cursor position",
    "^A  Home     Start of the line",
    "^E  End      End of the line",
    "^Y  PgUp     Previous page",
    "PgDn         Next page",
    "M-\\ ^Home    First line",
    "M-/ ^End     Last line",
    "^D  Del      Delete the character at the cursor",
    "^H  Bksp     Delete the character before it",
    "^F ^B ^P ^N  Right, left, up, down",
    "",
    "Tabs are shown 8 columns wide. Control characters",
    "in the file are shown as ^X, and invalid UTF-8 as",
    "\xef\xbf\xbd. Bytes you do not edit are saved as they are.",
};

// ---- 描く ----

/// 画面を今の状態にする
pub fn render() void {
    updateSize();
    if (full) {
        out("\x1b[0m\x1b[2J");
        @memset(&shown, 0);
        shown_top = null;
        full = false;
    }
    const tr = textRows();
    var cx: usize = 0;
    var cy: usize = 0;
    if (st.mode == .help) {
        shown_top = null;
        drawTitle();
        commitRow(0);
        const max_top = help_lines.len -| tr;
        if (st.help_top > max_top) st.help_top = max_top;
        var y: usize = 0;
        while (y < tr) : (y += 1) {
            const i = st.help_top + y;
            if (i < help_lines.len) _ = putSafe(help_lines[i], cols);
            commitRow(1 + y);
        }
    } else {
        adjustTop();
        if (shown_top) |old| {
            if (old != st.top_line) {
                const d: isize = @as(isize, @intCast(st.top_line)) - @as(isize, @intCast(old));
                if (@abs(d) < tr) scrollText(d);
            }
        }
        shown_top = st.top_line;
        drawTitle();
        commitRow(0);
        const cs = buffer.lineStart(st.cur);
        const cc = columnOf(cs, st.cur);
        if (st.want_dirty) {
            st.want = cc;
            st.want_dirty = false;
        }
        const h = hscrollFor(cc);
        var s: ?usize = st.top;
        var y: usize = 0;
        while (y < tr) : (y += 1) {
            if (s) |start| {
                drawLine(start, if (start == cs) h else 0);
                s = buffer.nextLine(start);
            }
            commitRow(1 + y);
        }
        cy = 1 + st.cur_line - st.top_line;
        cx = cc - h;
    }
    drawStatus();
    if (st.mode == .prompt) {
        cy = statusRow();
        cx = @min(prompt_end, cols -| 1);
    }
    commitRow(statusRow());
    if (helpRows() > 0) {
        drawShortcuts(0);
        commitRow(rows - 2);
        drawShortcuts(1);
        commitRow(rows - 1);
    }
    if (st.mode == .help) {
        out("\x1b[?25l");
    } else {
        moveTo(@min(cy, rows - 1), @min(cx, cols - 1));
        out("\x1b[?25h");
    }
    flush();
}
