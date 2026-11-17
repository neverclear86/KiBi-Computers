---
title: System call reference
description: Which WASI functions and KiBi extensions (kibi:*) programs on KiBi Computers can use, and how they are called.
---

On a KiBi Computers computer, programs run as WebAssembly (WASM) modules. A program reaches operating-system features (system calls) through two interfaces:

- **WASI**: the standard interface. Files, standard I/O, clocks, random numbers, waiting (`poll_oneoff`), and so on
- **The `kibi:*` extensions**: features WASI does not have. Events, timers, processes, pipes, the TTY, devices, power, mounts, and so on. They are defined as WIT (WebAssembly Interface Types) packages, and WASI p1 programs get the same functions as imports

This reference is for people who write programs (in C, Zig, Rust, or anything else that compiles to WASM) and for people who build language runtimes or SDKs.

## Current status

| Interface | Status |
| --- | --- |
| WASI p1 (`wasi_snapshot_preview1`) | **Supported**. Only commands (modules that export `_start`) run. Per-function support and differences from the standard: [WASI p1](./wasi-p1.md) |
| p1 imports for `kibi:*` | **Supported**. Common rules: [Common rules for kibi:*](./kibi-abi.md). Per-package pages are listed below |
| WASI p2 and the Component Model | **Partly supported**. Command components that export `wasi:cli/run@0.2.x` run with exec. `kibi:*` is not available from p2 yet, and their state is not saved ([p2 and p3](./p2-p3.md)) |
| WASI p3 (async) | **Not implemented** ([#266](https://github.com/neverclear86/KiBi-Computers/issues/266), [#268](https://github.com/neverclear86/KiBi-Computers/issues/268)) |
| p2 and p3 interfaces for `kibi:*` | **Provisional**. The WIT exists, but its shape will change when the p2 and p3 layers are built ([p2 and p3 plans](./p2-p3.md)) |
| Sockets (`sock_*`, `wasi:sockets`) | **Not implemented**. The p1 `sock_*` functions return `NOSYS` ([#76](https://github.com/neverclear86/KiBi-Computers/issues/76)) |

p2, p3, and the Component Model will be added as thin layers over the same kernel foundation (file descriptors, event waiting, streams). p2 and p3 will use the same `kibi:*` WIT.

## Pages

| Page | Contents |
| --- | --- |
| [WASI p1](./wasi-p1.md) | Per-function support, differences from the standard, what is rejected at load time, waiting and saving |
| [Common rules for kibi:*](./kibi-abi.md) | How p1 imports are derived, handles, errors (the `error-code` table), `cabi_realloc`, imports that may block |
| [kibi:kernel](./kibi-kernel.md) | Shared types, event queues, timers, event kinds |
| [kibi:process](./kibi-process.md) | Spawning, waiting for, and signalling processes; the working directory; the process list |
| [kibi:io](./kibi-io.md) | Duplicating file descriptors, pipes, the TTY |
| [kibi:device](./kibi-device.md) | Listing and calling devices |
| [kibi:computer](./kibi-computer.md) | Remaining RAM, uptime and in-game time, power |
| [kibi:fs](./kibi-fs.md) | Mounting disks, listing mounts and disks |
| [p2 and p3 plans](./p2-p3.md) | Current status and plans for p2, p3, and the Component Model; provisional interfaces |

## Versions and compatibility

- Every `kibi:*` package is `@0.1.0`. The loader accepts only an exact version match (an import with `@0.2.0` or with no version fails with `NOEXEC`)
- **Compatibility is not kept until the first release (v0.1.0).** WIT, import signatures, and error values may change without a version bump. Compatibility is kept from the first release on
- Numbers marked as "provisional" in this reference (limits, default capacities, fuel, and so on) will be reviewed before the first release ([#251](https://github.com/neverclear86/KiBi-Computers/issues/251)). Do not write programs that depend on them

## Terms

| Term | Meaning |
| --- | --- |
| device | A peripheral connected to a computer (a screen, a disk drive, redstone I/O, a block from another mod, and so on) |
| component | A component in the WASM Component Model. It never means a peripheral |
| fd | A kernel file descriptor number. It is the same number as the p1 fd, and it is also used as the handle of `kibi:*` resources |
| machine | One computer. Processes, files, and devices are separate per machine |
| wait point | A process waiting in a call that may block. A process at a wait point holds no thread and can be saved with the world ([Waiting and saving](./wasi-p1.md#waiting-and-saving)) |
| fuel | The amount of computation a process may use. WASM instructions and system calls use it up; when it runs out, the process waits for the next allotment. The amounts are provisional |
