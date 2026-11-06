//! 独自拡張 `kibi:io/tty`（#427。ABI は docs/adr/0427-kibi-tty.md）の包み（#98）。
const errno = @import("errno.zig");
const io = @import("io.zig");

pub const Error = errno.Error;

/// flags mode。ビットは個別に効く
pub const Mode = packed struct(u8) {
    /// ICANON: 行単位の入力と行編集
    canonical: bool = false,
    /// ECHO
    echo: bool = false,
    /// ISIG: Ctrl+C・Ctrl+\ をシグナルにする
    signals: bool = false,
    /// ICRNL: 入力の CR を LF にする
    cr_to_nl: bool = false,
    /// OPOST|ONLCR: 出力の LF を CR LF にする
    nl_to_crnl: bool = false,
    _pad: u3 = 0,

    /// 全部オン（既定）
    pub const cooked: Mode = .{ .canonical = true, .echo = true, .signals = true, .cr_to_nl = true, .nl_to_crnl = true };
    /// 全部オフ（cfmakeraw 相当）
    pub const raw: Mode = .{};
};

/// 画面の大きさ。generation は大きさが変わるたびに増える
pub const Size = struct { columns: u32, rows: u32, generation: u64 };

const ResultMode = extern struct { is_err: u8, val: u8 };
const ResultU32 = extern struct { is_err: u8, val: u32 };
const ResultSize = extern struct { is_err: u8, columns: u32 align(8), rows: u32, generation: u64 };

const raw = struct {
    extern "kibi:io/tty@0.1.0" fn @"is-tty"(fd: u32) i32;
    extern "kibi:io/tty@0.1.0" fn @"get-mode"(fd: u32, ret: *ResultMode) void;
    extern "kibi:io/tty@0.1.0" fn @"set-mode"(fd: u32, mode: i32, ret: *io.ResultUnit) void;
    extern "kibi:io/tty@0.1.0" fn @"get-size"(fd: u32, ret: *ResultSize) void;
    extern "kibi:io/tty@0.1.0" fn @"get-foreground"(fd: u32, ret: *ResultU32) void;
    extern "kibi:io/tty@0.1.0" fn @"set-foreground"(fd: u32, pid: u32, ret: *io.ResultUnit) void;
};

/// [fd] が TTY か
pub fn isTty(fd: io.Fd) bool {
    return raw.@"is-tty"(fd) != 0;
}

pub fn getMode(fd: io.Fd) Error!Mode {
    var r: ResultMode = undefined;
    raw.@"get-mode"(fd, &r);
    if (r.is_err != 0) return io.kibiError(r.val);
    return @bitCast(r.val & 0x1f);
}

pub fn setMode(fd: io.Fd, mode: Mode) Error!void {
    var r: io.ResultUnit = undefined;
    raw.@"set-mode"(fd, @as(u8, @bitCast(mode)) & 0x1f, &r);
    try io.checkUnit(r);
}

pub fn getSize(fd: io.Fd) Error!Size {
    var r: ResultSize = undefined;
    raw.@"get-size"(fd, &r);
    if (r.is_err != 0) return io.kibiError(@truncate(r.columns));
    return .{ .columns = r.columns, .rows = r.rows, .generation = r.generation };
}

/// 前景のプロセスの pid（0 = なし）
pub fn getForeground(fd: io.Fd) Error!u32 {
    var r: ResultU32 = undefined;
    raw.@"get-foreground"(fd, &r);
    if (r.is_err != 0) return io.kibiError(@truncate(r.val));
    return r.val;
}

/// 前景のプロセスを [pid] にする（0 = なし）
pub fn setForeground(fd: io.Fd, pid: u32) Error!void {
    var r: io.ResultUnit = undefined;
    raw.@"set-foreground"(fd, pid, &r);
    try io.checkUnit(r);
}

comptime {
    if (@sizeOf(ResultMode) != 2) @compileError("result<mode>");
    if (@sizeOf(ResultSize) != 24 or @offsetOf(ResultSize, "columns") != 8 or @offsetOf(ResultSize, "generation") != 16) @compileError("result<size>");
}
