//! `/sbin/init`（#100）: 標準 OS の最初のプロセス（pid 1）。
//!
//! 1. `/etc/inittab` を読む（なければ既定。書式は下と docs/adr/0100-sbin-init.md）
//! 2. `run` の行を書いた順に、1つずつ終わるまで待って動かす（起動時の準備）
//! 3. `daemon`・`respawn` の行を後ろで起こす（標準入力は渡さない。出力はコンソール）
//! 4. コンソールの TTY を初期化して（cooked に戻す）、`shell` の行のシェルをログインなしで前景に起こす
//! 5. 子の終わりを待ち続ける。`shell`・`respawn` は終わったら起こし直す（1 秒未満で終わったら 1 秒おく）
//!
//! inittab の書式（1 行 1 項目。`#` で始まる行と空行は飛ばす。語は空白・タブで区切り、引用符は使えない）:
//!
//! ```
//! env PATH=/bin:/sbin       # 子に渡す環境変数を足す・置き換える
//! run /sbin/setup arg...    # 起動時に、終わるまで待って動かす
//! daemon /sbin/foo arg...   # 後ろで 1 回起こす
//! respawn /sbin/bar arg...  # 後ろで起こし、終わったら起こし直す
//! shell /bin/sh arg...      # コンソールのシェル（1 つだけ）。終わったら起こし直す
//! ```
//!
//! inittab がない・読めないときは `shell /bin/sh` だけの既定で動く。inittab があって `shell` がなければ、シェルを起こさない。
//! 動かす子も起こし直す予定もなくなったら、init は終わる（マシンが止まる）。
const kibi = @import("kibi");
const io = kibi.io;
const fs = kibi.fs;
const process = kibi.process;
const tty = kibi.tty;
const wasi = kibi.wasi;

/// 設定ファイルのパス
const INITTAB = "/etc/inittab";
/// 既定のシェル
const DEFAULT_SHELL = "/bin/sh";
/// 設定ファイルの大きさの上限（バイト）
const MAX_CONF = 8 * 1024;
/// 項目（run・daemon・respawn・shell）の数の上限
const MAX_ENTRIES = 32;
/// 全部の項目の語の数の上限
const MAX_WORDS = 512;
/// 環境変数の数の上限
const MAX_ENV = 32;
/// これより短い間に終わった子は、起こし直す前に [RESPAWN_DELAY_NS] おく（すぐ落ちる子で回り続けないため）
const MIN_UPTIME_NS: u64 = 1_000_000_000;
const RESPAWN_DELAY_NS: u64 = 1_000_000_000;

const Kind = enum { run, daemon, respawn, shell };

const Entry = struct {
    kind: Kind,
    /// argv（`argv[0]` は実行ファイルのパス）
    argv: []const []const u8,
    child: ?process.Child = null,
    /// 起こした時刻（単調な時計）
    started_ns: u64 = 0,
    /// 起こし直す時刻。null なら予定なし
    restart_at: ?u64 = null,
};

var conf_buf: [MAX_CONF]u8 = undefined;
var words: [MAX_WORDS][]const u8 = undefined;
var words_len: usize = 0;
var entries: [MAX_ENTRIES]Entry = undefined;
var entries_len: usize = 0;
var env: [MAX_ENV]process.EnvEntry = undefined;
var env_len: usize = 0;

const default_shell_argv = [_][]const u8{DEFAULT_SHELL};

pub fn main() u8 {
    _ = setEnv("PATH", "/bin:/sbin");
    _ = setEnv("HOME", "/");
    // コンソールの TTY は再起動をまたいで残るので、前の起動のモードを戻す
    resetTty();
    loadConfig();

    for (entries[0..entries_len]) |*e| {
        if (e.kind == .run) runOnce(e);
    }
    const now = monotonic();
    for (entries[0..entries_len]) |*e| {
        if (e.kind != .run) start(e, now);
    }
    return supervise();
}

// ---- 設定 ----

fn loadConfig() void {
    const n = readConfig() catch |e| {
        if (e != error.NOENT) {
            say3("init: ", INITTAB, ": ");
            say2(kibi.errno.name(e), "; using the default\n");
        }
        useDefault();
        return;
    };
    parse(conf_buf[0..n]);
}

fn readConfig() kibi.Error!usize {
    const fd = try fs.open(INITTAB, .{});
    defer io.close(fd) catch {};
    const n = try io.readAll(fd, &conf_buf);
    if (n == conf_buf.len) {
        // 上限ちょうどで切れたか、もっと続くか
        var one: [1]u8 = undefined;
        if ((try io.read(fd, &one)) != 0) return error.FBIG;
    }
    return n;
}

fn useDefault() void {
    entries_len = 0;
    addEntry(.shell, &default_shell_argv);
}

fn parse(text: []const u8) void {
    var line_no: usize = 0;
    var have_shell = false;
    var rest = text;
    while (rest.len > 0) {
        line_no += 1;
        const end = indexOf(rest, '\n') orelse rest.len;
        const line = rest[0..end];
        rest = if (end < rest.len) rest[end + 1 ..] else rest[rest.len..];

        const start_words = words_len;
        var it = Words{ .s = line };
        const first = it.next() orelse continue;
        if (first[0] == '#') continue;
        while (it.next()) |w| {
            if (words_len == MAX_WORDS) {
                warn(line_no, "too many words");
                words_len = start_words;
                break;
            }
            words[words_len] = w;
            words_len += 1;
        }
        const args = words[start_words..words_len];

        if (eql(first, "env")) {
            words_len = start_words;
            if (args.len != 1) {
                warn(line_no, "env takes one NAME=VALUE");
                continue;
            }
            const eq = indexOf(args[0], '=') orelse {
                warn(line_no, "env takes one NAME=VALUE");
                continue;
            };
            if (eq == 0) {
                warn(line_no, "env takes one NAME=VALUE");
                continue;
            }
            if (!setEnv(args[0][0..eq], args[0][eq + 1 ..])) warn(line_no, "too many env entries");
            continue;
        }
        const kind: Kind = if (eql(first, "run"))
            .run
        else if (eql(first, "daemon"))
            .daemon
        else if (eql(first, "respawn"))
            .respawn
        else if (eql(first, "shell"))
            .shell
        else {
            words_len = start_words;
            warn(line_no, "unknown entry");
            continue;
        };
        if (args.len == 0) {
            warn(line_no, "missing program");
            continue;
        }
        if (args.len > process.MAX_SPAWN_ARGS) {
            words_len = start_words;
            warn(line_no, "too many arguments");
            continue;
        }
        if (kind == .shell) {
            if (have_shell) {
                words_len = start_words;
                warn(line_no, "only one shell is allowed");
                continue;
            }
            have_shell = true;
        }
        if (entries_len == MAX_ENTRIES) {
            words_len = start_words;
            warn(line_no, "too many entries");
            continue;
        }
        addEntry(kind, args);
    }
}

fn addEntry(kind: Kind, argv: []const []const u8) void {
    entries[entries_len] = .{ .kind = kind, .argv = argv };
    entries_len += 1;
}

/// 環境変数を足す・置き換える。入りきらなければ false
fn setEnv(name: []const u8, value: []const u8) bool {
    for (env[0..env_len]) |*e| {
        if (eql(e.name.ptr[0..e.name.len], name)) {
            e.value = str(value);
            return true;
        }
    }
    if (env_len == MAX_ENV) return false;
    env[env_len] = .{ .name = str(name), .value = str(value) };
    env_len += 1;
    return true;
}

fn str(s: []const u8) process.String {
    return .{ .ptr = s.ptr, .len = @intCast(s.len) };
}

const Words = struct {
    s: []const u8,
    i: usize = 0,

    fn next(self: *Words) ?[]const u8 {
        while (self.i < self.s.len and isSpace(self.s[self.i])) self.i += 1;
        if (self.i == self.s.len) return null;
        const b = self.i;
        while (self.i < self.s.len and !isSpace(self.s[self.i])) self.i += 1;
        return self.s[b..self.i];
    }
};

fn isSpace(c: u8) bool {
    return c == ' ' or c == '\t' or c == '\r';
}

// ---- 子 ----

fn spawn(e: *Entry, background: bool) ?process.Child {
    const closed_stdin = [_]process.FdAssign{process.FdAssign.closed(0)};
    const fds: []const process.FdAssign = if (background) &closed_stdin else &.{};
    const child = process.spawn(e.argv[0], e.argv, .{
        .env = env[0..env_len],
        .cwd = "/",
        .fds = fds,
    }) catch |err| {
        say3("init: cannot start ", e.argv[0], ": ");
        say2(kibi.errno.name(err), "\n");
        return null;
    };
    return child;
}

/// コンソールの TTY を初期化する（前の子が raw のまま終わっても、次の子は cooked で始まる）
fn resetTty() void {
    if (!tty.isTty(io.STDIN)) return;
    tty.setMode(io.STDIN, tty.Mode.cooked) catch {};
    tty.setForeground(io.STDIN, 0) catch {};
}

fn setForeground(pid: u32) void {
    if (tty.isTty(io.STDIN)) tty.setForeground(io.STDIN, pid) catch {};
}

/// `run` の項目を前景で動かし、終わるまで待つ
fn runOnce(e: *Entry) void {
    resetTty();
    const child = spawn(e, false) orelse return;
    setForeground(child.pid());
    const status = child.wait() catch |err| {
        say3("init: cannot wait for ", e.argv[0], ": ");
        say2(kibi.errno.name(err), "\n");
        child.release();
        resetTty();
        return;
    };
    child.release();
    resetTty();
    report(e, status);
}

/// `daemon`・`respawn`・`shell` の項目を起こす
fn start(e: *Entry, now: u64) void {
    e.restart_at = null;
    if (e.kind == .shell) resetTty();
    const child = spawn(e, e.kind != .shell) orelse return;
    e.child = child;
    e.started_ns = now;
    if (e.kind == .shell) setForeground(child.pid());
}

/// 終わり方が普通（コード 0）でなければコンソールに書く
fn report(e: *const Entry, status: process.ExitStatus) void {
    var b: [20]u8 = undefined;
    switch (status) {
        .exited => |code| {
            if (code == 0) return;
            say3("init: ", e.argv[0], " exited with code ");
            say2(io.formatUnsigned(&b, code), "\n");
        },
        .killed => |sig| {
            // null はマシンの停止・再起動（言うことはない）
            const s = sig orelse return;
            say3("init: ", e.argv[0], " was killed by ");
            say2(@tagName(s), "\n");
        },
        .crashed => say3("init: ", e.argv[0], " crashed\n"),
    }
}

/// 子を待ち、起こし直し続ける。動かす子も予定もなくなったら終わる
fn supervise() u8 {
    var subs: [MAX_ENTRIES + 1]wasi.Subscription = undefined;
    var events: [MAX_ENTRIES + 1]wasi.Event = undefined;
    while (true) {
        const now = monotonic();
        for (entries[0..entries_len]) |*e| {
            if (e.restart_at) |at| if (at <= now) start(e, now);
        }

        var n: usize = 0;
        var next_restart: ?u64 = null;
        for (entries[0..entries_len], 0..) |*e, i| {
            if (e.child) |c| {
                subs[n] = .{ .userdata = i, .tag = wasi.EVENTTYPE_FD_READ, .u = .{ .fd = .{ .fd = c.handle } } };
                n += 1;
            } else if (e.restart_at) |at| {
                next_restart = if (next_restart) |r| @min(r, at) else at;
            }
        }
        if (next_restart) |at| {
            const timeout = if (at > now) at - now else 0;
            subs[n] = .{
                .userdata = MAX_ENTRIES,
                .tag = wasi.EVENTTYPE_CLOCK,
                .u = .{ .clock = .{ .id = wasi.CLOCK_MONOTONIC, .timeout = timeout, .precision = 0, .flags = 0 } },
            };
            n += 1;
        }
        if (n == 0) {
            say2("init: nothing left to run", "\n");
            return 0;
        }

        var got: wasi.Size = 0;
        kibi.errno.check(wasi.poll_oneoff(&subs, &events, @intCast(n), &got)) catch |err| {
            say2("init: poll failed: ", kibi.errno.name(err));
            say2("\n", "");
            return 1;
        };

        const after = monotonic();
        for (entries[0..entries_len]) |*e| reap(e, after);
    }
}

/// [e] の子が終わっていれば受け取り、起こし直す予定を立てる
fn reap(e: *Entry, now: u64) void {
    const c = e.child orelse return;
    const status = (c.status() catch |err| {
        say3("init: cannot wait for ", e.argv[0], ": ");
        say2(kibi.errno.name(err), "\n");
        c.release();
        e.child = null;
        return;
    }) orelse return;
    c.release();
    e.child = null;
    if (e.kind == .shell) resetTty();
    report(e, status);
    // マシンの停止・再起動で止められたときは起こし直さない
    if (status == .killed and status.killed == null) return;
    if (e.kind == .shell or e.kind == .respawn) {
        e.restart_at = if (now -| e.started_ns < MIN_UPTIME_NS) now + RESPAWN_DELAY_NS else now;
    }
}

// ---- 小物 ----

fn monotonic() u64 {
    return kibi.time.monotonic() catch 0;
}

fn warn(line_no: usize, what: []const u8) void {
    var b: [20]u8 = undefined;
    say3("init: ", INITTAB, ":");
    say3(io.formatUnsigned(&b, line_no), ": ", what);
    say2("\n", "");
}

fn say2(a: []const u8, b: []const u8) void {
    io.err(a);
    io.err(b);
}

fn say3(a: []const u8, b: []const u8, c: []const u8) void {
    io.err(a);
    io.err(b);
    io.err(c);
}

fn indexOf(s: []const u8, c: u8) ?usize {
    for (s, 0..) |x, i| if (x == c) return i;
    return null;
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}
