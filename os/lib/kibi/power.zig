//! 独自拡張 `kibi:computer/power`（#306。ABI は docs/adr/0306-kibi-power.md）の包み（#454）。
//!
//! どちらも、どのプロセスからでも呼べる。受け付けたら呼んだプロセスもその場で止まるので戻らない
//! （まとめて書く出力は、呼ぶ前に書き出しておく）。コンソールに出すメッセージはカーネルが出さないので、出すならコマンドが出す。
const io = @import("io.zig");
const errno = @import("errno.zig");
const process = @import("process.zig");

pub const Error = errno.Error;

const raw = struct {
    // 戻らない import だが、`noreturn` の宣言には頼らない（万一戻っても、下の包みが終わらせる）
    extern "kibi:computer/power@0.1.0" fn shutdown() void;
    extern "kibi:computer/power@0.1.0" fn reboot(ret: *io.ResultUnit) void;
};

/// マシンを止める（必ず受け付ける）
pub fn shutdown() noreturn {
    raw.shutdown();
    process.exit(1);
}

/// マシンを再起動する。受け付けたら戻らない。戻るのは断られたとき（`NOTSUP`。起動を通らずに作ったマシン・サーバーを閉じる途中）
pub fn reboot() Error {
    var r: io.ResultUnit = undefined;
    r.is_err = 0;
    raw.reboot(&r);
    if (r.is_err != 0) return io.kibiError(r.err);
    return error.NOTSUP;
}
