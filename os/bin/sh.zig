//! `/bin/sh`（#101）: 標準 OS のシェル（POSIX sh 風）。
//!
//! ```
//! sh [-eux...] [-c コマンド [名前 [引数...]]]
//! sh [-eux...] スクリプト [引数...]
//! sh [-eux...] [-s] [引数...]         # 標準入力から読む（TTY なら会話的）
//! ```
//!
//! 構文・展開・実行の決めごとは docs/adr/0101-shell.md。ソースは `os/bin/sh/` に分けている:
//! `parse.zig`（字句・構文）、`expand.zig`（展開）、`exec.zig`（実行）、`builtins.zig`（組み込み）、
//! `vars.zig`（変数・関数）、`mem.zig`（領域）、`edit.zig`・`complete.zig`・`history.zig`（行の編集・補完・履歴。#102）。
const kibi = @import("kibi");
const util = @import("sh/util.zig");
const mem = @import("sh/mem.zig");
const vars = @import("sh/vars.zig");
const state = @import("sh/state.zig");
const exec = @import("sh/exec.zig");
const builtins = @import("sh/builtins.zig");
const history = @import("sh/history.zig");

const io = kibi.io;
const fs = kibi.fs;
const process = kibi.process;
const eql = util.eql;

/// 入力を読む領域（1 つのコマンドの大きさの上限）
var input_buf: [64 * 1024]u8 = undefined;
var self_buf: [fs.MAX_PATH]u8 = undefined;

pub fn main() u8 {
    const argv = process.args() catch |e| {
        util.complain(&.{ "cannot read the arguments: ", kibi.errno.describe(e) });
        return 2;
    };
    state.shell_pid = process.selfPid();
    importEnviron() catch {
        util.complain(&.{"out of memory"});
        return 2;
    };

    var i: usize = 1;
    var command: ?[]const u8 = null;
    var use_stdin = false;
    var force_interactive = false;
    var sub_state: ?[]const u8 = null;
    while (i < argv.len) : (i += 1) {
        const a = argv[i];
        if (util.startsWith(a, "--kibi-sub=")) {
            sub_state = a["--kibi-sub=".len..];
            continue;
        }
        if (util.startsWith(a, "--kibi-prelude=")) {
            // サブシェル: 親の変数・関数・オプション
            exec.runText(a["--kibi-prelude=".len..], "", 1) catch |e| exec.handleTopError(e);
            continue;
        }
        if (eql(a, "--") or eql(a, "-")) {
            i += 1;
            break;
        }
        if (a.len < 2 or (a[0] != '-' and a[0] != '+')) break;
        const on = a[0] == '-';
        for (a[1..]) |c| {
            switch (c) {
                'c' => command = "",
                's' => use_stdin = true,
                'i' => force_interactive = true,
                else => if (!builtins.setOption(c, on)) {
                    util.complain(&.{ a, ": unknown option" });
                    return 2;
                },
            }
        }
    }

    if (sub_state) |v| subshellState(v);
    if (argv.len > 0) self_path(argv[0]);

    if (command != null) {
        if (i >= argv.len) {
            util.complain(&.{"-c: option requires an argument"});
            return 2;
        }
        command = argv[i];
        i += 1;
        if (i < argv.len) {
            vars.arg0 = argv[i];
            i += 1;
        }
        setParams(argv[i..]);
        exec.runText(command.?, "", 1) catch |e| exec.handleTopError(e);
        return state.status;
    }

    if (!use_stdin and i < argv.len) {
        const script = argv[i];
        vars.arg0 = script;
        setParams(argv[i + 1 ..]);
        const fd = fs.open(script, .{}) catch |e| {
            util.complain(&.{ script, ": ", kibi.errno.describe(e) });
            return if (e == error.NOENT) 127 else 126;
        };
        var r = exec.Reader{ .fd = fd, .buf = &input_buf, .prompt = false, .name = script };
        r.runAll() catch |e| exec.handleTopError(e);
        return state.status;
    }

    if (argv.len > 0) vars.arg0 = argv[0];
    setParams(argv[i..]);
    state.interactive = force_interactive or (kibi.tty.isTty(io.STDIN) and kibi.tty.isTty(io.STDERR));
    if (state.interactive) {
        // Ctrl+C・Ctrl+\ は前景の子に届く。シェル自身は止まらない
        process.setSignalIgnored(.interrupt, true) catch {};
        process.setSignalIgnored(.quit, true) catch {};
        // 前景がなければ（init を通さずに起こされた）自分が持つ
        const fg = kibi.tty.getForeground(io.STDIN) catch 0;
        if (fg == 0) kibi.tty.setForeground(io.STDIN, process.selfPid()) catch {};
    }
    // 行の編集・補完・履歴（#102）は、標準入力と標準エラー出力が TTY のときだけ
    const edit = state.interactive and kibi.tty.isTty(io.STDIN) and kibi.tty.isTty(io.STDERR);
    if (edit) history.load();
    var r = exec.Reader{ .fd = io.STDIN, .buf = &input_buf, .prompt = state.interactive, .edit = edit, .name = "" };
    r.runAll() catch |e| exec.handleTopError(e);
    if (state.interactive) io.err("\n");
    return state.status;
}

/// 環境変数を、export された変数にする。`PWD` は今の cwd にそろえる
fn importEnviron() util.Error!void {
    const env = process.environ() catch &[_][]const u8{};
    for (env) |entry| {
        const eq = util.indexOf(entry, '=') orelse continue;
        const name = entry[0..eq];
        if (!util.isName(name)) continue;
        try vars.set(name, entry[eq + 1 ..]);
        try vars.setExported(name, true);
    }
    try vars.set("PWD", fs.cwd());
    try vars.setExported("PWD", true);
}

fn setParams(list: []const []const u8) void {
    vars.setParams(list) catch {
        util.complain(&.{"out of memory"});
        process.exit(2);
    };
}

/// サブシェルに使う、このシェルのパス
fn self_path(arg0: []const u8) void {
    if (util.indexOf(arg0, '/') != null) {
        if (fs.absolute(&self_buf, arg0)) |p| exec.self_path = p else |_| {}
        return;
    }
    if (exec.findProgram(arg0, &self_buf)) |p| exec.self_path = p;
}

/// `--kibi-sub=<$?>,<$$>,<$!>`（サブシェルとして起こされた）
fn subshellState(v: []const u8) void {
    var parts: [3][]const u8 = .{ "", "", "" };
    var k: usize = 0;
    var start: usize = 0;
    for (v, 0..) |c, j| {
        if (c == ',' and k < 2) {
            parts[k] = v[start..j];
            k += 1;
            start = j + 1;
        }
    }
    parts[k] = v[start..];
    if (io.parseUnsigned(parts[0])) |s| state.status = @truncate(s);
    if (io.parseUnsigned(parts[1])) |p| state.shell_pid = @truncate(p);
    if (io.parseUnsigned(parts[2])) |p| state.last_bg = @truncate(p);
}
