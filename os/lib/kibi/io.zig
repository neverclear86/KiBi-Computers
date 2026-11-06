//! fd の読み書きと、独自拡張 `kibi:io/fd`・`kibi:io/pipe`（#75）の包み（#98）。
const wasi = @import("wasi.zig");
const errno = @import("errno.zig");

pub const Error = errno.Error;
pub const Fd = wasi.Fd;

pub const STDIN: Fd = 0;
pub const STDOUT: Fd = 1;
pub const STDERR: Fd = 2;

/// 1 回書く。書けたバイト数
pub fn write(fd: Fd, bytes: []const u8) Error!usize {
    const iov = [_]wasi.Ciovec{.{ .base = bytes.ptr, .len = @intCast(bytes.len) }};
    var n: wasi.Size = 0;
    try errno.check(wasi.fd_write(fd, &iov, 1, &n));
    return n;
}

/// 全部書く。0 バイトしか書けなければ `IO`
pub fn writeAll(fd: Fd, bytes: []const u8) Error!void {
    var rest = bytes;
    while (rest.len > 0) {
        const n = try write(fd, rest);
        if (n == 0) return error.IO;
        rest = rest[n..];
    }
}

/// 1 回読む。EOF なら 0
pub fn read(fd: Fd, buf: []u8) Error!usize {
    const iov = [_]wasi.Iovec{.{ .base = buf.ptr, .len = @intCast(buf.len) }};
    var n: wasi.Size = 0;
    try errno.check(wasi.fd_read(fd, &iov, 1, &n));
    return n;
}

/// [buf] が埋まるか EOF まで読む。読めたバイト数
pub fn readAll(fd: Fd, buf: []u8) Error!usize {
    var total: usize = 0;
    while (total < buf.len) {
        const n = try read(fd, buf[total..]);
        if (n == 0) break;
        total += n;
    }
    return total;
}

pub fn close(fd: Fd) Error!void {
    try errno.check(wasi.fd_close(fd));
}

pub const Whence = enum(u8) { set = wasi.WHENCE_SET, cur = wasi.WHENCE_CUR, end = wasi.WHENCE_END };

/// 位置を動かす。動かした後の位置
pub fn seek(fd: Fd, offset: i64, whence: Whence) Error!u64 {
    var pos: wasi.Filesize = 0;
    try errno.check(wasi.fd_seek(fd, offset, @intFromEnum(whence), &pos));
    return pos;
}

/// 標準出力に全部書く（失敗は捨てる）
pub fn out(bytes: []const u8) void {
    writeAll(STDOUT, bytes) catch {};
}

/// 標準エラー出力に全部書く（失敗は捨てる）
pub fn err(bytes: []const u8) void {
    writeAll(STDERR, bytes) catch {};
}

/// 符号なし整数を 10 進で [buf] に書く。書いた部分
pub fn formatUnsigned(buf: []u8, value: u64) []const u8 {
    var tmp: [20]u8 = undefined;
    var v = value;
    var i: usize = tmp.len;
    while (true) {
        i -= 1;
        tmp[i] = @intCast('0' + v % 10);
        v /= 10;
        if (v == 0) break;
    }
    const digits = tmp[i..];
    const n = @min(digits.len, buf.len);
    @memcpy(buf[0..n], digits[0..n]);
    return buf[0..n];
}

/// 10 進の符号なし整数を読む。空・数字以外・桁あふれは null
pub fn parseUnsigned(s: []const u8) ?u64 {
    if (s.len == 0) return null;
    var v: u64 = 0;
    for (s) |c| {
        if (c < '0' or c > '9') return null;
        const mul = @mulWithOverflow(v, 10);
        if (mul[1] != 0) return null;
        const add = @addWithOverflow(mul[0], c - '0');
        if (add[1] != 0) return null;
        v = add[0];
    }
    return v;
}

// ---- kibi:io/fd・kibi:io/pipe（ABI は docs/adr/0075-kibi-io-pipe-dup.md）----

/// result<u32, error-code>（8 バイト、align 4）。err は val の下位バイト
const ResultU32 = extern struct { is_err: u8, val: u32 };
/// result<_, error-code>（2 バイト）
pub const ResultUnit = extern struct { is_err: u8, err: u8 };
/// result<pipe-ends, error-code>（12 バイト）
const ResultPipe = extern struct { is_err: u8, read: u32, write: u32 };

/// 生の import（宣言の名前が import の名前になるので、包む関数と名前が重ならないよう入れ物に入れる）
const raw = struct {
    extern "kibi:io/fd@0.1.0" fn dup(fd: u32, ret: *ResultU32) void;
    extern "kibi:io/fd@0.1.0" fn @"dup-to"(fd: u32, to: u32, ret: *ResultUnit) void;
    extern "kibi:io/pipe@0.1.0" fn create(ret: *ResultPipe) void;
};

/// kibi:kernel/types の error-code（判別子）を [Error] にする（p1 の errno = 判別子 + 1）
pub fn kibiError(code: u8) Error {
    return errno.toError(@enumFromInt(@as(u16, code) + 1));
}

pub fn checkUnit(r: ResultUnit) Error!void {
    if (r.is_err != 0) return kibiError(r.err);
}

/// fd を複製する（空いている最小の番号）
pub fn dup(fd: Fd) Error!Fd {
    var r: ResultU32 = undefined;
    raw.dup(fd, &r);
    if (r.is_err != 0) return kibiError(@truncate(r.val));
    return r.val;
}

/// fd を [to] に複製する（[to] が開いていれば閉じてから。dup2 相当）
pub fn dupTo(fd: Fd, to: Fd) Error!void {
    var r: ResultUnit = undefined;
    raw.@"dup-to"(fd, to, &r);
    try checkUnit(r);
}

pub const Pipe = struct { read: Fd, write: Fd };

/// パイプを作る
pub fn pipe() Error!Pipe {
    var r: ResultPipe = undefined;
    raw.create(&r);
    if (r.is_err != 0) return kibiError(@truncate(r.read));
    return .{ .read = r.read, .write = r.write };
}

comptime {
    if (@sizeOf(ResultU32) != 8 or @offsetOf(ResultU32, "val") != 4) @compileError("result<u32>");
    if (@sizeOf(ResultUnit) != 2) @compileError("result<_>");
    if (@sizeOf(ResultPipe) != 12 or @offsetOf(ResultPipe, "read") != 4) @compileError("result<pipe-ends>");
}
