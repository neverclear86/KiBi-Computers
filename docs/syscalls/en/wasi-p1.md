---
title: WASI p1
description: Per-function support for wasi_snapshot_preview1, differences from the standard, what is rejected at load time, and waiting and saving.
---

KiBi Computers provides WASI p1 (module name `wasi_snapshot_preview1`) with the function signatures exactly as defined in witx. This page lists support per function and the places where behavior differs from the standard (and from common implementations such as wasmtime).

## Modules that can run

- **Only commands** run. A module that does not export the function `_start` fails at load time with `NOEXEC`. There is no reactor entry point (an export that receives events); to receive events, wait on a queue's fd with `poll_oneoff`
- Returning from `_start` ends the process with exit code 0; calling `proc_exit(n)` ends it with exit code `n` (all 32 bits)
- Component Model components cannot run yet (`NOEXEC`; see [p2 and p3 plans](./p2-p3.md))
- Export your linear memory. Calls without a memory return `FAULT` or trap

## Per-function support

"Yes" means the function is implemented. `NOSYS` means it can be imported, but always returns `NOSYS`.

<!-- check:p1-functions -->
| Function | Supported | Notes |
| --- | --- | --- |
| `args_get` | Yes | |
| `args_sizes_get` | Yes | |
| `environ_get` | Yes | Always includes `PWD` ([Working directory](#working-directory)) |
| `environ_sizes_get` | Yes | Same as above |
| `clock_res_get` | Yes | 1 µs for every clock |
| `clock_time_get` | Yes | [Clocks](#clocks) |
| `fd_advise` | Yes | Only checks its arguments; has no effect on the contents. Streams return `SPIPE` |
| `fd_allocate` | Yes | Grows the file to `offset + len` (never shrinks it) |
| `fd_close` | Yes | |
| `fd_datasync` | Yes | Streams return `INVAL` |
| `fd_fdstat_get` | Yes | [File types and rights](#file-types-and-rights) |
| `fd_fdstat_set_flags` | Yes | `APPEND` and `NONBLOCK` can be changed. `DSYNC`, `RSYNC`, and `SYNC` return `NOTSUP`; unknown bits return `INVAL` |
| `fd_fdstat_set_rights` | Yes | Checks the fd, then always returns `NOTSUP` (p1 rights are not kept) |
| `fd_filestat_get` | Yes | For streams, only the file type and `nlink = 1` |
| `fd_filestat_set_size` | Yes | A non-writable fd returns `BADF`; streams return `INVAL` |
| `fd_filestat_set_times` | Yes | `*_NOW` uses the machine's real time. Giving `ATIM` together with `ATIM_NOW` (or `MTIM` with `MTIM_NOW`) returns `INVAL` |
| `fd_pread` | Yes | Does not move the position. Streams return `SPIPE` |
| `fd_prestat_get` | Yes | [Preopens](#preopens) |
| `fd_prestat_dir_name` | Yes | Same as above |
| `fd_pwrite` | Yes | Does not move the position. Streams return `SPIPE` |
| `fd_read` | Yes | May block. [Reading and writing](#reading-and-writing) |
| `fd_readdir` | Yes | [Reading directories](#reading-directories) |
| `fd_renumber` | Yes | The target may be closed (as long as it is in range) |
| `fd_seek` | Yes | Streams return `SPIPE`, directories `ISDIR`, an unknown whence `INVAL` |
| `fd_sync` | Yes | Streams return `INVAL` |
| `fd_tell` | Yes | Streams return `SPIPE` |
| `fd_write` | Yes | May block. [Reading and writing](#reading-and-writing) |
| `path_create_directory` | Yes | `EXIST` if the last name in the path is `.` or `..` |
| `path_filestat_get` | Yes | |
| `path_filestat_set_times` | Yes | Same rules as `fd_filestat_set_times`. Read-only mounts return `ROFS` |
| `path_link` | Yes | Checks the base fd, then always returns `NOTSUP` (there are no hard links) |
| `path_open` | Yes | [Paths](#paths) |
| `path_readlink` | Yes | There are no links: `INVAL` if the path exists, otherwise the lookup error |
| `path_remove_directory` | Yes | `INVAL` if the last name in the path is `.`, `NOTEMPTY` if it is `..` |
| `path_rename` | Yes | Crossing a mount returns `XDEV`; a mount point returns `BUSY`. `INVAL` if the last name in the path is `.` or `..` |
| `path_symlink` | Yes | Checks the base fd, then always returns `NOTSUP` (there are no symbolic links) |
| `path_unlink_file` | Yes | Directories return `ISDIR` |
| `poll_oneoff` | Yes | May block. [poll_oneoff](#poll_oneoff) |
| `proc_exit` | Yes | Calling it inside the module's start function makes the process end as crashed |
| `proc_raise` | `NOSYS` | Deprecated even in p1, so it is not implemented |
| `random_get` | Yes | Filled from the machine environment's entropy source |
| `sched_yield` | Yes | Returns 0 at once. CPU time is assigned by fuel, so yielding has no effect |
| `sock_accept` | `NOSYS` | Sockets are not implemented ([#76](https://github.com/neverclear86/KiBi-Computers/issues/76)) |
| `sock_recv` | `NOSYS` | Same as above |
| `sock_send` | `NOSYS` | Same as above |
| `sock_shutdown` | `NOSYS` | Same as above |
<!-- /check -->

## What is rejected at load time

When a program is run (by `spawn` in `kibi:process`, from the shell, and so on), the kernel checks the module's imports and exports. If any of the following applies, it refuses to run the module with `NOEXEC`:

- A function from `wasi_snapshot_preview1` whose name is not in the table above
- An import of an implemented p1 function with a type different from witx
- An import that is not a function (memory, table, global, tag)
- Any module name other than `wasi_snapshot_preview1` and names starting with `kibi:` (such as `env`)
- A wrong `kibi:*` import (a function that is not provided, a type mismatch, a version mismatch), or importing a function that needs `cabi_realloc` without exporting `cabi_realloc` ([Common rules for kibi:*](./kibi-abi.md))
- Reserved names: imports from the module `kibi` (a space the kernel uses for fuel metering and wait-point conversion), including the old names `kibi.memory_remaining` and `kibi.memory_commit`. The exports `kibi_fuel` and `kibi_async_state` are reserved too
- Code that cannot be converted to wait points ([Waiting and saving](#waiting-and-saving))
- A module that is too large, that has too many functions, locals, and so on, or that takes too long to load (the limits are provisional)

An executable that is not a regular file returns `ACCES`; one that is too large returns `FBIG`. A file that starts with `#!` is run by the interpreter on that line (argv becomes `[interpreter, (arguments on the #! line), absolute path of the script, original argv[1..]]`).

## File descriptors

### Standard I/O and preopens

- fds 0, 1, and 2 are standard input, standard output, and standard error. Whoever creates the process decides what they are connected to (the console, a pipe, or nothing)
- The first process started at boot (init) gets exactly one preopen: the directory `/` at fd 3. Every mount (disks, `/tmp`, `/dev`) appears under `/`, so this one preopen reaches everything
- Whether a child gets fds 3 and up is decided by its creator (with `spawn` in `kibi:process`, only the fds 3 and up that are listed are passed)

### Preopens

`fd_prestat_get` **reports any fd that has a directory open as a preopen**. Its name is the absolute path the directory was opened with (`/`, `/home`, and so on). An fd that is not a directory returns `BADF`. A directory fd passed to a child becomes a preopen of the child as is. wasi-libc reads preopens from fd 3 until it gets `BADF`, so place preopens contiguously from fd 3.

### File types and rights

| What the fd refers to | filetype from `fd_fdstat_get` | Rights |
| --- | --- | --- |
| Regular file | `REGULAR_FILE` | Position, sync, and attribute operations, plus `FD_READ` and `FD_WRITE` according to how it was opened |
| Directory | `DIRECTORY` | `path_*`, `fd_readdir`, and so on. All inheriting rights |
| `/dev/null`, `/dev/zero`, `/dev/random`, `/dev/urandom` opened with `path_open` | `CHARACTER_DEVICE` | Same as a regular file (has `SEEK` and `TELL`) |
| Console | `CHARACTER_DEVICE` | `FD_READ` and `FD_WRITE` (according to how it was opened), poll, filestat. No `SEEK` or `TELL` |
| Pipes and handles such as event queues, timers, and processes | `UNKNOWN` | Same as above |

- Rights are computed each time from the kind of fd and whether it was opened for reading or writing. Only read versus write is actually enforced; the finer p1 rights (`fd_fdstat_set_rights`, `rights_inheriting` of `path_open`) cannot narrow access
- wasi-libc's `isatty` is true only for an fd that is a `CHARACTER_DEVICE` and also lacks the `SEEK` and `TELL` rights. The console is such an fd. The `/dev` files are `CHARACTER_DEVICE` but have `SEEK` and `TELL`, so it is false for them

### Reading and writing

- `fd_read` and `fd_write` combine the iovecs into one read or write. One call handles at most 1 MiB in total; anything beyond that is a partial read or write
- On a blocking fd, a read waits until at least some data is available, and a write waits until it can write. A write to a stream may return as soon as some bytes are written (a partial write; wasi-libc writes the rest again)
- On a `NONBLOCK` fd, if nothing can be done, the call returns `AGAIN`. **`NONBLOCK` is a per-fd attribute**: unlike POSIX, it is not shared with duplicated fds
- End of file is a 0-byte read
- The guest memory ranges are all checked before any reading or writing; if one is out of bounds, nothing is done and `FAULT` is returned

### Reading directories

- `fd_readdir` returns `.` and `..`, then the entries in name order
- The cookie is the index in that order, and `d_next` of a dirent is the index + 1. Reading can continue with the same meaning across a save and restore (if the contents change in between, entries may be skipped or repeated, as with POSIX `readdir`)
- The last dirent that does not fit in the buffer is cut off (`bufused == buf_len`)

## Paths

- A path is UTF-8 given as `(ptr, len)` (no NUL terminator). Broken UTF-8 returns `ILSEQ`; a path that is too long (provisional: 4096 bytes) returns `NAMETOOLONG` without reading the memory
- Only paths under the base fd can be reached. **Absolute paths and `..` that leaves the base return `NOTCAPABLE`**
- If the base fd is not open, `BADF`; if it is not a directory, `NOTDIR`
- There are no links. The `SYMLINK_FOLLOW` bit of `lookupflags` is accepted and ignored; unknown bits return `INVAL`
- A trailing `/` means "`NOTDIR` if it is not a directory"
- `path_open`: `oflags` uses `CREAT`, `DIRECTORY`, `EXCL`, and `TRUNC`. `rights_base` is looked at only for `FD_READ` (read) and `FD_WRITE` (write). `fdflags` uses `APPEND` and `NONBLOCK`; `DSYNC`, `RSYNC`, and `SYNC` return `NOTSUP`, unknown bits `INVAL`. If the fd table is full, `MFILE` is returned before opening (so `TRUNC` never empties a file and then fails)

## poll_oneoff

- If the number of subscriptions is 0 or exceeds the limit (provisional: 1024), `INVAL`. If `in`, `out`, or `nevents` is out of bounds, `FAULT` without writing anything
- If any subscription has an unknown tag, the whole call returns `INVAL` (as in wasmtime)
- An `event` is written for each ready subscription (including those with errors), in subscription order. If none is ready, the call waits until one is. The fd's `NONBLOCK` is ignored (as with POSIX `poll`)
- Checking takes nothing out (queue events and timer counts stay)

### fd subscriptions

| What the fd refers to | `FD_READ` | `FD_WRITE` |
| --- | --- | --- |
| Not open, or no right in that direction | `BADF` event | Same |
| Pipe | Bytes to read, or the write end was closed. `nbytes` is the bytes available | Free space, or the read end was closed. `nbytes` is the free space |
| Console | Input to read | Space to write |
| File or directory | Always ready | Always ready |
| Event queue, timer, device call (`kibi:*` handles) | Something to take, or finished. `nbytes` is 1 | `BADF` (no write right) |
| Child process (`kibi:process` handle) | Finished. `nbytes` is 0 | `BADF` (no write right) |
| Any other kind that cannot be waited on | `INVAL` event | Same |

- `HANGUP` (in `eventrwflags`) is set only when the other end of a pipe was closed

### Clock subscriptions

- `precision` is not used. If `flags` has any bit other than `ABSTIME`, or the clock `id` is unknown, that subscription gets an `INVAL` event
- The deadline is converted to monotonic-clock time when the call starts. An absolute `REALTIME` deadline is converted then too, so later jumps of real time are not followed
- `timeout` is read as unsigned, and additions saturate (a very large value waits forever). A deadline at or before now is ready at once (`timeout = 0` checks without waiting)
- If the internal timer used for clock waits hits the machine's limit, the whole call returns `AGAIN`

## Clocks

| id | Value |
| --- | --- |
| `REALTIME` | The real time of the machine's environment (nanoseconds) |
| `MONOTONIC` | The machine's monotonic clock. 0 when the machine was created |
| `PROCESS_CPUTIME`, `THREAD_CPUTIME` | Monotonic time elapsed since the process started. **It is not CPU time; it includes time spent waiting** (both have the same value) |

- The resolution of every clock is 1 µs
- Timers (`kibi:kernel/timers`) and clock waits in `poll_oneoff` use the same clock as `MONOTONIC`
- Uptime (in ticks) and in-game time are in `kibi:computer/clock`. Clock jumps on restoring from a save are reported by the `restored` event ([kibi:kernel](./kibi-kernel.md#event-kinds))

## Working directory

p1 has no function to read or change the working directory (cwd). The kernel keeps a cwd per process and passes it to p1 programs in the environment variable `PWD`.

- The environment shown by `environ_get` always contains exactly one `PWD=<kernel cwd>` (if the given environment has `PWD`, its value is replaced in place; otherwise it is appended). The value is the cwd at the first `environ_*` call and does not change after that
- wasi-libc does not read `PWD` at startup. To make relative paths start from the kernel cwd, `chdir` to `getenv("PWD")` when your program starts
- libc's `chdir` is not passed to the kernel. To have children inherit it, also call `set-cwd` in `kibi:process` ([kibi:process](./kibi-process.md))

## Waiting and saving

The functions that may block are the ones in the table of [Imports that may block](./kibi-abi.md#imports-that-may-block).

- At load time, the kernel **converts the module to wait points** (it rewrites the module so that the state in the middle of a call can be moved out of the linear memory). While a converted process waits in a function that may block, it holds no thread; its machine can be saved with the world, and after loading, the call continues where it was. Running out of fuel usually stops the process in the same way
- **A module whose code cannot be converted fails at load time with `NOEXEC`.** This happens when a function that can reach a blocking call contains SIMD (v128) locals or instructions, typed references (GC, function references), a tail call to a blocking function, and so on. The error message says which function and which feature. A module that imports no function that may block is never rejected for this reason
- Exceptions: inside the module's start function (while the instance is being created) and inside `cabi_realloc`, the state cannot be moved out. Waiting inside the start function holds a thread while waiting. **Calling a function that may block inside `cabi_realloc` traps** ([Common rules for kibi:*](./kibi-abi.md#cabi_realloc))
- After restoring from a save, the call that was waiting is retried (`fd_read` from before the read, `poll_oneoff` with the same deadline). `spawn` starts loading again if the child was not yet created, and never creates it twice. `invoke` returns `failed("interrupted")` for a main-thread method that was running ([kibi:device](./kibi-device.md))
- The detailed rules on when saves happen and on processes that could not be saved are in ADR [0081](https://github.com/neverclear86/KiBi-Computers/blob/main/docs/adr/0081-wait-point-save-lifecycle.md) and [0085](https://github.com/neverclear86/KiBi-Computers/blob/main/docs/adr/0085-restore-on-load.md) (in Japanese)

## Fuel

Each system call uses a little fuel. Some calls use more in proportion to the work: bytes read or written, events taken, entries listed, mounting a disk, and so on. All amounts are provisional and will be set before the first release ([#251](https://github.com/neverclear86/KiBi-Computers/issues/251)).
