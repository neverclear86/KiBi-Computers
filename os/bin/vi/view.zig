//! 画面に描く（docs/adr/0104-vi.md の「表示」）。描き方は nano の `view.zig`（ADR 0105「表示」）と同じ。
//!
//! 画面は、上から本文、いちばん下の 1 行（モードの表示・知らせ・`:` の入力）。テキストの終わりより後ろの行は `~`。
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
/// 入力の後ろの桁（[drawStatus] が行を埋める前に控える）
var cmd_end: usize = 0;

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

/// [pos] の文字の次の位置（行の終わりなら [pos]）
pub fn nextGlyph(start: usize, pos: usize) usize {
    const g = glyph(pos, columnOf(start, pos));
    return pos + g.len;
}

// ---- 割り付け ----

/// 本文の行の数（1 行の画面では、いちばん下の行と分け合う 1 行）
pub fn textRows() usize {
    return if (rows >= 2) rows - 1 else 1;
}

fn statusRow() usize {
    return rows - 1;
}

/// 行の番号の欄の幅（番号の後ろの空白を含む。出さなければ 0）
fn gutter() usize {
    if (!st.number) return 0;
    var d: usize = 1;
    var v = buffer.lineCount();
    while (v >= 10) : (v /= 10) d += 1;
    const w = @max(d, 3) + 1;
    return if (w + 8 <= cols) w else 0;
}

/// いちばん下の行に出すもの（入力・知らせ）がある（挿入の表示は、1 行の画面では本文を覆わない）
fn statusVisible() bool {
    return st.mode == .command or st.msg().len > 0;
}

fn textCovered() bool {
    return rows == 1 and statusVisible();
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

/// 本文の行のつなぎ方。挿入でテキストの終わりの改行の後ろに居るときだけ、その空の行も描く
fn nextShown(start: usize) ?usize {
    if (st.mode == .insert and st.cur == buffer.len) return buffer.nextLineOrEnd(start);
    return buffer.nextLine(start);
}

/// カーソルが本文の中に見えるよう、いちばん上の行を決める
pub fn adjustTop() void {
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
    const n: usize = @abs(d);
    out("\x1b[1;");
    outNum(tr);
    out("r\x1b[");
    outNum(n);
    out(if (d > 0) "S" else "T");
    out("\x1b[r");
    if (d > 0) {
        var i: usize = 0;
        while (i < tr) : (i += 1) shown[i] = if (i + n < tr) shown[i + n] else 0;
    } else {
        var i: usize = tr;
        while (i > 0) {
            i -= 1;
            shown[i] = if (i >= n) shown[i - n] else 0;
        }
    }
}

/// 横に送る量（カーソルの桁 [cc] が見えるように。[w] は本文の幅。送った行の頭には `$` を置く）
fn hscrollFor(cc: usize, w: usize) usize {
    if (w < 4 or cc + 1 < w) return 0;
    const chunk = if (w > 16) w - 8 else w / 2;
    return ((cc + 1 - w) / chunk + 1) * chunk;
}

/// 本文の 1 行を描く。[h] は横に送った量、[w] は本文の幅
fn drawLine(start: usize, h: usize, w: usize) void {
    const base = used;
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
            if (x + g.width > w - 1) {
                // 右端に入らない: これが最後の文字でちょうど入るなら書き、そうでなければ右端に `$`
                const last = glyph(p + g.len, col + g.width).kind == .end;
                if (last and x + g.width <= w) {
                    putGlyph(p, g);
                } else {
                    spaces((base + w) -| (used + 1));
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

/// どんな文字列でも、端末を動かさない形で最大 [max] セル書く（ファイルの名前・知らせなど）。書いたセル数
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

fn safeWidth(s: []const u8) usize {
    const save_r = rlen;
    const save_u = used;
    const w = putSafe(s, MAX_COLS * 4);
    rlen = save_r;
    used = save_u;
    return w;
}

fn drawStatus() void {
    switch (st.mode) {
        .command => {
            putByte(st.cmd_prefix);
            used += 1;
            // 入力が入りきらなければ、後ろの方を見せる
            var s = st.cmdText();
            const room = cols -| 2;
            while (s.len > 0 and safeWidth(s) > room) {
                var k: usize = 1;
                while (k < s.len and text.isContinuation(s[k])) k += 1;
                s = s[k..];
            }
            _ = putSafe(s, room);
            cmd_end = used;
        },
        else => {
            const m = st.msg();
            if (m.len > 0) {
                if (st.msg_error) put("\x1b[7m");
                _ = putSafe(m, cols);
                if (st.msg_error) put("\x1b[0m");
            } else if (st.mode == .insert) {
                const s = "-- INSERT --";
                _ = putSafe(s, cols);
            }
        },
    }
}

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
    adjustTop();
    if (shown_top) |old| {
        if (old != st.top_line) {
            const d: isize = @as(isize, @intCast(st.top_line)) - @as(isize, @intCast(old));
            if (@abs(d) < tr and tr > 1) scrollText(d);
        }
    }
    shown_top = st.top_line;
    const gw = gutter();
    const tw = cols - gw;
    const cs = buffer.lineStart(st.cur);
    const cc = columnOf(cs, st.cur);
    if (st.want_dirty) {
        st.want = cc;
        st.want_dirty = false;
    }
    const h = hscrollFor(cc, tw);
    var s: ?usize = st.top;
    var y: usize = 0;
    // いちばん下の行が覆うときは、作ってから捨てないよう、作りもしない
    while (y < tr and !textCovered()) : (y += 1) {
        if (s) |start| {
            if (gw > 0) {
                var b: [20]u8 = undefined;
                const num = io.formatUnsigned(&b, st.top_line + y + 1);
                spaces(gw - 1 -| num.len);
                put(num);
                used += num.len;
                spaces(1);
            }
            drawLine(start, if (start == cs) h else 0, tw);
            s = nextShown(start);
        } else {
            putByte('~');
            used += 1;
        }
        commitRow(y);
    }
    // 1 行の画面では、出すものがないときにいちばん下の行を確定しない（本文を空の行で消さない）
    if (rows > 1 or statusVisible()) {
        drawStatus();
        commitRow(statusRow());
    }
    if (st.mode == .command) {
        moveTo(statusRow(), @min(cmd_end, cols -| 1));
        out("\x1b[?25h");
    } else if (textCovered()) {
        // 知らせが本文を覆っている間は、カーソルを置く場所がない
        out("\x1b[?25l");
    } else {
        const cy = st.cur_line - st.top_line;
        moveTo(@min(cy, rows - 1), @min(gw + cc - h, cols - 1));
        out("\x1b[?25h");
    }
    flush();
}
