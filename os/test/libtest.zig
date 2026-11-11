//! 共通ライブラリ `kibi`（#98）を、カーネルの上で確かめるプログラム（core のテスト `StandardOsTest` が動かす）。
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

/// [n] 段再帰して、どの段でもスタックの残りが [above] より少ないか
noinline fn deeper(n: u32, above: usize) bool {
    // volatile で書いて、配列をスタックに置かせる（レジスターに溶かさせない）
    var pad: [256]u8 = undefined;
    const vp: *volatile [256]u8 = &pad;
    vp[0] = @truncate(n);
    vp[255] = @truncate(n);
    const here = kibi.stack.remaining();
    if (here == 0 or here >= above) return false;
    if (n == 0) return vp[0] == 0;
    return deeper(n - 1, here) and vp[255] == @as(u8, @truncate(n));
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
        caseSpawn();
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
    } else if (eql(case, "widths")) {
        // 幅が 2・0 の範囲を書く（core の `CharWidth` と比べる。#105）
        var cp: u32 = 0;
        var run_start: u32 = 0;
        var run_width: u2 = 1;
        while (cp <= 0x110000) : (cp += 1) {
            const w: u2 = if (cp < 0x110000) kibi.text.width(@intCast(cp)) else 1;
            if (w != run_width) {
                if (run_width != 1) {
                    say(if (run_width == 2) "wide " else "zero ");
                    num(run_start);
                    say(" ");
                    num(cp - 1);
                    say("\n");
                }
                run_start = cp;
                run_width = w;
            }
        }
    } else if (eql(case, "utf8")) {
        // 厳しい読み方: 引数の 16 進のバイト列ごとに、読めた文字（なければ -）
        for (rest) |hex| {
            var b: [8]u8 = undefined;
            var n: usize = 0;
            var i: usize = 0;
            while (i + 1 < hex.len and n < b.len) : (i += 2) {
                const hi = hexDigit(hex[i]);
                const lo = hexDigit(hex[i + 1]);
                b[n] = @intCast(hi * 16 + lo);
                n += 1;
            }
            if (kibi.text.decode(b[0..n])) |c| num(c) else say("-");
            say(" ");
        }
        say("\n");
    } else if (eql(case, "stack")) {
        // 再帰するほど残りが減り、0 にはならない（#514）
        const top = kibi.stack.remaining();
        expect("remaining > 0", top > 0);
        expect("deeper has less", deeper(8, top));
        line("stack ok");
    } else if (eql(case, "block")) {
        // 標準入力が閉じるまで読み続ける（kill の場面で、先に終わらない子にする）
        var b: [64]u8 = undefined;
        while (true) {
            const n = io.read(io.STDIN, &b) catch break;
            if (n == 0) break;
        }
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
    expect("readdir entries", count == 2 and seen_b and seen_c);
    // 項目の頭より小さい領域はエラーになる（読み直しを繰り返さない）
    var tiny: [8]u8 = undefined;
    var small = fs.Dir.init(dfd, &tiny);
    if (small.next()) |_| fail("readdir with a tiny buffer", null) else |e| expect("tiny buffer INVAL", e == error.INVAL);
    io.close(dfd) catch {};

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

fn caseSpawn() void {
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
    // 止める。子は標準入力（書き手をこちらが持つパイプ）を待ち続けるので、kill より先には終わらない
    const hold = io.pipe() catch |e| return fail("pipe hold", e);
    const s = process.spawn("/bin/libtest", &.{ "libtest", "block" }, .{
        .fds = &.{process.FdAssign.inherit(0, hold.read)},
    }) catch |e| return fail("spawn blocker", e);
    io.close(hold.read) catch {};
    s.kill(.terminate) catch |e| return fail("kill", e);
    const ks = s.wait() catch |e| return fail("wait killed", e);
    io.close(hold.write) catch {};
    switch (ks) {
        .killed => |sig| {
            say("killed ");
            line(if (sig) |x| @tagName(x) else "(machine)");
        },
        .exited => fail("exited before kill", null),
        .crashed => fail("crashed", null),
    }
    s.release();
    // ないファイル
    if (process.spawn("/bin/none", &.{"none"}, .{})) |_| fail("spawn missing", null) else |e| {
        say("spawn missing ");
        line(kibi.errno.name(e));
    }
    line("spawn ok");
}

fn str(s: []const u8) process.String {
    return .{ .ptr = s.ptr, .len = @intCast(s.len) };
}

fn hexDigit(c: u8) u64 {
    return switch (c) {
        '0'...'9' => c - '0',
        'a'...'f' => c - 'a' + 10,
        'A'...'F' => c - 'A' + 10,
        else => 0,
    };
}
