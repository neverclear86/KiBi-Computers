// #32 の追加調査: ls と同じ（dirent の整数を読む u32_at だけを unsafe_get にしたもの）
//
// `ls`: ディレクトリ（引数。なければ `/`）の名前を1行ずつ書く（#32）。`.`・`..` は書かない。並びは fd_readdir の順

///|
/// dirent: next(u64) ino(u64) namlen(u32) type(u8) と 3 バイトの詰め物 = 24 バイト
let dirent_size : Int = 24

///|
fn u32_at(b : FixedArray[Byte], i : Int) -> Int {
  b.unsafe_get(i).to_int() |
  (b.unsafe_get(i + 1).to_int() << 8) |
  (b.unsafe_get(i + 2).to_int() << 16) |
  (b.unsafe_get(i + 3).to_int() << 24)
}

///|
fn u64_at(b : FixedArray[Byte], i : Int) -> Int64 {
  (u32_at(b, i).to_int64() & 0xFFFFFFFFL) | (u32_at(b, i + 4).to_int64() << 32)
}

///|
fn main {
  let argv = @sys.args()
  let path : Bytes = if argv.length() > 1 { argv[1] } else { b"/" }
  let fd = @sys.open(path, true)
  if fd < 0 {
    ignore(@sys.write_bytes(@sys.stderr, b"ls: cannot open "))
    ignore(@sys.write_bytes(@sys.stderr, path))
    ignore(@sys.write_bytes(@sys.stderr, b"\n"))
    @sys.exit(1)
    return
  }
  let buf : FixedArray[Byte] = FixedArray::make(4096, b'\x00')
  let mut cookie = 0L
  while true {
    let used = @sys.readdir(fd, buf, cookie)
    if used < 0 {
      @sys.exit(1)
      return
    }
    let mut i = 0
    let mut progressed = false
    while i + dirent_size <= used {
      let namlen = u32_at(buf, i + 16)
      // 名前が切れていたら、この項目の cookie から読み直す
      if i + dirent_size + namlen > used {
        break
      }
      let name = i + dirent_size
      cookie = u64_at(buf, i)
      progressed = true
      let dot = namlen == 1 && buf[name] == b'.'
      let dotdot = namlen == 2 && buf[name] == b'.' && buf[name + 1] == b'.'
      if !dot && !dotdot {
        ignore(@sys.write_all(@sys.stdout, buf, name, namlen))
        ignore(@sys.write_bytes(@sys.stdout, b"\n"))
      }
      i = i + dirent_size + namlen
    }
    // バッファが埋まらなければ終わり
    if used < buf.length() || !progressed {
      break
    }
  }
  @sys.close(fd)
}
