//! 標準 OS の共通ライブラリ `kibi`（#98）。WASI p1 と独自拡張 `kibi:*` の import を包む。
//!
//! 標準 OS のプログラム（`os/bin`・`os/sbin`）は `@import("kibi")` で使う。
//! Zig の標準ライブラリの高い層（`std.Io`・アロケーター）は版ごとに形が変わり、大きさも増えるので使わない。
//! バッファは静的に置く（.bss はゼロなので、触るまで RAM に数えない。#407）。
//!
//! 可変長の結果を返す import（`events` の `take`・`process.listProcesses`・`mounts.listMounts`・`mounts.listDisks`）を使うプログラムは、
//! `cabi_realloc`（`canon`。静的な領域から切り出すだけ）も export する（`core/build.gradle.kts` の `osReallocPrograms` に足したプログラムだけに出る）。
//!
//! 出していないもの（使うプログラムができたときに足す）: `kibi:kernel/timers`・
//! `kibi:computer/clock`・`get-cwd`（cwd は `fs.cwd()` が `PWD` から持つ）。

pub const wasi = @import("wasi.zig");
pub const errno = @import("errno.zig");
pub const io = @import("io.zig");
pub const fs = @import("fs.zig");
pub const process = @import("process.zig");
pub const tty = @import("tty.zig");
pub const memory = @import("memory.zig");
pub const time = @import("time.zig");
pub const cli = @import("cli.zig");
pub const tree = @import("tree.zig");
pub const stack = @import("stack.zig");
pub const text = @import("text.zig");
pub const keys = @import("keys.zig");
pub const events = @import("events.zig");
pub const canon = @import("canon.zig");
pub const mounts = @import("mounts.zig");
pub const power = @import("power.zig");

pub const Error = errno.Error;
