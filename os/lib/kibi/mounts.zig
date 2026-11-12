//! 独自拡張 `kibi:fs/mounts`（#56・#453。ABI は docs/adr/0056-disk-hotplug-and-mount.md・0453-mount-list-and-usage.md）の包み（#454）。
//!
//! `mount`・`unmount` は待たない。[listMounts]・[listDisks] の結果は `cabi_realloc` の領域（`canon.zig`）に置かれ、
//! `canon.reset` を呼ぶまで読める（包みの中では戻さないので、2 つを続けて呼んでも両方読める）。
//! これらを使うプログラムは `cabi_realloc` を export する（`core/build.gradle.kts` の `osReallocPrograms`）。
//! 配置は `core/src/main/wasm/common/kibi_fs.h` と同じ（下の `comptime` で確かめる）。
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

/// enum fs-kind
pub const Kind = enum(u8) { disk = 0, image = 1, tmp = 2, dev = 3, _ };

/// record fs-usage（40 バイト、align 8）。使用量は管理の分を含むので `du` とは合わない。`tmp` の空きは目安
pub const Usage = extern struct {
    total_bytes: u64,
    used_bytes: u64,
    available_bytes: u64,
    /// 0 なら上限なし（tmp）
    total_entries_is_some: u8,
    total_entries: u32,
    /// 根を除く
    used_entries: u32,

    pub fn totalEntries(self: *const Usage) ?u32 {
        return if (self.total_entries_is_some != 0) self.total_entries else null;
    }
};

/// record mount-info（72 バイト、align 8）
pub const Mount = extern struct {
    point: String,
    source: String,
    kind: Kind,
    /// 0 か 1
    read_only: u8,
    /// `stat` の `dev` と同じ値
    id: u32,
    usage_is_some: u8,
    usage_val: Usage,

    /// 容量。FS がまだつながっていない・数を返さない FS なら null
    pub fn usage(self: *const Mount) ?*const Usage {
        return if (self.usage_is_some != 0) &self.usage_val else null;
    }

    pub fn readOnly(self: *const Mount) bool {
        return self.read_only != 0;
    }
};

/// record disk-info（32 バイト、align 8）
pub const Disk = extern struct {
    /// `disk:<識別子>`（そのまま [mount] に渡せる）
    source: String,
    capacity_bytes: u64,
    max_entries: u32,
    mount_point_is_some: u8,
    mount_point_val: String,

    /// マウントの位置。マウントしていない（開いている途中を含む）なら null
    pub fn mountPoint(self: *const Disk) ?[]const u8 {
        return if (self.mount_point_is_some != 0) self.mount_point_val.slice() else null;
    }
};

const MountList = extern struct { ptr: [*]const Mount, len: u32 };
const DiskList = extern struct { ptr: [*]const Disk, len: u32 };

comptime {
    if (@sizeOf(String) != 8 or @alignOf(String) != 4) @compileError("string");
    if (@sizeOf(Usage) != 40 or @alignOf(Usage) != 8) @compileError("fs-usage");
    if (@offsetOf(Usage, "used_bytes") != 8 or @offsetOf(Usage, "available_bytes") != 16) @compileError("fs-usage.bytes");
    if (@offsetOf(Usage, "total_entries_is_some") != 24 or @offsetOf(Usage, "total_entries") != 28) @compileError("fs-usage.total-entries");
    if (@offsetOf(Usage, "used_entries") != 32) @compileError("fs-usage.used-entries");
    if (@sizeOf(Mount) != 72 or @alignOf(Mount) != 8) @compileError("mount-info");
    if (@offsetOf(Mount, "source") != 8 or @offsetOf(Mount, "kind") != 16) @compileError("mount-info.source/kind");
    if (@offsetOf(Mount, "read_only") != 17 or @offsetOf(Mount, "id") != 20) @compileError("mount-info.read-only/id");
    if (@offsetOf(Mount, "usage_is_some") != 24 or @offsetOf(Mount, "usage_val") != 32) @compileError("mount-info.usage");
    if (@sizeOf(Disk) != 32 or @alignOf(Disk) != 8) @compileError("disk-info");
    if (@offsetOf(Disk, "capacity_bytes") != 8 or @offsetOf(Disk, "max_entries") != 16) @compileError("disk-info.capacity");
    if (@offsetOf(Disk, "mount_point_is_some") != 20 or @offsetOf(Disk, "mount_point_val") != 24) @compileError("disk-info.mount-point");
    if (@sizeOf(MountList) != 8 or @sizeOf(DiskList) != 8) @compileError("list");
}

/// 生の import（宣言の名前が import の名前になるので、包む関数と名前が重ならないよう入れ物に入れる）
const raw = struct {
    extern "kibi:fs/mounts@0.1.0" fn mount(src_ptr: [*]const u8, src_len: u32, pt_ptr: [*]const u8, pt_len: u32, ro: i32, ret: *io.ResultUnit) void;
    extern "kibi:fs/mounts@0.1.0" fn unmount(pt_ptr: [*]const u8, pt_len: u32, ret: *io.ResultUnit) void;
    extern "kibi:fs/mounts@0.1.0" fn @"list-mounts"(ret: *MountList) void;
    extern "kibi:fs/mounts@0.1.0" fn @"list-disks"(ret: *DiskList) void;
};

/// ディスク [source]（`disk:<識別子>`）を [point] にマウントする。[point] の相対パスはカーネルの cwd から解決されるので、
/// 呼び手は絶対パスにして渡す（`fs.absolute`）。ない・同梱イメージなら `NOENT`、使用中・位置が埋まっていれば `BUSY`
pub fn mount(source: []const u8, point: []const u8, read_only: bool) Error!void {
    var r: io.ResultUnit = undefined;
    raw.mount(source.ptr, @intCast(source.len), point.ptr, @intCast(point.len), @intFromBool(read_only), &r);
    try io.checkUnit(r);
}

/// [point] のマウントを外す。マウントの位置でなければ `INVAL`、`/`・`/tmp`・`/dev`・下にマウントがあれば `BUSY`
pub fn unmount(point: []const u8) Error!void {
    var r: io.ResultUnit = undefined;
    raw.unmount(point.ptr, @intCast(point.len), &r);
    try io.checkUnit(r);
}

/// マウントの一覧（足した順。待たない）。`canon.reset` まで読める
pub fn listMounts() []const Mount {
    _ = canon.ARENA_SIZE; // 使うプログラムにだけ `cabi_realloc` を入れる
    var r: MountList = undefined;
    raw.@"list-mounts"(&r);
    return r.ptr[0..r.len];
}

/// 挿さっているディスクの一覧（スロットの順。待たない）。同梱イメージは含まない。`canon.reset` まで読める
pub fn listDisks() []const Disk {
    _ = canon.ARENA_SIZE;
    var r: DiskList = undefined;
    raw.@"list-disks"(&r);
    return r.ptr[0..r.len];
}

