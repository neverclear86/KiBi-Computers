//! 共通ライブラリ `kibi`（#98）を、カーネルの上で確かめるプログラム（core のテスト `OsLibraryTest` が動かす）。
//!
//! `libtest <場面> [引数...]` の場面ごとに、結果を標準出力に書く。期待と違えば `FAIL: ...` を書いて 1 で終わる。
const kibi = @import("kibi");
const io = kibi.io;
const fs = kibi.fs;
const process = kibi.process;

var failed = false;

fn say(s: []const u8) void {
    io.out(s);
}

fn line(s: []const u8) void {
    io.out(s);
    io.out("\n");
}

fn num(v: u64) void {
    var b: [20]u8 = undefined;
    io.out(io.formatUnsigned(&b, v));
}

fn fail(what: []const u8, e: ?kibi.Error) void {
    failed = true;
    say("FAIL: ");
    say(what);
    if (e) |x| {
        say(": ");
        say(kibi.errno.name(x));
    }
    say("\n");
}

fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}

fn expect(what: []const u8, ok: bool) void {
    if (!ok) fail(what, null);
}

pub fn main() u8 {
    const argv = process.args() catch |e| {
        fail("args", e);
        return 1;
    };
    if (argv.len < 2) {
        line("usage: libtest <case>");
        return 2;
    }
    const case = argv[1];
    const rest = argv[2..];
    if (eql(case, "args")) {
        for (rest, 0..) |a, i| {
            if (i > 0) say("|");
            say(a);
        }
        say("\n");
    } else if (eql(case, "env")) {
        for (rest) |name| {
            say(name);
            say("=");
            line(process.getenv(name) orelse "(none)");
        }
    } else if (eql(case, "exit")) {
        const code = io.parseUnsigned(if (rest.len > 0) rest[0] else "") orelse 255;
        process.exit(@intCast(@min(code, 255)));
    } else if (eql(case, "path")) {
        casePath();
    } else if (eql(case, "file")) {
        caseFile(if (rest.len > 0) rest[0] else "/tmp");
    } else if (eql(case, "stat")) {
        for (rest) |path| {
            say(path);
            say(" ");
            if (fs.stat(path)) |st| line(@tagName(st.kind)) else |e| line(kibi.errno.name(e));
        }
    } else if (eql(case, "errno")) {
        _ = fs.open("/no/such/file", .{}) catch |e| {
            say(kibi.errno.name(e));
            say(" ");
            line(kibi.errno.describe(e));
            return 0;
        };
        fail("open succeeded", null);
    } else if (eql(case, "pipe")) {
        casePipe();
    } else if (eql(case, "spawn")) {
        caseSpawn(argv[0]);
    } else if (eql(case, "pid")) {
        say("self ");
        num(process.selfPid());
        say(" parent ");
        num(process.parentPid());
        say("\n");
    } else if (eql(case, "tty")) {
        caseTty();
    } else if (eql(case, "memory")) {
        const before = kibi.memory.remaining();
        expect("remaining > 0", before > 0);
        expect("commit", kibi.memory.commit(&scratch, 0));
        expect("remaining after commit", kibi.memory.remaining() <= before);
        line("memory ok");
    } else if (eql(case, "time")) {
        const t0 = kibi.time.monotonic() catch |e| return failWith("monotonic", e);
        kibi.time.sleep(2_000_000) catch |e| return failWith("sleep", e);
        const t1 = kibi.time.monotonic() catch |e| return failWith("monotonic", e);
        expect("slept", t1 >= t0 + 2_000_000);
        line("time ok");
    } else {
        fail("unknown case", null);
    }
    return if (failed) 1 else 0;
}

fn failWith(what: []const u8, e: kibi.Error) u8 {
    fail(what, e);
    return 1;
}

var scratch: [8192]u8 = undefined;

fn caseTty() void {
    const tty = kibi.tty;
    if (!tty.isTty(io.STDOUT)) {
        if (tty.getMode(io.STDOUT)) |_| fail("getMode on a non-tty", null) else |e| {
            say("not a tty ");
            line(kibi.errno.name(e));
        }
        return;
    }
    const mode = tty.getMode(io.STDOUT) catch |e| return fail("getMode", e);
    expect("cooked", @as(u8, @bitCast(mode)) == @as(u8, @bitCast(tty.Mode.cooked)));
    tty.setMode(io.STDOUT, .{ .canonical = true, .signals = true }) catch |e| return fail("setMode", e);
    const changed = tty.getMode(io.STDOUT) catch |e| return fail("getMode 2", e);
    expect("changed", changed.canonical and changed.signals and !changed.echo and !changed.nl_to_crnl);
    tty.setMode(io.STDOUT, tty.Mode.cooked) catch |e| return fail("setMode back", e);
    const size = tty.getSize(io.STDOUT) catch |e| return fail("getSize", e);
    say("size ");
    num(size.columns);
    say("x");
    num(size.rows);
    say("\n");
    const fg = tty.getForeground(io.STDOUT) catch |e| return fail("getForeground", e);
    say("foreground ");
    num(fg);
    say("\n");
    line("tty ok");
}

fn casePath() void {
    var buf: [fs.MAX_PATH]u8 = undefined;
    const cases = [_][3][]const u8{
        .{ "/", "a/b", "/a/b" },
        .{ "/x/y", "../z", "/x/z" },
        .{ "/x", "/abs//./p/", "/abs/p" },
        .{ "/", "../../..", "/" },
        .{ "/a", ".", "/a" },
        .{ "/a/b", "", "/a/b" },
    };
    for (cases) |c| {
        const got = fs.normalizeInto(&buf, c[0], c[1]) catch |e| {
            fail("normalize", e);
            continue;
        };
        if (!eql(got, c[2])) {
            fail("normalize", null);
            say("  got ");
            line(got);
        }
    }
    say("cwd ");
    line(fs.cwd());
    fs.chdir("/home") catch |e| fail("chdir /home", e);
    fs.chdir("..") catch |e| fail("chdir ..", e);
    fs.chdir("home") catch |e| fail("chdir home", e);
    say("cwd ");
    line(fs.cwd());
    if (fs.chdir("/no/such")) |_| fail("chdir to a missing dir", null) else |e| {
        say("chdir missing ");
        line(kibi.errno.name(e));
    }
    line("path ok");
}

fn caseFile(dir: []const u8) void {
    fs.chdir(dir) catch |e| return fail("chdir", e);
    fs.mkdir("sub") catch |e| return fail("mkdir", e);
    const fd = fs.open("sub/a.txt", .{ .read = true, .write = true, .create = true, .truncate = true }) catch |e| return fail("open create", e);
    io.writeAll(fd, "hello, kibi\n") catch |e| return fail("write", e);
    _ = io.seek(fd, 0, .set) catch |e| return fail("seek", e);
    var buf: [64]u8 = undefined;
    const n = io.readAll(fd, &buf) catch |e| return fail("read", e);
    expect("read back", eql(buf[0..n], "hello, kibi\n"));
    const st = fs.fstat(fd) catch |e| return fail("fstat", e);
    expect("fstat size", st.size == 12 and st.kind == .file);
    io.close(fd) catch |e| return fail("close", e);

    // 追記
    const af = fs.open("sub/a.txt", .{ .read = false, .write = true, .append = true }) catch |e| return fail("open append", e);
    io.writeAll(af, "more\n") catch |e| return fail("append", e);
    io.close(af) catch {};
    const s2 = fs.stat("sub/a.txt") catch |e| return fail("stat", e);
    expect("stat size after append", s2.size == 17);

    // 排他の作成
    if (fs.open("sub/a.txt", .{ .write = true, .create = true, .exclusive = true })) |_| fail("exclusive create", null) else |e| expect("EXIST", e == error.EXIST);

    fs.rename("sub/a.txt", "sub/b.txt") catch |e| return fail("rename", e);
    const f3 = fs.open("sub/c.txt", .{ .write = true, .create = true }) catch |e| return fail("open c", e);
    io.close(f3) catch {};

    // 一覧（小さい領域で、読み直しを通す）
    const dfd = fs.open("sub", .{ .directory = true }) catch |e| return fail("open dir", e);
    var dbuf: [40]u8 = undefined;
    var it = fs.Dir.init(dfd, &dbuf);
    var count: usize = 0;
    var seen_b = false;
    var seen_c = false;
    while (it.next() catch |e| return fail("readdir", e)) |entry| {
        count += 1;
        if (eql(entry.name, "b.txt")) seen_b = entry.kind == .file;
        if (eql(entry.name, "c.txt")) seen_c = entry.kind == .file;
    }
    io.close(dfd) catch {};
    expect("readdir entries", count == 2 and seen_b and seen_c);

    if (fs.rmdir("sub")) |_| fail("rmdir non-empty", null) else |e| expect("NOTEMPTY", e == error.NOTEMPTY);
    fs.unlink("sub/b.txt") catch |e| return fail("unlink b", e);
    fs.unlink("sub/c.txt") catch |e| return fail("unlink c", e);
    fs.rmdir("sub") catch |e| return fail("rmdir", e);
    if (fs.stat("sub")) |_| fail("stat after rmdir", null) else |e| expect("NOENT", e == error.NOENT);
    line("file ok");
}

fn casePipe() void {
    const p = io.pipe() catch |e| return fail("pipe", e);
    const copy = io.dup(p.write) catch |e| return fail("dup", e);
    io.writeAll(copy, "via dup\n") catch |e| return fail("write dup", e);
    io.close(copy) catch {};
    io.close(p.write) catch {};
    var buf: [64]u8 = undefined;
    const n = io.readAll(p.read, &buf) catch |e| return fail("read pipe", e);
    expect("pipe content", eql(buf[0..n], "via dup\n"));
    io.close(p.read) catch {};
    // dupTo で標準出力をパイプに向け、戻す
    const q = io.pipe() catch |e| return fail("pipe 2", e);
    const saved = io.dup(io.STDOUT) catch |e| return fail("dup stdout", e);
    io.dupTo(q.write, io.STDOUT) catch |e| return fail("dupTo", e);
    io.out("redirected\n");
    io.dupTo(saved, io.STDOUT) catch |e| return fail("dupTo back", e);
    io.close(saved) catch {};
    io.close(q.write) catch {};
    const m = io.readAll(q.read, &buf) catch |e| return fail("read pipe 2", e);
    expect("dupTo content", eql(buf[0..m], "redirected\n"));
    io.close(q.read) catch {};
    line("pipe ok");
}

fn caseSpawn(self_path: []const u8) void {
    // 終了コード
    const c = process.spawn("/bin/libtest", &.{ "libtest", "exit", "7" }, .{}) catch |e| return fail("spawn", e);
    expect("child pid", c.pid() != process.selfPid());
    const st = c.wait() catch |e| return fail("wait", e);
    switch (st) {
        .exited => |code| {
            say("exited ");
            num(code);
            say("\n");
        },
        else => fail("not exited", null),
    }
    c.release();
    // 子の標準出力をパイプへ、環境変数と cwd を指定
    const p = io.pipe() catch |e| return fail("pipe", e);
    // 根の preopen は既定で子に渡る
    const r = process.spawn("/bin/libtest", &.{ "libtest", "stat", "/home" }, .{}) catch |e| return fail("spawn stat", e);
    _ = r.wait() catch |e| return fail("wait stat", e);
    r.release();
    const env = [_]process.EnvEntry{.{ .name = str("GREETING"), .value = str("hi") }};
    const d = process.spawn("/bin/libtest", &.{ "libtest", "env", "GREETING", "PWD" }, .{
        .env = &env,
        .cwd = "/home",
        .fds = &.{process.FdAssign.inherit(1, p.write)},
    }) catch |e| return fail("spawn env", e);
    io.close(p.write) catch {};
    var buf: [256]u8 = undefined;
    const n = io.readAll(p.read, &buf) catch |e| return fail("read child", e);
    io.close(p.read) catch {};
    say("child said ");
    say(buf[0..n]);
    _ = d.wait() catch |e| return fail("wait env", e);
    d.release();
    // 止める
    const s = process.spawn("/bin/libtest", &.{ "libtest", "time" }, .{}) catch |e| return fail("spawn sleeper", e);
    s.kill(.terminate) catch |e| return fail("kill", e);
    const ks = s.wait() catch |e| return fail("wait killed", e);
    switch (ks) {
        .killed => |sig| {
            say("killed ");
            line(if (sig) |x| @tagName(x) else "(machine)");
        },
        .exited => line("killed (exited first)"),
        .crashed => fail("crashed", null),
    }
    s.release();
    // ないファイル
    if (process.spawn("/bin/none", &.{"none"}, .{})) |_| fail("spawn missing", null) else |e| {
        say("spawn missing ");
        line(kibi.errno.name(e));
    }
    _ = self_path;
    line("spawn ok");
}

fn str(s: []const u8) process.String {
    return .{ .ptr = s.ptr, .len = @intCast(s.len) };
}
