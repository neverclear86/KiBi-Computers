//! エディタの状態（表示と操作の両方から見る）。
const kibi = @import("kibi");

/// カーソル（テキストの中のバイトの位置）と、その行の番号（0 から）
pub var cur: usize = 0;
pub var cur_line: usize = 0;
/// 上下に動くときに目指す表示の桁。[want_end] なら行の終わり（`$` の後）
pub var want: usize = 0;
pub var want_dirty: bool = true;
pub var want_end: bool = false;

/// 画面のいちばん上に出す行の頭と、その行の番号
pub var top: usize = 0;
pub var top_line: usize = 0;
/// 次に描くとき、カーソルが画面の外ならカーソルの行を真ん中に置く（飛んだとき）
pub var center: bool = false;

/// 保存してから変えたか
pub var modified: bool = false;

/// ファイルの名前（空なら名前のないバッファ）
pub var name_buf: [kibi.fs.MAX_PATH]u8 = undefined;
pub var name_len: usize = 0;

pub fn name() []const u8 {
    return name_buf[0..name_len];
}

pub fn setName(s: []const u8) void {
    const n = @min(s.len, name_buf.len);
    @memcpy(name_buf[0..n], s[0..n]);
    name_len = n;
}

// ---- いちばん下の行の知らせ ----

pub var msg_buf: [512]u8 = undefined;
pub var msg_len: usize = 0;
/// 知らせが誤りか（反転で出す）
pub var msg_error: bool = false;

pub fn msg() []const u8 {
    return msg_buf[0..msg_len];
}

pub fn clearMsg() void {
    msg_len = 0;
    msg_error = false;
}

pub fn msgAdd(s: []const u8) void {
    const n = @min(s.len, msg_buf.len - msg_len);
    @memcpy(msg_buf[msg_len..][0..n], s[0..n]);
    msg_len += n;
}

pub fn msgNum(v: u64) void {
    var b: [20]u8 = undefined;
    msgAdd(kibi.io.formatUnsigned(&b, v));
}

pub fn setMsg(s: []const u8) void {
    clearMsg();
    msgAdd(s);
}

pub fn setError(s: []const u8) void {
    setMsg(s);
    msg_error = true;
}

// ---- モード ----

pub const Mode = enum { normal, insert, command };
pub var mode: Mode = .normal;

/// いちばん下の行の入力（mode == .command）。[cmd_prefix] は `:`・`/`・`?`
pub var cmd_prefix: u8 = ':';
pub var cmd_buf: [kibi.fs.MAX_PATH]u8 = undefined;
pub var cmd_len: usize = 0;

pub fn cmdText() []const u8 {
    return cmd_buf[0..cmd_len];
}

// ---- 設定（`:set`） ----

/// 行の番号を出す（`number`）
pub var number: bool = false;
/// 探すときに ASCII の大文字・小文字を区別しない（`ignorecase`）
pub var ignorecase: bool = false;
