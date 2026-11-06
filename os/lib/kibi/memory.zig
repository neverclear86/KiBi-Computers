//! 独自拡張 `kibi:computer/memory`（#408・#73）の包み（#98）。
//!
//! RAM は、初めて書き込んだ 4 KiB の区画の数で数える（#407）。
const raw = struct {
    extern "kibi:computer/memory@0.1.0" fn remaining() u64;
    extern "kibi:computer/memory@0.1.0" fn commit(ptr: u32, len: u32, keep: u64) i32;
};

/// RAM を数える区画の大きさ（バイト）
pub const PAGE_SIZE = 4096;

/// 残りの RAM（バイト）。プロセスの上限とマシンの RAM のうち、小さい方の残り
pub fn remaining() u64 {
    return raw.remaining();
}

/// [bytes] の区画を今のうちに RAM として数える（以後そこへ書いても、RAM の上限で止まらない）。
/// 数えた後の残りが [keep] 未満になるなら数えずに false
pub fn commit(bytes: []const u8, keep: u64) bool {
    return raw.commit(@intCast(@intFromPtr(bytes.ptr)), @intCast(bytes.len), keep) == 1;
}
