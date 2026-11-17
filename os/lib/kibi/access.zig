//! 独自拡張 `kibi:computer/access`（#110。ABI は docs/adr/0110-player-access-commands.md）の包み。
//!
//! どれも待たない。[getAccess] の結果は `cabi_realloc` の領域（`canon.zig`）に置かれ、`canon.reset` を呼ぶまで読める。
//! これを使うプログラムは `cabi_realloc` を export する（`core/build.gradle.kts` の `osReallocPrograms`）。
//! `request*` は依頼を出すだけで、反映はゲーム内で持ち主か OP が承認したとき（ok は「受け付けた」の意味）。
//! 配置は `core/src/main/wasm/common/kibi_access.h` と同じ（下の `comptime` で確かめる）。
const io = @import("io.zig");
const errno = @import("errno.zig");
const canon = @import("canon.zig");

pub const Error = errno.Error;

/// string（8 バイト、align 4）
pub const String = extern struct {
    ptr: [*]const u8,
    len: u32,

    pub fn slice(self: String) []const u8 {
        return self.ptr[0..self.len];
    }
};

/// enum access-state
pub const State = enum(u8) { unowned = 0, owned = 1, locked = 2, _ };

/// record player（16 バイト、align 4）。`id` は UUID の小文字の正規形
pub const Player = extern struct {
    name: String,
    id: String,
};

/// record access-info（40 バイト、align 4）
pub const Info = extern struct {
    state: State,
    _pad0: [3]u8,
    /// option<player> の判別子（@4。payload は @8）
    owner_is_some: u8,
    owner_val: Player,
    members_ptr: [*]const Player,
    members_len: u32,
    invites_ptr: [*]const String,
    invites_len: u32,

    /// 持ち主（`unowned`・`locked` なら null）
    pub fn owner(self: *const Info) ?*const Player {
        return if (self.owner_is_some != 0) &self.owner_val else null;
    }

    pub fn members(self: *const Info) []const Player {
        return self.members_ptr[0..self.members_len];
    }

    /// 名前で足した保留（小文字）
    pub fn invites(self: *const Info) []const String {
        return self.invites_ptr[0..self.invites_len];
    }
};

/// result<access-info, error-code>（44 バイト、align 4）。err のときは payload の先頭の 1 バイトが判別子
const ResultInfo = extern struct {
    is_err: u8,
    payload: Info,
};

comptime {
    if (@sizeOf(String) != 8 or @alignOf(String) != 4) @compileError("string");
    if (@sizeOf(Player) != 16 or @alignOf(Player) != 4 or @offsetOf(Player, "id") != 8) @compileError("player");
    if (@offsetOf(Info, "owner_is_some") != 4 or @offsetOf(Info, "owner_val") != 8) @compileError("access-info.owner");
    if (@offsetOf(Info, "members_ptr") != 24 or @offsetOf(Info, "invites_ptr") != 32) @compileError("access-info.lists");
    if (@sizeOf(Info) != 40 or @alignOf(Info) != 4) @compileError("access-info");
    if (@sizeOf(ResultInfo) != 44 or @offsetOf(ResultInfo, "payload") != 4) @compileError("result<access-info>");
}

/// 生の import（宣言の名前が import の名前になるので、包む関数と名前が重ならないよう入れ物に入れる）
const raw = struct {
    extern "kibi:computer/access@0.1.0" fn @"get-access"(ret: *ResultInfo) void;
    extern "kibi:computer/access@0.1.0" fn @"request-add"(ptr: [*]const u8, len: u32, ret: *io.ResultUnit) void;
    extern "kibi:computer/access@0.1.0" fn @"request-remove"(ptr: [*]const u8, len: u32, ret: *io.ResultUnit) void;
    extern "kibi:computer/access@0.1.0" fn @"request-transfer"(ptr: [*]const u8, len: u32, ret: *io.ResultUnit) void;
};

/// 今の持ち主と権限。持ち主と権限のないコンピューターなら `NOTSUP`。`canon.reset` まで読める
pub fn getAccess() Error!Info {
    _ = canon.ARENA_SIZE; // 使うプログラムにだけ `cabi_realloc` を入れる
    var r: ResultInfo = undefined;
    raw.@"get-access"(&r);
    if (r.is_err != 0) return io.kibiError(@intFromEnum(r.payload.state));
    return r.payload;
}

/// 名前 [name] を保留として足すよう依頼する。形が違えば `INVAL`、口がなければ `NOTSUP`、承認待ちが上限なら `AGAIN`
pub fn requestAdd(name: []const u8) Error!void {
    var r: io.ResultUnit = undefined;
    raw.@"request-add"(name.ptr, @intCast(name.len), &r);
    try io.checkUnit(r);
}

/// メンバー（UUID）か保留（名前）を外すよう依頼する。エラーは [requestAdd] と同じ
pub fn requestRemove(name_or_id: []const u8) Error!void {
    var r: io.ResultUnit = undefined;
    raw.@"request-remove"(name_or_id.ptr, @intCast(name_or_id.len), &r);
    try io.checkUnit(r);
}

/// 持ち主を [name] のプレイヤーに変えるよう依頼する。エラーは [requestAdd] と同じ
pub fn requestTransfer(name: []const u8) Error!void {
    var r: io.ResultUnit = undefined;
    raw.@"request-transfer"(name.ptr, @intCast(name.len), &r);
    try io.checkUnit(r);
}
