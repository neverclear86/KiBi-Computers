//! WASI p1（`wasi_snapshot_preview1`）の生の import（#98）。
//!
//! Zig の標準ライブラリ（`std.os.wasi`・`std.Io`）は版ごとに形が変わるので使わず、ここで import を宣言する。
//! 戻り値はどれも errno（`u16`。0 が成功）。包んだ関数は `io.zig`・`fs.zig`・`process.zig` などにある。

pub const Fd = u32;
pub const Size = u32;
pub const Filesize = u64;
pub const Timestamp = u64;
pub const Rights = u64;

pub const Iovec = extern struct { base: [*]u8, len: Size };
pub const Ciovec = extern struct { base: [*]const u8, len: Size };

/// filetype_t
pub const Filetype = enum(u8) {
    UNKNOWN = 0,
    BLOCK_DEVICE = 1,
    CHARACTER_DEVICE = 2,
    DIRECTORY = 3,
    REGULAR_FILE = 4,
    SOCKET_DGRAM = 5,
    SOCKET_STREAM = 6,
    SYMBOLIC_LINK = 7,
    _,
};

/// filestat_t（64 バイト）
pub const Filestat = extern struct {
    dev: u64,
    ino: u64,
    filetype: Filetype,
    nlink: u64,
    size: Filesize,
    atim: Timestamp,
    mtim: Timestamp,
    ctim: Timestamp,
};

/// dirent_t（24 バイト。名前は後ろに続く）
pub const Dirent = extern struct {
    next: u64,
    ino: u64,
    namlen: u32,
    filetype: Filetype,
};

/// subscription_t（48 バイト）。`tag` が 0 なら clock、1・2 なら fd_read・fd_write
pub const Subscription = extern struct {
    userdata: u64,
    tag: u8,
    u: extern union {
        clock: extern struct { id: u32, timeout: Timestamp, precision: Timestamp, flags: u16 },
        fd: extern struct { fd: Fd },
    },
};

/// event_t（32 バイト）
pub const Event = extern struct {
    userdata: u64,
    @"error": u16,
    tag: u8,
    nbytes: Filesize,
    flags: u16,
};

pub const EVENTTYPE_CLOCK: u8 = 0;
pub const EVENTTYPE_FD_READ: u8 = 1;
pub const EVENTTYPE_FD_WRITE: u8 = 2;
/// eventrwflags_t: 相手側が閉じた（パイプ）
pub const EVENT_FD_READWRITE_HANGUP: u16 = 1;
/// subclockflags_t: timeout が絶対時刻
pub const SUBSCRIPTION_CLOCK_ABSTIME: u16 = 1;

/// clockid_t
pub const CLOCK_REALTIME: u32 = 0;
pub const CLOCK_MONOTONIC: u32 = 1;

/// whence_t
pub const WHENCE_SET: u8 = 0;
pub const WHENCE_CUR: u8 = 1;
pub const WHENCE_END: u8 = 2;

/// lookupflags_t: シンボリックリンクをたどる
pub const LOOKUP_SYMLINK_FOLLOW: u32 = 1;

/// oflags_t
pub const O_CREAT: u16 = 1 << 0;
pub const O_DIRECTORY: u16 = 1 << 1;
pub const O_EXCL: u16 = 1 << 2;
pub const O_TRUNC: u16 = 1 << 3;

/// fstflags_t: 時刻を今にする
pub const FST_ATIM_NOW: u16 = 1 << 1;
pub const FST_MTIM_NOW: u16 = 1 << 3;

/// fdflags_t
pub const FDFLAG_APPEND: u16 = 1 << 0;
pub const FDFLAG_NONBLOCK: u16 = 1 << 2;

/// rights_t（使うものだけ）
pub const RIGHT_FD_READ: Rights = 1 << 1;
pub const RIGHT_FD_SEEK: Rights = 1 << 2;
pub const RIGHT_FD_TELL: Rights = 1 << 5;
pub const RIGHT_FD_WRITE: Rights = 1 << 6;
pub const RIGHT_FD_READDIR: Rights = 1 << 14;
pub const RIGHT_FD_FILESTAT_GET: Rights = 1 << 21;
/// 開くときに求める権利（全部。カーネルは持てない権利を黙って落とす）
pub const RIGHTS_ALL: Rights = (1 << 29) - 1;


pub extern "wasi_snapshot_preview1" fn args_sizes_get(argc: *Size, buf_size: *Size) u16;
pub extern "wasi_snapshot_preview1" fn args_get(argv: [*][*:0]u8, buf: [*]u8) u16;
pub extern "wasi_snapshot_preview1" fn environ_sizes_get(count: *Size, buf_size: *Size) u16;
pub extern "wasi_snapshot_preview1" fn environ_get(environ: [*][*:0]u8, buf: [*]u8) u16;
pub extern "wasi_snapshot_preview1" fn clock_time_get(id: u32, precision: Timestamp, time: *Timestamp) u16;
pub extern "wasi_snapshot_preview1" fn fd_close(fd: Fd) u16;
pub extern "wasi_snapshot_preview1" fn fd_read(fd: Fd, iovs: [*]const Iovec, n: Size, nread: *Size) u16;
pub extern "wasi_snapshot_preview1" fn fd_write(fd: Fd, iovs: [*]const Ciovec, n: Size, written: *Size) u16;
pub extern "wasi_snapshot_preview1" fn fd_seek(fd: Fd, offset: i64, whence: u8, newoffset: *Filesize) u16;
pub extern "wasi_snapshot_preview1" fn fd_filestat_get(fd: Fd, buf: *Filestat) u16;
pub extern "wasi_snapshot_preview1" fn fd_readdir(fd: Fd, buf: [*]u8, len: Size, cookie: u64, used: *Size) u16;
pub extern "wasi_snapshot_preview1" fn path_open(dirfd: Fd, dirflags: u32, path: [*]const u8, path_len: Size, oflags: u16, base: Rights, inheriting: Rights, fdflags: u16, fd: *Fd) u16;
pub extern "wasi_snapshot_preview1" fn path_filestat_get(dirfd: Fd, flags: u32, path: [*]const u8, path_len: Size, buf: *Filestat) u16;
pub extern "wasi_snapshot_preview1" fn path_filestat_set_times(dirfd: Fd, flags: u32, path: [*]const u8, path_len: Size, atim: Timestamp, mtim: Timestamp, fst_flags: u16) u16;
pub extern "wasi_snapshot_preview1" fn path_create_directory(dirfd: Fd, path: [*]const u8, path_len: Size) u16;
pub extern "wasi_snapshot_preview1" fn path_remove_directory(dirfd: Fd, path: [*]const u8, path_len: Size) u16;
pub extern "wasi_snapshot_preview1" fn path_unlink_file(dirfd: Fd, path: [*]const u8, path_len: Size) u16;
pub extern "wasi_snapshot_preview1" fn path_rename(old_fd: Fd, old_path: [*]const u8, old_len: Size, new_fd: Fd, new_path: [*]const u8, new_len: Size) u16;
pub extern "wasi_snapshot_preview1" fn poll_oneoff(in: [*]const Subscription, out: [*]Event, n: Size, nevents: *Size) u16;
pub extern "wasi_snapshot_preview1" fn proc_exit(code: u32) noreturn;
pub extern "wasi_snapshot_preview1" fn sched_yield() u16;
pub extern "wasi_snapshot_preview1" fn random_get(buf: [*]u8, len: Size) u16;

comptime {
    if (@sizeOf(Subscription) != 48 or @offsetOf(Subscription, "u") != 16) @compileError("subscription_t");
    if (@sizeOf(Event) != 32 or @offsetOf(Event, "nbytes") != 16 or @offsetOf(Event, "flags") != 24) @compileError("event_t");
    if (@sizeOf(Filestat) != 64 or @offsetOf(Filestat, "size") != 32) @compileError("filestat_t");
    if (@sizeOf(Dirent) != 24 or @offsetOf(Dirent, "filetype") != 20) @compileError("dirent_t");
}
