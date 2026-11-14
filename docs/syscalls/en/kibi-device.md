---
title: kibi:device
description: Listing connected devices and calling their methods (calls that wait for the result, and calls queued without waiting).
---

`kibi:device@0.1.0` lists the devices connected to the computer (screens, disk drives, blocks from other mods, and so on) and calls their methods. Devices also appear as the files `/dev/device/<address>` (an fd opened with `path_open` can be used as a `device` handle).

## types

```wit
// kibi-device/types.wit
package kibi:device@0.1.0;
interface types {
    use kibi:kernel/types@0.1.0.{error-code};
    variant value {
        none,
        integer(s64),
        float(f64),
        boolean(bool),
        text(string),
        bytes(list<u8>),
    }
    record device-info {
        address: string,
        kind: string,
    }
    record method-info {
        name: string,
        main-thread: bool,
    }
    variant call-error {
        kernel(error-code),
        detached,
        no-such-method,
        bad-arguments(string),
        failed(string),
    }
}
```

- `value` is a value without nesting. Its case names are the same as `event-value` in `kibi:kernel/events`, but the discriminant numbers differ (starting from `none` = 0)
- `device-info.address` is a lowercase UUID (36 characters), and `kind` is `<namespace>:<name>` (for example `kibicomputers:screen`)
- A method whose `method-info.main-thread` is true runs on the game's main thread during the next tick, so its result takes time

## bus

```wit
// kibi-device/bus.wit
package kibi:device@0.1.0;
interface bus {
    use types.{value, device-info, method-info, call-error};
    list-devices: func() -> list<device-info>;
    resource device {
        open: static func(address: string) -> result<device, call-error>;
        info: func() -> device-info;
        methods: func() -> list<method-info>;
        invoke: func(method: string, args: list<value>) -> result<list<value>, call-error>;
        start: func(method: string, args: list<value>) -> result<pending-call, call-error>;
    }
    resource pending-call {
        take: func() -> option<result<list<value>, call-error>>;
    }
}
```

- `list-devices` does not wait. It copies a list that is rebuilt only when connections change. Connecting and disconnecting are reported by the `kibi:kernel` events `device-attached` and `device-detached` ([Event kinds](./kibi-kernel.md#event-kinds))
- `device.open`: an address that is not 36 bytes, or that is not found, returns `detached`. The opened handle has the read right only
- `device.info` keeps returning the original values after the device is removed. `methods` is empty if the device is removed or its kind changed
- `device.invoke` is an **import that may block**. It waits until the result is ready (until the next tick for main-thread methods). A method that runs right away on the process's thread returns its result at once
- `device.start` queues a call without waiting and returns a `pending-call`. Up to 16 per process (provisional); more returns `kernel(again)`. `invoke` does not count toward this
- A `pending-call` becomes ready for `FD_READ` in `poll_oneoff` when it finishes. `take` returns the result without waiting (`none` if not finished) and does not close it; every call returns the same result. Closing it before it finishes cancels the call
- A handle stays after its device is removed. Calls through it return `detached`

### After restoring from a save

- Among main-thread methods that `invoke` was waiting for, those that were running when the save was taken return `failed("interrupted")`. Those that had not started yet run after the restore. Either way, a method never runs twice
- Calls queued with `start` follow the same rule

## p1 imports

Module: `kibi:device/bus@0.1.0`

<!-- check:imports kibi:device/bus@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `list-devices` | `(ret_ptr: i32) -> ()` | required | — |
| `[static]device.open` | `(addr_ptr: i32, addr_len: i32, ret_ptr: i32) -> ()` | required | — |
| `[method]device.info` | `(self: i32, ret_ptr: i32) -> ()` | required | — |
| `[method]device.methods` | `(self: i32, ret_ptr: i32) -> ()` | required | — |
| `[method]device.invoke` | `(self: i32, m_ptr: i32, m_len: i32, a_ptr: i32, a_len: i32, ret_ptr: i32) -> ()` | required | may block |
| `[method]device.start` | `(self: i32, m_ptr: i32, m_len: i32, a_ptr: i32, a_len: i32, ret_ptr: i32) -> ()` | required | — |
| `[method]pending-call.take` | `(self: i32, ret_ptr: i32) -> ()` | required | — |
| `[resource-drop]device` | `(self: i32) -> ()` | — | — |
| `[resource-drop]pending-call` | `(self: i32) -> ()` | — | — |
<!-- /check -->

`open` and `start` need `cabi_realloc` too, because `call-error` contains a `string`. `kibi:device/types` has no functions, so it has no imports.

## Memory layout

| Type | Layout | Size / align |
| --- | --- | --- |
| `value` | Discriminant `u8 @0`, value `@8` (`integer` and `float` are 8 bytes, `boolean` is a `u8`, `text` and `bytes` are `ptr @8` and `len @12`) | 16 / 8 |
| `device-info` | `address` `ptr @0`, `len @4`; `kind` `ptr @8`, `len @12` | 16 / 4 |
| `method-info` | `name` `ptr @0`, `len @4`; `main-thread: u8 @8` | 12 / 4 |
| `call-error` | Discriminant `u8 @0`; for `kernel`, the `error-code` `u8 @4`; for `bad-arguments` and `failed`, `ptr @4` and `len @8` | 12 / 4 |
| `result<list<value>, call-error>` | Discriminant `u8 @0`; if ok, `ptr @4` and `len @8`; if err, `call-error @4` | 16 / 4 |
| `result<device, call-error>`, `result<pending-call, call-error>` | Discriminant `u8 @0`; if ok, the handle `u32 @4`; if err, `call-error @4` | 16 / 4 |
| `option<result<list<value>, call-error>>` | Discriminant `u8 @0`, `result @4` | 20 / 4 |

The `value` discriminants are `none` = 0, `integer` = 1, `float` = 2, `boolean` = 3, `text` = 4, `bytes` = 5. The `call-error` discriminants are `kernel` = 0, `detached` = 1, `no-such-method` = 2, `bad-arguments` = 3, `failed` = 4.

## Argument and handle mistakes

`invoke` and `start` check their arguments in parameter order (`method` → `args`), then the handle.

| Condition | Result |
| --- | --- |
| `method` over 64 bytes (before reading it) | `kernel(name-too-long)` |
| The `args` ptr not aligned to 8 (even with length 0; before the other checks of `args`) | Trap |
| Over 32 `args` (before reading the elements) | `kernel(too-big)` |
| The total length of `text` and `bytes` over 64 KiB (before reading the contents) | `kernel(too-big)` |
| A `value` discriminant outside 0 to 5, or a UTF-8 mistake | `kernel(invalid)` |
| An argument location outside the linear memory | `kernel(fault)` |
| An invalid handle to `invoke`, `start`, or `take` | `kernel(bad-descriptor)` |
| An invalid handle to `info`, `methods`, or `[resource-drop]*` | Trap |
| The fd table full (`open`, `start`, `invoke` of a main-thread method) | `kernel(mfile)` |

A `boolean` is true if nonzero. Returned strings are UTF-8. The limit numbers are provisional.
