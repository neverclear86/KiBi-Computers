//! `clear`: 端末の画面を消して、カーソルを左上に置く
const kibi = @import("kibi");

pub fn main() u8 {
    // CUP（左上へ）と ED 2（画面全体を消す）
    kibi.io.writeAll(kibi.io.STDOUT, "\x1b[H\x1b[2J") catch return 1;
    return 0;
}
