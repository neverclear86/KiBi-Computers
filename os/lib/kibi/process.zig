//! 引数・環境変数・終了と、独自拡張 `kibi:process/process`（#74。ABI は docs/adr/0074-kibi-process.md）の包み（#98）。
//!
//! 引数と環境変数は、初めて使うときに静的な領域に読み込む（.bss はゼロなので、触るまで RAM に数えない）。
const wasi = @import("wasi.zig");
const errno = @import("errno.zig");
const io = @import("io.zig");

pub const Error = errno.Error;

/// 引数と環境変数を読み込む領域の大きさ（バイト。カーネルの上限 64 KiB に合わせる。ADR 0045）。入りきらなければ `TOOBIG`
pub const ARGS_BUF_SIZE = 64 * 1024;
/// 引数と環境変数の数の上限
pub const MAX_ARGS = 256;

var args_buf: [ARGS_BUF_SIZE]u8 = undefined;
var args_ptrs: [MAX_ARGS][*:0]u8 = undefined;
var args_slices: [MAX_ARGS][]const u8 = undefined;
var args_len: ?usize = null;

var env_buf: [ARGS_BUF_SIZE]u8 = undefined;
var env_ptrs: [MAX_ARGS][*:0]u8 = undefined;
var env_slices: [MAX_ARGS][]const u8 = undefined;
var env_len: ?usize = null;

fn span(s: [*:0]const u8) []const u8 {
    var n: usize = 0;
    while (s[n] != 0) n += 1;
    return s[0..n];
}

/// 引数（argv。`args()[0]` はプログラムの名前）
pub fn args() Error![]const []const u8 {
    if (args_len) |n| return args_slices[0..n];
    var argc: wasi.Size = 0;
    var size: wasi.Size = 0;
    try errno.check(wasi.args_sizes_get(&argc, &size));
    if (argc > MAX_ARGS or size > ARGS_BUF_SIZE) return error.TOOBIG;
    try errno.check(wasi.args_get(&args_ptrs, &args_buf));
    for (0..argc) |i| args_slices[i] = span(args_ptrs[i]);
    args_len = argc;
    return args_slices[0..argc];
}

/// 環境変数（`名前=値` の並び）
pub fn environ() Error![]const []const u8 {
    if (env_len) |n| return env_slices[0..n];
    var count: wasi.Size = 0;
    var size: wasi.Size = 0;
    try errno.check(wasi.environ_sizes_get(&count, &size));
    if (count > MAX_ARGS or size > ARGS_BUF_SIZE) return error.TOOBIG;
    try errno.check(wasi.environ_get(&env_ptrs, &env_buf));
    for (0..count) |i| env_slices[i] = span(env_ptrs[i]);
    env_len = count;
    return env_slices[0..count];
}

/// 環境変数 [name] の値。なければ（読めなければ）null
pub fn getenv(name: []const u8) ?[]const u8 {
    const all = environ() catch return null;
    for (all) |entry| {
        if (entry.len > name.len and entry[name.len] == '=' and eql(entry[0..name.len], name)) return entry[name.len + 1 ..];
    }
    return null;
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}

/// プロセスを終える
pub fn exit(code: u8) noreturn {
    wasi.proc_exit(code);
}

/// 計算を続けるプロセスが、ほかに CPU を譲る
pub fn yield() void {
    _ = wasi.sched_yield();
}

// ---- kibi:process/process ----


/// enum signal
pub const Signal = enum(u8) {
    /// Ctrl+C
    interrupt = 0,
    /// Ctrl+\
    quit = 1,
    /// 普通の終了の求め
    terminate = 2,
    /// 無視できない
    kill = 3,
};

/// 子の終わり方（variant exit-status）
pub const ExitStatus = union(enum) {
    /// 終了コード
    exited: u32,
    /// シグナルで止められた。null はマシンの停止・再起動
    killed: ?Signal,
    /// トラップ・例外
    crashed,
};

/// string（8 バイト、align 4）
pub const String = extern struct { ptr: [*]const u8, len: u32 };
/// tuple<string, string>
pub const EnvEntry = extern struct { name: String, value: String };
/// record fd-assign（12 バイト）。tag が 0 なら親の [parent] を渡し、1 なら割り当てない
pub const FdAssign = extern struct {
    fd: u32,
    tag: u8,
    parent: u32,

    /// 子の [fd] に、親の [parent] を渡す
    pub fn inherit(fd: u32, parent: u32) FdAssign {
        return .{ .fd = fd, .tag = 0, .parent = parent };
    }

    /// 子の [fd] を閉じる（0〜2 を引き継がないとき）
    pub fn closed(fd: u32) FdAssign {
        return .{ .fd = fd, .tag = 1, .parent = 0 };
    }
};

const ResultProcess = extern struct { is_err: u8, val: u32 };

const raw = struct {
    extern "kibi:process/process@0.1.0" fn @"[static]process.spawn"(
        path_ptr: [*]const u8,
        path_len: u32,
        args_ptr: [*]const String,
        args_len: u32,
        env_is_some: i32,
        env_ptr: [*]const EnvEntry,
        env_len: u32,
        cwd_is_some: i32,
        cwd_ptr: [*]const u8,
        cwd_len: u32,
        fds_ptr: [*]const FdAssign,
        fds_len: u32,
        ret: *ResultProcess,
    ) void;
    extern "kibi:process/process@0.1.0" fn @"[method]process.pid"(self: u32) u32;
    extern "kibi:process/process@0.1.0" fn @"[method]process.status"(self: u32, ret: *align(4) [16]u8) void;
    extern "kibi:process/process@0.1.0" fn @"[method]process.kill"(self: u32, sig: i32, ret: *io.ResultUnit) void;
    extern "kibi:process/process@0.1.0" fn @"[resource-drop]process"(self: u32) void;
    extern "kibi:process/process@0.1.0" fn @"kill-pid"(pid: u32, sig: i32, ret: *io.ResultUnit) void;
    extern "kibi:process/process@0.1.0" fn @"self-pid"() u32;
    extern "kibi:process/process@0.1.0" fn @"parent-pid"() u32;
    extern "kibi:process/process@0.1.0" fn @"set-signal-ignored"(sig: i32, ignored: i32, ret: *io.ResultUnit) void;
    extern "kibi:process/process@0.1.0" fn @"set-cwd"(path_ptr: [*]const u8, path_len: u32, ret: *io.ResultUnit) void;
};

/// 子を作るときの指定
pub const SpawnOptions = struct {
    /// 子の環境変数（`名前`・`値`）。null なら親のものを引き継ぐ
    env: ?[]const EnvEntry = null,
    /// 子の cwd。null なら親のもの
    cwd: ?[]const u8 = null,
    /// fd の割り当て。0〜2 は書かなければ引き継ぎ、3 以降は書いたものだけ（[inherit_root] を除く）
    fds: []const FdAssign = &.{},
    /// 根 `/` の preopen（fd 3。`fs.ROOT`）を子にも渡す（[fds] に fd 3 があればそちらを使う）。
    /// 標準 OS のプログラムはパスを fd 3 から開くので、普通は渡す
    inherit_root: bool = true,
};

/// [SpawnOptions.fds] の数の上限
pub const MAX_SPAWN_FDS = 32;
/// 根の preopen の fd（`fs.ROOT` と同じ）
const ROOT_FD: u32 = 3;

/// 引数を [String] に写す領域の大きさ
pub const MAX_SPAWN_ARGS = 256;

/// 子のプロセス（ハンドルはカーネルの fd 番号）
pub const Child = struct {
    handle: u32,

    /// 子の pid
    pub fn pid(self: Child) u32 {
        return raw.@"[method]process.pid"(self.handle);
    }

    /// 終わっていれば終わり方（待たない。受け取ったら刈り取られる）。まだなら null
    pub fn status(self: Child) Error!?ExitStatus {
        var r: [16]u8 align(4) = undefined;
        raw.@"[method]process.status"(self.handle, &r);
        if (r[0] != 0) return io.kibiError(r[4]);
        if (r[4] == 0) return null;
        return switch (r[8]) {
            0 => .{ .exited = @as(u32, r[12]) | @as(u32, r[13]) << 8 | @as(u32, r[14]) << 16 | @as(u32, r[15]) << 24 },
            1 => .{ .killed = if (r[12] == 0) null else @as(Signal, @enumFromInt(r[13])) },
            else => .crashed,
        };
    }

    /// 終わるまで待つ（`poll_oneoff` の FD_READ で待ち、終わり方を受け取る）
    pub fn wait(self: Child) Error!ExitStatus {
        while (true) {
            if (try self.status()) |s| return s;
            const sub = [_]wasi.Subscription{.{ .userdata = 0, .tag = wasi.EVENTTYPE_FD_READ, .u = .{ .fd = .{ .fd = self.handle } } }};
            var ev: [1]wasi.Event = undefined;
            var n: wasi.Size = 0;
            try errno.check(wasi.poll_oneoff(&sub, &ev, 1, &n));
        }
    }

    /// シグナルを送る（待たない）
    pub fn kill(self: Child, sig: Signal) Error!void {
        var r: io.ResultUnit = undefined;
        raw.@"[method]process.kill"(self.handle, @intFromEnum(sig), &r);
        try io.checkUnit(r);
    }

    /// ハンドルを閉じる（子は止めない）
    pub fn release(self: Child) void {
        raw.@"[resource-drop]process"(self.handle);
    }
};

/// [path] の実行ファイルを、引数 [argv]（`argv[0]` を含む）で子として起こす。読み込みが終わるまで待つ
pub fn spawn(path: []const u8, argv: []const []const u8, options: SpawnOptions) Error!Child {
    if (argv.len > MAX_SPAWN_ARGS) return error.TOOBIG;
    var strs: [MAX_SPAWN_ARGS]String = undefined;
    for (argv, 0..) |a, i| strs[i] = .{ .ptr = a.ptr, .len = @intCast(a.len) };
    if (options.fds.len + 1 > MAX_SPAWN_FDS) return error.TOOBIG;
    var fds: [MAX_SPAWN_FDS]FdAssign = undefined;
    @memcpy(fds[0..options.fds.len], options.fds);
    var nfds = options.fds.len;
    if (options.inherit_root) {
        var has_root = false;
        for (options.fds) |f| has_root = has_root or f.fd == ROOT_FD;
        if (!has_root) {
            fds[nfds] = FdAssign.inherit(ROOT_FD, ROOT_FD);
            nfds += 1;
        }
    }
    const env = options.env orelse &[_]EnvEntry{};
    const cwd = options.cwd orelse "";
    var r: ResultProcess = undefined;
    raw.@"[static]process.spawn"(
        path.ptr,
        @intCast(path.len),
        &strs,
        @intCast(argv.len),
        @intFromBool(options.env != null),
        env.ptr,
        @intCast(env.len),
        @intFromBool(options.cwd != null),
        cwd.ptr,
        @intCast(cwd.len),
        &fds,
        @intCast(nfds),
        &r,
    );
    if (r.is_err != 0) return io.kibiError(@truncate(r.val));
    return .{ .handle = r.val };
}

/// pid [pid] のプロセスにシグナルを送る
pub fn killPid(target: u32, sig: Signal) Error!void {
    var r: io.ResultUnit = undefined;
    raw.@"kill-pid"(target, @intFromEnum(sig), &r);
    try io.checkUnit(r);
}

/// 自分の pid
pub fn selfPid() u32 {
    return raw.@"self-pid"();
}

/// 親の pid（親がなければ 0）
pub fn parentPid() u32 {
    return raw.@"parent-pid"();
}

/// シグナル [sig] を無視するか（`kill` は無視できない）
pub fn setSignalIgnored(sig: Signal, ignored: bool) Error!void {
    var r: io.ResultUnit = undefined;
    raw.@"set-signal-ignored"(@intFromEnum(sig), @intFromBool(ignored), &r);
    try io.checkUnit(r);
}

/// カーネルの cwd を変える（このライブラリのパスの解決にも使う。`fs.cwd()`）
pub fn setCwdRaw(path: []const u8) Error!void {
    var r: io.ResultUnit = undefined;
    raw.@"set-cwd"(path.ptr, @intCast(path.len), &r);
    try io.checkUnit(r);
}

comptime {
    if (@sizeOf(String) != 8 or @sizeOf(EnvEntry) != 16) @compileError("string");
    if (@sizeOf(FdAssign) != 12 or @offsetOf(FdAssign, "tag") != 4 or @offsetOf(FdAssign, "parent") != 8) @compileError("fd-assign");
    if (@sizeOf(ResultProcess) != 8) @compileError("result<process>");
}
