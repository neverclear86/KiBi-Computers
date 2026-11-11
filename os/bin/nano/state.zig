//! エディタの状態（表示と操作の両方から見る）。
const kibi = @import("kibi");

/// カーソル（テキストの中のバイトの位置）と、その行の番号（0 から）
pub var cur: usize = 0;
pub var cur_line: usize = 0;
/// 上下に動くときに目指す表示の桁。[want_dirty] なら次に使う前に今の桁から求め直す
pub var want: usize = 0;
pub var want_dirty: bool = true;

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

// ---- 状態の行の知らせ ----

pub var msg_buf: [512]u8 = undefined;
pub var msg_len: usize = 0;

pub fn msg() []const u8 {
    return msg_buf[0..msg_len];
}

pub fn clearMsg() void {
    msg_len = 0;
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
    msg_len = 0;
    msgAdd(s);
}

// ---- 画面の使い方 ----

pub const Mode = enum { edit, prompt, help };
pub var mode: Mode = .edit;

/// 入力欄（mode == .prompt）
pub var prompt_label: []const u8 = "";
pub var prompt_buf: [kibi.fs.MAX_PATH]u8 = undefined;
pub var prompt_len: usize = 0;

pub fn promptText() []const u8 {
    return prompt_buf[0..prompt_len];
}

/// 下に出すキーの案内
pub const Shortcut = struct { key: []const u8, label: []const u8 };
pub var shortcuts: []const Shortcut = &.{};

/// ヘルプの画面で、いちばん上に出す行
pub var help_top: usize = 0;
