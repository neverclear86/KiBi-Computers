//! 標準 OS の `cabi_realloc`（Canonical ABI。#87・#454）。可変長の結果を返す `kibi:*` の import は、ホストがゲストのこの関数で場所を取って書く。
//!
//! 使う包み: `events.Queue.takeOne`（`kibi:kernel/events` の `take`）・`process.listProcesses`・`mounts.listMounts`・`mounts.listDisks`。
//! これらを使うプログラムだけが `cabi_realloc` を export する（`core/build.gradle.kts` の `osReallocPrograms` に足し、`--export=cabi_realloc`
//! でビルドする）。`needsRealloc` の import を持つのに export していなければ、読み込みで NOEXEC になる（`StandardOsRestoreTest` が検出する）。
//! 記号は 1 つしか置けないので、`cabi_realloc` はここにだけ書く（ほかのファイルに `export fn cabi_realloc` を書かない）。
//!
//! 標準 OS は標準のアロケーターを使わないので、静的な領域（.bss。触るまで RAM に数えない。#407）から前へ切り出すだけにする:
//! - 戻すのは [reset] だけ。包みの中では戻さない（`mount` が `list-mounts` と `list-disks` を続けて呼び、両方を同時に読めるように）。
//!   結果を読み終えて次の呼び出しに移るとき、プログラムの側が [reset] を呼ぶ。`events.Queue.takeOne` は毎回、取り出す前に戻す
//!   （取り出したイベントは次の `take` までしか読めない）
//! - 領域（[ARENA_SIZE]）が足りなければ、線形メモリを `memory.grow` で増やし、増やしたページを新しい領域にして、その先頭から切り出す
//!   （プロセスの表が大きくなっても `ps` が動くように）。ページの残りは次の割り当てに回すので、小さな割り当てが続いても 1 ページずつ増えない。
//!   増やした分は [reset] でも返さない（一覧を 1 回取って終わるコマンドだけが使う）
//! - 増やせなければ線形メモリの外を返す（ホストがトラップする）。0 を返すと、アドレス 0 から上書きしてメモリを壊すため
//! - 止まりうる import（`fd_write` など）は呼ばない（`cabi_realloc` の中で呼ぶとトラップ。ADR 0073）

/// 静的な領域の大きさ。`ps` の既定の表（128 件）の最悪 128 × (28 + 16 × 8 + 255) ≒ 52 KiB と、
/// マウント・ディスクの一覧（10 件程度。位置の文字列は普通は短い）が収まる大きさ
pub const ARENA_SIZE = 64 * 1024;
var arena: [ARENA_SIZE]u8 align(16) = undefined;

/// 今切り出している領域（最初は静的な [arena]。溢れたら `memory.grow` で増やしたページ）。[region_used] は先頭からの使用量
var region_base: usize = 0; // 0 は未初期化（静的領域のアドレスは 0 にならない）
var region_size: usize = 0;
var region_used: usize = 0;

/// 足りないときに返す場所（線形メモリの外。align 8 に揃えてあるので、ホストは範囲外としてトラップする）
pub const OUT_OF_MEMORY: u32 = 0xFFFF_FFF8;

const WASM_PAGE = 64 * 1024;

/// 領域を静的な [arena] の先頭へ戻す。それまでに受け取った結果は、以後読めない
/// （増やしたページは返さない）
pub fn reset() void {
    region_base = @intFromPtr(&arena);
    region_size = ARENA_SIZE;
    region_used = 0;
}

/// 今の領域から切り出す。入らなければ null
fn carve(a: usize, len: usize) ?u32 {
    const start = (region_base + region_used + a - 1) & ~(a - 1);
    const offset = start - region_base;
    if (offset <= region_size and len <= region_size - offset) {
        region_used = offset + len;
        return @intCast(start);
    }
    return null;
}

/// ホストが結果の場所を取る（Canonical ABI の `cabi_realloc`）。新しく取るだけで、[old] は使わない
/// （ホストは `cabi_realloc(0, 0, align, size)` で呼ぶ）
export fn cabi_realloc(old: u32, old_len: u32, alignment: u32, new_len: u32) u32 {
    _ = old;
    _ = old_len;
    const a: usize = if (alignment == 0) 1 else alignment;
    if (a & (a - 1) != 0 or a > WASM_PAGE) return OUT_OF_MEMORY;
    if (region_base == 0) reset();
    if (carve(a, new_len)) |p| return p;
    return grow(new_len);
}

/// 線形メモリを増やして、増やしたページを新しい領域にし、その先頭から切り出す
/// （ページの先頭なので、どの align にも揃っている）。古い領域の残りは捨てる
fn grow(len: u32) u32 {
    const pages: usize = @max((@as(usize, len) + WASM_PAGE - 1) / WASM_PAGE, 1);
    const old = @wasmMemoryGrow(0, pages);
    if (old < 0) return OUT_OF_MEMORY;
    region_base = @as(usize, @intCast(old)) * WASM_PAGE;
    region_size = pages * WASM_PAGE;
    region_used = len;
    return @intCast(region_base);
}
