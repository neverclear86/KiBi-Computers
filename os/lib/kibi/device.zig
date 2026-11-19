//! 独自拡張 `kibi:device/bus`（#77。ABI は docs/adr/0077-kibi-device.md）の包み（#108。ADR 0108）。
//!
//! - 結果の文字列・リスト（[listDevices]・[Device.info]・[Device.methods]・[Device.invoke] の結果と、`call-error` の文）は
//!   `cabi_realloc` の領域（`canon.zig`）に置かれ、`canon.reset` を呼ぶまで読める。包みの中では戻さない。
//!   一覧を読みながらデバイスごとに呼ぶときは、一覧を写してから 1 台ずつ `canon.reset` する（領域を積み上げないため）
//! - これらを使うプログラムは `cabi_realloc` を export する（`core/build.gradle.kts` の `osReallocPrograms`）
//! - [Device.invoke] は止まりうる import（メインスレッドのメソッドは次の tick より後に返る）。待機点型へ変換される
//! - `start`・`take`（待たずに積む呼び出し）は、使うプログラムができたときに足す
//!
//! 配置は `core/src/main/wasm/common/kibi_device.h` と同じ（下の `comptime` で確かめる）。
//! 値の判別子は `kibi:kernel/events` の `event-value` と番号が違う（none=0 から。ADR 0065）ので、`events.Value` を使い回さない。
const io = @import("io.zig");
const errno = @import("errno.zig");
const canon = @import("canon.zig");

pub const Error = errno.Error;

/// 呼び出し 1 回の引数・結果の数の最大（`DeviceLimits.MAX_VALUES`）
pub const MAX_VALUES = 32;
/// 呼び出し 1 回の text・bytes の中身の合計の最大（バイト。`DeviceLimits.MAX_PAYLOAD_BYTES`）
pub const MAX_PAYLOAD_BYTES = 64 * 1024;
/// メソッドの名前の最大（バイト。`DeviceLimits.MAX_METHOD_BYTES`）
pub const MAX_METHOD_BYTES = 64;
/// address の長さ（小文字の UUID）
pub const ADDRESS_BYTES = 36;
/// 種類の最大（バイト。`DeviceKind.MAX_BYTES`）
pub const MAX_KIND_BYTES = 64;

/// string・list<u8>（8 バイト、align 4）
pub const String = extern struct {
    ptr: [*]const u8,
    len: u32,

    pub fn of(s: []const u8) String {
        return .{ .ptr = s.ptr, .len = @intCast(s.len) };
    }

    pub fn slice(self: String) []const u8 {
        return self.ptr[0..self.len];
    }
};

/// variant value の判別子（`events` の値とは番号が違う）
pub const Tag = enum(u8) { none = 0, integer = 1, float = 2, boolean = 3, text = 4, bytes = 5, _ };

/// variant value（16 バイト、align 8）。引数の並びは align 8 に置く（揃っていなければ長さ 0 でもトラップ。#430）
pub const Value = extern struct {
    tag: Tag,
    val: extern union {
        integer: i64,
        float: f64,
        boolean: u8,
        text: String,
        bytes: String,
    },

    pub fn none() Value {
        return .{ .tag = .none, .val = .{ .integer = 0 } };
    }

    pub fn integer(v: i64) Value {
        return .{ .tag = .integer, .val = .{ .integer = v } };
    }

    pub fn float(v: f64) Value {
        return .{ .tag = .float, .val = .{ .float = v } };
    }

    pub fn boolean(v: bool) Value {
        return .{ .tag = .boolean, .val = .{ .boolean = @intFromBool(v) } };
    }

    pub fn text(s: []const u8) Value {
        return .{ .tag = .text, .val = .{ .text = String.of(s) } };
    }

    pub fn bytes(s: []const u8) Value {
        return .{ .tag = .bytes, .val = .{ .bytes = String.of(s) } };
    }
};

/// record device-info（16 バイト、align 4）
pub const Info = extern struct {
    address: String,
    kind: String,
};

/// record method-info（12 バイト、align 4）
pub const MethodInfo = extern struct {
    name: String,
    main_thread: u8,

    /// メインスレッドで呼ぶメソッドか（結果は次の tick より後）
    pub fn mainThread(self: *const MethodInfo) bool {
        return self.main_thread != 0;
    }
};

/// variant call-error の判別子
pub const ErrorTag = enum(u8) { kernel = 0, detached = 1, no_such_method = 2, bad_arguments = 3, failed = 4, _ };

/// variant call-error（12 バイト、align 4）
const CallError = extern struct {
    tag: ErrorTag,
    val: extern union {
        kernel: u8,
        message: String,
    },
};

const InfoList = extern struct { ptr: [*]const Info, len: u32 };
const MethodList = extern struct { ptr: [*]const MethodInfo, len: u32 };
const ValueList = extern struct { ptr: [*]const Value, len: u32 };

/// result<list<value>, call-error>（16 バイト、align 4）
const ResultValues = extern struct {
    is_err: u8,
    val: extern union { ok: ValueList, err: CallError },
};

/// result<device, call-error>（16 バイト、align 4）
const ResultHandle = extern struct {
    is_err: u8,
    val: extern union { ok: u32, err: CallError },
};

comptime {
    if (@sizeOf(String) != 8 or @alignOf(String) != 4) @compileError("string");
    if (@sizeOf(Value) != 16 or @alignOf(Value) != 8 or @offsetOf(Value, "val") != 8) @compileError("value");
    if (@sizeOf(Info) != 16 or @alignOf(Info) != 4 or @offsetOf(Info, "kind") != 8) @compileError("device-info");
    if (@sizeOf(MethodInfo) != 12 or @alignOf(MethodInfo) != 4 or @offsetOf(MethodInfo, "main_thread") != 8) @compileError("method-info");
    if (@sizeOf(CallError) != 12 or @alignOf(CallError) != 4 or @offsetOf(CallError, "val") != 4) @compileError("call-error");
    if (@sizeOf(ResultValues) != 16 or @alignOf(ResultValues) != 4 or @offsetOf(ResultValues, "val") != 4) @compileError("result<list<value>>");
    if (@offsetOf(ResultValues, "val") + @offsetOf(ValueList, "len") != 8) @compileError("result ok.len");
    if (@offsetOf(ResultValues, "val") + @offsetOf(CallError, "val") + @offsetOf(String, "len") != 12) @compileError("result err.message.len");
    if (@sizeOf(ResultHandle) != 16 or @offsetOf(ResultHandle, "val") != 4) @compileError("result<device>");
    if (@sizeOf(InfoList) != 8 or @sizeOf(MethodList) != 8 or @sizeOf(ValueList) != 8) @compileError("list");
}

/// 生の import（宣言の名前が import の名前になるので、包む関数と名前が重ならないよう入れ物に入れる）
const raw = struct {
    extern "kibi:device/bus@0.1.0" fn @"list-devices"(ret: *InfoList) void;
    extern "kibi:device/bus@0.1.0" fn @"[static]device.open"(addr_ptr: [*]const u8, addr_len: u32, ret: *ResultHandle) void;
    extern "kibi:device/bus@0.1.0" fn @"[method]device.info"(self: u32, ret: *Info) void;
    extern "kibi:device/bus@0.1.0" fn @"[method]device.methods"(self: u32, ret: *MethodList) void;
    extern "kibi:device/bus@0.1.0" fn @"[method]device.invoke"(self: u32, m_ptr: [*]const u8, m_len: u32, a_ptr: [*]const Value, a_len: u32, ret: *ResultValues) void;
    extern "kibi:device/bus@0.1.0" fn @"[resource-drop]device"(self: u32) void;
};

/// 呼び出しの失敗（`call-error`）。文は `canon.reset` まで読める
pub const Failure = union(enum) {
    /// カーネルの誤り（上限・不正な値・`again` など）
    kernel: Error,
    /// デバイスが外れた・見つからない
    detached,
    /// そのメソッドがない
    no_such_method,
    /// デバイスが引数を断った（文はデバイスの作者が書く。制御文字を含みうる）
    bad_arguments: []const u8,
    /// デバイスの処理が失敗した（文はデバイスの作者が書く。保存の前後で実行中だったものは `interrupted`）
    failed: []const u8,

    fn of(e: CallError) Failure {
        return switch (e.tag) {
            .kernel => .{ .kernel = io.kibiError(e.val.kernel) },
            .detached => .detached,
            .no_such_method => .no_such_method,
            .bad_arguments => .{ .bad_arguments = e.val.message.slice() },
            .failed => .{ .failed = e.val.message.slice() },
            _ => .{ .kernel = error.INVAL },
        };
    }
};

/// 成功の値か [Failure]
pub fn Result(comptime T: type) type {
    return union(enum) { ok: T, err: Failure };
}

/// つながっているデバイスの一覧（待たない）。`canon.reset` まで読める
pub fn listDevices() []const Info {
    _ = canon.ARENA_SIZE; // 使うプログラムにだけ `cabi_realloc` を入れる
    var r: InfoList = undefined;
    raw.@"list-devices"(&r);
    return r.ptr[0..r.len];
}

/// 開いたデバイス（ハンドルはカーネルの fd）。[close] で 1 回だけ閉じる
pub const Device = struct {
    handle: u32,

    /// [address]（小文字の 36 バイト）のデバイスを開く。見つからない・36 バイトでない・大文字を含むなら `detached`
    /// （大文字は呼び手が [lowerAddress] でそろえる）。fd 表が満杯なら `kernel(MFILE)`
    pub fn open(address: []const u8) Result(Device) {
        _ = canon.ARENA_SIZE;
        var r: ResultHandle = undefined;
        raw.@"[static]device.open"(address.ptr, @intCast(address.len), &r);
        if (r.is_err != 0) return .{ .err = Failure.of(r.val.err) };
        return .{ .ok = .{ .handle = r.val.ok } };
    }

    /// address と種類（外れていても開いたときの値）
    pub fn info(self: Device) Info {
        var r: Info = undefined;
        raw.@"[method]device.info"(self.handle, &r);
        return r;
    }

    /// メソッドの一覧（登録した順）。外れている・種類が変わったなら空
    pub fn methods(self: Device) []const MethodInfo {
        var r: MethodList = undefined;
        raw.@"[method]device.methods"(self.handle, &r);
        return r.ptr[0..r.len];
    }

    /// [method] を [args]（align 8）で呼び、結果まで待つ。上限（[MAX_VALUES]・[MAX_PAYLOAD_BYTES]・[MAX_METHOD_BYTES]）を
    /// 超えると `kernel(TOOBIG)`・`kernel(NAMETOOLONG)`、text が UTF-8 でなければ `kernel(INVAL)`。
    /// 失敗の後に自動でやり直さない（メインスレッドのメソッドはもう走ったかもしれない）
    pub fn invoke(self: Device, method: []const u8, args: []const Value) Result([]const Value) {
        var r: ResultValues = undefined;
        raw.@"[method]device.invoke"(self.handle, method.ptr, @intCast(method.len), args.ptr, @intCast(args.len), &r);
        if (r.is_err != 0) return .{ .err = Failure.of(r.val.err) };
        return .{ .ok = r.val.ok.ptr[0..r.val.ok.len] };
    }

    /// 閉じる（1 回だけ呼ぶ。閉じたハンドルをもう一度渡すとトラップ）
    pub fn close(self: Device) void {
        raw.@"[resource-drop]device"(self.handle);
    }
};

// ---- デバイスの指定 ----

/// 種類 [kind] が [want] に当たるか。全体が同じか、[want] に `:` がなければ `:` の後ろが同じ
/// （`screen` は `kibicomputers:screen` に当たる。Lua の `peripheral.hasType` と同じ規則。ADR 0094）
pub fn hasType(kind: []const u8, want: []const u8) bool {
    if (eql(kind, want)) return true;
    for (want) |c| if (c == ':') return false;
    for (kind, 0..) |c, i| {
        if (c == ':') return eql(kind[i + 1 ..], want);
    }
    return false;
}

/// [s] の英大文字を小文字にして [buf] に書く（カーネルは大文字の address を受けない）。[buf] より長ければ null
pub fn lowerAddress(buf: []u8, s: []const u8) ?[]const u8 {
    if (s.len > buf.len) return null;
    for (s, 0..) |c, i| buf[i] = if (c >= 'A' and c <= 'Z') c + ('a' - 'A') else c;
    return buf[0..s.len];
}

/// デバイスの指定 [spec]（[lowerAddress] でそろえた後）が、address・種類の組に当たるか:
/// - address と同じ（`exact`）
/// - address の頭（`prefix`）か、種類が [hasType] で当たる（`kind`）
pub const Match = enum { none, exact, prefix, kind };

pub fn match(address: []const u8, kind: []const u8, spec: []const u8) Match {
    if (spec.len == 0) return .none;
    if (eql(address, spec)) return .exact;
    if (address.len > spec.len and eql(address[0..spec.len], spec)) return .prefix;
    if (hasType(kind, spec)) return .kind;
    return .none;
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}
