//! 独自拡張 `kibi:kernel/events`（#73。ABI は docs/adr/0073-kibi-wit.md）の包みと、標準 OS の `cabi_realloc`（#87）。
//!
//! `take` の可変長の結果（イベントの並び・文字列・値）は、ホストがゲストの `cabi_realloc` で場所を取って書く。
//! 標準 OS は標準のアロケーターを使わないので、`cabi_realloc` は静的な領域（.bss。触るまで RAM に数えない）から切り出すだけにする:
//! - `take` を呼ぶ直前に先頭へ戻す。取り出したイベントは、次の `take` までしか読めない。
//! - 1 回に 1 件だけ取り出す（[Queue.takeOne]）。1 件の最悪（デバイスが出す大きなイベント）でも約 5 KiB で、領域に収まる。
//! - 足りなければ線形メモリの外を返す（ホストがトラップする）。0 を返すと、アドレス 0 から上書きしてメモリを壊すため。
//! - 止まりうる import（`fd_write` など）は呼ばない（`cabi_realloc` の中で呼ぶとトラップ。ADR 0073）。
//!
//! `cabi_realloc` は、`kibi.events` を使うプログラムだけが export する（`core/build.gradle.kts` の `osEventsPrograms` に足し、`--export=cabi_realloc` でビルドする）。
//! `take` を import するのに `cabi_realloc` がなければ、読み込みで NOEXEC になる。
const wasi = @import("wasi.zig");
const io = @import("io.zig");
const errno = @import("errno.zig");

pub const Error = errno.Error;

/// 復元のイベントの種類（カーネルが source "" で出す。#87、ADR 0087）
pub const RESTORED = "restored";

// ---- 配置（Canonical ABI。core/src/main/wasm/common/kibi_events.h と同じ）----

/// string・list<u8>（8 バイト、align 4）
pub const Bytes = extern struct {
    ptr: [*]const u8,
    len: u32,

    pub fn slice(self: Bytes) []const u8 {
        return self.ptr[0..self.len];
    }
};

pub const VALUE_INTEGER: u8 = 0;
pub const VALUE_FLOAT: u8 = 1;
pub const VALUE_BOOLEAN: u8 = 2;
pub const VALUE_TEXT: u8 = 3;
pub const VALUE_BYTES: u8 = 4;

/// variant event-value（16 バイト、align 8）
pub const Value = extern struct {
    tag: u8,
    val: extern union {
        integer: i64,
        float: f64,
        boolean: u8,
        text: Bytes,
        bytes: Bytes,
    },
};

/// record event（24 バイト、align 4）
pub const Event = extern struct {
    kind: Bytes,
    source: Bytes,
    values_ptr: [*]const Value,
    values_len: u32,

    pub fn values(self: *const Event) []const Value {
        return self.values_ptr[0..self.values_len];
    }

    /// カーネルが出した「保存から戻った」（種類 `restored`・source ""）か。デバイスの同じ名前のイベントは source がアドレス
    pub fn isRestored(self: *const Event) bool {
        return eql(self.kind.slice(), RESTORED) and self.source.len == 0;
    }
};

/// record event-batch（16 バイト、align 8）
const Batch = extern struct {
    events_ptr: [*]const Event,
    events_len: u32,
    dropped: u64,
};

/// result<event-queue, error-code>（8 バイト）。err は val の下位バイト
const ResultQueue = extern struct { is_err: u8, val: u32 };
/// result<event-batch, error-code>（24 バイト、align 8）。err は ok の先頭バイト
const ResultBatch = extern struct {
    is_err: u8,
    val: extern union { ok: Batch, err: u8 },
};

comptime {
    if (@sizeOf(Bytes) != 8 or @alignOf(Bytes) != 4) @compileError("string");
    if (@sizeOf(Value) != 16 or @alignOf(Value) != 8 or @offsetOf(Value, "val") != 8) @compileError("event-value");
    if (@sizeOf(Event) != 24 or @alignOf(Event) != 4) @compileError("event");
    if (@offsetOf(Event, "source") != 8 or @offsetOf(Event, "values_ptr") != 16) @compileError("event fields");
    if (@sizeOf(Batch) != 16 or @offsetOf(Batch, "dropped") != 8) @compileError("event-batch");
    if (@sizeOf(ResultQueue) != 8 or @offsetOf(ResultQueue, "val") != 4) @compileError("result<event-queue>");
    if (@sizeOf(ResultBatch) != 24 or @offsetOf(ResultBatch, "val") != 8) @compileError("result<event-batch>");
    if (@offsetOf(ResultBatch, "val") + @offsetOf(Batch, "events_len") != 12) @compileError("events.len");
    if (@offsetOf(ResultBatch, "val") + @offsetOf(Batch, "dropped") != 16) @compileError("dropped");
}

/// 生の import（宣言の名前が import の名前になるので、包む関数と名前が重ならないよう入れ物に入れる）
const raw = struct {
    extern "kibi:kernel/events@0.1.0" fn @"[static]event-queue.open"(kinds: [*]const Bytes, kinds_len: u32, cap_is_some: u32, cap: u32, ret: *ResultQueue) void;
    extern "kibi:kernel/events@0.1.0" fn @"[method]event-queue.take"(self: u32, max: u32, ret: *ResultBatch) void;
    extern "kibi:kernel/events@0.1.0" fn @"[resource-drop]event-queue"(self: u32) void;
};

// ---- cabi_realloc ----

/// `take` の結果を置く領域。1 件の最悪は event 24 + 種類 64 + source 128 + 値 32×16 + 文字列・バイト列の中身 4096 に、
/// 割り当てごとの詰め物を足して約 5 KiB
const ARENA_SIZE = 8 * 1024;
var arena: [ARENA_SIZE]u8 align(16) = undefined;
var arena_used: usize = 0;

/// 足りないときに返す場所（線形メモリの外。align 8 に揃えてあるので、ホストは範囲外としてトラップする）
const OUT_OF_ARENA: u32 = 0xFFFF_FFF8;

/// ホストが `take` の結果の場所を取る（Canonical ABI の `cabi_realloc`）。新しく取るだけで、[old] は使わない
/// （ホストは `cabi_realloc(0, 0, align, size)` で呼ぶ）
export fn cabi_realloc(old: u32, old_len: u32, alignment: u32, new_len: u32) u32 {
    _ = old;
    _ = old_len;
    const a: usize = if (alignment == 0) 1 else alignment;
    if (a & (a - 1) != 0) return OUT_OF_ARENA;
    const base = @intFromPtr(&arena);
    const start = (base + arena_used + a - 1) & ~(a - 1);
    const offset = start - base;
    if (offset > ARENA_SIZE or new_len > ARENA_SIZE - offset) return OUT_OF_ARENA;
    arena_used = offset + new_len;
    return @intCast(start);
}

// ---- キュー ----

/// 1 回の取り出しの結果。[event] は次の `take` まで読める
pub const Taken = struct {
    /// 取り出したイベント（なければ null）
    event: ?*const Event,
    /// 前回の取り出しから、あふれて捨てた数
    dropped: u64,
};

pub const Queue = struct {
    handle: u32,

    /// [kinds] の種類を受け取るキューを開く（空ならすべて）。マシンのキューが上限なら NFILE、ハンドル表が満杯なら MFILE
    pub fn open(kinds: []const []const u8) Error!Queue {
        var list: [16]Bytes = undefined;
        if (kinds.len > list.len) return error.INVAL;
        for (kinds, 0..) |k, i| list[i] = .{ .ptr = k.ptr, .len = @intCast(k.len) };
        var r: ResultQueue = undefined;
        raw.@"[static]event-queue.open"(&list, @intCast(kinds.len), 0, 0, &r);
        if (r.is_err != 0) return io.kibiError(@truncate(r.val));
        return .{ .handle = r.val };
    }

    /// 待たずに 1 件取り出す
    pub fn takeOne(self: Queue) Error!Taken {
        arena_used = 0;
        var r: ResultBatch = undefined;
        raw.@"[method]event-queue.take"(self.handle, 1, &r);
        if (r.is_err != 0) return io.kibiError(r.val.err);
        const b = r.val.ok;
        return .{ .event = if (b.events_len > 0) &b.events_ptr[0] else null, .dropped = b.dropped };
    }

    /// 空になるまで取り出す。カーネルの `restored` があったか、あふれて捨てた分があれば true（描き直す合図。#87）
    pub fn drainRestored(self: Queue) Error!bool {
        var redraw = false;
        while (true) {
            const t = try self.takeOne();
            if (t.dropped > 0) redraw = true;
            const e = t.event orelse return redraw;
            if (e.isRestored()) redraw = true;
        }
    }

    /// 閉じる（1 回だけ呼ぶ。閉じたハンドルをもう一度渡すとトラップ）
    pub fn close(self: Queue) void {
        raw.@"[resource-drop]event-queue"(self.handle);
    }
};

/// 「保存から戻った」だけを受け取るキューを開く（開けなければ null。呼び手は従来どおり入力だけを待つ）
pub fn openRestored() ?Queue {
    return Queue.open(&.{RESTORED}) catch null;
}

pub const Ready = struct {
    /// [fd] が読める（か、読むとエラー・終わりが分かる）
    input: bool = false,
    /// キューにイベントか、あふれて捨てた分がある
    queue: bool = false,
};

/// [fd] の入力か [q] のイベントを待つ（`poll_oneoff` の FD_READ を 2 つ。時計では起きない）。
/// キューの側がエラーで起きたら `IO`（ハンドルが不正なとき。呼び手はキューを閉じずに手放して、入力だけを待つ。不正なハンドルの drop はトラップする）
pub fn waitInputOrQueue(fd: io.Fd, q: Queue) Error!Ready {
    const subs = [_]wasi.Subscription{
        .{ .userdata = 0, .tag = wasi.EVENTTYPE_FD_READ, .u = .{ .fd = .{ .fd = fd } } },
        .{ .userdata = 1, .tag = wasi.EVENTTYPE_FD_READ, .u = .{ .fd = .{ .fd = q.handle } } },
    };
    var evs: [2]wasi.Event = undefined;
    while (true) {
        var n: wasi.Size = 0;
        errno.check(wasi.poll_oneoff(&subs, &evs, subs.len, &n)) catch |e| {
            if (e == error.INTR) continue;
            return e;
        };
        var ready: Ready = .{};
        for (evs[0..n]) |ev| {
            if (ev.userdata == 0) {
                ready.input = true;
            } else {
                if (ev.@"error" != 0) return error.IO;
                ready.queue = true;
            }
        }
        if (ready.input or ready.queue) return ready;
    }
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}
