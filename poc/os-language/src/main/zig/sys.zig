//! WASI p1 の生の import と、4 つのプログラムが共有する小さな道具（#32）。
//!
//! MoonBit 側（`../moonbit/sys`）と同じ形にそろえるため、std の高い層（`std.Io`・アロケーター）は使わず、
//! import を整数の引数でそのまま宣言する。バッファは静的に置く（.bss はゼロなので、触るまで RAM に数えない）。

pub extern "wasi_snapshot_preview1" fn fd_write(fd: i32, iovs: [*]const Iovec, n: u32, written: *u32) u16;
pub extern "wasi_snapshot_preview1" fn fd_read(fd: i32, iovs: [*]const Iovec, n: u32, nread: *u32) u16;
pub extern "wasi_snapshot_preview1" fn fd_readdir(fd: i32, buf: [*]u8, len: u32, cookie: u64, used: *u32) u16;
pub extern "wasi_snapshot_preview1" fn fd_close(fd: i32) u16;
pub extern "wasi_snapshot_preview1" fn path_open(dirfd: i32, dirflags: u32, path: [*]const u8, path_len: u32, oflags: u16, base: u64, inheriting: u64, fdflags: u16, fd: *i32) u16;
pub extern "wasi_snapshot_preview1" fn args_sizes_get(argc: *u32, buf_size: *u32) u16;
pub extern "wasi_snapshot_preview1" fn args_get(argv: [*][*:0]u8, buf: [*]u8) u16;
pub extern "wasi_snapshot_preview1" fn proc_exit(code: u32) noreturn;

/// 独自拡張（#408・#73。`kibi:computer/memory@0.1.0` の `commit`）。`[ptr, ptr+len)` を今のうちに RAM として数える。数えた後の残りが keep 未満になるなら数えずに 0
extern "kibi:computer/memory@0.1.0" fn commit(ptr: u32, len: u32, keep: u64) i32;
pub fn memory_commit(ptr: u32, len: u32, keep: u64) i32 {
    return commit(ptr, len, keep);
}
/// 独自拡張（#408・#73。`kibi:computer/memory@0.1.0` の `remaining`）。残りの RAM（バイト）
extern "kibi:computer/memory@0.1.0" fn remaining() u64;
pub fn memory_remaining() u64 {
    return remaining();
}

pub const Iovec = extern struct { base: [*]const u8, len: u32 };

pub const STDIN: i32 = 0;
pub const STDOUT: i32 = 1;
pub const STDERR: i32 = 2;
/// カーネルが fd 3 に置く根 `/` の preopen（`SpawnOptions.ROOT_PREOPEN`）
pub const ROOT: i32 = 3;

/// 全部書く。失敗したら false
pub fn writeAll(fd: i32, bytes: []const u8) bool {
    var rest = bytes;
    while (rest.len > 0) {
        const iov = [_]Iovec{.{ .base = rest.ptr, .len = @intCast(rest.len) }};
        var n: u32 = 0;
        if (fd_write(fd, &iov, 1, &n) != 0 or n == 0) return false;
        rest = rest[n..];
    }
    return true;
}

/// 1 回読む。EOF なら 0、失敗なら null
pub fn read(fd: i32, buf: []u8) ?usize {
    const iov = [_]Iovec{.{ .base = buf.ptr, .len = @intCast(buf.len) }};
    var n: u32 = 0;
    if (fd_read(fd, &iov, 1, &n) != 0) return null;
    return n;
}

var args_buf: [4096]u8 = undefined;
var argv_ptrs: [64][*:0]u8 = undefined;

/// 引数（argv）。入りきらなければ空
pub fn args() []const [*:0]u8 {
    var argc: u32 = 0;
    var size: u32 = 0;
    if (args_sizes_get(&argc, &size) != 0 or argc > argv_ptrs.len or size > args_buf.len) return &.{};
    if (args_get(&argv_ptrs, &args_buf) != 0) return &.{};
    return argv_ptrs[0..argc];
}

pub fn span(s: [*:0]const u8) []const u8 {
    var n: usize = 0;
    while (s[n] != 0) n += 1;
    return s[0..n];
}

/// 根からの相対パスにする（先頭の `/` を落とす。空なら `.`）
pub fn relative(path: []const u8) []const u8 {
    var p = path;
    while (p.len > 0 and p[0] == '/') p = p[1..];
    return if (p.len == 0) "." else p;
}

/// rights_t のビット: fd_read・fd_readdir
pub const RIGHT_FD_READ: u64 = 1 << 1;
pub const RIGHT_FD_READDIR: u64 = 1 << 14;
/// oflags_t のビット: ディレクトリ
pub const OFLAG_DIRECTORY: u16 = 1 << 1;

/// 根からの [path] を開く。失敗なら null
pub fn open(path: []const u8, directory: bool) ?i32 {
    const rel = relative(path);
    var fd: i32 = -1;
    const rights: u64 = if (directory) RIGHT_FD_READDIR else RIGHT_FD_READ;
    const oflags: u16 = if (directory) OFLAG_DIRECTORY else 0;
    if (path_open(ROOT, 1, rel.ptr, @intCast(rel.len), oflags, rights, 0, 0, &fd) != 0) return null;
    return fd;
}
