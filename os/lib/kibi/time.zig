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
