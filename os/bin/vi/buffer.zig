//! 編集中のテキスト（ギャップバッファ）。バイト列をそのまま持ち、解釈は表示のときだけする。
//!
//! 形は nano の `buffer.zig`（ADR 0105）と同じ。ほかのコマンドのディレクトリを `@import` すると、Gradle の入力の追跡から漏れるので写している。
//! 領域は静的に置き、使う範囲（長さ + ギャップ）だけを `kibi:computer/memory` で先に数える（#407）。
//! ギャップは [CHUNK] ずつ広げるので、触る区画は「長さ + CHUNK」を超えない。
//!
//! 行の数え方は vi と同じ: 改行で終わるテキストの、最後の改行の後ろ（空）は行に数えない（[lineCount]・[nextLine]）。
const kibi = @import("kibi");

/// 扱えるテキストの大きさの上限（バイト。仮の値。nano と同じ）
pub const MAX_LEN = 4 * 1024 * 1024;
/// ギャップを広げる単位・RAM を数える単位
pub const CHUNK = 64 * 1024;

var mem: [MAX_LEN + CHUNK]u8 = undefined;
var committed: usize = 0;

/// テキストの長さ（バイト）
pub var len: usize = 0;
/// ギャップの始まり（論理の位置と同じ）と長さ
var gap: usize = 0;
var gap_len: usize = 0;
/// 改行の数 + 1
pub var lines: usize = 1;

pub const Error = error{ TooBig, OutOfMemory };

/// 物理の [n] バイトまでを RAM として数える
fn commitTo(n: usize) Error!void {
    if (n > mem.len) return error.TooBig;
    while (committed < n) {
        const end = @min(committed + CHUNK, mem.len);
        if (!kibi.memory.commit(mem[committed..end], 0)) return error.OutOfMemory;
        committed = end;
    }
}

/// 論理の位置 [i] のバイト
pub fn at(i: usize) u8 {
    return if (i < gap) mem[i] else mem[i + gap_len];
}

/// [i] から最大 [buf].len バイトを写す（写したバイト数）
pub fn copy(i: usize, buf: []u8) usize {
    const n = @min(buf.len, len -| i);
    var k: usize = 0;
    if (i < gap) {
        const a = @min(n, gap - i);
        @memcpy(buf[0..a], mem[i..][0..a]);
        k = a;
    }
    if (k < n) {
        const from = i + k + gap_len;
        @memcpy(buf[k..n], mem[from..][0 .. n - k]);
    }
    return n;
}

/// 空にする
pub fn clear() void {
    len = 0;
    gap = 0;
    gap_len = 0;
    lines = 1;
}

/// fd から全部読んで、テキストにする（前の中身は捨てる）
pub fn load(fd: kibi.io.Fd) (Error || kibi.Error)!void {
    clear();
    errdefer clear();
    while (true) {
        if (len >= MAX_LEN) {
            // ちょうど上限: もう 1 バイトあれば大きすぎる（途中まで読んだものは開かない）
            var probe: [1]u8 = undefined;
            if (try kibi.io.read(fd, &probe) != 0) return error.TooBig;
            break;
        }
        if (len == committed) try commitTo(len + 1);
        const got = try kibi.io.read(fd, mem[len..@min(committed, MAX_LEN)]);
        if (got == 0) break;
        len += got;
    }
    gap = len;
    for (mem[0..len]) |c| {
        if (c == '\n') lines += 1;
    }
}

/// 全部を fd に書く
pub fn writeTo(fd: kibi.io.Fd) kibi.Error!void {
    try kibi.io.writeAll(fd, mem[0..gap]);
    try kibi.io.writeAll(fd, mem[gap + gap_len .. len + gap_len]);
}

fn moveGap(pos: usize) void {
    if (pos < gap) {
        const n = gap - pos;
        @memmove(mem[pos + gap_len ..][0..n], mem[pos..][0..n]);
    } else if (pos > gap) {
        const n = pos - gap;
        @memmove(mem[gap..][0..n], mem[gap + gap_len ..][0..n]);
    }
    gap = pos;
}

/// ギャップを [need] バイト以上にする
fn grow(need: usize) Error!void {
    const new_gap = @max(need, CHUNK);
    const old_end = len + gap_len;
    const new_end = len + new_gap;
    try commitTo(new_end);
    const tail = old_end - (gap + gap_len);
    @memmove(mem[gap + new_gap ..][0..tail], mem[gap + gap_len ..][0..tail]);
    gap_len = new_gap;
}

/// あと [n] バイト入れられるよう、先に場所と RAM を取る（元に戻すで、途中まで入れて止まらないように）
pub fn reserve(n: usize) Error!void {
    if (n > MAX_LEN - len) return error.TooBig;
    if (gap_len < n) try grow(n);
}

fn countNewlines(s: []const u8) usize {
    var n: usize = 0;
    for (s) |c| n += @intFromBool(c == '\n');
    return n;
}

/// [pos] に [bytes] を入れる（[bytes] はこのバッファの中を指してはいけない）
pub fn insert(pos: usize, bytes: []const u8) Error!void {
    if (bytes.len > MAX_LEN - len) return error.TooBig;
    if (gap_len < bytes.len) try grow(bytes.len);
    moveGap(pos);
    @memcpy(mem[gap..][0..bytes.len], bytes);
    gap += bytes.len;
    gap_len -= bytes.len;
    len += bytes.len;
    lines += countNewlines(bytes);
}

/// [a, b) の後ろに、[a, b) を [times] 回続けて入れる（挿入の回数。`3ix<Esc>` の後の 2 回分）
pub fn repeatRange(a: usize, b: usize, times: usize) Error!void {
    const n = b - a;
    if (n == 0 or times == 0) return;
    const total = n *| times;
    if (total > MAX_LEN - len) return error.TooBig;
    if (gap_len < total) try grow(total);
    moveGap(b);
    const nl = countNewlines(mem[a..b]);
    var k: usize = 0;
    while (k < times) : (k += 1) {
        // [a, b) はギャップの前にあり、ギャップへ写すので重ならない
        @memcpy(mem[gap..][0..n], mem[a..b]);
        gap += n;
    }
    gap_len -= total;
    len += total;
    lines += nl * times;
}

/// [a, b) を消す
pub fn remove(a: usize, b: usize) void {
    if (b <= a) return;
    moveGap(a);
    lines -= countNewlines(mem[a + gap_len ..][0 .. b - a]);
    gap_len += b - a;
    len -= b - a;
}

/// [a, b) の改行の数
pub fn newlinesIn(a: usize, b: usize) usize {
    var n: usize = 0;
    var i = a;
    while (i < b) : (i += 1) n += @intFromBool(at(i) == '\n');
    return n;
}

// ---- 行 ----

/// 行の数（vi の数え方。改行で終わるテキストの最後の改行の後ろは数えない。空のテキストは 1 行）
pub fn lineCount() usize {
    return if (len > 0 and at(len - 1) == '\n') lines - 1 else lines;
}

/// [pos] のある行の頭
pub fn lineStart(pos: usize) usize {
    var i = pos;
    while (i > 0 and at(i - 1) != '\n') i -= 1;
    return i;
}

/// [pos] のある行の終わり（改行の位置か、テキストの終わり）
pub fn lineEnd(pos: usize) usize {
    var i = pos;
    while (i < len and at(i) != '\n') i += 1;
    return i;
}

/// [start]（行の頭）の次の行の頭。最後の行なら null（テキストの終わりの改行の後ろは行にしない）
pub fn nextLine(start: usize) ?usize {
    const e = lineEnd(start);
    return if (e + 1 < len) e + 1 else null;
}

/// [nextLine] と同じだが、テキストの終わりの改行の後ろ（挿入で居られる空の行）も行にする
pub fn nextLineOrEnd(start: usize) ?usize {
    const e = lineEnd(start);
    return if (e < len) e + 1 else null;
}

/// [start]（行の頭）の前の行の頭。最初の行なら null
pub fn prevLine(start: usize) ?usize {
    if (start == 0) return null;
    return lineStart(start - 1);
}

/// [regex] に渡す 1 行（[start, end)）
pub const Line = struct {
    start: usize,
    len: usize,

    pub fn at(self: Line, i: usize) u8 {
        return mem[physical(self.start + i)];
    }
};

fn physical(i: usize) usize {
    return if (i < gap) i else i + gap_len;
}

pub fn line(start: usize, end: usize) Line {
    return .{ .start = start, .len = end - start };
}
