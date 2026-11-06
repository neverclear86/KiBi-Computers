// #32 の追加調査: shell_fast と同じプログラムを、MoonBit で書いた TLSF のアロケーター（-allocator tlsf-mbt）でリンクしたもの
//
// #32 の追加調査: shell_blit の、バイトを1つずつ見るループ（改行を探す・空白で区切る）だけを unsafe_get にしたもの。
// 範囲はループの条件（k < end ≤ 長さ）で保証している。
//
// #32 の追加調査: shell と同じ振る舞いを、標準ライブラリの普通の書き方で確保と写しを減らしたもの。
//
// - 語の範囲はタプルの Array ではなく、開始・終わりの FixedArray[Int] に入れる（語ごとの確保をなくす）
// - 1行はタプルの Option で返さず、LineReader のフィールドに置く
// - バイトの写し（行の詰め直し・語の写し）は FixedArray::blit_to にする
// - 添字は普通の `buf[i]`（範囲検査あり）のまま

///|
struct LineReader {
  buf : FixedArray[Byte]
  mut start : Int
  mut end : Int
  mut line_start : Int
  mut line_end : Int
}

///|
/// 次の1行（改行は含まない）を line_start・line_end に置く。EOF で残りがなければ false
fn LineReader::read_line(self : LineReader) -> Bool {
  while true {
    for k in self.start..<self.end {
      if self.buf.unsafe_get(k) == b'\n' {
        self.line_start = self.start
        self.line_end = k
        self.start = k + 1
        return true
      }
    }
    if self.start > 0 {
      let rest = self.end - self.start
      self.buf.blit_to(self.buf, len=rest, src_offset=self.start, dst_offset=0)
      self.start = 0
      self.end = rest
    }
    if self.end == self.buf.length() {
      // 改行のない長い行: そのまま1行として返す
      self.line_start = 0
      self.line_end = self.end
      self.start = self.end
      return true
    }
    let n = @sys.read(@sys.stdin, self.buf, self.end, self.buf.length() - self.end)
    if n < 0 {
      return false
    }
    if n == 0 {
      if self.end == self.start {
        return false
      }
      self.line_start = self.start
      self.line_end = self.end
      self.start = self.end
      return true
    }
    self.end = self.end + n
  }
  false
}

///|
/// 語の範囲（開始・終わり）。128 語まで
struct Words {
  starts : FixedArray[Int]
  ends : FixedArray[Int]
  mut count : Int
}

///|
fn is_blank(c : Byte) -> Bool {
  c == b' ' || c == b'\t'
}

///|
/// [buf] の `[from, to)` を空白で区切り、語の範囲を [words] に入れる
fn split(buf : FixedArray[Byte], from : Int, to : Int, words : Words) -> Unit {
  words.count = 0
  let mut i = from
  while i < to {
    while i < to && is_blank(buf.unsafe_get(i)) {
      i = i + 1
    }
    let s = i
    while i < to && !is_blank(buf.unsafe_get(i)) {
      i = i + 1
    }
    if i > s && words.count < words.starts.length() {
      words.starts[words.count] = s
      words.ends[words.count] = i
      words.count = words.count + 1
    }
  }
}

///|
fn main {
  let reader : LineReader = {
    buf: FixedArray::make(4096, b'\x00'),
    start: 0,
    end: 0,
    line_start: 0,
    line_end: 0,
  }
  let words : Words = {
    starts: FixedArray::make(128, 0),
    ends: FixedArray::make(128, 0),
    count: 0,
  }
  let out : FixedArray[Byte] = FixedArray::make(8192, b'\x00')
  while true {
    if !@sys.write_bytes(@sys.stdout, b"> ") {
      return
    }
    if !reader.read_line() {
      return
    }
    split(reader.buf, reader.line_start, reader.line_end, words)
    let mut n = 0
    for k in 0..<words.count {
      let s = words.starts[k]
      let len = words.ends[k] - s
      if n + len + 3 > out.length() {
        break
      }
      out[n] = b'['
      reader.buf.blit_to(out, len=len, src_offset=s, dst_offset=n + 1)
      out[n + 1 + len] = b']'
      n = n + len + 2
    }
    out[n] = b'\n'
    n = n + 1
    if !@sys.write_all(@sys.stdout, out, 0, n) {
      return
    }
  }
}
