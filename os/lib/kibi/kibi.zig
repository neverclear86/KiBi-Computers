//! 標準 OS の共通ライブラリ `kibi`（#98）。WASI p1 と独自拡張 `kibi:*` の import を包む。
//!
//! 標準 OS のプログラム（`os/bin`・`os/sbin`）は `@import("kibi")` で使う。
//! Zig の標準ライブラリの高い層（`std.Io`・アロケーター）は版ごとに形が変わり、大きさも増えるので使わない。
//! バッファは静的に置く（.bss はゼロなので、触るまで RAM に数えない。#407）。
//!
//! 出していないもの（使うプログラムができたときに足す）: `kibi:kernel/events`・`kibi:kernel/timers`・
//! `kibi:computer/clock`・`get-cwd`（cabi_realloc の export が要る。cwd は `fs.cwd()` が `PWD` から持つ）。

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

pub const Error = errno.Error;
