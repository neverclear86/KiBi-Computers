// #32 の追加調査: shell と同じ（配列の添字を unsafe_get・unsafe_set にして、範囲検査を外しただけ）
//
// シェルの読み取りループ（#32）: `> ` を出して1行読み、空白で区切った語を `[語]` の形で1行に書く。EOF で終わる。
//
// Zig 側と同じ振る舞い（行は 4 KiB まで。超えた分は次の行として扱う）。語は行のバッファの中の範囲
// （開始・終わり）の配列として持つ（MoonBit の Array。ヒープに置く）。

///|
struct LineReader {
  buf : FixedArray[Byte]
  mut start : Int
  mut end : Int
}

///|
/// 次の1行（改行は含まない）の範囲。EOF で残りがなければ None
fn LineReader::read_line(self : LineReader) -> (Int, Int)? {
  while true {
    for k in self.start..<self.end {
      if self.buf.unsafe_get(k) == b'\n' {
        let line = (self.start, k)
        self.start = k + 1
        return Some(line)
      }
    }
    if self.start > 0 {
      let rest = self.end - self.start
      for i in 0..<rest {
        self.buf.unsafe_set(i, self.buf.unsafe_get(self.start + i))
      }
      self.start = 0
      self.end = rest
    }
    if self.end == self.buf.length() {
      // 改行のない長い行: そのまま1行として返す
      self.start = self.end
      return Some((0, self.end))
    }
    let n = @sys.read(@sys.stdin, self.buf, self.end, self.buf.length() - self.end)
    if n < 0 {
      return None
    }
    if n == 0 {
      if self.end == self.start {
        return None
      }
      let line = (self.start, self.end)
      self.start = self.end
      return Some(line)
    }
    self.end = self.end + n
  }
  None
}

///|
fn is_blank(c : Byte) -> Bool {
  c == b' ' || c == b'\t'
}

///|
/// [buf] の `[from, to)` を空白で区切り、語の範囲を [words] に入れる（128 語まで）
fn split(buf : FixedArray[Byte], from : Int, to : Int, words : Array[(Int, Int)]) -> Unit {
  words.clear()
  let mut i = from
  while i < to {
    while i < to && is_blank(buf.unsafe_get(i)) {
      i = i + 1
    }
    let s = i
    while i < to && !is_blank(buf.unsafe_get(i)) {
      i = i + 1
    }
    if i > s && words.length() < 128 {
      words.push((s, i))
    }
  }
}

///|
fn main {
  let reader : LineReader = { buf: FixedArray::make(4096, b'\x00'), start: 0, end: 0 }
  let words : Array[(Int, Int)] = []
  let out : FixedArray[Byte] = FixedArray::make(8192, b'\x00')
  while true {
    if !@sys.write_bytes(@sys.stdout, b"> ") {
      return
    }
    guard reader.read_line() is Some((from, to)) else { return }
    split(reader.buf, from, to, words)
    let mut n = 0
    for w in words {
      let (s, e) = w
      let len = e - s
      if n + len + 3 > out.length() {
        break
      }
      out.unsafe_set(n, b'[')
      for j in 0..<len {
        out.unsafe_set(n + 1 + j, reader.buf.unsafe_get(s + j))
      }
      out.unsafe_set(n + 1 + len, b']')
      n = n + len + 2
    }
    out.unsafe_set(n, b'\n')
    n = n + 1
    if !@sys.write_all(@sys.stdout, out, 0, n) {
      return
    }
  }
}
