---
title: kibi:process
description: Spawning, waiting for, and signalling processes; the kernel working directory; the process list.
---

The `process` interface of `kibi:process@0.1.0` creates, waits for, and stops child processes, and handles the kernel working directory (cwd) and the list of the machine's processes. There is no fork. A child is created from an executable in the VFS, and its arguments, environment, cwd, and fds are decided once, at creation.

```wit
// kibi-process/process.wit
package kibi:process@0.1.0;
interface process {
    use kibi:kernel/types@0.1.0.{error-code};
    enum signal {
        interrupt,
        quit,
        terminate,
        kill,
    }
    variant exit-status {
        exited(u32),
        killed(option<signal>),
        crashed,
    }
    variant process-state {
        starting,
        running,
        waiting,
        finished(exit-status),
    }
    record process-info {
        pid: u32,
        parent-pid: u32,
        state: process-state,
        args: list<string>,
    }
    variant fd-source {
        parent(u32),
        closed,
    }
    record fd-assign {
        fd: u32,
        source: fd-source,
    }
    record spawn-options {
        args: list<string>,
        env: option<list<tuple<string, string>>>,
        cwd: option<string>,
        fds: list<fd-assign>,
    }
    resource process {
        spawn: static func(path: string, options: spawn-options) -> result<process, error-code>;
        pid: func() -> u32;
        status: func() -> result<option<exit-status>, error-code>;
        kill: func(sig: signal) -> result<_, error-code>;
    }
    kill-pid: func(pid: u32, sig: signal) -> result<_, error-code>;
    list-processes: func() -> list<process-info>;
    self-pid: func() -> u32;
    parent-pid: func() -> u32;
    set-signal-ignored: func(sig: signal, ignored: bool) -> result<_, error-code>;
    get-cwd: func() -> string;
    set-cwd: func(path: string) -> result<_, error-code>;
}
```

## Behavior

### spawn

- `path` is an executable in the VFS. A relative path starts from the calling process's kernel cwd. `PATH` is not searched (that is the shell's job)
- This **import may block**: it waits until the executable is loaded (compiled). The loaded result of the same program is shared across the server, so later spawns may finish at once
- The child's ABI is decided by the executable's form, not by the parent. The load-time rejections are the same as in [WASI p1](./wasi-p1.md#what-is-rejected-at-load-time), and errors such as `no-exec` are returned as is
- The child's handle is put into the caller's fd table with the read right. If the table is full, the child is not created and `mfile` is returned; if the machine's number of processes is at its limit, `nfile`
- If `args` is empty, argv is the single string `path`
- If `env` is `none`, the parent's environment is used; if `some`, exactly that list is used, in order
- If `cwd` is `none`, the parent's kernel cwd is used (not the value after libc's `chdir`)
- `fds`: fds 0, 1, and 2 are inherited from the parent's same fds unless listed. Fds 3 and up are passed only if listed. Preopen directories must be listed explicitly too (the child's preopens should be contiguous from fd 3). `closed` leaves the fd unassigned

### status and waiting

- `status` returns how the child ended without waiting (`none` if it has not ended). When the parent receives `some`, the child is reaped. After reaping, the same handle keeps returning the same value
- To wait for a child to end, wait on its handle with `FD_READ` in `poll_oneoff`. `FD_WRITE` returns `bad-descriptor`, and `fd_read` returns `invalid`
- Only the parent reaps. Other processes that were given the fd do not
- When the parent closes its last handle to a child, the child loses its parent (if it has already ended, it is reaped at once)

### Signals

- Signals have no handlers; the only effects are "ignored" or "stop". `kill` cannot be ignored
- `kill` and `kill-pid` do not wait. Learn that the process stopped from `status` or `poll_oneoff`. Sending a signal to yourself that stops you stops you right away
- Delivered, ignored, and already ended all count as success. A missing target returns `no-such-process`; init returns `not-permitted`
- `kill` on a handle succeeds without sending anything if the handle's child is gone (its pid was reused by another process)
- `set-signal-ignored` is not inherited by children

### Working directory

- `get-cwd` and `set-cwd` read and change the kernel cwd. It is the default `cwd` for children and the base of relative paths
- p1's `PWD` ([WASI p1](./wasi-p1.md#working-directory)) keeps its first value. libc's `chdir` and `set-cwd` are separate, so call both to keep them in step

### list-processes

- Returns every process on the machine (yourself, ended processes not yet reaped, orphaned zombies) in ascending pid order. It does not wait and can be called inside `cabi_realloc`. The result is a snapshot at the time of the call
- `process-state`: `starting` while loading, `running` when runnable or running (including waiting for fuel), `waiting` when waiting for events and so on, `finished` when ended and not yet reaped
- `args` holds argv from the start, up to 16 entries and 255 bytes of UTF-8 in total (provisional). An entry that does not fit is cut at a UTF-8 character boundary, put in, and the list stops there. An entry that would become empty is not put in. There is no mark that something was cut

## p1 imports

Module: `kibi:process/process@0.1.0`

<!-- check:imports kibi:process/process@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `[static]process.spawn` | `(path_ptr: i32, path_len: i32, args_ptr: i32, args_len: i32, env_is_some: i32, env_ptr: i32, env_len: i32, cwd_is_some: i32, cwd_ptr: i32, cwd_len: i32, fds_ptr: i32, fds_len: i32, ret_ptr: i32) -> ()` | — | may block |
| `[method]process.pid` | `(self: i32) -> i32` | — | — |
| `[method]process.status` | `(self: i32, ret_ptr: i32) -> ()` | — | — |
| `[method]process.kill` | `(self: i32, sig: i32, ret_ptr: i32) -> ()` | — | — |
| `[resource-drop]process` | `(self: i32) -> ()` | — | — |
| `kill-pid` | `(pid: i32, sig: i32, ret_ptr: i32) -> ()` | — | — |
| `list-processes` | `(ret_ptr: i32) -> ()` | required | — |
| `self-pid` | `() -> i32` | — | — |
| `parent-pid` | `() -> i32` | — | — |
| `set-signal-ignored` | `(sig: i32, ignored: i32, ret_ptr: i32) -> ()` | — | — |
| `get-cwd` | `(ret_ptr: i32) -> ()` | required | — |
| `set-cwd` | `(path_ptr: i32, path_len: i32, ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

Passing an invalid handle to `[method]process.pid` or `[resource-drop]process` traps. `status` and `kill` return `bad-descriptor`.

## Memory layout

| Type | Layout | Size / align |
| --- | --- | --- |
| `string`, `list<T>` | `ptr @0`, `len @4` | 8 / 4 |
| `tuple<string, string>` (an `env` element) | Name `ptr @0`, `len @4`; value `ptr @8`, `len @12` | 16 / 4 |
| `fd-assign` | `fd: u32 @0`, the `source` discriminant `u8 @4` (`parent` = 0, `closed` = 1), the `parent` fd `u32 @8` | 12 / 4 |
| `result<process, error-code>` | Discriminant `u8 @0`; if ok, the handle `u32 @4`; if err, `u8 @4` | 8 / 4 |
| `result<_, error-code>` | Discriminant `u8 @0`; if err, `u8 @1` | 2 / 1 |
| `exit-status` | Discriminant `u8 @0` (`exited` = 0, `killed` = 1, `crashed` = 2); for `exited`, `u32 @4`; for `killed`, the `option` discriminant `u8 @4` and the `signal` `u8 @5` | 8 / 4 |
| `result<option<exit-status>, error-code>` | Discriminant `u8 @0`. If ok, the `option` discriminant `u8 @4` and `exit-status @8`; if err, `u8 @4` | 16 / 4 |
| `process-state` | Discriminant `u8 @0` (`starting` = 0, `running` = 1, `waiting` = 2, `finished` = 3); for `finished`, `exit-status @4` | 12 / 4 |
| `process-info` | `pid @0`, `parent-pid @4`, `state @8`, `args @20` | 28 / 4 |

The `signal` discriminants are `interrupt` = 0, `quit` = 1, `terminate` = 2, `kill` = 3.

## spawn argument checks

Checked in parameter order (`path` → `args` → `env` → `cwd` → `fds`); the first match is returned.

| Condition | Result |
| --- | --- |
| `path` or `cwd` over 4096 bytes | `name-too-long` |
| The array ptr of `args`, `env`, or `fds` not aligned to 4 (even with length 0; `env` is not checked when `none`) | Trap |
| Over 4096 `args` or 4096 `env` pairs (provisional; before reading the elements) | `too-big` |
| The strings' total over the limit (provisional; before reading the contents) | `too-big` |
| Over 256 `fds` (provisional) | `invalid` |
| A string that is not UTF-8, an invalid `option` or `fd-source` discriminant, a duplicate fd in `fds` | `invalid` |
| An argument location outside the linear memory | `fault` |
| The executable is missing, not a regular file, or not in a runnable form | `no-entry`, `access`, `no-exec`, and so on |
| A `parent` fd in `fds` that is not open | `bad-descriptor` |
| `cwd` missing or not a directory | `no-entry`, `not-directory` |

A `signal` discriminant outside 0 to 3 returns `invalid`, and passing `kill` to `set-signal-ignored` returns `invalid`. `set-cwd` returns `no-entry` for a missing path and `not-directory` for something that is not a directory.

This order may differ in the Component Model layer ([Common rules](./kibi-abi.md#errors)).
