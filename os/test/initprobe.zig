//! `/sbin/init`（#100）を確かめるための子（core のテスト `InitTest` が `/etc/inittab` に書いて動かす）。
//!
//! `initprobe <名前> [終了コード] [raw] [forever]`: 自分の様子を 1 行で標準出力に書く（引数がなければ名前は argv[0] の最後の部分）。
//! `<名前> fg=<self|other> stdin=<tty|none> mode=<cooked|other> FOO=<値> HOME=<値> args=<引数の数> cwd=<cwd>`
//! - `raw`: 終わる前にコンソールを raw にする（init が次の子の前に cooked に戻すかを見る）
//! - `forever`: 書いた後、終わらずに眠り続ける
const kibi = @import("kibi");
const io = kibi.io;
const tty = kibi.tty;
const process = kibi.process;

pub fn main() u8 {
    const argv = process.args() catch return 100;
    if (argv.len == 0) return 101;
    // 名前がなければ（`/bin/sh` として動かしたとき）、argv[0] の最後の部分
    const name = if (argv.len >= 2) argv[1] else basename(argv[0]);
    const opts = if (argv.len >= 2) argv[2..] else argv[argv.len..];
    var code: u8 = 0;
    var raw = false;
    var forever = false;
    for (opts) |a| {
        if (eql(a, "raw")) {
            raw = true;
        } else if (eql(a, "forever")) {
            forever = true;
        } else if (io.parseUnsigned(a)) |v| {
            code = @intCast(v & 0xff);
        }
    }

    // init は子を作ってから前景にするので、標準入力が TTY なら前景になるまで少し（最大 1 秒）待つ
    var fg = tty.getForeground(io.STDOUT) catch 0;
    if (tty.isTty(io.STDIN)) {
        var tries: u32 = 0;
        while (fg != process.selfPid() and tries < 100) : (tries += 1) {
            kibi.time.sleep(10_000_000) catch {};
            fg = tty.getForeground(io.STDOUT) catch 0;
        }
    }

    io.out(name);
    // 前景かは、標準出力（コンソール）の TTY で見る（後ろの子は標準入力を持たない）
    io.out(if (fg == process.selfPid()) " fg=self" else " fg=other");
    io.out(if (tty.isTty(io.STDIN)) " stdin=tty" else " stdin=none");
    const mode = tty.getMode(io.STDOUT) catch tty.Mode.raw;
    io.out(if (@as(u8, @bitCast(mode)) == @as(u8, @bitCast(tty.Mode.cooked))) " mode=cooked" else " mode=other");
    io.out(" FOO=");
    io.out(process.getenv("FOO") orelse "(none)");
    io.out(" HOME=");
    io.out(process.getenv("HOME") orelse "(none)");
    var b: [20]u8 = undefined;
    io.out(" args=");
    io.out(io.formatUnsigned(&b, argv.len));
    io.out(" cwd=");
    io.out(kibi.fs.cwd());
    io.out("\n");

    if (raw) tty.setMode(io.STDOUT, tty.Mode.raw) catch {};
    if (forever) {
        while (true) kibi.time.sleep(60_000_000_000) catch {};
    }
    return code;
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}

fn basename(path: []const u8) []const u8 {
    var i = path.len;
    while (i > 0 and path[i - 1] != '/') i -= 1;
    return path[i..];
}
