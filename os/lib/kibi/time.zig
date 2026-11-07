//! 時計と眠り（WASI p1 の clock_time_get・poll_oneoff）の包み（#98）。
const wasi = @import("wasi.zig");
const errno = @import("errno.zig");

pub const Error = errno.Error;

/// 単調な時計（ナノ秒）
pub fn monotonic() Error!u64 {
    var t: wasi.Timestamp = 0;
    try errno.check(wasi.clock_time_get(wasi.CLOCK_MONOTONIC, 1, &t));
    return t;
}

/// 壁時計（1970 年からのナノ秒）
pub fn realtime() Error!u64 {
    var t: wasi.Timestamp = 0;
    try errno.check(wasi.clock_time_get(wasi.CLOCK_REALTIME, 1, &t));
    return t;
}

/// [ns] ナノ秒眠る（待つ間は待機点になる）
pub fn sleep(ns: u64) Error!void {
    const sub = [_]wasi.Subscription{.{
        .userdata = 0,
        .tag = wasi.EVENTTYPE_CLOCK,
        .u = .{ .clock = .{ .id = wasi.CLOCK_MONOTONIC, .timeout = ns, .precision = 0, .flags = 0 } },
    }};
    var ev: [1]wasi.Event = undefined;
    var n: wasi.Size = 0;
    try errno.check(wasi.poll_oneoff(&sub, &ev, 1, &n));
}

/// 暦の日時（UTC。#475）
pub const DateTime = struct {
    year: u32,
    /// 1〜12
    month: u8,
    /// 1〜31
    day: u8,
    hour: u8,
    minute: u8,
    second: u8,
    /// 曜日（0 = 日曜）
    weekday: u8,
    /// 年の中の日（1〜366）
    yday: u16,
};

/// 1970 年からの秒を UTC の日時にする（Howard Hinnant の civil_from_days）
pub fn civil(secs: u64) DateTime {
    const days = secs / 86400;
    const rem = secs % 86400;
    const z: i64 = @as(i64, @intCast(days)) + 719468;
    const era = @divFloor(z, 146097);
    const doe: i64 = z - era * 146097;
    const yoe = @divFloor(doe - @divFloor(doe, 1460) + @divFloor(doe, 36524) - @divFloor(doe, 146096), 365);
    const doy = doe - (365 * yoe + @divFloor(yoe, 4) - @divFloor(yoe, 100));
    const mp = @divFloor(5 * doy + 2, 153);
    const d = doy - @divFloor(153 * mp + 2, 5) + 1;
    const m = if (mp < 10) mp + 3 else mp - 9;
    const y = yoe + era * 400 + @intFromBool(m <= 2);
    // 3 月始まりの日（doy）を 1 月始まりにする
    const leap = @mod(y, 4) == 0 and (@mod(y, 100) != 0 or @mod(y, 400) == 0);
    const jan1 = if (m <= 2) doy - 306 else doy + 59 + @as(i64, @intFromBool(leap));
    return .{
        .year = @intCast(y),
        .month = @intCast(m),
        .day = @intCast(d),
        .hour = @intCast(rem / 3600),
        .minute = @intCast(rem % 3600 / 60),
        .second = @intCast(rem % 60),
        // 1970-01-01 は木曜
        .weekday = @intCast((days + 4) % 7),
        .yday = @intCast(jan1 + 1),
    };
}
