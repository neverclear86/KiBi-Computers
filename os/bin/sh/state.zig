//! シェル全体の状態（#101）
/// `$?`
pub var status: u8 = 0;
/// `$$`（子のシェルでは親のシェルの pid）
pub var shell_pid: u32 = 0;
/// `$!`（最後に後ろで起こしたもの）
pub var last_bg: ?u32 = null;

/// 会話的（プロンプトを出し、誤りで終わらない）
pub var interactive = false;
/// `set -e`: 失敗したら終わる
pub var errexit = false;
/// `set -u`: 値のない変数を展開したら誤り
pub var nounset = false;
/// `set -x`: 動かすコマンドを書く
pub var xtrace = false;
/// `set -f`: パス名の展開をしない
pub var noglob = false;
/// `set -C`: `>` で、あるファイルを上書きしない
pub var noclobber = false;
/// `set -v`: 読んだ入力を書く
pub var verbose = false;

/// 条件（if・while・&&・|| の左・!）の中。`set -e` を効かせない
pub var in_condition: u32 = 0;

/// break・continue・return の途中
pub const Flow = enum { none, break_, continue_, return_ };
pub var flow: Flow = .none;
/// break・continue の残りの段数
pub var flow_levels: u32 = 0;
/// 入れ子のループの数（break の段数の上限）
pub var loop_depth: u32 = 0;
/// 動いている関数の数
pub var func_depth: u32 = 0;
/// `.` で読んでいるファイルの数（return で抜ける）
pub var dot_depth: u32 = 0;
