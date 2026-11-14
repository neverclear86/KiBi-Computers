---
title: kibi:kernel
description: The shared type (error-code), event queues, timers, and the kinds of events the kernel sends.
---

`kibi:kernel@0.1.0` holds the types shared by the other packages and the kernel's event machinery (event queues and timers).

## types

```wit
// kibi-kernel/types.wit
package kibi:kernel@0.1.0;
interface types {
    enum error-code {
        too-big,
        access,
        addr-in-use,
        addr-not-avail,
        af-not-supported,
        again,
        already,
        bad-descriptor,
        bad-message,
        busy,
        canceled,
        child,
        conn-aborted,
        conn-refused,
        conn-reset,
        deadlock,
        dest-addr-required,
        domain,
        quota,
        exist,
        fault,
        file-too-large,
        host-unreachable,
        identifier-removed,
        illegal-byte-sequence,
        in-progress,
        interrupted,
        invalid,
        io,
        is-connected,
        is-directory,
        loop,
        mfile,
        too-many-links,
        message-size,
        multihop,
        name-too-long,
        net-down,
        net-reset,
        net-unreachable,
        nfile,
        no-buffers,
        no-device,
        no-entry,
        no-exec,
        no-lock,
        no-link,
        no-memory,
        no-message,
        no-protocol-option,
        no-space,
        no-sys,
        not-connected,
        not-directory,
        not-empty,
        not-recoverable,
        not-socket,
        not-supported,
        no-tty,
        no-such-device,
        overflow,
        owner-dead,
        not-permitted,
        pipe,
        protocol,
        protocol-not-supported,
        protocol-type,
        range,
        read-only,
        invalid-seek,
        no-such-process,
        stale,
        timed-out,
        text-file-busy,
        cross-device,
        not-capable,
    }
}
```

The mapping between each `error-code` value and the p1 errno is in the table in [Common rules for kibi:*](./kibi-abi.md#errors). `types` has no functions, so it has no imports.

## events

An event queue collects the events the kernel and devices send, filtered by kind. Events are taken without waiting; to wait, a p1 program waits on the queue's fd with `FD_READ` in `poll_oneoff`. It is ready when there is at least one event or when some were dropped.

```wit
// kibi-kernel/events.wit
package kibi:kernel@0.1.0;
interface events {
    use types.{error-code};
    variant event-value {
        integer(s64),
        float(f64),
        boolean(bool),
        text(string),
        bytes(list<u8>),
    }
    record event {
        kind: string,
        source: string,
        values: list<event-value>,
    }
    record event-batch {
        events: list<event>,
        dropped: u64,
    }
    resource event-queue {
        open: static func(kinds: list<string>, capacity: option<u32>) -> result<event-queue, error-code>;
        take: func(max: u32) -> result<event-batch, error-code>;
    }
}
```

- `event.kind` is the kind (1 to 64 bytes of UTF-8), `source` is the sender (a device address and so on; `""` if none), and `values` are the values (up to 32)
- `event-queue.open`: an empty `kinds` receives every kind. `kinds` has up to 16 entries, each 1 to 64 bytes of UTF-8. Omitting `capacity` uses the default capacity (provisional); if given, it must be 1 to 256
- `event-queue.take`: takes up to `max` events, oldest first, without waiting (empty if none). `max` must be at least 1; a value larger than the capacity means "all". `dropped` is the number of events discarded because the queue was full since the previous take
- An event reaches every queue that receives its kind

### p1 imports

Module: `kibi:kernel/events@0.1.0`

<!-- check:imports kibi:kernel/events@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `[static]event-queue.open` | `(kinds_ptr: i32, kinds_len: i32, cap_is_some: i32, cap: i32, ret_ptr: i32) -> ()` | — | — |
| `[method]event-queue.take` | `(self: i32, max: i32, ret_ptr: i32) -> ()` | required | — |
| `[resource-drop]event-queue` | `(self: i32) -> ()` | — | — |
<!-- /check -->

### Memory layout

| Type | Layout | Size / align |
| --- | --- | --- |
| `string`, `list<T>` | `ptr: i32 @0`, `len: i32 @4` | 8 / 4 |
| `result<event-queue, error-code>` | Discriminant `u8 @0`. If ok, the handle `i32 @4`; if err, the `error-code` `u8 @4` | 8 / 4 |
| `result<event-batch, error-code>` | Discriminant `u8 @0`. If ok, `events.ptr @8`, `events.len @12`, `dropped: u64 @16`; if err, `u8 @8` | 24 / 8 |
| `event` | `kind @0`, `source @8`, `values @16` (each a `ptr` and `len`) | 24 / 4 |
| `event-value` | Discriminant `u8 @0`, value `@8` (`integer` and `float` are 8 bytes, `boolean` is a `u8`, `text` and `bytes` are `ptr @8` and `len @12`) | 16 / 8 |

The `event-value` discriminants are `integer` = 0, `float` = 1, `boolean` = 2, `text` = 3, `bytes` = 4. The payload of `result<event-batch, …>` is aligned to `dropped` (a u64), so it starts at @8.

### Argument checks

Checked from top to bottom; the first match is returned.

| Condition | Result |
| --- | --- |
| `kinds_ptr` not aligned to 4 (even with length 0) | Trap |
| `kinds_len` over 16 | `invalid` |
| The `kinds` array outside the linear memory | `fault` |
| An element's length outside 1 to 64 | `invalid` |
| An element's contents outside the linear memory | `fault` |
| An element that is not valid UTF-8 | `invalid` |
| `cap_is_some` neither 0 nor 1, or `capacity` outside 1 to 256 | `invalid` |
| The machine's number of queues at its limit | `nfile` |
| The fd table full | `mfile` |
| `max` of `take` is 0 | `invalid` |
| `self` of `take` is not a queue | `bad-descriptor` |

Returned strings are UTF-8.

## timers

A timer is a kernel object that fires at a given time or interval. It uses the monotonic clock (p1 `clock_time_get(MONOTONIC)`), and times are in nanoseconds. While the fire count is 1 or more, the timer's fd is ready for `FD_READ` in `poll_oneoff`.

```wit
// kibi-kernel/timers.wit
package kibi:kernel@0.1.0;
interface timers {
    use types.{error-code};
    use events.{event-queue};
    resource timer {
        create: static func(events: option<tuple<borrow<event-queue>, s64>>) -> result<timer, error-code>;
        set-after: func(duration: u64, interval: u64) -> result<_, error-code>;
        set-at: func(instant: u64, interval: u64) -> result<_, error-code>;
        cancel: func() -> result<_, error-code>;
        read: func() -> result<u64, error-code>;
    }
}
```

- `timer.create`: creates a stopped timer. If `events` gives a queue and a tag, each firing puts a `timer` event into that queue (then `read` returns 0 and the fd is never ready for `FD_READ`). If omitted, no events are sent
- `set-after`: fires `duration` from now (0 means at once). If `interval` is not 0, it then repeats every `interval`. Setting it again resets the count to 0
- `set-at`: fires when the monotonic clock reaches `instant` (at once if already past)
- `cancel`: stops the timer and resets the count to 0
- `read`: takes the number of firings since the last read and resets it to 0. It does not wait. A repeating timer that fired late counts all the missed periods together
- A time argument of 2^63 or more is treated as 2^63 − 1 (it never actually fires)
- To just sleep, you do not need a timer: use a clock subscription in `poll_oneoff` (wasi-libc's `nanosleep` and so on). A timer fd and clock subscriptions can be waited on together in one `poll_oneoff`

### p1 imports

Module: `kibi:kernel/timers@0.1.0`

<!-- check:imports kibi:kernel/timers@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `[static]timer.create` | `(events_is_some: i32, events: i32, tag: i64, ret_ptr: i32) -> ()` | — | — |
| `[method]timer.set-after` | `(self: i32, duration: i64, interval: i64, ret_ptr: i32) -> ()` | — | — |
| `[method]timer.set-at` | `(self: i32, instant: i64, interval: i64, ret_ptr: i32) -> ()` | — | — |
| `[method]timer.cancel` | `(self: i32, ret_ptr: i32) -> ()` | — | — |
| `[method]timer.read` | `(self: i32, ret_ptr: i32) -> ()` | — | — |
| `[resource-drop]timer` | `(self: i32) -> ()` | — | — |
<!-- /check -->

If `events_is_some` is 0, `events` and `tag` are not read.

### Memory layout

| Type | Layout | Size / align |
| --- | --- | --- |
| `result<timer, error-code>` | Discriminant `u8 @0`; if ok, the handle `i32 @4`; if err, `u8 @4` | 8 / 4 |
| `result<_, error-code>` | Discriminant `u8 @0`; if err, `u8 @1` | 2 / 1 |
| `result<u64, error-code>` | Discriminant `u8 @0`; if ok, `u64 @8`; if err, `u8 @8` | 16 / 8 |

### Errors

| Condition | Result |
| --- | --- |
| `events_is_some` neither 0 nor 1 | `invalid` |
| `events` is not an event queue, or is not open | `bad-descriptor` |
| That queue does not receive the `timer` kind | `invalid` |
| The fd table full | `mfile` |
| `self` is not a timer (`set-after`, `set-at`, `cancel`, `read`) | `bad-descriptor` |
| `interval` is not 0 and shorter than the minimum interval (provisional: 50 ms) | `invalid` |
| The machine's number of running timers at its limit | `nfile` |

## Event kinds

Events from the kernel have `source` `""` (`"console"` for the TTY). Devices may send events with the same kind names, so tell the kernel's events apart by `source`.

| Kind | `source` | `values` | When |
| --- | --- | --- | --- |
| `timer` | `""` | `[integer(tag), integer(count)]` | A timer created with a queue fires ([timers](#timers)) |
| `tty-resize` | `"console"` | `[integer(columns), integer(rows), integer(generation)]` | The screen size actually changes ([kibi:io](./kibi-io.md#tty)) |
| `device-attached` | `""` | `[text(address), text(kind)]` | A device is connected ([kibi:device](./kibi-device.md)) |
| `device-detached` | `""` | `[text(address), text(kind)]` | A device is disconnected |
| `disk-inserted` | `""` | `[text(identifier), text(mount point)]` | A disk is inserted ([kibi:fs](./kibi-fs.md)) |
| `disk-removed` | `""` | `[text(identifier), text(mount point)]` | A disk is removed |
| `restored` | `""` | `[integer(real-time jump in ns), integer(in-game time jump in ticks)]` | The machine is restored from a save (sent even if the jumps are 0 or negative) |

- `device-attached` and `device-detached` for the same address arrive in the order they happened. When one arrives, the `kibi:device` list is already updated. Connections made before boot are not reported, so read the list after booting
- On restoring from a save, `restored` is followed by `device-attached` for every device reconnected in the restored world (these may overlap with devices connected before the save, and no `device-detached` is sent for devices that disappeared). Read the list again when you get `restored` and when `dropped` is not 0
- `restored` only means "restored from a save". It does not promise anything about whether the screen contents are kept
- The second value of `restored` (the in-game time jump) is always 0 for now, because the platform layers do not pass the in-game time to the machine yet ([#439](https://github.com/neverclear86/KiBi-Computers/issues/439); same as `world-ticks`)
- **Devices can also send events of their own kinds not in the table above** (with `source` set to the device address). See the device's documentation for the kinds and values. This table is not a closed list
