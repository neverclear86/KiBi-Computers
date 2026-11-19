---
title: kibi:computer
description: Remaining RAM, uptime and in-game time, power (shutdown and reboot), and the owner and permissions (read and request changes).
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

- RAM is counted as the number of 4 KiB chunks a process has written to for the first time. Merely reserving a large linear memory does not count any chunks. However, the chunk table of the linear memory is counted in proportion to the pages declared (or grown with `memory.grow`), at 128 bytes per page (a provisional value). So even a large declaration alone uses a little RAM, and on a machine with little RAM a module that declares too much cannot be instantiated (out of memory). The number of written chunks does not include the table
- `remaining` is the smaller of what is left of the process limit and of the machine's RAM (in bytes)
- `commit` counts the chunks of `[ptr, ptr + len)` now. If the remainder after counting would be below `keep`, it counts nothing and returns `false`. It returns `true` if there are no new chunks to count, and `false` if the range is outside the linear memory. Use it to check that there is enough before writing a large buffer
- In p1, `ptr` is an address in the calling module's linear memory; in p2, in the linear memory (memory 0) of the core module that imports `commit` ([p2 and p3](./p2-p3.md))

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
- **Not implemented yet: in the game it always returns 0**, because the platform layers do not pass the in-game time to the machine yet ([#439](https://github.com/neverclear86/KiBi-Computers/issues/439))
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

## access

```wit
// kibi-computer/access.wit
package kibi:computer@0.1.0;
interface access {
    use kibi:kernel/types@0.1.0.{error-code};
    enum access-state {
        unowned,
        owned,
        locked,
    }
    record player {
        name: string,
        id: string,
    }
    record access-info {
        state: access-state,
        owner: option<player>,
        members: list<player>,
        invites: list<string>,
    }
    get-access: func() -> result<access-info, error-code>;
    request-add: func(name: string) -> result<_, error-code>;
    request-remove: func(name-or-id: string) -> result<_, error-code>;
    request-transfer: func(name: string) -> result<_, error-code>;
}
```

- Shows the owner, members and pending invites of the computer (case) ([ADR 0131](../adr/0131-player-access.md)) and **requests** changes to them (#110, [ADR 0110](../adr/0110-player-access-commands.md)). A program cannot change them directly
- `get-access` returns the current state. When `unowned` (no owner) or `locked` (the saved record could not be read), `owner` is none and `members` and `invites` are empty. `invites` are pending entries added by name (lowercase); expired ones are left out. Requests waiting for approval are not included
- `request-add`, `request-remove` and `request-transfer` return ok once the request is accepted. ok does not mean "applied". A request is applied when the owner or an operator approves it in the game with `/kibicomputers access approve <ID>` (for a `transfer` on a case without an owner, the player who becomes the owner; for a `locked` case, operators only); read the result with `get-access`. When the owner is online, approve and deny buttons appear in their chat
- Requests waiting for approval are limited per computer (4 by default, config `access.maxRequestsPerComputer`) and expire (300 seconds by default, `access.requestTtlSeconds`). Identical requests (same kind and argument; names ignore case) are merged into one and return ok. Waiting requests are not saved (they are gone when the server stops)
- The argument of `remove` is a name (the pending entry, and members currently online with that name) or a UUID in canonical form (36 characters with hyphens; uppercase is accepted)
- The target of a `transfer` is looked up by name among the online players when the request reaches the game, and fixed to that UUID. If the player is not online then, the request is dropped (ok only means "accepted", so a dropped request is not reported). Approval applies to the fixed UUID, so it is applied even if the player has logged out since

| Situation | Result |
| --- | --- |
| Wrong name form (`[A-Za-z0-9_]{1,16}`), a `remove` argument that is neither a name nor a UUID, more than 64 bytes, not UTF-8 | `invalid` |
| This computer has no owner and permissions (such as a machine created outside the game) | `not-supported` |
| Too many requests waiting for approval | `again` |

- If the string range of a `request-*` is outside the linear memory, it returns `fault` (no trap). The retptr of a `request-*` is checked after the request is accepted (if it is outside, the request is delivered and then it traps). `get-access` sends no request, so it checks its retptr first
- `get-access` can also be called from inside `cabi_realloc`. `cabi_realloc` is called in Canonical ABI field order (owner name, owner id, the `members` array, each member's name and id, the `invites` array, each invite)

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

Module: `kibi:computer/access@0.1.0`

<!-- check:imports kibi:computer/access@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `get-access` | `(ret_ptr: i32) -> ()` | required | — |
| `request-add` | `(name_ptr: i32, name_len: i32, ret_ptr: i32) -> ()` | — | — |
| `request-remove` | `(arg_ptr: i32, arg_len: i32, ret_ptr: i32) -> ()` | — | — |
| `request-transfer` | `(name_ptr: i32, name_len: i32, ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

The retptr of `request-*` is a `result<_, error-code>`, the same as `reboot`. The retptr of `get-access` has this shape (strings are `ptr`, `len`; `list<T>` is `ptr`, `len`; padding and unused value space are 0).

| Type | Layout | Size / align |
| --- | --- | --- |
| `result<access-info, error-code>` | Discriminant `u8 @0`; if ok, `access-info @4`; if err, `u8 @4` | 44 / 4 |
| `access-info` | `state: u8 @0`; the `owner` discriminant `u8 @4` and `player @8`; `members @24`, `@28`; `invites @32`, `@36` | 40 / 4 |
| `player` | `name @0`, `@4`; `id @8`, `@12` | 16 / 4 |

The `access-state` discriminants are `unowned` = 0, `owned` = 1, `locked` = 2.
