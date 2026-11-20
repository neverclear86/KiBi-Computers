//! 元に戻す（`u`）・やり直す（Ctrl+R）の記録（docs/adr/0104-vi.md の「元に戻す」）。
//!
//! 1 つのコマンド（挿入の 1 回、`:s` の 1 回を含む）で変えたものを 1 組にまとめ、組の中には「入れた」「消した」を順に並べる。
//! 記録の領域は静的に置き、使う分だけ `kibi:computer/memory` で先に数える（#407。テキストと同じ理由）。
//! 領域が足りなくなったら古い組から捨てる。1 組が領域より大きいか RAM が足りなければ、記録を全部捨てて知らせる
//! （編集そのものは断らない。記録できないことでトラップして、保存していない編集を失わないため）。
//!
//! 1 件の形: `種類(1) 位置(4) 長さ(4) バイト列 大きさ(4)`。後ろの大きさで、後ろからもたどれる。
const kibi = @import("kibi");
const buffer = @import("buffer.zig");

/// 記録の領域の大きさ（バイト。仮の値。テキストの上限と同じ）
pub const CAP = buffer.MAX_LEN;
const CHUNK = buffer.CHUNK;

var mem: [CAP]u8 = undefined;
var committed: usize = 0;

pub const Kind = enum(u8) { group = 0, insert = 1, delete = 2 };

const HEAD = 9;
const FOOT = 4;

/// 戻せる組の終わり（その後ろ、[log_end] まではやり直せる組）
pub var undo_end: usize = 0;
pub var log_end: usize = 0;
/// 今のコマンドの組を書き始めたか・その組の頭
var open: bool = false;
var group_start: usize = 0;
/// 今のコマンドは記録できなかった（以後このコマンドでは書かない）
var broken: bool = false;
/// 今のコマンドの前のカーソル（組の頭に書く）
var cursor_before: usize = 0;
/// 記録を捨てたときの知らせ（呼ぶ側が出して null にする）
pub var lost: ?[]const u8 = null;
/// 戻す・やり直すを当てている間は書かない
pub var suspended: bool = false;

fn put32(at: usize, v: usize) void {
    const x: u32 = @intCast(v);
    mem[at] = @truncate(x);
    mem[at + 1] = @truncate(x >> 8);
    mem[at + 2] = @truncate(x >> 16);
    mem[at + 3] = @truncate(x >> 24);
}

fn get32(at: usize) usize {
    return @as(usize, mem[at]) | @as(usize, mem[at + 1]) << 8 | @as(usize, mem[at + 2]) << 16 | @as(usize, mem[at + 3]) << 24;
}

fn commitTo(n: usize) bool {
    while (committed < n) {
        const end = @min(committed + CHUNK, mem.len);
        if (!kibi.memory.commit(mem[committed..end], 0)) return false;
        committed = end;
    }
    return true;
}

/// 記録を全部捨てる
pub fn reset() void {
    undo_end = 0;
    log_end = 0;
    open = false;
    group_start = 0;
}

fn drop(message: []const u8) void {
    reset();
    broken = true;
    lost = message;
}

/// コマンドの始まり（[cursor] はその前のカーソル）
pub fn begin(cursor: usize) void {
    open = false;
    broken = false;
    cursor_before = cursor;
}

/// 今のコマンドで何か変えたか（記録できなかったものを含めない）
pub fn changedNow() bool {
    return open;
}

/// [need] バイトを書ける場所を作る（古い組を捨てる）。作れなければ false
fn room(need: usize) bool {
    if (need > CAP) return false;
    if (log_end + need <= CAP) return true;
    // 今の組より前の、組の頭のうち、そこより前を捨てれば入るところ
    var off: usize = 0;
    const limit = if (open) group_start else log_end;
    var cut: ?usize = null;
    while (off < limit) {
        const next = off + HEAD + get32(off + 5) + FOOT;
        if (next <= limit and (next >= log_end or @as(Kind, @enumFromInt(mem[next])) == .group)) {
            if (log_end - next + need <= CAP) {
                cut = next;
                break;
            }
        }
        off = next;
    }
    const c = cut orelse return false;
    const rest = log_end - c;
    @memmove(mem[0..rest], mem[c..log_end]);
    log_end -= c;
    undo_end -|= c;
    if (open) group_start -= c;
    return true;
}

fn writeRecord(kind: Kind, pos: usize, src_pos: usize, len: usize) bool {
    const need = HEAD + len + FOOT;
    if (!room(need)) return false;
    if (!commitTo(log_end + need)) return false;
    const at = log_end;
    mem[at] = @intFromEnum(kind);
    put32(at + 1, pos);
    put32(at + 5, len);
    if (len > 0) _ = buffer.copy(src_pos, mem[at + HEAD ..][0..len]);
    put32(at + HEAD + len, need);
    log_end += need;
    return true;
}

/// 組を書き始める（前にやり直せる組があれば捨てる）
fn ensureGroup() bool {
    if (broken) return false;
    if (open) return true;
    log_end = undo_end;
    if (!writeRecord(.group, cursor_before, 0, 0)) {
        drop("Not enough room to undo this change");
        return false;
    }
    open = true;
    group_start = log_end - (HEAD + FOOT);
    undo_end = log_end;
    return true;
}

/// テキストの [pos] に [len] バイト入れた（入れた後に呼ぶ）
pub fn recordInsert(pos: usize, len: usize) void {
    if (suspended or len == 0 or !ensureGroup()) return;
    // 直前の記録が、すぐ前に続けて入れたものなら、つなげる（挿入で 1 文字ずつ打ったとき）
    if (log_end >= group_start + 2 * (HEAD + FOOT)) {
        const last_size = get32(log_end - FOOT);
        const last = log_end - last_size;
        if (last > group_start and @as(Kind, @enumFromInt(mem[last])) == .insert) {
            const lpos = get32(last + 1);
            const llen = get32(last + 5);
            if (lpos + llen == pos and log_end + len <= CAP and commitTo(log_end + len)) {
                const data_end = last + HEAD + llen;
                _ = buffer.copy(pos, mem[data_end..][0..len]);
                put32(last + 5, llen + len);
                put32(data_end + len, last_size + len);
                log_end += len;
                undo_end = log_end;
                return;
            }
        }
    }
    if (!writeRecord(.insert, pos, pos, len)) {
        drop("Not enough room to undo this change");
        return;
    }
    undo_end = log_end;
}

/// テキストの [a, b) を消す（消す前に呼ぶ）
pub fn recordDelete(a: usize, b: usize) void {
    if (suspended or b <= a or !ensureGroup()) return;
    if (!writeRecord(.delete, a, a, b - a)) {
        drop("Not enough room to undo this change");
        return;
    }
    undo_end = log_end;
}

pub const Record = struct {
    kind: Kind,
    pos: usize,
    data: []const u8,
    /// 記録の頭と終わり
    start: usize,
    end: usize,
};

pub fn recordAt(off: usize) Record {
    const len = get32(off + 5);
    return .{
        .kind = @enumFromInt(mem[off]),
        .pos = get32(off + 1),
        .data = mem[off + HEAD ..][0..len],
        .start = off,
        .end = off + HEAD + len + FOOT,
    };
}

pub fn recordBefore(off: usize) Record {
    return recordAt(off - get32(off - FOOT));
}
