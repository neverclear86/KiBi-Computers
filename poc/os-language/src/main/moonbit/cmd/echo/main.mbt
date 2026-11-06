// `echo`: 引数を空白でつないで1行で書く（#32）。Zig 側と同じく 4 KiB で切る

///|
fn main {
  let argv = @sys.args()
  let line : FixedArray[Byte] = FixedArray::make(4096, b'\x00')
  let cap = line.length()
  let mut n = 0
  for i in 1..<argv.length() {
    let s = argv[i]
    if i > 1 && n < cap {
      line[n] = b' '
      n = n + 1
    }
    let k = if s.length() < cap - n { s.length() } else { cap - n }
    line.blit_from_bytes(n, s, 0, k)
    n = n + k
  }
  if n < cap {
    line[n] = b'\n'
    n = n + 1
  }
  if !@sys.write_all(@sys.stdout, line, 0, n) {
    @sys.exit(1)
  }
}
