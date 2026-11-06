// #32 の追加調査: kmem と同じ（moon.pkg でアロケーターだけ変える）
//
// `kmem`: p1 の上で独自拡張 `kibi.*`（#408）の import を呼べるかを確かめる（#32）。
// 4 KiB を memory_commit で数え、残りの RAM が正なら `kibi ok` を書いて 0 で終わる。どちらかが違えば 1

///|
fn main {
  let buf : FixedArray[Byte] = FixedArray::make(8192, b'\x00')
  let committed = @sys.memory_commit(buf, 0, 4096, 0L)
  let remaining = @sys.memory_remaining()
  if committed && remaining > 0L {
    ignore(@sys.write_bytes(@sys.stdout, b"kibi ok\n"))
  } else {
    ignore(@sys.write_bytes(@sys.stdout, b"kibi failed\n"))
    @sys.exit(1)
  }
}
