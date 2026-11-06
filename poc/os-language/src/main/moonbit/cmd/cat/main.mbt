// `cat`: 引数のファイル（なければ標準入力）を標準出力へ写す（#32）

///|
fn copy(fd : Int, buf : FixedArray[Byte]) -> Bool {
  while true {
    let n = @sys.read(fd, buf, 0, buf.length())
    if n < 0 {
      return false
    }
    if n == 0 {
      return true
    }
    if !@sys.write_all(@sys.stdout, buf, 0, n) {
      return false
    }
  }
  true
}

///|
fn main {
  let argv = @sys.args()
  let buf : FixedArray[Byte] = FixedArray::make(4096, b'\x00')
  if argv.length() <= 1 {
    if !copy(@sys.stdin, buf) {
      @sys.exit(1)
    }
    return
  }
  let mut failed = false
  for i in 1..<argv.length() {
    let path = argv[i]
    let fd = @sys.open(path, false)
    if fd < 0 {
      ignore(@sys.write_bytes(@sys.stderr, b"cat: cannot open "))
      ignore(@sys.write_bytes(@sys.stderr, path))
      ignore(@sys.write_bytes(@sys.stderr, b"\n"))
      failed = true
      continue
    }
    if !copy(fd, buf) {
      failed = true
    }
    @sys.close(fd)
  }
  if failed {
    @sys.exit(1)
  }
}
