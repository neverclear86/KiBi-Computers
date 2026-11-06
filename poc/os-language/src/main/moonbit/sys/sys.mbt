// WASI p1 の生の import と、プログラムが共有する小さな道具（#32）。
//
// Zig 側（../../zig/sys.zig）と同じ形にそろえるため、import を整数の引数でそのまま宣言する。
// バッファは MoonBit の FixedArray[Byte] に置き、その中身の線形メモリのアドレスを WASI に渡す（写さない）。

///|
#unsafe_skip_stub_check
fn wasi_fd_write(fd : Int, iovs : Int, n : Int, written : Int) -> Int = "wasi_snapshot_preview1" "fd_write"

///|
#unsafe_skip_stub_check
fn wasi_fd_read(fd : Int, iovs : Int, n : Int, nread : Int) -> Int = "wasi_snapshot_preview1" "fd_read"

///|
#unsafe_skip_stub_check
fn wasi_fd_readdir(
  fd : Int,
  buf : Int,
  len : Int,
  cookie : Int64,
  used : Int,
) -> Int = "wasi_snapshot_preview1" "fd_readdir"

///|
#unsafe_skip_stub_check
fn wasi_fd_close(fd : Int) -> Int = "wasi_snapshot_preview1" "fd_close"

///|
#unsafe_skip_stub_check
fn wasi_path_open(
  dirfd : Int,
  dirflags : Int,
  path : Int,
  path_len : Int,
  oflags : Int,
  base : Int64,
  inheriting : Int64,
  fdflags : Int,
  fd_out : Int,
) -> Int = "wasi_snapshot_preview1" "path_open"

///|
#unsafe_skip_stub_check
fn wasi_args_sizes_get(argc : Int, size : Int) -> Int = "wasi_snapshot_preview1" "args_sizes_get"

///|
#unsafe_skip_stub_check
fn wasi_args_get(argv : Int, buf : Int) -> Int = "wasi_snapshot_preview1" "args_get"

///|
#unsafe_skip_stub_check
fn wasi_proc_exit(code : Int) = "wasi_snapshot_preview1" "proc_exit"

///|
/// 独自拡張（#408）。`[ptr, ptr+len)` を今のうちに RAM として数える。数えた後の残りが keep 未満になるなら数えずに 0
#unsafe_skip_stub_check
fn kibi_memory_commit(ptr : Int, len : Int, keep : Int64) -> Int = "kibi:computer/memory@0.1.0" "commit"

///|
/// 独自拡張（#408）。残りの RAM（バイト）
#unsafe_skip_stub_check
fn kibi_memory_remaining() -> Int64 = "kibi:computer/memory@0.1.0" "remaining"

///|
/// FixedArray[Byte] を渡すと、線形メモリのアドレス（i32）になる。そのまま返す
#borrow(b)
extern "wasm" fn raw_address(b : FixedArray[Byte]) -> Int =
  #|(func (param i32) (result i32) (local.get 0))

///|
#borrow(b)
extern "wasm" fn raw_bytes_address(b : Bytes) -> Int =
  #|(func (param i32) (result i32) (local.get 0))

///|
extern "wasm" fn load_i32(addr : Int) -> Int =
  #|(func (param i32) (result i32) (i32.load (local.get 0)))

///|
extern "wasm" fn load_u8(addr : Int) -> Int =
  #|(func (param i32) (result i32) (i32.load8_u (local.get 0)))

///|
extern "wasm" fn store_i32(addr : Int, value : Int) -> Unit =
  #|(func (param i32) (param i32) (i32.store (local.get 0) (local.get 1)))

///|
/// iovec（8 バイト）と、WASI が書く結果（4 バイトずつ）を置く場所
let scratch : FixedArray[Byte] = FixedArray::make(32, b'\x00')

///|
/// 渡した参照から中身の先頭までの差（バイト）。印を書いて、線形メモリから探す（参照が中身の先頭を指すなら 0）
let payload_offset : Int = find_payload_offset()

///|
fn find_payload_offset() -> Int {
  let probe : FixedArray[Byte] = [b'\xA5', b'\x5A', b'\xC3', b'\x3C', b'\x96', b'\x69', b'\x0F', b'\xF0']
  let base = raw_address(probe)
  for k in 0..<64 {
    let mut same = true
    for i in 0..<8 {
      if load_u8(base + k + i) != probe[i].to_int() {
        same = false
        break
      }
    }
    if same {
      return k
    }
  }
  // 見つからない: 線形メモリの配置が想定と違う
  wasi_proc_exit(70)
  0
}

///|
/// [b] の中身の [off] バイト目の線形メモリのアドレス
pub fn address(b : FixedArray[Byte], off : Int) -> Int {
  raw_address(b) + payload_offset + off
}

///|
fn bytes_address(b : Bytes, off : Int) -> Int {
  raw_bytes_address(b) + payload_offset + off
}

///|
pub let stdin : Int = 0

///|
pub let stdout : Int = 1

///|
pub let stderr : Int = 2

///|
/// カーネルが fd 3 に置く根 `/` の preopen（`SpawnOptions.ROOT_PREOPEN`）
pub let root : Int = 3

///|
fn write_all_at(fd : Int, addr : Int, len : Int) -> Bool {
  let iov = address(scratch, 0)
  let out = address(scratch, 8)
  let mut p = addr
  let mut rest = len
  while rest > 0 {
    store_i32(iov, p)
    store_i32(iov + 4, rest)
    store_i32(out, 0)
    if wasi_fd_write(fd, iov, 1, out) != 0 {
      return false
    }
    let n = load_i32(out)
    if n <= 0 {
      return false
    }
    p = p + n
    rest = rest - n
  }
  true
}

///|
/// [buf] の `[off, off+len)` を全部書く。失敗したら false
pub fn write_all(fd : Int, buf : FixedArray[Byte], off : Int, len : Int) -> Bool {
  write_all_at(fd, address(buf, off), len)
}

///|
/// [b] を全部書く。失敗したら false
pub fn write_bytes(fd : Int, b : Bytes) -> Bool {
  write_all_at(fd, bytes_address(b, 0), b.length())
}

///|
/// [b] の `[off, off+len)` を全部書く。失敗したら false
pub fn write_bytes_range(fd : Int, b : Bytes, off : Int, len : Int) -> Bool {
  write_all_at(fd, bytes_address(b, off), len)
}

///|
/// [buf] の [off] から最大 [len] バイトを1回読む。EOF なら 0、失敗なら -1
pub fn read(fd : Int, buf : FixedArray[Byte], off : Int, len : Int) -> Int {
  let iov = address(scratch, 0)
  let out = address(scratch, 8)
  store_i32(iov, address(buf, off))
  store_i32(iov + 4, len)
  store_i32(out, 0)
  if wasi_fd_read(fd, iov, 1, out) != 0 {
    return -1
  }
  load_i32(out)
}

///|
/// 引数（argv）。取れなければ空
pub fn args() -> Array[Bytes] {
  let argc_at = address(scratch, 16)
  let size_at = address(scratch, 20)
  if wasi_args_sizes_get(argc_at, size_at) != 0 {
    return []
  }
  let argc = load_i32(argc_at)
  let size = load_i32(size_at)
  let ptrs : FixedArray[Byte] = FixedArray::make(argc * 4 + 4, b'\x00')
  let buf : FixedArray[Byte] = FixedArray::make(size + 1, b'\x00')
  if wasi_args_get(address(ptrs, 0), address(buf, 0)) != 0 {
    return []
  }
  let base = address(buf, 0)
  let result : Array[Bytes] = []
  for i in 0..<argc {
    let start = load_i32(address(ptrs, i * 4)) - base
    let mut end = start
    while end < size && buf[end] != b'\x00' {
      end = end + 1
    }
    result.push(Bytes::makei(end - start, j => buf[start + j]))
  }
  result
}

///|
/// rights_t のビット: fd_read
let right_fd_read : Int64 = 2L

///|
/// rights_t のビット: fd_readdir
let right_fd_readdir : Int64 = 16384L

///|
/// 根からの [path] を開く（先頭の `/` は落とす。空なら `.`）。失敗なら -1
pub fn open(path : Bytes, directory : Bool) -> Int {
  let mut start = 0
  while start < path.length() && path[start] == b'/' {
    start = start + 1
  }
  let fd_at = address(scratch, 24)
  store_i32(fd_at, -1)
  let rights = if directory { right_fd_readdir } else { right_fd_read }
  let oflags = if directory { 2 } else { 0 }
  let err = if start == path.length() {
    let dot : Bytes = b"."
    wasi_path_open(root, 1, bytes_address(dot, 0), 1, oflags, rights, 0L, 0, fd_at)
  } else {
    wasi_path_open(root, 1, bytes_address(path, start), path.length() - start, oflags, rights, 0L, 0, fd_at)
  }
  if err != 0 {
    return -1
  }
  load_i32(fd_at)
}

///|
/// ディレクトリの項目を [buf] に読む（fd_readdir）。書いたバイト数、失敗なら -1
pub fn readdir(fd : Int, buf : FixedArray[Byte], cookie : Int64) -> Int {
  let used_at = address(scratch, 8)
  store_i32(used_at, 0)
  if wasi_fd_readdir(fd, address(buf, 0), buf.length(), cookie, used_at) != 0 {
    return -1
  }
  load_i32(used_at)
}

///|
pub fn close(fd : Int) -> Unit {
  ignore(wasi_fd_close(fd))
}

///|
/// プロセスを [code] で終える
pub fn exit(code : Int) -> Unit {
  wasi_proc_exit(code)
}

///|
/// [buf] の `[off, off+len)` を今のうちに RAM として数える（`kibi:computer/memory@0.1.0` の `commit`）。数えたら true
pub fn memory_commit(buf : FixedArray[Byte], off : Int, len : Int, keep : Int64) -> Bool {
  kibi_memory_commit(address(buf, off), len, keep) == 1
}

///|
/// 残りの RAM（バイト。`kibi:computer/memory@0.1.0` の `remaining`）
pub fn memory_remaining() -> Int64 {
  kibi_memory_remaining()
}
