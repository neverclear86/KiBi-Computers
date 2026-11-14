---
title: kibi:fs
description: Mounting and unmounting disks, and listing mounts and disks with their capacity.
---

The `mounts` interface of `kibi:fs@0.1.0` handles disk mounts. Reading and writing files themselves uses WASI (`path_open`, `fd_read`, and so on). None of its functions wait.

```wit
// kibi-fs/mounts.wit
package kibi:fs@0.1.0;
interface mounts {
    use kibi:kernel/types@0.1.0.{error-code};
    mount: func(source: string, point: string, read-only: bool) -> result<_, error-code>;
    unmount: func(point: string) -> result<_, error-code>;
    enum fs-kind {
        disk,
        image,
        tmp,
        dev,
    }
    record fs-usage {
        total-bytes: u64,
        used-bytes: u64,
        available-bytes: u64,
        total-entries: option<u32>,
        used-entries: u32,
    }
    record mount-info {
        point: string,
        source: string,
        kind: fs-kind,
        read-only: bool,
        id: u32,
        usage: option<fs-usage>,
    }
    record disk-info {
        source: string,
        capacity-bytes: u64,
        max-entries: u32,
        mount-point: option<string>,
    }
    list-mounts: func() -> list<mount-info>;
    list-disks: func() -> list<disk-info>;
}
```

## Behavior

- An inserted disk is mounted automatically by the kernel at `/mnt/<first 8 characters of the identifier>`, and the kernel sends the events `disk-inserted` and `disk-removed` ([Event kinds](./kibi-kernel.md#event-kinds))
- The `source` of `mount` is `disk:<identifier>`. A relative `point` starts from the calling process's kernel cwd. `point` need not exist (that location and its ancestors appear as synthetic directories)
- Even if `read-only` is false, a read-only disk is mounted read-only
- `unmount` works even with open files. Later operations on those files return `io`
- `mount` opens the disk every time, so it uses more fuel than other calls (in proportion to the number of entries on the disk; provisional)
- `list-mounts` returns mounts in the order they were added; `list-disks` returns the inserted disks in slot order. Both can be called inside `cabi_realloc`. They are separate calls, so if a disk is inserted or removed in between, the two results may disagree
- Usage includes bookkeeping overhead, so it does not match the total file size (`du`). The free space of `tmp` is an estimate from the remaining RAM and does not guarantee that a write will succeed

## Errors

| Condition | Result |
| --- | --- |
| `source` is not `disk:<identifier>`, is a bundled image (`disk:image:*`), or is not inserted now | `no-entry` |
| That disk is already mounted or is being opened | `busy` |
| `point` already has a mount | `busy` |
| `point` is not a directory, or something on the way in the path is not a directory | `not-directory` |
| The machine is not running, the disk cannot be opened, or it was removed while opening | `io` |
| `point` of `unmount` is `/`, `/tmp`, or `/dev`, or has other mounts below it | `busy` |
| `point` of `unmount` is not a mount point | `invalid` |
| A string outside the linear memory | `fault` |
| `source` over 4096 bytes or not UTF-8 | `invalid` |
| `point` too long (4096 bytes) | `name-too-long` |

## p1 imports

Module: `kibi:fs/mounts@0.1.0`

<!-- check:imports kibi:fs/mounts@0.1.0 -->
| Function | Core type | cabi_realloc | Blocks |
| --- | --- | --- | --- |
| `mount` | `(src_ptr: i32, src_len: i32, pt_ptr: i32, pt_len: i32, read_only: i32, ret_ptr: i32) -> ()` | — | — |
| `unmount` | `(pt_ptr: i32, pt_len: i32, ret_ptr: i32) -> ()` | — | — |
| `list-mounts` | `(ret_ptr: i32) -> ()` | required | — |
| `list-disks` | `(ret_ptr: i32) -> ()` | required | — |
<!-- /check -->

## Memory layout

| Type | Layout | Size / align |
| --- | --- | --- |
| `result<_, error-code>` | Discriminant `u8 @0`; if err, `u8 @1` | 2 / 1 |
| `list<T>` (the retptr of `list-mounts` and `list-disks`) | `ptr @0`, `len @4` | 8 / 4 |
| `fs-usage` | `total-bytes: u64 @0`, `used-bytes @8`, `available-bytes @16`, the `total-entries` discriminant `u8 @24` and value `u32 @28`, `used-entries: u32 @32` | 40 / 8 |
| `mount-info` | `point @0`, `@4`; `source @8`, `@12`; `kind: u8 @16`; `read-only: u8 @17`; `id: u32 @20`; the `usage` discriminant `u8 @24` and `fs-usage @32` | 72 / 8 |
| `disk-info` | `source @0`, `@4`; `capacity-bytes: u64 @8`; `max-entries: u32 @16`; the `mount-point` discriminant `u8 @20`, `ptr @24`, `len @28` | 32 / 8 |

The `fs-kind` discriminants are `disk` = 0, `image` = 1, `tmp` = 2, `dev` = 3. Padding and unused value space are filled with 0.
