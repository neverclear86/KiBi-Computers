//! `kmem`: p1 の上で独自拡張 `kibi.*`（#408）の import を呼べるかを確かめる（#32）。
//! 4 KiB を memory_commit で数え、残りの RAM が正なら `kibi ok` を書いて 0 で終わる。どちらかが違えば 1
const sys = @import("sys.zig");

var buf: [8192]u8 = undefined;

pub fn main() void {
    const committed = sys.memory_commit(@intCast(@intFromPtr(&buf)), 4096, 0) == 1;
    const remaining = sys.memory_remaining();
    if (committed and remaining > 0) {
        _ = sys.writeAll(sys.STDOUT, "kibi ok\n");
    } else {
        _ = sys.writeAll(sys.STDOUT, "kibi failed\n");
        sys.proc_exit(1);
    }
}
