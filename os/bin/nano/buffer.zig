//! 編集中のテキスト（ギャップバッファ）。バイト列をそのまま持ち、解釈は表示のときだけする。
//!
//! 領域は静的に置き、使う範囲（長さ + ギャップ）だけを `kibi:computer/memory` で先に数える（#407）。
//! 数えずに書くと、RAM の上限を超えたところでトラップしてプロセスが落ち、保存していない編集を失うため。
//! ギャップは [CHUNK] ずつ広げるので、触る区画は「長さ + CHUNK」を超えない。
const kibi = @import("kibi");

/// 扱えるテキストの大きさの上限（バイト。仮の値。4段目の HDD より小さい）
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
/// 行の数（改行の数 + 1）
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
    for (buf[0..n], 0..) |*b, k| b.* = at(i + k);
    return n;
}

/// fd から全部読んで、テキストにする（前の中身は捨てる）
pub fn load(fd: kibi.io.Fd) (Error || kibi.Error)!void {
    len = 0;
    gap = 0;
    gap_len = 0;
    lines = 1;
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

fn countNewlines(s: []const u8) usize {
    var n: usize = 0;
    for (s) |c| n += @intFromBool(c == '\n');
    return n;
}

/// [pos] に [bytes] を入れる
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

/// [a, b) を消す
pub fn remove(a: usize, b: usize) void {
    if (b <= a) return;
    moveGap(a);
    lines -= countNewlines(mem[a + gap_len ..][0 .. b - a]);
    gap_len += b - a;
    len -= b - a;
}

// ---- 行 ----

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

/// [start]（行の頭）の次の行の頭。最後の行なら null
pub fn nextLine(start: usize) ?usize {
    const e = lineEnd(start);
    return if (e < len) e + 1 else null;
}

/// [start]（行の頭）の前の行の頭。最初の行なら null
pub fn prevLine(start: usize) ?usize {
    if (start == 0) return null;
    return lineStart(start - 1);
}

fn lower(c: u8) u8 {
    return if (c >= 'A' and c <= 'Z') c + 32 else c;
}

/// [from] から後ろに [needle] を探す（終わりまでなければ頭から。ASCII の大文字・小文字は区別しない）。見つかった位置
pub fn find(needle: []const u8, from: usize) ?usize {
    if (needle.len == 0 or needle.len > len) return null;
    const last = len - needle.len;
    // 始める位置が最後の候補より後ろなら、頭から探す（剰余だと途中から始まって、頭に近い一致を飛ばす）
    const start = if (from > last) 0 else from;
    var k: usize = 0;
    while (k <= last) : (k += 1) {
        const i = (start + k) % (last + 1);
        var j: usize = 0;
        while (j < needle.len and lower(at(i + j)) == lower(needle[j])) j += 1;
        if (j == needle.len) return i;
    }
    return null;
}
