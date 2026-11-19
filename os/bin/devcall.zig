//! `devcall デバイス メソッド [引数…]`: デバイスのメソッドを呼び、結果を 1 行に 1 つ書く（`kibi:device` の `invoke`。#108、ADR 0108）。
//!
//! デバイスの指定（英大文字は小文字にそろえる）:
//! - address 全体と同じものがあれば、それ
//! - なければ、address の頭が同じもの・種類が当たるもの（`screen` は `kibicomputers:screen` に当たる。Lua の `hasType` と同じ）。
//!   1 台だけなら、それ。なければ `no such device`、2 台以上なら `ambiguous device`（どちらも 1）
//!
//! 引数の型（前置きで決める。なければ形から推す）:
//! - `s:文字列`・`i:整数`・`f:小数`（`inf`・`nan` も）・`b:true|false`・`x:16進`（bytes。偶数桁）
//! - 前置きなし: `none` は none、`true`・`false` は boolean、`[+-]?数字` は integer（s64 の外は誤り）、
//!   数字と `.`・`e` の小数は float、ほかは text
//! - 形の誤りは 2。text は UTF-8 でなければならない（2）
//!
//! 結果: none は `none`、integer は 10 進、float は `.`・`e` を含む形（`2.0`・`1e+20`。`inf`・`nan` はそのまま）、
//! boolean は `true`・`false`、text は制御文字を `?` にしたもの、bytes は `x:` と 16 進。
//! 上限（引数 32 個、text・bytes の合計 64 KiB、メソッド名 64 バイト）は呼ぶ前に確かめる（1）。
//! 失敗は `devcall: 対象: 説明` で 1。失敗してもやり直さない（メインスレッドのメソッドはもう走ったかもしれない）
const std = @import("std");
const kibi = @import("kibi");
const cli = kibi.cli;
const device = kibi.device;
const canon = kibi.canon;
const errno = kibi.errno;
const text = kibi.text;
const io = kibi.io;

const SYNOPSIS = "devcall device method [arg ...]";

/// 引数の並び（align 8。`Value` が i64 を含むので揃う）
var args: [device.MAX_VALUES]device.Value = undefined;
/// `x:` の bytes を置く所
var bytes_buf: [device.MAX_PAYLOAD_BYTES]u8 = undefined;
var bytes_used: usize = 0;
var payload: usize = 0;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    // オプションはない（`--` だけ受ける）。負の数の引数はメソッドの後ろなので、オプションと見なされない
    if (opts.next()) |c| return cli.badOption(c, SYNOPSIS);
    const operands = opts.rest();
    if (operands.len < 2) return cli.usage("missing operand", SYNOPSIS);
    const spec = operands[0];
    const method = operands[1];
    const rest = operands[2..];

    if (rest.len > device.MAX_VALUES) {
        cli.warn(null, "too many arguments (max 32)");
        return 1;
    }
    for (rest, 0..) |a, i| {
        parseArg(a, &args[i]) catch |e| {
            var nbuf: [24]u8 = undefined;
            const subject = argSubject(&nbuf, i + 1);
            cli.warn(subject, argMessage(e));
            return if (e == error.TooLarge) 1 else cli.USAGE;
        };
    }
    if (method.len > device.MAX_METHOD_BYTES) {
        warnPrintable(method, "method name too long");
        return 1;
    }

    var addr_buf: [device.ADDRESS_BYTES]u8 = undefined;
    const address = resolve(&addr_buf, spec) orelse return 1;
    canon.reset();
    const dev = switch (device.Device.open(address)) {
        .ok => |d| d,
        .err => |f| {
            report(spec, method, f);
            return 1;
        },
    };
    defer dev.close();
    canon.reset();
    const values = switch (dev.invoke(method, args[0..rest.len])) {
        .ok => |v| v,
        .err => |f| {
            report(spec, method, f);
            return 1;
        },
    };
    const out = &cli.stdout;
    for (values) |*v| {
        writeValue(out, v);
        out.byte('\n');
    }
    out.flush();
    canon.reset();
    return @intFromBool(out.failed);
}

// ---- デバイスの指定 ----

/// [spec] を address にして [buf] に写す。見つからない・曖昧なら知らせて null
fn resolve(buf: *[device.ADDRESS_BYTES]u8, spec: []const u8) ?[]const u8 {
    var lower: [device.MAX_KIND_BYTES]u8 = undefined;
    const s = device.lowerAddress(&lower, spec) orelse {
        warnPrintable(spec, "no such device");
        return null;
    };
    canon.reset();
    const list = device.listDevices();
    var found: ?*const device.Info = null;
    var count: usize = 0;
    for (list) |*d| {
        switch (device.match(d.address.slice(), d.kind.slice(), s)) {
            .exact => {
                found = d;
                count = 1;
                break;
            },
            .prefix, .kind => {
                if (count == 0) found = d;
                count += 1;
            },
            .none => {},
        }
    }
    if (count == 0) {
        warnPrintable(spec, "no such device");
        return null;
    }
    if (count > 1) {
        warnPrintable(spec, "ambiguous device");
        return null;
    }
    const a = found.?.address.slice();
    if (a.len != buf.len) {
        warnPrintable(spec, "no such device");
        return null;
    }
    @memcpy(buf, a);
    return buf;
}

// ---- 引数 ----

const ArgError = error{ BadInteger, IntegerRange, BadFloat, BadBoolean, BadBytes, BadUtf8, TooLarge };

fn argMessage(e: ArgError) []const u8 {
    return switch (e) {
        error.BadInteger => "invalid integer",
        error.IntegerRange => "integer out of range",
        error.BadFloat => "invalid number",
        error.BadBoolean => "invalid boolean (true or false)",
        error.BadBytes => "invalid hex bytes",
        error.BadUtf8 => "invalid UTF-8",
        error.TooLarge => "arguments too large",
    };
}

/// `argument N`
fn argSubject(buf: *[24]u8, n: usize) []const u8 {
    const p = "argument ";
    @memcpy(buf[0..p.len], p);
    const d = io.formatUnsigned(buf[p.len..], n);
    return buf[0 .. p.len + d.len];
}

fn parseArg(s: []const u8, out: *device.Value) ArgError!void {
    if (s.len >= 2 and s[1] == ':') {
        const body = s[2..];
        switch (s[0]) {
            's' => return textArg(body, out),
            'i' => {
                out.* = device.Value.integer(try parseInteger(body));
                return;
            },
            'f' => {
                out.* = device.Value.float(std.fmt.parseFloat(f64, body) catch return error.BadFloat);
                return;
            },
            'b' => {
                if (cli.eql(body, "true")) {
                    out.* = device.Value.boolean(true);
                } else if (cli.eql(body, "false")) {
                    out.* = device.Value.boolean(false);
                } else return error.BadBoolean;
                return;
            },
            'x' => return bytesArg(body, out),
            else => {},
        }
    }
    if (cli.eql(s, "none")) {
        out.* = device.Value.none();
    } else if (cli.eql(s, "true")) {
        out.* = device.Value.boolean(true);
    } else if (cli.eql(s, "false")) {
        out.* = device.Value.boolean(false);
    } else if (isIntegerSyntax(s)) {
        out.* = device.Value.integer(try parseInteger(s));
    } else if (floatOf(s)) |f| {
        out.* = device.Value.float(f);
    } else {
        return textArg(s, out);
    }
}

fn textArg(s: []const u8, out: *device.Value) ArgError!void {
    if (!validUtf8(s)) return error.BadUtf8;
    try addPayload(s.len);
    out.* = device.Value.text(s);
}

fn bytesArg(hex: []const u8, out: *device.Value) ArgError!void {
    if (hex.len % 2 != 0) return error.BadBytes;
    const n = hex.len / 2;
    try addPayload(n);
    const dst = bytes_buf[bytes_used..][0..n];
    for (dst, 0..) |*b, i| {
        const hi = hexDigit(hex[2 * i]) orelse return error.BadBytes;
        const lo = hexDigit(hex[2 * i + 1]) orelse return error.BadBytes;
        b.* = hi << 4 | lo;
    }
    bytes_used += n;
    out.* = device.Value.bytes(dst);
}

/// text・bytes の合計を数える（`DeviceLimits.MAX_PAYLOAD_BYTES`）
fn addPayload(n: usize) ArgError!void {
    if (n > device.MAX_PAYLOAD_BYTES - payload) return error.TooLarge;
    payload += n;
}

fn hexDigit(c: u8) ?u8 {
    return switch (c) {
        '0'...'9' => c - '0',
        'a'...'f' => c - 'a' + 10,
        'A'...'F' => c - 'A' + 10,
        else => null,
    };
}

/// `[+-]?[0-9]+`
fn isIntegerSyntax(s: []const u8) bool {
    const body = if (s.len > 0 and (s[0] == '+' or s[0] == '-')) s[1..] else s;
    if (body.len == 0) return false;
    for (body) |c| if (c < '0' or c > '9') return false;
    return true;
}

/// s64 の範囲で読む（範囲の外を丸めない）
fn parseInteger(s: []const u8) ArgError!i64 {
    if (!isIntegerSyntax(s)) return error.BadInteger;
    const neg = s[0] == '-';
    const body = if (s[0] == '+' or s[0] == '-') s[1..] else s;
    const mag = io.parseUnsigned(body) orelse return error.IntegerRange;
    const limit: u64 = if (neg) 1 << 63 else (1 << 63) - 1;
    if (mag > limit) return error.IntegerRange;
    if (neg) return if (mag == 1 << 63) std.math.minInt(i64) else -@as(i64, @intCast(mag));
    return @intCast(mag);
}

/// 前置きなしの小数（数字と `.`・`e`・`E`・`+`・`-` だけで、数字と `.` か `e` を含み、読めるもの）。そうでなければ null（text にする）
fn floatOf(s: []const u8) ?f64 {
    var digit = false;
    var mark = false;
    for (s) |c| switch (c) {
        '0'...'9' => digit = true,
        '.', 'e', 'E' => mark = true,
        '+', '-' => {},
        else => return null,
    };
    if (!digit or !mark) return null;
    return std.fmt.parseFloat(f64, s) catch null;
}

fn validUtf8(s: []const u8) bool {
    var i: usize = 0;
    while (i < s.len) {
        _ = text.decode(s[i..]) orelse return false;
        i += text.sequenceLength(s[i]);
    }
    return true;
}

// ---- 結果 ----

fn writeValue(out: *cli.Writer, v: *const device.Value) void {
    switch (v.tag) {
        .none => out.write("none"),
        .integer => writeInteger(out, v.val.integer),
        .float => writeFloat(out, v.val.float),
        .boolean => out.write(if (v.val.boolean != 0) "true" else "false"),
        .text => out.printable(v.val.text.slice()),
        .bytes => {
            out.write("x:");
            const digits = "0123456789abcdef";
            for (v.val.bytes.slice()) |b| {
                out.byte(digits[b >> 4]);
                out.byte(digits[b & 15]);
            }
        },
        _ => out.byte('?'),
    }
}

fn writeInteger(out: *cli.Writer, v: i64) void {
    if (v < 0) out.byte('-');
    var tmp: [20]u8 = undefined;
    out.write(io.formatUnsigned(&tmp, @abs(v)));
}

/// 小数を、読み直すと同じ値になる最短の形で書く。`.`・`e` を含まなければ `.0` を足す（引数に戻すと float と推されるように）。
/// `inf`・`-inf`・`nan` は前置きなしでは text と読まれるので、`f:` を付ける（`f:inf`）
fn writeFloat(out: *cli.Writer, v: f64) void {
    if (std.math.isNan(v) or std.math.isInf(v)) out.write("f:");
    var buf: [64]u8 = undefined;
    const a = @abs(v);
    const mode: std.fmt.float.Mode = if (a == 0 or (a >= 1e-5 and a < 1e16)) .decimal else .scientific;
    const s = std.fmt.float.render(&buf, v, .{ .mode = mode }) catch
        std.fmt.float.render(&buf, v, .{}) catch "?";
    out.write(s);
    for (s) |c| switch (c) {
        '.', 'e', 'n' => return, // `nan`・`inf` は `n` を含む
        else => {},
    };
    out.write(".0");
}

// ---- 知らせ ----

/// 呼び出しの失敗を `devcall: 対象: 説明` で知らせる（外れた・見つからないはデバイス、ほかはメソッドを対象にする）
fn report(spec: []const u8, method: []const u8, f: device.Failure) void {
    switch (f) {
        .detached => warnPrintable(spec, "no such device"),
        .no_such_method => warnPrintable(method, "no such method"),
        .kernel => |e| warnPrintable(method, errno.describe(e)),
        .bad_arguments, .failed => |m| warnPrintable(method, m),
    }
}

var subject_buf: [128]u8 = undefined;
var message_buf: [1024]u8 = undefined;

/// 対象と説明の制御文字を `?` にして知らせる（説明はデバイスの作者が書く文でありうる。端末のエスケープを送らない）
fn warnPrintable(subject: []const u8, message: []const u8) void {
    cli.warn(sanitize(&subject_buf, subject), sanitize(&message_buf, message));
}

fn sanitize(buf: []u8, s: []const u8) []const u8 {
    const n = @min(s.len, buf.len);
    for (s[0..n], 0..) |c, i| buf[i] = if (c < 0x20 or c == 0x7f) '?' else c;
    return buf[0..n];
}
