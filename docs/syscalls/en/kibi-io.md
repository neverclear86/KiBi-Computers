---
title: kibi:io
description: Duplicating file descriptors (dup, dup2), pipes, and the TTY mode, size, and foreground process.
---

`kibi:io@0.1.0` holds fd operations that WASI p1 lacks. `fd` and `pipe` are for p1 only; p2 uses `pipe-streams`, and p3 is planned to use another (provisional) interface. `tty` is planned to have the same shape in p1, p2, and p3.

An fd argument is the process's kernel fd number (the same number as the p1 fd).

## fd

```wit
// kibi-io/fd.wit
package kibi:io@0.1.0;
interface fd {
    use kibi:kernel/types@0.1.0.{error-code};
    dup: func(fd: u32) -> result<u32, error-code>;
    dup-to: func(fd: u32, to: u32) -> result<_, error-code>;
}
```

- `dup` duplicates into the lowest free number (POSIX `dup`). `dup-to` duplicates into `to`, closing `to` first if it is open (POSIX `dup2`). If `fd` and `to` are the same, it only checks that the fd is open
- A duplicated fd refers to the same thing and shares the file position and stream contents. The rights and the fd flags (such as `NONBLOCK`) are copied
- **Difference from POSIX**: `NONBLOCK` is a per-fd attribute, so after duplicating, each fd can change it separately (it is not shared)
- If `fd` is not open or `to` is out of range, `bad-descriptor`, and `to` is not closed. If the table is full, `mfile`
- Whatever is open at `to` (including event queues and child handles) is closed as a plain fd

## pipe

```wit
// kibi-io/pipe.wit
package kibi:io@0.1.0;
interface pipe {
    use kibi:kernel/types@0.1.0.{error-code};
    record pipe-ends {
        read: u32,
        write: u32,
    }
    create: func() -> result<pipe-ends, error-code>;
}
```

- Use the read end with `fd_read` and `FD_READ` in `poll_oneoff`, and the write end with `fd_write` and `FD_WRITE`. Using an end in the wrong direction returns `bad-descriptor`
- When every write end is closed, the reader gets end of file (0 bytes) after the remaining data. When every read end is closed, the writer gets `pipe`
- The buffer size is the default (provisional: 4096 bytes). The two ends take the lowest free numbers in order; if there are not two free numbers, nothing is created and `mfile` is returned
- The fd flags of both ends are 0 (blocking). For non-blocking reads and writes, set `NONBLOCK` with `fd_fdstat_set_flags`
- `fd_fdstat_get` reports both ends as `UNKNOWN`

## tty

```wit
// kibi-io/tty.wit
package kibi:io@0.1.0;
interface tty {
    use kibi:kernel/types@0.1.0.{error-code};
    flags mode {
        canonical,
        echo,
        signals,
        cr-to-nl,
        nl-to-crnl,
    }
    record size {
        columns: u32,
        rows: u32,
        generation: u64,
    }
    is-tty: func(fd: u32) -> bool;
    get-mode: func(fd: u32) -> result<mode, error-code>;
    set-mode: func(fd: u32, mode: mode) -> result<_, error-code>;
    get-size: func(fd: u32) -> result<size, error-code>;
    get-foreground: func(fd: u32) -> result<u32, error-code>;
    set-foreground: func(fd: u32, pid: u32) -> result<_, error-code>;
}
```

- If `fd` is not open, `bad-descriptor` (`is-tty` returns `false`); if it is not a TTY, `no-tty`. The fd's read and write rights do not matter (as with termios)
- The `mode` bits act independently. All on (0x1f) is the default, cooked; all off (0) is raw (like `cfmakeraw`). `set-mode` ignores undefined high bits
- Leaving `canonical` turns the line being edited into input that can be read as is
- `get-size` returns the size and the generation as a pair from the same moment. When the screen size changes, a `tty-resize` event arrives ([kibi:kernel](./kibi-kernel.md#event-kinds)). Missed changes show up in the generation
- If `pid` of `set-foreground` is 2^31 or more, `invalid`, checked before the fd. Whether the process is alive is not checked. Any process can change it

## p1 imports

Module: `kibi:io/fd@0.1.0`

<!-- check:imports kibi:io/fd@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `dup` | `(fd: i32, ret_ptr: i32) -> ()` | — | — |
| `dup-to` | `(fd: i32, to: i32, ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

Module: `kibi:io/pipe@0.1.0`

<!-- check:imports kibi:io/pipe@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `create` | `(ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

Module: `kibi:io/tty@0.1.0`

<!-- check:imports kibi:io/tty@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `is-tty` | `(fd: i32) -> i32` | — | — |
| `get-mode` | `(fd: i32, ret_ptr: i32) -> ()` | — | — |
| `set-mode` | `(fd: i32, mode: i32, ret_ptr: i32) -> ()` | — | — |
| `get-size` | `(fd: i32, ret_ptr: i32) -> ()` | — | — |
| `get-foreground` | `(fd: i32, ret_ptr: i32) -> ()` | — | — |
| `set-foreground` | `(fd: i32, pid: i32, ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

## Memory layout

| Type | Layout | Size / align |
| --- | --- | --- |
| `result<u32, error-code>` | Discriminant `u8 @0`; if ok, `u32 @4`; if err, `u8 @4` | 8 / 4 |
| `result<_, error-code>` | Discriminant `u8 @0`; if err, `u8 @1` | 2 / 1 |
| `result<pipe-ends, error-code>` | Discriminant `u8 @0`; if ok, `read: u32 @4` and `write: u32 @8`; if err, `u8 @4` | 12 / 4 |
| `result<mode, error-code>` | Discriminant `u8 @0`; if ok, the bits `u8 @1`; if err, `u8 @1` | 2 / 1 |
| `result<size, error-code>` | Discriminant `u8 @0`; if ok, `columns: u32 @8`, `rows: u32 @12`, `generation: u64 @16`; if err, `u8 @8` | 24 / 8 |

The `mode` bits are `canonical` = 1, `echo` = 2, `signals` = 4, `cr-to-nl` = 8, `nl-to-crnl` = 16.
