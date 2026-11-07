//! スタックの残り（#514）。
//!
//! 標準 OS のプログラムは、スタックを線形メモリの頭（`__stack_low` = 0 から `__stack_high` まで）に置き、
//! 静的データはその後ろに置く（Zig と `zig cc` のリンカーの既定。core の `StackLayoutTest` が確かめる）。
//! あふれたスタックは 0 より下（符号なしで 4 GiB 近く）を指すので、黙って静的データを壊さず、範囲外の読み書きで止まる。
//! 深く再帰するプログラムは、止まる前に [remaining] を見て、自分で誤りにする。

const low = @extern(*allowzero const u8, .{ .name = "__stack_low" });

/// 今の関数から下に残っているスタック（バイト）
pub fn remaining() usize {
    const sp = @frameAddress();
    const lo = @intFromPtr(low);
    return if (sp > lo) sp - lo else 0;
}
