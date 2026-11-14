---
title: kibi:computer
description: Remaining RAM, uptime and in-game time, and power (shutdown and reboot).
---

`kibi:computer@0.1.0` covers the computer (machine) as a whole. None of its functions wait.

## memory

```wit
// kibi-computer/memory.wit
package kibi:computer@0.1.0;
interface memory {
    remaining: func() -> u64;
    commit: func(ptr: u32, len: u32, keep: u64) -> bool;
}
```

- RAM is counted as the number of 4 KiB chunks a process has written to for the first time. Merely reserving a large linear memory does not use it up
- `remaining` is the smaller of what is left of the process limit and of the machine's RAM (in bytes)
- `commit` counts the chunks of `[ptr, ptr + len)` now. If the remainder after counting would be below `keep`, it counts nothing and returns `false`. It returns `true` if there are no new chunks to count, and `false` if the range is outside the linear memory. Use it to check that there is enough before writing a large buffer

## clock

```wit
// kibi-computer/clock.wit
package kibi:computer@0.1.0;
interface clock {
    uptime-ticks: func() -> u64;
    world-ticks: func() -> u64;
}
```

- `uptime-ticks` is the time since boot in ticks (1 tick is meant to be 50 ms). It is 0 at boot, counts only ticks in which the machine ran, and continues across a save and restore
- `world-ticks` is the in-game time (world ticks). It is 0 if there is no world, and it jumps over the time between a save and a restore
- For the current time and a monotonic clock in nanoseconds, use WASI `clock_time_get` ([WASI p1](./wasi-p1.md#clocks))

## power

```wit
// kibi-computer/power.wit
package kibi:computer@0.1.0;
interface power {
    use kibi:kernel/types@0.1.0.{error-code};
    shutdown: func();
    reboot: func() -> result<_, error-code>;
}
```

- Any process can call these (there are no privilege levels inside a machine)
- `shutdown` stops the machine. It is always accepted and does not return, because the calling process stops at once too
- `reboot` stops every process and boots again with the same settings as the last boot. Once accepted, it does not return. It returns only when refused, with `not-supported` (a machine created without booting or restoring, or a server that is shutting down)
- Rebooting repeatedly in a short time makes the delay before booting again longer (the return value does not change)
- The retptr of `reboot` is written only when refused. So even if the retptr is outside the linear memory, an accepted reboot happens (no trap). If it is refused and the retptr is outside, it traps

## p1 imports

Module: `kibi:computer/memory@0.1.0`

<!-- check:imports kibi:computer/memory@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `remaining` | `() -> i64` | — | — |
| `commit` | `(ptr: i32, len: i32, keep: i64) -> i32` | — | — |
<!-- /check -->

Module: `kibi:computer/clock@0.1.0`

<!-- check:imports kibi:computer/clock@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `uptime-ticks` | `() -> i64` | — | — |
| `world-ticks` | `() -> i64` | — | — |
<!-- /check -->

Module: `kibi:computer/power@0.1.0`

<!-- check:imports kibi:computer/power@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `shutdown` | `() -> ()` | — | — |
| `reboot` | `(ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

The retptr of `reboot` is a `result<_, error-code>` (discriminant `u8 @0`; if err, `u8 @1`; 2 bytes, align 1).
