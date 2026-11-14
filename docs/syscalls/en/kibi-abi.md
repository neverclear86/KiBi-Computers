---
title: Common rules for kibi:*
description: How WASI p1 imports are derived from the kibi:* WIT, handles, errors, cabi_realloc, and imports that may block.
---

The extensions are defined as WIT packages named `kibi:<area>`. WASI p1 programs get each function as an import **in the flattened core form of the Component Model Canonical ABI**, unchanged. This is the same form wit-bindgen generates for guest imports, so both hand-written declarations and generated bindings can call them.

| Package | Interfaces (usable from p1) | Page |
| --- | --- | --- |
| `kibi:kernel@0.1.0` | `types`, `events`, `timers` | [kibi:kernel](./kibi-kernel.md) |
| `kibi:process@0.1.0` | `process` | [kibi:process](./kibi-process.md) |
| `kibi:io@0.1.0` | `fd`, `pipe`, `tty` | [kibi:io](./kibi-io.md) |
| `kibi:device@0.1.0` | `types`, `bus` | [kibi:device](./kibi-device.md) |
| `kibi:computer@0.1.0` | `memory`, `clock`, `power` | [kibi:computer](./kibi-computer.md) |
| `kibi:fs@0.1.0` | `mounts` | [kibi:fs](./kibi-fs.md) |

Interfaces whose names end in `-poll`, `-async`, or `-streams` are provisional ones for p2 and p3 and cannot be imported from p1 ([p2 and p3 plans](./p2-p3.md)). The current time and the monotonic clock come from WASI `clock_time_get`; they are not in `kibi:*`.

The original WIT files (with descriptive comments, in Japanese) are in [`core/src/main/wit/`](https://github.com/neverclear86/KiBi-Computers/tree/main/core/src/main/wit) in the repository. The WIT on each page is that WIT with the comments removed.

## Deriving imports

- **Module name**: `<namespace>:<package>/<interface>@<version>`. Example: `kibi:kernel/events@0.1.0`
- **Function name**: the same as wit-bindgen. A plain function uses its own name; resource functions are `[constructor]<resource>`, `[method]<resource>.<function>`, and `[static]<resource>.<function>`; closing a handle is `[resource-drop]<resource>`
- **Parameters**: the flattened Canonical ABI form. `string` and `list<T>` become two values `(ptr, len)`, `option<T>` becomes a discriminant and a value, `bool` is an i32 (nonzero is true), `enum` is its discriminant as an i32, `flags` is the bits as an i32, `u64` and `s64` are i64, and other integers are i32
- **Results**: a result that fits in one value (`u32`, `u64`, `bool`, and so on) is the return value. A result that does not fit (`result`, `option`, `record`, `string`, `list`, and so on) is written by the host to **a return pointer (retptr) passed as the last argument**; the function then has no return value
- **Memory layout**: as in the Canonical ABI (little endian, each type aligned to its alignment, records in field order, variants as a discriminant followed by a payload aligned to the largest alignment). Each page lists the sizes and offsets of the main types
- The "Core type" column in each page's import table gives the parameter names and types and the result type. `()` means no result

## Handles

- A resource handle is **a kernel fd number**. It shares the number space with p1 fds, so you can close it with `fd_close`, wait on it with `FD_READ` in `poll_oneoff`, and pass it to a child with `spawn` in `kibi:process`
- `[resource-drop]<resource>` closes the handle like `fd_close`. If the handle is not open or is of another kind, it traps, as in the Component Model
- Passing an invalid handle to a function whose result has no `error-code` (for example `[method]process.pid`) traps. Functions that return an `error-code` return `bad-descriptor`
- If the fd table is full, `mfile`; if a machine-wide limit is hit (the number of queues, running timers, processes, and so on), `nfile`

## Errors

The `enum error-code` in `kibi:kernel/types` is the 76 WASI p1 errno values other than `success`, in the same order. **p1 errno = discriminant + 1.** In C, add 1 to the discriminant to get the `errno` value.

<!-- check:error-codes -->
| Discriminant | `error-code` | p1 errno | p1 name |
| --- | --- | --- | --- |
| 0 | `too-big` | 1 | `2BIG` |
| 1 | `access` | 2 | `ACCES` |
| 2 | `addr-in-use` | 3 | `ADDRINUSE` |
| 3 | `addr-not-avail` | 4 | `ADDRNOTAVAIL` |
| 4 | `af-not-supported` | 5 | `AFNOSUPPORT` |
| 5 | `again` | 6 | `AGAIN` |
| 6 | `already` | 7 | `ALREADY` |
| 7 | `bad-descriptor` | 8 | `BADF` |
| 8 | `bad-message` | 9 | `BADMSG` |
| 9 | `busy` | 10 | `BUSY` |
| 10 | `canceled` | 11 | `CANCELED` |
| 11 | `child` | 12 | `CHILD` |
| 12 | `conn-aborted` | 13 | `CONNABORTED` |
| 13 | `conn-refused` | 14 | `CONNREFUSED` |
| 14 | `conn-reset` | 15 | `CONNRESET` |
| 15 | `deadlock` | 16 | `DEADLK` |
| 16 | `dest-addr-required` | 17 | `DESTADDRREQ` |
| 17 | `domain` | 18 | `DOM` |
| 18 | `quota` | 19 | `DQUOT` |
| 19 | `exist` | 20 | `EXIST` |
| 20 | `fault` | 21 | `FAULT` |
| 21 | `file-too-large` | 22 | `FBIG` |
| 22 | `host-unreachable` | 23 | `HOSTUNREACH` |
| 23 | `identifier-removed` | 24 | `IDRM` |
| 24 | `illegal-byte-sequence` | 25 | `ILSEQ` |
| 25 | `in-progress` | 26 | `INPROGRESS` |
| 26 | `interrupted` | 27 | `INTR` |
| 27 | `invalid` | 28 | `INVAL` |
| 28 | `io` | 29 | `IO` |
| 29 | `is-connected` | 30 | `ISCONN` |
| 30 | `is-directory` | 31 | `ISDIR` |
| 31 | `loop` | 32 | `LOOP` |
| 32 | `mfile` | 33 | `MFILE` |
| 33 | `too-many-links` | 34 | `MLINK` |
| 34 | `message-size` | 35 | `MSGSIZE` |
| 35 | `multihop` | 36 | `MULTIHOP` |
| 36 | `name-too-long` | 37 | `NAMETOOLONG` |
| 37 | `net-down` | 38 | `NETDOWN` |
| 38 | `net-reset` | 39 | `NETRESET` |
| 39 | `net-unreachable` | 40 | `NETUNREACH` |
| 40 | `nfile` | 41 | `NFILE` |
| 41 | `no-buffers` | 42 | `NOBUFS` |
| 42 | `no-device` | 43 | `NODEV` |
| 43 | `no-entry` | 44 | `NOENT` |
| 44 | `no-exec` | 45 | `NOEXEC` |
| 45 | `no-lock` | 46 | `NOLCK` |
| 46 | `no-link` | 47 | `NOLINK` |
| 47 | `no-memory` | 48 | `NOMEM` |
| 48 | `no-message` | 49 | `NOMSG` |
| 49 | `no-protocol-option` | 50 | `NOPROTOOPT` |
| 50 | `no-space` | 51 | `NOSPC` |
| 51 | `no-sys` | 52 | `NOSYS` |
| 52 | `not-connected` | 53 | `NOTCONN` |
| 53 | `not-directory` | 54 | `NOTDIR` |
| 54 | `not-empty` | 55 | `NOTEMPTY` |
| 55 | `not-recoverable` | 56 | `NOTRECOVERABLE` |
| 56 | `not-socket` | 57 | `NOTSOCK` |
| 57 | `not-supported` | 58 | `NOTSUP` |
| 58 | `no-tty` | 59 | `NOTTY` |
| 59 | `no-such-device` | 60 | `NXIO` |
| 60 | `overflow` | 61 | `OVERFLOW` |
| 61 | `owner-dead` | 62 | `OWNERDEAD` |
| 62 | `not-permitted` | 63 | `PERM` |
| 63 | `pipe` | 64 | `PIPE` |
| 64 | `protocol` | 65 | `PROTO` |
| 65 | `protocol-not-supported` | 66 | `PROTONOSUPPORT` |
| 66 | `protocol-type` | 67 | `PROTOTYPE` |
| 67 | `range` | 68 | `RANGE` |
| 68 | `read-only` | 69 | `ROFS` |
| 69 | `invalid-seek` | 70 | `SPIPE` |
| 70 | `no-such-process` | 71 | `SRCH` |
| 71 | `stale` | 72 | `STALE` |
| 72 | `timed-out` | 73 | `TIMEDOUT` |
| 73 | `text-file-busy` | 74 | `TXTBSY` |
| 74 | `cross-device` | 75 | `XDEV` |
| 75 | `not-capable` | 76 | `NOTCAPABLE` |
<!-- /check -->

Errors versus traps:

| Mistake | Result |
| --- | --- |
| A wrong argument value (a length or count over the limit, invalid UTF-8, an invalid discriminant) | `invalid` (`too-big` or `name-too-long` for some functions) |
| An argument pointer outside the linear memory | `fault` |
| The ptr of a `list` argument not aligned to its element (even with length 0) | Trap |
| The retptr outside the linear memory or not aligned | Trap |
| A location returned by `cabi_realloc` outside the linear memory or not aligned | Trap |
| `[resource-drop]` of an invalid handle; an invalid handle to a function that returns no `error-code` | Trap |

- If there are several mistakes, the first one found in the checking order is returned. The order is fixed per function and is given in each page's table of checks (or its text). A handle (the leading `self` or `fd`) is not always checked in parameter order: some functions check the other argument values first and the handle after (`max` of `event-queue.take`, `method` and `args` of `device.invoke` and `start`, `pid` of `tty.set-foreground`)
- **Known difference from the Component Model layer**: the Component Model reads in (lifts) all arguments before the host checks them, so combinations where p1 returns an `error-code` but a later argument has a bad alignment will trap there. Also, out-of-bounds pointers trap in the Component Model. The order will be aligned when the p2 layer is built ([#616](https://github.com/neverclear86/KiBi-Computers/issues/616)), so do not rely on which mistake is reported first

## cabi_realloc

For a function whose result has variable length (a result containing `string` or `list`), the host calls the guest function `cabi_realloc` to get space for the result.

```c
// Type: (old_ptr: i32, old_size: i32, align: i32, new_size: i32) -> i32
void *cabi_realloc(void *old_ptr, size_t old_size, size_t align, size_t new_size);
```

- A module that imports even one function that needs `cabi_realloc` must export `cabi_realloc` with this name and type. Otherwise it fails at load time with `NOEXEC`. The "cabi_realloc" column in each page's table says which functions need it
- The host calls it with `old_ptr = 0` and `old_size = 0`. As in the Canonical ABI, it calls it even for a list of size 0. The call order is the same as in the Component Model (the outer list first, then the strings and lists in the elements, from the front)
- The space belongs to the guest (if `cabi_realloc` uses `malloc`, you may `free` it)
- Returning a location outside the linear memory, or one that is not aligned, traps
- Fuel used inside `cabi_realloc` counts as the program's
- **Imports that may block (the table below) cannot be called inside `cabi_realloc`.** Calling one traps. Other imports (`clock_time_get`, `kibi:*` functions that do not block) can be called

## Imports that may block

The functions in the following table may make the process wait until the result is ready ([Waiting and saving](./wasi-p1.md#waiting-and-saving)).

<!-- check:wait-imports -->
| Module | Function | Waits for |
| --- | --- | --- |
| `wasi_snapshot_preview1` | `fd_read` | Input to read |
| `wasi_snapshot_preview1` | `fd_write` | Space to write |
| `wasi_snapshot_preview1` | `poll_oneoff` | Any subscription |
| `kibi:process/process@0.1.0` | `[static]process.spawn` | Loading (compiling) the executable |
| `kibi:device/bus@0.1.0` | `[method]device.invoke` | The result of a device method |
<!-- /check -->

The other `kibi:*` functions do not wait. `shutdown` and `reboot` in `kibi:computer/power` do not return once accepted, because the calling process stops at once too.

## Versions

- Every package is `@0.1.0`. The loader accepts only an exact version match; another version or a name without a version fails with `NOEXEC`
- Until the first release (v0.1.0), compatibility may break without a version bump

## Relation to WASIX

The names and signatures do not follow WASIX. WASI p1, p2, p3 and the `kibi:*` WIT are authoritative.
