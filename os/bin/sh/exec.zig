//! コマンドの実行（#101）。
//!
//! fork がないので、シェルの中で動かすもの（組み込み・関数・複合コマンド）と、子として起こすもの（外のプログラム・
//! サブシェル `( )`・パイプラインの各段・後ろで動かすもの・コマンド置換）を分ける。
//! 子のシェル（サブシェル）は、同じ `sh` を `--kibi-sub=...` と `-c <文字列>` で起こし、変数・関数・オプションを
//! 文字列の頭（`prelude`）に書いて渡す（export していない変数も見えるように）。
const kibi = @import("kibi");
const util = @import("util.zig");
const mem = @import("mem.zig");
const parse = @import("parse.zig");
const expand = @import("expand.zig");
const vars = @import("vars.zig");
const state = @import("state.zig");
const builtins = @import("builtins.zig");
const edit = @import("edit.zig");

const io = kibi.io;
const fs = kibi.fs;
const process = kibi.process;
const tty = kibi.tty;
const wasi = kibi.wasi;
const Error = util.Error;
const eql = util.eql;
const Node = parse.Node;

/// パイプの大きさ（カーネルの既定。ADR 0059）。シェルの中で動かすもののヒアドキュメントは、これより大きくできない
const PIPE_CAPACITY = 4096;
/// 子を待つ間に書くもの（ヒアドキュメント・シェルの中で動かした段の出力）の数の上限。超えたら、その場で全部書く
const MAX_PENDING = 64;
/// 関数の呼び出しの深さの上限
const MAX_FUNC_DEPTH = 100;
/// 子として起こせる段の数の上限（パイプライン）
const MAX_STAGES = 32;

/// このシェルの実行ファイル（サブシェルに使う）
pub var self_path: []const u8 = "/bin/sh";

/// 直前のコマンド置換の終了コード（代入だけのコマンドの `$?`）
var subst_status: ?u8 = null;

/// `exec 4>file` などで開いた fd（4〜9。子に引き継ぐ）
var user_fds: u16 = 0;

// ---- 出力（組み込みが使う。コマンド置換の中では、シェルの中の領域に集める） ----

pub const Buffer = struct {
    data: []u8 = &[_]u8{},
    len: usize = 0,

    pub fn append(self: *Buffer, s: []const u8) Error!void {
        if (self.len + s.len > self.data.len) {
            var cap = @max(256, self.data.len * 2);
            while (cap < self.len + s.len) cap *= 2;
            const n = try mem.heapAlloc(cap);
            @memcpy(n[0..self.len], self.data[0..self.len]);
            mem.heapFree(self.data);
            self.data = n;
        }
        @memcpy(self.data[self.len..][0..s.len], s);
        self.len += s.len;
    }

    /// 中身（heapFree で返す）
    pub fn take(self: *Buffer) []u8 {
        if (self.len == 0) {
            mem.heapFree(self.data);
            return &[_]u8{};
        }
        return self.data[0..self.len];
    }
};

/// コマンド置換を、シェルの中で集めているところ
pub var capture_buf: ?*Buffer = null;

/// 組み込みの標準出力
pub fn out(s: []const u8) Error!bool {
    if (capture_buf) |b| {
        try b.append(s);
        return true;
    }
    io.writeAll(io.STDOUT, s) catch return false;
    return true;
}

// ---- 終了コード ----

fn statusOf(s: process.ExitStatus) u8 {
    return switch (s) {
        .exited => |c| @truncate(c),
        .killed => |sig| if (sig) |x| switch (x) {
            .interrupt => 130,
            .quit => 131,
            .terminate => 143,
            .kill => 137,
        } else 137,
        .crashed => 139,
    };
}

fn checkErrexit() void {
    if (state.errexit and state.in_condition == 0 and state.status != 0) process.exit(state.status);
}

// ---- 入り口 ----

pub fn run(node: *Node) Error!void {
    switch (node.kind) {
        .simple => {
            try runSimple(node);
            checkErrexit();
        },
        .pipeline => |p| {
            if (p.count == 1) {
                state.in_condition += 1;
                defer state.in_condition -= 1;
                try run(p.first);
                state.status = @intFromBool(state.status == 0);
            } else {
                try runPipeline(p.first, p.count);
                if (p.bang) state.status = @intFromBool(state.status == 0) else checkErrexit();
            }
        },
        .and_or => |a| {
            {
                state.in_condition += 1;
                defer state.in_condition -= 1;
                try run(a.left);
            }
            if (state.flow != .none) return;
            if ((state.status == 0) == a.is_and) try run(a.right);
        },
        .list => |l| {
            var it = l.first;
            while (it) |item| : (it = item.next) {
                if (item.background) {
                    try runBackground(item.node);
                } else {
                    try run(item.node);
                }
                if (state.flow != .none) return;
            }
        },
        .func => |f| {
            const m = mem.mark();
            defer mem.release(m);
            const text = try parse.nodeText(f.body, &mem.ast);
            try vars.defineFunc(f.name, text);
            state.status = 0;
        },
        .subshell => {
            const m = mem.mark();
            defer mem.release(m);
            const pm = pendingMark();
            const child = (try startNode(node, .{})) orelse {
                finishPending(pm);
                return;
            };
            var list = [_]?process.Child{child};
            state.status = waitChildren(&list, pm);
            checkErrexit();
        },
        else => try runCompound(node),
    }
}

fn runCompound(node: *Node) Error!void {
    var saves = Saves{};
    if (!(try applyRedirs(node.redirs, &saves, true))) {
        restoreRedirs(&saves);
        state.status = 1;
        return;
    }
    defer restoreRedirs(&saves);
    switch (node.kind) {
        .group => |body| try run(body),
        .if_ => |i| {
            {
                state.in_condition += 1;
                defer state.in_condition -= 1;
                try run(i.cond);
            }
            if (state.flow != .none) return;
            if (state.status == 0) {
                try run(i.then);
            } else if (i.else_) |e| {
                try run(e);
            } else state.status = 0;
        },
        .loop => |l| {
            state.loop_depth += 1;
            defer state.loop_depth -= 1;
            var last: u8 = 0;
            while (true) {
                {
                    state.in_condition += 1;
                    defer state.in_condition -= 1;
                    try run(l.cond);
                }
                if (loopFlow()) |brk| {
                    if (brk) break else continue;
                }
                const ok = (state.status == 0) != l.until;
                if (!ok) break;
                try run(l.body);
                last = state.status;
                if (loopFlow()) |brk| {
                    if (brk) break else continue;
                }
            }
            state.status = last;
        },
        .for_ => |f| {
            const m = mem.mark();
            defer mem.release(m);
            var values = mem.List([]const u8).init(&mem.ast);
            if (f.has_in) {
                try expand.fields(f.words, &values);
            } else {
                for (vars.params) |p| try values.append(try mem.str.dupe(p));
            }
            state.loop_depth += 1;
            defer state.loop_depth -= 1;
            var last: u8 = 0;
            for (values.items) |v| {
                try vars.set(f.name, v);
                try run(f.body);
                last = state.status;
                if (loopFlow()) |brk| {
                    if (brk) break else continue;
                }
            }
            state.status = last;
        },
        .case_ => |c| {
            const m = mem.mark();
            defer mem.release(m);
            const word = try expand.string(c.word);
            var it = c.items;
            state.status = 0;
            outer: while (it) |item| : (it = item.next) {
                var p: ?*parse.Word = item.patterns;
                while (p) |pw| : (p = pw.next) {
                    const pat = try expand.pattern(pw.text);
                    if (expand.match(pat, word)) {
                        if (item.body) |b| try run(b);
                        break :outer;
                    }
                }
            }
        },
        else => unreachable,
    }
}

/// ループの中の break・continue。抜けるなら true、次へなら false、何もなければ null
fn loopFlow() ?bool {
    switch (state.flow) {
        .none => return null,
        .return_ => return true,
        .break_ => {
            if (state.flow_levels > 1) {
                state.flow_levels -= 1;
            } else state.flow = .none;
            return true;
        },
        .continue_ => {
            if (state.flow_levels > 1) {
                state.flow_levels -= 1;
                // 外のループで続ける
                return true;
            }
            state.flow = .none;
            return false;
        },
    }
}

// ---- 単純なコマンド ----

fn runSimple(node: *Node) Error!void {
    const s = node.kind.simple;
    const m = mem.mark();
    defer mem.release(m);
    subst_status = null;
    var argv = mem.List([]const u8).init(&mem.ast);
    try expand.fields(s.words, &argv);

    if (argv.items.len == 0) {
        // 代入とリダイレクトだけ
        var a = s.assigns;
        while (a) |w| : (a = w.next) {
            const eq = util.indexOf(w.text, '=').?;
            const value = try expand.assignValue(w.text[eq + 1 ..]);
            if (state.xtrace) trace(&.{ w.text[0..eq], "=", value });
            try vars.set(w.text[0..eq], value);
        }
        var saves = Saves{};
        const ok = try applyRedirs(node.redirs, &saves, true);
        restoreRedirs(&saves);
        state.status = if (!ok) 1 else subst_status orelse 0;
        return;
    }
    if (state.xtrace) trace(argv.items);
    try runArgv(node, argv.items, s.assigns, false);
}

/// 展開した [argv] を動かす（[node] のリダイレクトと [assigns] の代入を使う）
fn runArgv(node: *Node, argv: []const []const u8, assigns: ?*parse.Word, no_functions: bool) Error!void {
    const name = argv[0];
    const kind = builtins.kindOf(name);
    if (kind == .special) {
        // 特別な組み込みの代入は残る
        var a = assigns;
        while (a) |w| : (a = w.next) {
            const eq = util.indexOf(w.text, '=').?;
            try vars.set(w.text[0..eq], try expand.assignValue(w.text[eq + 1 ..]));
        }
        if (eql(name, "exec")) return execBuiltin(node, argv);
        return runInProcess(node, argv, null);
    }
    if (!no_functions) {
        if (vars.findFunc(name)) |f| return callFunction(node, f, argv, assigns);
    }
    if (kind == .regular) {
        if (eql(name, "command")) return commandBuiltin(node, argv, assigns);
        return runInProcess(node, argv, assigns);
    }
    // 外のプログラム
    var map = FdMap{};
    defer map.cleanup();
    if (!(try map.addRedirs(node.redirs))) {
        state.status = 1;
        return;
    }
    const env = try tempEnv(assigns);
    const pm = pendingMark();
    var list = [_]?process.Child{spawnProgram(argv, env, &map)};
    map.afterSpawn(list[0] != null);
    map.cleanup();
    if (list[0] == null) return;
    state.status = waitChildren(&list, pm);
}

/// `NAME=v cmd` の代入を、子の環境変数にする
fn tempEnv(assigns: ?*parse.Word) Error![]process.EnvEntry {
    var extra = mem.List(process.EnvEntry).init(&mem.ast);
    var a = assigns;
    while (a) |w| : (a = w.next) {
        const eq = util.indexOf(w.text, '=').?;
        const value = try expand.assignValue(w.text[eq + 1 ..]);
        try extra.append(.{ .name = vars.str(w.text[0..eq]), .value = vars.str(value) });
    }
    return vars.environ(&mem.ast, extra.items);
}

/// 組み込みをシェルの中で動かす（[assigns] は終わるまでの一時の代入）
fn runInProcess(node: *Node, argv: []const []const u8, assigns: ?*parse.Word) Error!void {
    const frame = vars.savedMark();
    // 一時の代入だけを戻す（`local` が覚えたものは、関数が終わるまで残す）
    defer if (assigns != null) vars.restoreLocals(frame);
    try tempAssign(assigns, frame);
    var saves = Saves{};
    if (!(try applyRedirs(node.redirs, &saves, true))) {
        restoreRedirs(&saves);
        state.status = 1;
        return;
    }
    defer restoreRedirs(&saves);
    state.status = try builtins.run(argv);
}

fn tempAssign(assigns: ?*parse.Word, frame: usize) Error!void {
    var a = assigns;
    while (a) |w| : (a = w.next) {
        const eq = util.indexOf(w.text, '=').?;
        const name = w.text[0..eq];
        const value = try expand.assignValue(w.text[eq + 1 ..]);
        try vars.makeLocal(name, frame);
        try vars.set(name, value);
        try vars.setExported(name, true);
    }
}

/// 関数の中で `local` が覚える位置
pub var local_frame: usize = 0;

fn callFunction(node: *Node, f: *vars.Func, argv: []const []const u8, assigns: ?*parse.Word) Error!void {
    if (state.func_depth >= MAX_FUNC_DEPTH) {
        util.complain(&.{ argv[0], ": too deeply nested function calls" });
        return error.Abort;
    }
    f.busy += 1;
    defer vars.releaseFunc(f);
    const frame = vars.savedMark();
    defer vars.restoreLocals(frame);
    try tempAssign(assigns, frame);
    var saves = Saves{};
    if (!(try applyRedirs(node.redirs, &saves, true))) {
        restoreRedirs(&saves);
        state.status = 1;
        return;
    }
    defer restoreRedirs(&saves);

    const old_params = vars.params;
    try vars.setParams(argv[1..]);
    defer {
        vars.freeParams(vars.params);
        vars.params = old_params;
    }
    const old_frame = local_frame;
    local_frame = vars.savedMark();
    defer local_frame = old_frame;
    state.func_depth += 1;
    defer state.func_depth -= 1;

    const saved_loops = state.loop_depth;
    state.loop_depth = 0;
    defer state.loop_depth = saved_loops;

    state.status = 0;
    try runText(f.text, "", 1);
    if (state.flow == .return_) state.flow = .none;
    // 関数の外の break・continue には効かせない
    if (state.flow != .none) state.flow = .none;
}

/// `exec [cmd...]`
fn execBuiltin(node: *Node, argv: []const []const u8) Error!void {
    if (argv.len == 1) {
        // リダイレクトをこのシェルに残す
        var saves = Saves{};
        const ok = try applyRedirs(node.redirs, &saves, false);
        state.status = if (ok) 0 else 1;
        return;
    }
    // 置き換えられないので、動かしてその終了コードで終わる
    try runArgv(node, argv[1..], null, true);
    process.exit(state.status);
}

/// `command [-v|-V] name args...`
fn commandBuiltin(node: *Node, argv: []const []const u8, assigns: ?*parse.Word) Error!void {
    if (argv.len >= 2 and (eql(argv[1], "-v") or eql(argv[1], "-V"))) {
        return runInProcess(node, argv, assigns);
    }
    if (argv.len == 1) {
        state.status = 0;
        return;
    }
    return runArgv(node, argv[1..], assigns, true);
}

fn trace(parts: []const []const u8) void {
    io.err("+");
    for (parts) |p| {
        io.err(" ");
        io.err(p);
    }
    io.err("\n");
}

// ---- コマンドを探す ----

/// 外のプログラムのパス。見つからなければ null。[buf] に書く
pub fn findProgram(name: []const u8, buf: []u8) ?[]const u8 {
    if (util.indexOf(name, '/') != null) {
        return fs.absolute(buf, name) catch null;
    }
    const path = vars.get("PATH") orelse "/bin:/sbin";
    var rest = path;
    while (true) {
        const end = util.indexOf(rest, ':') orelse rest.len;
        const dir = if (end == 0) "." else rest[0..end];
        if (dir.len + 1 + name.len < buf.len) {
            var tmp: [fs.MAX_PATH]u8 = undefined;
            @memcpy(tmp[0..dir.len], dir);
            tmp[dir.len] = '/';
            @memcpy(tmp[dir.len + 1 ..][0..name.len], name);
            const joined = tmp[0 .. dir.len + 1 + name.len];
            if (fs.stat(joined)) |st| {
                if (st.kind == .file) {
                    return fs.absolute(buf, joined) catch null;
                }
            } else |_| {}
        }
        if (end == rest.len) break;
        rest = rest[end + 1 ..];
    }
    return null;
}

// ---- 子を起こす ----

/// 子の fd の割り当て（0〜9）
const Src = union(enum) { inherit, fd: io.Fd, closed };

const FdMap = struct {
    src: [10]Src = @splat(.inherit),
    /// 起こした後に閉じる fd
    temps: [24]io.Fd = undefined,
    ntemps: usize = 0,
    /// 起こした後に書くヒアドキュメント（書く端と中身）
    hd_fd: [8]io.Fd = undefined,
    hd_body: [8][]const u8 = undefined,
    nhd: usize = 0,

    fn temp(self: *FdMap, fd: io.Fd) void {
        if (self.ntemps < self.temps.len) {
            self.temps[self.ntemps] = fd;
            self.ntemps += 1;
        }
    }

    /// 親の側で、子の fd [n] の元になる fd
    fn parentFd(self: *FdMap, n: u32) ?io.Fd {
        if (n >= 10) return n;
        return switch (self.src[n]) {
            .inherit => n,
            .fd => |f| f,
            .closed => null,
        };
    }

    fn addRedirs(self: *FdMap, first: ?*parse.Redir) Error!bool {
        var r = first;
        while (r) |x| : (r = x.next) {
            const n = x.fd orelse defaultFd(x.op);
            if (!checkFd(n)) return false;
            switch (x.op) {
                .dup_in, .dup_out => {
                    const w = try expand.string(x.target);
                    if (eql(w, "-")) {
                        self.src[n] = .closed;
                        continue;
                    }
                    const v = io.parseUnsigned(w) orelse {
                        util.complain(&.{ w, ": bad file descriptor" });
                        return false;
                    };
                    if (v > 9 or !checkFd(@intCast(v))) {
                        if (v > 9) util.complain(&.{ w, ": bad file descriptor" });
                        return false;
                    }
                    const pf = self.parentFd(@intCast(v)) orelse {
                        util.complain(&.{ w, ": bad file descriptor" });
                        return false;
                    };
                    self.src[n] = .{ .fd = pf };
                },
                .heredoc, .heredoc_strip => {
                    const body = try heredocBody(x);
                    const p = io.pipe() catch |e| {
                        util.complain(&.{ "cannot make a pipe: ", kibi.errno.describe(e) });
                        return false;
                    };
                    self.temp(p.read);
                    if (self.nhd == self.hd_fd.len) {
                        util.complain(&.{"too many here-documents"});
                        io.close(p.write) catch {};
                        return false;
                    }
                    self.hd_fd[self.nhd] = p.write;
                    self.hd_body[self.nhd] = body;
                    self.nhd += 1;
                    self.src[n] = .{ .fd = p.read };
                },
                else => {
                    const f = (try openTarget(x)) orelse return false;
                    self.temp(f);
                    self.src[n] = .{ .fd = f };
                },
            }
        }
        return true;
    }

    /// kibi の spawn に渡す割り当て
    fn assigns(self: *FdMap, buf: []process.FdAssign) []process.FdAssign {
        var k: usize = 0;
        for (self.src, 0..) |s, i| {
            const n: u32 = @intCast(i);
            switch (s) {
                .inherit => {
                    if (n >= 4 and (user_fds >> @intCast(n)) & 1 != 0) {
                        buf[k] = process.FdAssign.inherit(n, n);
                        k += 1;
                    }
                },
                .fd => |f| {
                    buf[k] = process.FdAssign.inherit(n, f);
                    k += 1;
                },
                .closed => {
                    buf[k] = process.FdAssign.closed(n);
                    k += 1;
                },
            }
        }
        return buf[0..k];
    }

    /// 起こした後: ヒアドキュメントを、子を待つ間に書くものとして積む（[waitChildren] が少しずつ書く）。
    /// 起こせなかったら、書く端を閉じるだけ
    fn afterSpawn(self: *FdMap, spawned: bool) void {
        for (self.hd_fd[0..self.nhd], self.hd_body[0..self.nhd]) |fd, body| {
            if (spawned) {
                queueWrite(fd, body);
            } else io.close(fd) catch {};
        }
        self.nhd = 0;
    }

    fn cleanup(self: *FdMap) void {
        self.afterSpawn(false);
        for (self.temps[0..self.ntemps]) |fd| io.close(fd) catch {};
        self.ntemps = 0;
    }
};

fn defaultFd(op: parse.RedirOp) u32 {
    return switch (op) {
        .in, .rw, .dup_in, .heredoc, .heredoc_strip => 0,
        else => 1,
    };
}

/// ユーザーが使える fd か（3 は根の preopen なので使えない）
fn checkFd(n: u32) bool {
    if (n == 3) {
        util.complain(&.{"3: file descriptor 3 is reserved for the root directory"});
        return false;
    }
    if (n > 9) {
        var b: [12]u8 = undefined;
        util.complain(&.{ io.formatUnsigned(&b, n), ": bad file descriptor" });
        return false;
    }
    return true;
}

/// ファイルへのリダイレクトの先を開く。開けなければ知らせて null
fn openTarget(r: *parse.Redir) Error!?io.Fd {
    const path = try expand.string(r.target);
    const flags: fs.OpenFlags = switch (r.op) {
        .in => .{},
        .out => .{ .read = false, .write = true, .create = true, .truncate = true, .exclusive = state.noclobber },
        .clobber => .{ .read = false, .write = true, .create = true, .truncate = true },
        .append => .{ .read = false, .write = true, .create = true, .append = true },
        .rw => .{ .read = true, .write = true, .create = true },
        else => unreachable,
    };
    const fd = fs.open(path, flags) catch |e| {
        if (e == error.EXIST and r.op == .out) {
            util.complain(&.{ path, ": cannot overwrite existing file" });
        } else {
            util.complain(&.{ path, ": ", kibi.errno.describe(e) });
        }
        return null;
    };
    return fd;
}

/// ヒアドキュメントの中身（`<<-` は行頭のタブを落とし、区切りが引用されていなければ展開する）
fn heredocBody(r: *parse.Redir) Error![]const u8 {
    var body = r.body;
    if (r.op == .heredoc_strip) {
        var o = mem.List(u8).init(&mem.str);
        var at_line = true;
        for (body) |c| {
            if (at_line and c == '\t') continue;
            at_line = c == '\n';
            try o.append(c);
        }
        body = o.items;
    }
    if (r.quoted) return body;
    return expand.heredoc(body);
}

/// 外のプログラムを起こす。起こせなければ知らせて `$?` を入れ、null
fn spawnProgram(argv: []const []const u8, env: []const process.EnvEntry, map: *FdMap) ?process.Child {
    // 起こせないという知らせは、そのコマンドの標準エラー出力へ（`cmd 2>/dev/null` で黙る）
    const saved_err = util.err_fd;
    util.err_fd = map.parentFd(2);
    defer util.err_fd = saved_err;
    var buf: [fs.MAX_PATH]u8 = undefined;
    const path = findProgram(argv[0], &buf) orelse {
        util.complain(&.{ argv[0], ": not found" });
        state.status = 127;
        return null;
    };
    var fa: [16]process.FdAssign = undefined;
    const fds = map.assigns(&fa);
    const child = process.spawn(path, argv, .{ .env = env, .fds = fds }) catch |e| {
        if (e == error.NOEXEC) return spawnScript(path, argv, env, fds);
        util.complain(&.{ argv[0], ": ", kibi.errno.describe(e) });
        state.status = if (e == error.NOENT) 127 else 126;
        return null;
    };
    return child;
}

/// WASM でないファイル: `#!` の行があればそのプログラムで、なければシェルのスクリプトとして動かす
fn spawnScript(path: []const u8, argv: []const []const u8, env: []const process.EnvEntry, fds: []const process.FdAssign) ?process.Child {
    var head: [256]u8 = undefined;
    var n: usize = 0;
    if (fs.open(path, .{})) |fd| {
        n = io.readAll(fd, &head) catch 0;
        io.close(fd) catch {};
    } else |_| {}
    var interp: []const u8 = self_path;
    var interp_arg: ?[]const u8 = null;
    if (n >= 2 and head[0] == '#' and head[1] == '!') {
        const line_end = util.indexOf(head[2..n], '\n') orelse n - 2;
        const line = util.trim(head[2 .. 2 + line_end]);
        var j: usize = 0;
        while (j < line.len and line[j] != ' ' and line[j] != '\t') j += 1;
        if (j > 0) {
            interp = line[0..j];
            const rest = util.trim(line[j..]);
            if (rest.len > 0) interp_arg = rest;
        }
    }
    var list = mem.List([]const u8).init(&mem.ast);
    list.append(interp) catch return null;
    if (interp_arg) |a| list.append(a) catch return null;
    list.append(path) catch return null;
    if (argv.len > 1) list.appendSlice(argv[1..]) catch return null;
    const child = process.spawn(interp, list.items, .{ .env = env, .fds = fds }) catch |e| {
        util.complain(&.{ argv[0], ": ", interp, ": ", kibi.errno.describe(e) });
        state.status = if (e == error.NOENT) 127 else 126;
        return null;
    };
    return child;
}

/// パイプライン・後ろ・コマンド置換の段の標準入出力
const Base = struct {
    stdin: Src = .inherit,
    stdout: Src = .inherit,
    /// 外のプログラムも `( )` も、node ごとサブシェルに渡す（ヒアドキュメントを子のシェルに書かせる。後ろで動かすもの）
    whole: bool = false,
};

/// [node] を子として起こす。単純なコマンドで外のプログラムならそのまま、ほかはサブシェルで
fn startNode(node: *Node, base: Base) Error!?process.Child {
    const m = mem.mark();
    defer mem.release(m);
    if (node.kind == .simple) {
        const s = node.kind.simple;
        var argv = mem.List([]const u8).init(&mem.ast);
        // 語がない・組み込み・関数はサブシェルで（語の展開は子でもう一度行う）
        const first = s.words;
        if (!base.whole and first != null and isPlainWord(first.?.text)) {
            const name = first.?.text;
            if (builtins.kindOf(name) == .none and vars.findFunc(name) == null) {
                try expand.fields(s.words, &argv);
                if (argv.items.len > 0) {
                    if (state.xtrace) trace(argv.items);
                    var map = FdMap{ .src = baseSrc(base) };
                    defer map.cleanup();
                    if (!(try map.addRedirs(node.redirs))) {
                        state.status = 1;
                        return null;
                    }
                    const env = try tempEnv(s.assigns);
                    const child = spawnProgram(argv.items, env, &map);
                    map.afterSpawn(child != null);
                    return child;
                }
            }
        }
    }
    var map = FdMap{ .src = baseSrc(base) };
    defer map.cleanup();
    var target = node;
    if (node.kind == .subshell and !base.whole) {
        // `( ... )` は中身を子に渡し、リダイレクトはここで行う（括弧ごと渡すと、子がまたサブシェルを起こす）
        if (!(try map.addRedirs(node.redirs))) {
            state.status = 1;
            return null;
        }
        target = node.kind.subshell;
    }
    const text = try parse.nodeText(target, &mem.ast);
    const child = startSubshell(text, &map);
    map.afterSpawn(child != null);
    return child;
}

fn baseSrc(base: Base) [10]Src {
    var s: [10]Src = @splat(.inherit);
    s[0] = base.stdin;
    s[1] = base.stdout;
    return s;
}

/// 展開の効かない語か（引用符・`$`・パターンを含まない）
fn isPlainWord(w: []const u8) bool {
    for (w) |c| switch (c) {
        '\'', '"', '\\', '$', '`', '*', '?', '[', '~', '=' => return false,
        else => {},
    };
    return w.len > 0;
}

/// サブシェルを起こす（[text] は子が読むコマンド）
fn startSubshell(text: []const u8, map: *FdMap) ?process.Child {
    const m = mem.mark();
    defer mem.release(m);
    const pre = prelude() catch {
        util.complain(&.{"out of memory"});
        state.status = 2;
        return null;
    };
    var opt_buf: [80]u8 = undefined;
    const opt = subOption(&opt_buf);
    var argv = mem.List([]const u8).init(&mem.ast);
    // 頭（変数・関数）は `--kibi-prelude` で別に渡す（頭の代入で `$?` が変わらないよう、子は頭の後で `$?` を入れる）
    const pre_opt = mem.str.concat("--kibi-prelude=", pre) catch return null;
    argv.appendSlice(&.{ "sh", opt, pre_opt, "-c", text, vars.arg0 }) catch return null;
    argv.appendSlice(vars.params) catch return null;
    const env = vars.environ(&mem.ast, &.{}) catch return null;
    var fa: [16]process.FdAssign = undefined;
    const fds = map.assigns(&fa);
    const child = process.spawn(self_path, argv.items, .{ .env = env, .fds = fds }) catch |e| {
        util.complain(&.{ "cannot start a subshell: ", kibi.errno.describe(e) });
        state.status = 2;
        return null;
    };
    return child;
}

/// `--kibi-sub=<$?>,<$$>,<$!>`
fn subOption(buf: []u8) []const u8 {
    const head = "--kibi-sub=";
    var n: usize = head.len;
    @memcpy(buf[0..n], head);
    n += io.formatUnsigned(buf[n..], state.status).len;
    buf[n] = ',';
    n += 1;
    n += io.formatUnsigned(buf[n..], state.shell_pid).len;
    buf[n] = ',';
    n += 1;
    if (state.last_bg) |pid| n += io.formatUnsigned(buf[n..], pid).len;
    return buf[0..n];
}

/// 子のシェルに渡す、オプション・変数・関数を書いた頭
fn prelude() Error![]const u8 {
    var o = mem.List(u8).init(&mem.str);
    var flags: [8]u8 = undefined;
    var nf: usize = 0;
    if (state.errexit) {
        flags[nf] = 'e';
        nf += 1;
    }
    if (state.nounset) {
        flags[nf] = 'u';
        nf += 1;
    }
    if (state.xtrace) {
        flags[nf] = 'x';
        nf += 1;
    }
    if (state.noglob) {
        flags[nf] = 'f';
        nf += 1;
    }
    if (state.noclobber) {
        flags[nf] = 'C';
        nf += 1;
    }
    for (vars.all()) |v| {
        if (v.exported and !v.readonly) continue;
        if (v.value) |val| {
            if (!v.exported) {
                try o.appendSlice(v.name);
                try o.append('=');
                try quoteInto(&o, val);
                try o.append('\n');
            }
        } else if (v.exported) {
            try o.appendSlice("export ");
            try o.appendSlice(v.name);
            try o.append('\n');
        }
        if (v.readonly) {
            try o.appendSlice("readonly ");
            try o.appendSlice(v.name);
            try o.append('\n');
        }
    }
    for (vars.allFuncs()) |f| {
        try o.appendSlice(f.name);
        try o.appendSlice("() ");
        try o.appendSlice(f.text);
        try o.append('\n');
    }
    // オプションは最後に（-x で頭の代入を書かないため）
    if (nf > 0) {
        try o.appendSlice("set -");
        try o.appendSlice(flags[0..nf]);
        try o.append('\n');
    }
    return o.items;
}

/// `'...'` で引用する（中の `'` は `'\''`）
pub fn quoteInto(o: *mem.List(u8), s: []const u8) Error!void {
    try o.append('\'');
    for (s) |c| {
        if (c == '\'') {
            try o.appendSlice("'\\''");
        } else try o.append(c);
    }
    try o.append('\'');
}

// ---- 待つ ----

/// 子を待つ間に書くもの。中身は残る領域（heap）に持つ
const Pending = struct { fd: io.Fd, data: []u8, off: usize = 0, open: bool = true };
var pending: [MAX_PENDING]Pending = undefined;
var npending: usize = 0;

/// 書くものを積んだ位置（子を起こす前に覚え、[waitChildren] に渡す）
pub fn pendingMark() usize {
    return npending;
}

/// [fd] に [body] を、子を待つ間に書くものとして積む（写して持つ）。積めなければ、その場で全部書いて閉じる
fn queueWrite(fd: io.Fd, body: []const u8) void {
    if (body.len == 0) {
        io.close(fd) catch {};
        return;
    }
    if (npending < MAX_PENDING) {
        if (mem.heapDupe(body)) |copy| {
            pending[npending] = .{ .fd = fd, .data = copy };
            npending += 1;
            return;
        } else |_| {}
    }
    io.writeAll(fd, body) catch {};
    io.close(fd) catch {};
}

/// [queueWrite] と同じだが、[data]（heapAlloc で取ったもの）をそのまま持つ
fn queueOwned(fd: io.Fd, data: []u8) void {
    if (data.len > 0 and npending < MAX_PENDING) {
        pending[npending] = .{ .fd = fd, .data = data };
        npending += 1;
        return;
    }
    io.writeAll(fd, data) catch {};
    io.close(fd) catch {};
    mem.heapFree(data);
}

fn closePending(p: *Pending) void {
    if (!p.open) return;
    io.close(p.fd) catch {};
    mem.heapFree(p.data);
    p.open = false;
}

/// [from] から後に積んだものを片付ける（書き終わっていなければ、そこで閉じる）
fn finishPending(from: usize) void {
    if (from >= npending) return;
    for (pending[from..npending]) |*p| closePending(p);
    npending = from;
}

/// [from] から後に積んだものを、その場で全部書いて閉じる（待つ子がいないとき）
fn flushPending(from: usize) void {
    if (from >= npending) return;
    for (pending[from..npending]) |*p| {
        if (p.open) io.writeAll(p.fd, p.data[p.off..]) catch {};
        closePending(p);
    }
    npending = from;
}

/// 書ける分だけ書く（パイプは空きの分だけなので止まらない）。相手が閉じた・書き終えたら閉じる
fn pumpWrite(p: *Pending, ev: wasi.Event) void {
    if (!p.open) return;
    if (ev.@"error" != 0 or ev.flags & wasi.EVENT_FD_READWRITE_HANGUP != 0) return closePending(p);
    const rest = p.data[p.off..];
    // ファイルは nbytes が 0 でも止まらずに書ける
    const room: usize = if (ev.nbytes > 0) @intCast(@min(ev.nbytes, rest.len)) else @min(rest.len, PIPE_CAPACITY);
    const n = io.write(p.fd, rest[0..room]) catch return closePending(p);
    p.off += n;
    if (n == 0 or p.off == p.data.len) closePending(p);
}

/// 子を待つ間に読む、コマンド置換の出力
const Sink = struct { fd: io.Fd, buf: *Buffer, done: bool = false };

const SINK_TAG: u64 = 1 << 32;
const PENDING_TAG: u64 = 2 << 32;
const MAX_SUBS = MAX_STAGES + MAX_PENDING + 1;

/// 子がすべて終わるまで待ち、最後の子の終了コードを返す（ハンドルを閉じる）。
/// 待つ間に、積んであるヒアドキュメントなどを少しずつ書き、[from] から後に積んだものは最後に片付ける。
/// 標準入力が TTY で、このシェルが前景なら、子として起こした最後の段を前景にする。どれかが Ctrl+C で止まったら残りも止める
pub fn waitChildren(children: []?process.Child, from: usize) u8 {
    return waitIo(children, from, null) catch 2;
}

/// [waitChildren] に加えて、[sink] を書く側が閉じるまで読む。読んだものを置けなければ、子を止めて `OutOfMemory`
fn waitIo(children: []?process.Child, from: usize, sink: ?*Sink) Error!u8 {
    var last_status: u8 = state.status;
    const last_idx = children.len - 1;
    var fg_given = false;
    // 最後の段がシェルの中で動いたときも、Ctrl+C が届くよう、子のうち最後のものを前景にする
    var fg_child: ?process.Child = null;
    for (children) |maybe| {
        if (maybe) |c| fg_child = c;
    }
    if (fg_child) |c| {
        if (tty.isTty(io.STDIN)) {
            const fg = tty.getForeground(io.STDIN) catch 0;
            // 会話的なシェルは、前景がなければ自分のものとして渡す
            if (fg == process.selfPid() or (state.interactive and fg == 0)) {
                tty.setForeground(io.STDIN, c.pid()) catch {};
                fg_given = true;
            }
        }
    }
    var interrupted = false;
    var failure: ?Error = null;
    var done: [MAX_STAGES]bool = @splat(false);
    var subs: [MAX_SUBS]wasi.Subscription = undefined;
    var events: [MAX_SUBS]wasi.Event = undefined;
    var chunk: [1024]u8 = undefined;
    while (failure == null) {
        var n: usize = 0;
        for (children, 0..) |maybe, i| {
            const c = maybe orelse continue;
            if (done[i]) continue;
            const st = c.status() catch {
                done[i] = true;
                continue;
            };
            if (st) |s| {
                done[i] = true;
                const code = statusOf(s);
                if (i == last_idx) last_status = code;
                if (s == .killed) {
                    if (s.killed) |sig| {
                        if (sig == .interrupt or sig == .quit) interrupted = true;
                    }
                }
                continue;
            }
            subs[n] = .{ .userdata = i, .tag = wasi.EVENTTYPE_FD_READ, .u = .{ .fd = .{ .fd = c.handle } } };
            n += 1;
        }
        if (sink) |s| {
            if (!s.done) {
                subs[n] = .{ .userdata = SINK_TAG, .tag = wasi.EVENTTYPE_FD_READ, .u = .{ .fd = .{ .fd = s.fd } } };
                n += 1;
            }
        }
        // 書くものだけが残ったら終わる（読む側は子なので、子が終われば書けない）
        if (n == 0) break;
        if (interrupted) {
            for (children, 0..) |maybe, i| {
                if (!done[i]) if (maybe) |c| c.kill(.interrupt) catch {};
            }
        }
        for (pending[0..npending], 0..) |p, k| {
            if (!p.open) continue;
            subs[n] = .{ .userdata = PENDING_TAG + k, .tag = wasi.EVENTTYPE_FD_WRITE, .u = .{ .fd = .{ .fd = p.fd } } };
            n += 1;
        }
        var got: wasi.Size = 0;
        kibi.errno.check(wasi.poll_oneoff(&subs, &events, @intCast(n), &got)) catch break;
        for (events[0..got]) |ev| {
            if (ev.userdata >= PENDING_TAG) {
                pumpWrite(&pending[@intCast(ev.userdata - PENDING_TAG)], ev);
            } else if (ev.userdata == SINK_TAG) {
                const s = sink.?;
                const r = io.read(s.fd, &chunk) catch 0;
                if (r == 0) {
                    s.done = true;
                } else s.buf.append(chunk[0..r]) catch |e| {
                    failure = e;
                };
            }
        }
    }
    if (failure != null) {
        for (children, 0..) |maybe, i| {
            if (!done[i]) if (maybe) |c| c.kill(.terminate) catch {};
        }
    }
    for (children) |maybe| if (maybe) |c| c.release();
    finishPending(from);
    if (fg_given) tty.setForeground(io.STDIN, process.selfPid()) catch {};
    if (failure) |e| return e;
    if (interrupted and fg_given and !state.interactive) {
        // Ctrl+C はシェルのスクリプトごと止める
        process.killPid(process.selfPid(), .interrupt) catch {};
    }
    return last_status;
}

// ---- パイプライン ----

/// シェルの中で動かしてよい組み込み（標準入力を読まず、状態を変えない）
fn isPureStage(node: *Node) bool {
    if (node.kind != .simple) return false;
    const s = node.kind.simple;
    if (s.assigns != null or node.redirs != null) return false;
    const w = s.words orelse return false;
    if (!isPlainWord(w.text)) return false;
    return builtins.isPure(w.text) and vars.findFunc(w.text) == null;
}

fn runPipeline(first: *Node, count: usize) Error!void {
    if (count > MAX_STAGES) {
        util.complain(&.{"pipeline too long"});
        state.status = 2;
        return;
    }
    const pm = pendingMark();
    var children: [MAX_STAGES]?process.Child = @splat(null);
    // シェルの中で動かす段（後で動かす。途中の段の出力は集めて、子を待つ間に書く）
    var deferred: [MAX_STAGES]?*Node = @splat(null);
    var deferred_out: [MAX_STAGES]?io.Fd = @splat(null);
    var prev_read: ?io.Fd = null;
    var node: ?*Node = first;
    var i: usize = 0;
    var failed = false;
    while (node) |n| : ({
        node = n.next;
        i += 1;
    }) {
        var p: ?io.Pipe = null;
        if (n.next != null) {
            p = io.pipe() catch |e| {
                util.complain(&.{ "cannot make a pipe: ", kibi.errno.describe(e) });
                failed = true;
                break;
            };
        }
        if (isPureStage(n)) {
            deferred[i] = n;
            if (p) |pp| deferred_out[i] = pp.write;
            if (prev_read) |r| io.close(r) catch {};
        } else {
            var base = Base{};
            if (prev_read) |r| base.stdin = .{ .fd = r };
            if (p) |pp| base.stdout = .{ .fd = pp.write };
            children[i] = startNode(n, base) catch |e| {
                if (prev_read) |r| io.close(r) catch {};
                if (p) |pp| {
                    io.close(pp.write) catch {};
                    io.close(pp.read) catch {};
                }
                closeDeferred(&deferred_out);
                _ = waitChildren(children[0..@max(i, 1)], pm);
                return e;
            };
            if (prev_read) |r| io.close(r) catch {};
            if (p) |pp| io.close(pp.write) catch {};
        }
        prev_read = if (p) |pp| pp.read else null;
    }
    if (prev_read) |r| io.close(r) catch {};
    var last_pure_status: ?u8 = null;
    for (deferred[0..i], 0..) |d, k| {
        const dn = d orelse continue;
        if (deferred_out[k]) |t| {
            // 途中の段: 出力を集めて、子を待つ間に少しずつ書く（パイプが埋まってもシェルが止まらない）
            deferred_out[k] = null;
            var buf = Buffer{};
            const prev = capture_buf;
            capture_buf = &buf;
            runSimple(dn) catch {};
            capture_buf = prev;
            queueOwned(t, buf.take());
        } else {
            // 最後の段: シェルの標準出力にそのまま書く
            runSimple(dn) catch {};
        }
        if (k == count - 1) last_pure_status = state.status;
    }
    if (failed) {
        _ = waitChildren(children[0..@max(i, 1)], pm);
        state.status = 2;
        return;
    }
    const st = waitChildren(children[0..count], pm);
    state.status = last_pure_status orelse st;
}

fn hasHeredoc(first: ?*parse.Redir) bool {
    var r = first;
    while (r) |x| : (r = x.next) {
        if (x.op == .heredoc or x.op == .heredoc_strip) return true;
    }
    return false;
}

fn closeDeferred(fds: []?io.Fd) void {
    for (fds) |*f| {
        if (f.*) |x| io.close(x) catch {};
        f.* = null;
    }
}

// ---- 後ろで動かす ----

pub const Job = struct { child: process.Child, pid: u32, status: ?u8 = null };
pub const MAX_JOBS = 32;
pub var jobs: [MAX_JOBS]?Job = @splat(null);
/// 後ろのジョブのハンドルを置く fd（ユーザーのリダイレクトとぶつからないよう、高い番号に移す）
const JOB_FD_BASE: u32 = 200;

fn runBackground(node: *Node) Error!void {
    var slot: ?usize = null;
    for (jobs, 0..) |j, k| {
        if (j == null) {
            slot = k;
            break;
        }
    }
    if (slot == null) {
        reapJobs(false);
        for (jobs, 0..) |j, k| {
            if (j == null or j.?.status != null) {
                jobs[k] = null;
                slot = k;
                break;
            }
        }
    }
    const k = slot orelse {
        util.complain(&.{"too many background jobs"});
        state.status = 1;
        return;
    };
    // ヒアドキュメントを待たずに書けるのは子を待つ間だけなので、ヒアドキュメントがあれば子のシェルに書かせる
    const pm = pendingMark();
    const child = (try startNode(node, .{ .stdin = .closed, .whole = hasHeredoc(node.redirs) })) orelse return;
    flushPending(pm);
    var c = child;
    const hi = JOB_FD_BASE + @as(u32, @intCast(k));
    if (io.dupTo(c.handle, hi)) {
        io.close(c.handle) catch {};
        c = .{ .handle = hi };
    } else |_| {}
    const pid = c.pid();
    jobs[k] = .{ .child = c, .pid = pid };
    state.last_bg = pid;
    state.status = 0;
    if (state.interactive) {
        var b1: [12]u8 = undefined;
        var b2: [12]u8 = undefined;
        io.err("[");
        io.err(io.formatUnsigned(&b1, k + 1));
        io.err("] ");
        io.err(io.formatUnsigned(&b2, pid));
        io.err("\n");
    }
}

/// 終わった後ろのジョブの終了コードを受け取る（[report] なら終わったことを書く）
pub fn reapJobs(report: bool) void {
    for (&jobs, 0..) |*slot, k| {
        const j = &(slot.* orelse continue);
        if (j.status != null) continue;
        const st = (j.child.status() catch continue) orelse continue;
        j.status = statusOf(st);
        j.child.release();
        if (report) {
            var b: [12]u8 = undefined;
            io.err("[");
            io.err(io.formatUnsigned(&b, k + 1));
            io.err("] Done\n");
            // 知らせたものは片付ける（wait は終了コードを受け取れない。会話的なシェルだけ）
            slot.* = null;
        }
    }
}

/// `wait [pid...]`。pid なしなら全部
pub fn waitJobs(pids: []const u32) u8 {
    var status: u8 = 0;
    if (pids.len == 0) {
        for (&jobs) |*slot| {
            if (slot.*) |*j| {
                if (j.status == null) {
                    var list = [_]?process.Child{j.child};
                    j.status = waitChildrenNoFg(&list);
                }
                status = j.status.?;
                slot.* = null;
            }
        }
        return 0;
    }
    for (pids) |pid| {
        status = 127;
        for (&jobs) |*slot| {
            if (slot.*) |*j| {
                if (j.pid != pid) continue;
                if (j.status == null) {
                    var list = [_]?process.Child{j.child};
                    j.status = waitChildrenNoFg(&list);
                }
                status = j.status.?;
                slot.* = null;
                break;
            }
        }
    }
    return status;
}

fn waitChildrenNoFg(list: []?process.Child) u8 {
    const c = list[0].?;
    const st = c.wait() catch {
        c.release();
        return 127;
    };
    c.release();
    return statusOf(st);
}

// ---- コマンド置換 ----

/// [cmd] を動かし、標準出力を集めて返す（heapFree で返す）。`$?` はその終了コード
pub fn capture(cmd: []const u8) Error![]u8 {
    const m = mem.mark();
    defer mem.release(m);
    var p = parse.Parser.init(cmd, true);
    p.quiet = true;
    var only: ?*Node = null;
    var count: usize = 0;
    while (true) {
        const n = (p.top() catch {
            util.complain(&.{ "syntax error in command substitution: ", cmd });
            state.status = 2;
            subst_status = 2;
            return &[_]u8{};
        }) orelse break;
        if (n.kind == .list) {
            var it = n.kind.list.first;
            while (it) |item| : (it = item.next) {
                count += 1;
                only = if (item.background) null else item.node;
            }
        } else {
            count += 1;
            only = n;
        }
    }
    var buf = Buffer{};
    if (count == 0) {
        state.status = 0;
        subst_status = 0;
        return buf.take();
    }
    if (count == 1 and only != null and isPureStage(only.?)) {
        const prev = capture_buf;
        capture_buf = &buf;
        defer capture_buf = prev;
        try runSimple(only.?);
        subst_status = state.status;
        return buf.take();
    }
    const pipe = io.pipe() catch |e| {
        util.complain(&.{ "cannot make a pipe: ", kibi.errno.describe(e) });
        state.status = 2;
        return buf.take();
    };
    const base = Base{ .stdout = .{ .fd = pipe.write } };
    const pm = pendingMark();
    const child = blk: {
        if (count == 1 and only != null) {
            break :blk startNode(only.?, base) catch |e| {
                io.close(pipe.write) catch {};
                io.close(pipe.read) catch {};
                finishPending(pm);
                return e;
            };
        }
        var map = FdMap{ .src = baseSrc(base) };
        break :blk startSubshell(cmd, &map);
    };
    io.close(pipe.write) catch {};
    // 出力を読みながら、ヒアドキュメントを書く（`$(cat <<EOF ...)` で、互いに待ち合わないよう）
    var sink = Sink{ .fd = pipe.read, .buf = &buf };
    var list = [_]?process.Child{child};
    const st = waitIo(&list, pm, &sink) catch |e| {
        io.close(pipe.read) catch {};
        mem.heapFree(buf.take());
        return e;
    };
    io.close(pipe.read) catch {};
    if (child != null) state.status = st;
    subst_status = state.status;
    return buf.take();
}

// ---- シェルの中のリダイレクト ----

const SAVE_FD_BASE: u32 = 100;
var next_save_fd: u32 = SAVE_FD_BASE;

const Saves = struct {
    fds: [16]u32 = undefined,
    saved: [16]?io.Fd = undefined,
    n: usize = 0,
};

/// fd [n] を高い番号に写して覚える（閉じていれば null）
fn saveFd(n: u32) ?io.Fd {
    const d = io.dup(n) catch return null;
    const hi = next_save_fd;
    next_save_fd += 1;
    io.dupTo(d, hi) catch {
        next_save_fd -= 1;
        return d;
    };
    io.close(d) catch {};
    return hi;
}

fn restoreFd(n: u32, saved: ?io.Fd) void {
    if (saved) |s| {
        io.dupTo(s, n) catch {};
        io.close(s) catch {};
        if (s == next_save_fd - 1) next_save_fd -= 1;
    } else {
        io.close(n) catch {};
    }
}

/// リダイレクトをこのシェルの fd に行う。[save] なら前の fd を [saves] に覚える。失敗したら知らせて false
fn applyRedirs(first: ?*parse.Redir, saves: *Saves, save: bool) Error!bool {
    var r = first;
    while (r) |x| : (r = x.next) {
        const n = x.fd orelse defaultFd(x.op);
        if (!checkFd(n)) return false;
        if (save) {
            var seen = false;
            for (saves.fds[0..saves.n]) |f| seen = seen or f == n;
            if (!seen) {
                if (saves.n == saves.fds.len) {
                    util.complain(&.{"too many redirections"});
                    return false;
                }
                saves.fds[saves.n] = n;
                saves.saved[saves.n] = saveFd(n);
                saves.n += 1;
            }
        } else if (n >= 4) {
            user_fds |= @as(u16, 1) << @intCast(n);
        }
        switch (x.op) {
            .dup_in, .dup_out => {
                const w = try expand.string(x.target);
                if (eql(w, "-")) {
                    io.close(n) catch {};
                    if (!save and n >= 4) user_fds &= ~(@as(u16, 1) << @intCast(n));
                    continue;
                }
                const v = io.parseUnsigned(w) orelse {
                    util.complain(&.{ w, ": bad file descriptor" });
                    return false;
                };
                if (v > 9 or !checkFd(@intCast(v))) {
                    if (v > 9) util.complain(&.{ w, ": bad file descriptor" });
                    return false;
                }
                if (v == n) continue;
                io.dupTo(@intCast(v), n) catch {
                    util.complain(&.{ w, ": bad file descriptor" });
                    return false;
                };
            },
            .heredoc, .heredoc_strip => {
                const body = try heredocBody(x);
                if (body.len > PIPE_CAPACITY) {
                    util.complain(&.{"here-document too large for a built-in command or a compound command (4096 bytes at most)"});
                    return false;
                }
                const p = io.pipe() catch |e| {
                    util.complain(&.{ "cannot make a pipe: ", kibi.errno.describe(e) });
                    return false;
                };
                io.writeAll(p.write, body) catch {};
                io.close(p.write) catch {};
                io.dupTo(p.read, n) catch {};
                io.close(p.read) catch {};
            },
            else => {
                const f = (try openTarget(x)) orelse return false;
                if (f != n) {
                    io.dupTo(f, n) catch {};
                    io.close(f) catch {};
                }
            },
        }
    }
    return true;
}

fn restoreRedirs(saves: *Saves) void {
    while (saves.n > 0) {
        saves.n -= 1;
        restoreFd(saves.fds[saves.n], saves.saved[saves.n]);
    }
}

// ---- 入力を読んで動かす ----

/// [text] をすべて読んで動かす（eval・関数・`.`・`-c`）。構文の誤りなら `$?` を 2 にして戻る
pub fn runText(text: []const u8, name: []const u8, line_base: usize) Error!void {
    var p = parse.Parser.init(text, true);
    p.name = name;
    p.line_base = line_base;
    while (true) {
        const m = mem.mark();
        const node = p.top() catch |e| {
            mem.release(m);
            if (e == error.Syntax) {
                state.status = 2;
                return;
            }
            if (e == error.Incomplete) unreachable;
            return e;
        } orelse break;
        defer mem.release(m);
        try run(node);
        if (state.flow != .none) return;
    }
}

/// 入力の fd から読んで動かす（スクリプトのファイル・標準入力）
pub const Reader = struct {
    fd: io.Fd,
    buf: []u8,
    start: usize = 0,
    len: usize = 0,
    eof: bool = false,
    prompt: bool,
    /// 行の編集（#102）で読む（会話的で、標準入力と標準エラー出力が TTY）
    edit: bool = false,
    name: []const u8,
    line: usize = 1,

    fn fill(self: *Reader) Error!bool {
        if (self.start > 0) {
            const rest = self.len - self.start;
            // 重なってもよい写し
            var k: usize = 0;
            while (k < rest) : (k += 1) self.buf[k] = self.buf[self.start + k];
            self.start = 0;
            self.len = rest;
        }
        if (self.len == self.buf.len) return false;
        if (self.edit) return self.editLine();
        if (self.prompt) showPrompt(self.len > 0);
        const n = io.read(self.fd, self.buf[self.len..]) catch |e| {
            if (e == error.INTR or e == error.AGAIN) return true;
            self.eof = true;
            return true;
        };
        if (n == 0) self.eof = true;
        if (n > 0 and state.verbose) io.err(self.buf[self.len..][0..n]);
        self.len += n;
        return true;
    }

    /// 行の編集で 1 行読む。Ctrl+C なら、読みかけのコマンドを捨てて `$?` を 130 にする
    fn editLine(self: *Reader) Error!bool {
        if (self.buf.len - self.len < 2) return false;
        const m = mem.mark();
        defer mem.release(m);
        const p = promptText(self.len > 0);
        switch (edit.readLine(p, self.buf[self.len..])) {
            .line => |n| {
                if (state.verbose) io.err(self.buf[self.len..][0..n]);
                self.len += n;
            },
            .eof => self.eof = true,
            .interrupt => {
                self.len = self.start;
                state.status = 130;
            },
        }
        return true;
    }

    /// 入力が尽きるまで動かす
    pub fn runAll(self: *Reader) Error!void {
        while (true) {
            if (self.prompt and self.start == self.len) reapJobs(true);
            const m = mem.mark();
            var p = parse.Parser.init(self.buf[self.start..self.len], self.eof);
            p.name = self.name;
            p.line_base = self.line;
            const node = p.top() catch |e| {
                mem.release(m);
                switch (e) {
                    error.Incomplete => {
                        if (!(try self.fill())) {
                            util.complain(&.{"command too long"});
                            self.start = self.len;
                            state.status = 2;
                            if (!state.interactive) process.exit(2);
                        }
                        continue;
                    },
                    error.Syntax => {
                        state.status = 2;
                        if (!state.interactive) process.exit(2);
                        // 残りの行を捨てる
                        self.start = self.len;
                        continue;
                    },
                    error.OutOfMemory => {
                        util.complain(&.{"out of memory"});
                        self.start = self.len;
                        state.status = 2;
                        if (!state.interactive) process.exit(2);
                        continue;
                    },
                    else => {
                        self.start = self.len;
                        continue;
                    },
                }
            } orelse {
                mem.release(m);
                if (!self.eof and self.start == self.len) {
                    _ = try self.fill();
                    continue;
                }
                break;
            };
            const consumed = self.buf[self.start..][0..p.pos];
            for (consumed) |c| {
                if (c == '\n') self.line += 1;
            }
            run(node) catch |e| {
                mem.release(m);
                self.start += consumed.len;
                handleTopError(e);
                continue;
            };
            mem.release(m);
            self.start += consumed.len;
            state.flow = .none;
        }
    }
};

/// 一番外で受けたエラー。会話的でなければ終わる
pub fn handleTopError(e: Error) void {
    switch (e) {
        error.OutOfMemory => util.complain(&.{"out of memory"}),
        else => {},
    }
    if (state.status == 0) state.status = if (e == error.Abort) 1 else 2;
    if (!state.interactive) process.exit(state.status);
}

fn showPrompt(continued: bool) void {
    const m = mem.mark();
    defer mem.release(m);
    io.err(promptText(continued));
}

/// `PS1`・`PS2` を展開したもの（展開の領域に置く）
fn promptText(continued: bool) []const u8 {
    const raw = if (continued) (vars.get("PS2") orelse "> ") else (vars.get("PS1") orelse "$ ");
    return expand.string(raw) catch raw;
}
