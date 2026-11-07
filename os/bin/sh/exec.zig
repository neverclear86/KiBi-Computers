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
        if (stream) |st| if (st.buf == b) return streamOut(st, s);
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
                // 起こせなかった（コマンドがない・リダイレクトの誤り・括弧の中の展開の誤り）ときも、括弧が失敗したものとして `set -e` を見る
                finishPending(pm);
                checkErrexit();
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
            // 跡は bash・dash と同じく `+ name=value` の1語にする（#504）。値をつながずに続けて書く
            // （つなぐ領域が足りなくて代入が失敗しないように。#510）
            if (state.xtrace) traceAssign(w.text[0 .. eq + 1], value);
            try vars.set(w.text[0..eq], value);
        }
        var saves = Saves{};
        const ok = try applyRedirs(node.redirs, &saves, true);
        restoreRedirs(&saves);
        state.status = if (!ok) 1 else subst_status orelse 0;
        return;
    }
    // 跡は代入を展開した後に、代入の値と語を1行にして出す（`+ x=1 cmd`。#508）。
    // 代入の値は、代入する所（[runArgv] の先）で1回だけ展開する
    try runArgv(node, argv.items, s.assigns, false, if (state.xtrace) argv.items else null);
}

/// 展開した [argv] を動かす（[node] のリダイレクトと [assigns] の代入を使う）。
/// [tr] が null でなければ、代入を展開した後に `set -x` の跡（代入と [tr] の語）を出す
fn runArgv(node: *Node, argv: []const []const u8, assigns: ?*parse.Word, no_functions: bool, tr: ?[]const []const u8) Error!void {
    const name = argv[0];
    const kind = builtins.kindOf(name);
    if (kind == .special) {
        // 特別な組み込みの代入は残る
        var traced = Traced.init(tr);
        var a = assigns;
        while (a) |w| : (a = w.next) {
            const eq = util.indexOf(w.text, '=').?;
            const value = try expand.assignValue(w.text[eq + 1 ..]);
            try traced.add(w.text[0 .. eq + 1], value);
            try vars.set(w.text[0..eq], value);
        }
        traced.write(io.STDERR);
        if (eql(name, "exec")) return execBuiltin(node, argv);
        return runInProcess(node, argv, null, null);
    }
    if (!no_functions) {
        if (vars.findFunc(name)) |f| return callFunction(node, f, argv, assigns, tr);
    }
    if (kind == .regular) {
        if (eql(name, "command")) return commandBuiltin(node, argv, assigns, tr);
        return runInProcess(node, argv, assigns, tr);
    }
    // 外のプログラム。代入はリダイレクトの前に展開する（跡をリダイレクトの誤りより先に出すため）
    var traced = Traced.init(tr);
    const env = try tempEnv(assigns, &traced);
    traced.write(io.STDERR);
    var map = FdMap{};
    defer map.cleanup();
    if (!(try map.addRedirs(node.redirs))) {
        state.status = 1;
        return;
    }
    const pm = pendingMark();
    var list = [_]?process.Child{spawnProgram(argv, env, &map)};
    map.afterSpawn(list[0] != null);
    map.cleanup();
    if (list[0] == null) return;
    state.status = waitChildren(&list, pm);
}

/// `NAME=v cmd` の代入を、子の環境変数にする（展開した値は [traced] にも覚える）
fn tempEnv(assigns: ?*parse.Word, traced: *Traced) Error![]process.EnvEntry {
    var extra = mem.List(process.EnvEntry).init(&mem.ast);
    var a = assigns;
    while (a) |w| : (a = w.next) {
        const eq = util.indexOf(w.text, '=').?;
        const value = try expand.assignValue(w.text[eq + 1 ..]);
        try traced.add(w.text[0 .. eq + 1], value);
        try extra.append(.{ .name = vars.str(w.text[0..eq]), .value = vars.str(value) });
    }
    return vars.environ(&mem.ast, extra.items);
}

/// 組み込みをシェルの中で動かす（[assigns] は終わるまでの一時の代入）
fn runInProcess(node: *Node, argv: []const []const u8, assigns: ?*parse.Word, tr: ?[]const []const u8) Error!void {
    const frame = vars.savedMark();
    // 一時の代入だけを戻す（`local` が覚えたものは、関数が終わるまで残す）
    defer if (assigns != null) vars.restoreLocals(frame);
    try tempAssign(assigns, frame, tr);
    var saves = Saves{};
    if (!(try applyRedirs(node.redirs, &saves, true))) {
        restoreRedirs(&saves);
        state.status = 1;
        return;
    }
    defer restoreRedirs(&saves);
    state.status = try builtins.run(argv);
}

/// 一時の代入をする。[tr] が null でなければ、代入を終えてから `set -x` の跡を出す
fn tempAssign(assigns: ?*parse.Word, frame: usize, tr: ?[]const []const u8) Error!void {
    var traced = Traced.init(tr);
    var a = assigns;
    while (a) |w| : (a = w.next) {
        const eq = util.indexOf(w.text, '=').?;
        const name = w.text[0..eq];
        const value = try expand.assignValue(w.text[eq + 1 ..]);
        try traced.add(w.text[0 .. eq + 1], value);
        try vars.makeLocal(name, frame);
        try vars.set(name, value);
        try vars.setExported(name, true);
    }
    traced.write(io.STDERR);
}

/// 関数の中で `local` が覚える位置
pub var local_frame: usize = 0;

fn callFunction(node: *Node, f: *vars.Func, argv: []const []const u8, assigns: ?*parse.Word, tr: ?[]const []const u8) Error!void {
    if (state.func_depth >= MAX_FUNC_DEPTH) {
        // 呼べなくても、跡は呼ぶときと同じく代入を展開して出してから知らせる（#513）
        try expandAssignsOnly(assigns, tr);
        util.complain(&.{ argv[0], ": too deeply nested function calls" });
        return error.Abort;
    }
    f.busy += 1;
    defer vars.releaseFunc(f);
    const frame = vars.savedMark();
    defer vars.restoreLocals(frame);
    try tempAssign(assigns, frame, tr);
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
    try runArgv(node, argv[1..], null, true, null);
    process.exit(state.status);
}

/// [assigns] を展開するだけで代入はせず、[tr] が null でなければ `set -x` の跡（代入と [tr] の語）を出す
fn expandAssignsOnly(assigns: ?*parse.Word, tr: ?[]const []const u8) Error!void {
    var traced = Traced.init(tr);
    var a = assigns;
    while (a) |w| : (a = w.next) {
        const eq = util.indexOf(w.text, '=').?;
        try traced.add(w.text[0 .. eq + 1], try expand.assignValue(w.text[eq + 1 ..]));
    }
    traced.write(io.STDERR);
}

/// `command [-v|-V] name args...`
fn commandBuiltin(node: *Node, argv: []const []const u8, assigns: ?*parse.Word, tr: ?[]const []const u8) Error!void {
    if (argv.len >= 2 and (eql(argv[1], "-v") or eql(argv[1], "-V"))) {
        return runInProcess(node, argv, assigns, tr);
    }
    if (argv.len == 1) {
        // 動かすものはないが、代入は展開して跡に出す（`x=1 command` は `+ x=1 command`）。
        // `set -x` がなくても展開はする（コマンド置換や `${x:=w}` は動く。bash・dash と同じ）
        try expandAssignsOnly(assigns, tr);
        state.status = 0;
        return;
    }
    return runArgv(node, argv[1..], assigns, true, tr);
}

/// 代入の `set -x` の跡（`+ name=value`）を標準エラー出力に書く。[name_eq] は `name=` まで
fn traceAssign(name_eq: []const u8, value: []const u8) void {
    io.writeAll(io.STDERR, "+ ") catch {};
    io.writeAll(io.STDERR, name_eq) catch {};
    io.writeAll(io.STDERR, value) catch {};
    io.writeAll(io.STDERR, "\n") catch {};
}

/// 展開したコマンドの前の代入の1つ（`name=` と値）
const Assigned = struct { name_eq: []const u8, value: []const u8 };

/// コマンドの前の代入を展開しながら覚えて、語と1行にして `set -x` の跡を出す（`+ x=1 cmd`。#508）。
/// [words] が null なら（跡を出さないなら）何も覚えない。
/// 名前と値はつながずに続けて書く（つなぐ領域が足りなくて代入が失敗しないように。#510）
const Traced = struct {
    words: ?[]const []const u8,
    assigns: mem.List(Assigned),

    fn init(words: ?[]const []const u8) Traced {
        return .{ .words = words, .assigns = mem.List(Assigned).init(&mem.ast) };
    }

    fn add(self: *Traced, name_eq: []const u8, value: []const u8) Error!void {
        if (self.words == null) return;
        try self.assigns.append(.{ .name_eq = name_eq, .value = value });
    }

    /// 跡の長さ（`+`、各語の前の空白、改行）
    fn len(self: *const Traced) usize {
        var n: usize = 2;
        for (self.assigns.items) |a| n += 1 + a.name_eq.len + a.value.len;
        for (self.words orelse &.{}) |p| n += 1 + p.len;
        return n;
    }

    /// 跡を [fd] に書く（跡を出さないなら何もしない）
    fn write(self: *const Traced, fd: io.Fd) void {
        const words = self.words orelse return;
        io.writeAll(fd, "+") catch {};
        for (self.assigns.items) |a| {
            io.writeAll(fd, " ") catch {};
            io.writeAll(fd, a.name_eq) catch {};
            io.writeAll(fd, a.value) catch {};
        }
        for (words) |p| {
            io.writeAll(fd, " ") catch {};
            io.writeAll(fd, p) catch {};
        }
        io.writeAll(fd, "\n") catch {};
    }
};

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
};

/// [node] を子として起こす。単純なコマンドで外のプログラムならそのまま、ほかはサブシェルで。
/// `( 外のプログラム1つ )` も括弧を外して直に起こす（`$!`・`kill` が本来のコマンドを指すように。#471）
fn startNode(node: *Node, base: Base) Error!?process.Child {
    const m = mem.mark();
    defer mem.release(m);
    var map = FdMap{ .src = baseSrc(base) };
    defer map.cleanup();
    var target = node;
    if (node.kind == .subshell) {
        // `( ... )` は中身を子に渡し、リダイレクトはここで行う（括弧ごと渡すと、子がまたサブシェルを起こす）。
        // 括弧を外して起こすときも、一番外のリダイレクトは先にここで1度だけ行う（その誤りは今までどおり上へ渡す）
        if (!(try map.addRedirs(node.redirs))) {
            state.status = 1;
            return null;
        }
        target = node.kind.subshell;
    }
    var inner = node;
    while (innerOf(inner)) |x| inner = x;
    // 括弧の中の語は親で展開するので、親の状態を変えうる展開（`${x:=w}`・`$((x=1))`・`$(...)`）があればサブシェルで
    if (inner.kind == .simple and (inner == node or !mayChangeParent(node, inner))) {
        switch (try startProgram(node, inner, &map)) {
            .not_program => {},
            .done => |child| return child,
        }
    }
    const text = try parse.nodeText(target, &mem.ast);
    const child = startSubshell(text, &map);
    map.afterSpawn(child != null);
    return child;
}

const Started = union(enum) {
    /// 外のプログラムではない（サブシェルで起こす。[map] にはまだ何も足していない）
    not_program,
    /// 起こした子（起こせなければ null。`state.status` を入れてある）
    done: ?process.Child,
};

/// [inner]（単純なコマンド）が外のプログラムなら、そのまま子として起こす。
/// [outer] が括弧なら、そのリダイレクトは [map] に入れてあるので、その内側から [inner] までの括弧のリダイレクトを外から順に重ねる
fn startProgram(outer: *Node, inner: *Node, map: *FdMap) Error!Started {
    const s = inner.kind.simple;
    // 語がない・組み込み・関数はサブシェルで（語の展開は子でもう一度行う）
    const first = s.words orelse return .not_program;
    if (!isPlainWord(first.text)) return .not_program;
    const name = first.text;
    if (builtins.kindOf(name) != .none or vars.findFunc(name) != null) return .not_program;
    // ここから先の誤りは、括弧の中のもの（括弧がなければ今までどおり上へ渡す）
    const nested = outer != inner;
    var argv = mem.List([]const u8).init(&mem.ast);
    {
        // 括弧の中の展開の誤りの知らせは、一番外のリダイレクトを効かせた標準エラー出力へ（サブシェルで展開していたときと同じ）
        const saved_err = util.err_fd;
        if (nested) util.err_fd = map.parentFd(2);
        defer util.err_fd = saved_err;
        expand.fields(s.words, &argv) catch |e| return failedInside(e, nested);
    }
    if (argv.items.len == 0) return .not_program;
    // コマンドの前の代入は、跡に出すためにリダイレクトの前に1回だけ展開する（#508）。
    // 括弧の中なら親の状態を変えうる展開はない（[mayChangeParent]）ので、下でサブシェルに回しても子でもう一度展開してよい
    var traced = Traced.init(if (state.xtrace) argv.items else null);
    const env = blk: {
        const saved_err = util.err_fd;
        if (nested) util.err_fd = map.parentFd(2);
        defer util.err_fd = saved_err;
        break :blk tempEnv(s.assigns, &traced) catch |e| return failedInside(e, nested);
    };
    // 括弧の中の跡は親が出先に同期で書く。出先は読む側をまだ起こしていないパイプの段の書く端のこともある
    // （`( cmd ) 2>&1 | cat`。段は前から順に起こす）。作ったばかりの空のパイプなので容量までは止まらずに書けるが、
    // それを超える跡はサブシェルで起こして子に書かせる（親は止まらずに読む側を起こせる。#497）
    if (nested and state.xtrace and traced.len() > PIPE_CAPACITY) return .not_program;
    var n = if (nested) innerOf(outer).? else inner;
    while (true) {
        if (n == inner and state.xtrace) {
            // `set -x` の跡は、括弧のリダイレクトを効かせた標準エラー出力へ（サブシェルで起こしていたときと同じ。#478）。
            // コマンド自身のリダイレクトは効かせない（括弧のない `cmd 2>f` と同じ）
            if (nested) {
                if (map.parentFd(2)) |fd| traced.write(fd);
            } else traced.write(io.STDERR);
        }
        // 途中の括弧のリダイレクトの誤りの知らせは、その外までのリダイレクトを効かせた標準エラー出力へ
        // （サブシェルで起こしていたときと同じ。コマンド自身のリダイレクトの誤りも、括弧のリダイレクトまでを効かせた出先へ）
        const ok = blk: {
            const saved_err = util.err_fd;
            if (nested) util.err_fd = map.parentFd(2);
            defer util.err_fd = saved_err;
            break :blk map.addRedirs(n.redirs) catch |e| return failedInside(e, nested);
        };
        if (!ok) {
            state.status = 1;
            return .{ .done = null };
        }
        if (n == inner) break;
        n = innerOf(n).?;
    }
    const child = spawnProgram(argv.items, env, map);
    map.afterSpawn(child != null);
    return .{ .done = child };
}

/// 括弧を外して起こすときに、括弧の中で起きた誤り [e] を扱う。
/// 展開の誤り（`${x?msg}`・`set -u`・bad substitution）は、括弧を外す前と同じく、その括弧だけを終える（親のシェルは続ける）。
/// 括弧の中でなければ（[inside] が偽なら）今までどおり上へ渡す
fn failedInside(e: Error, inside: bool) Error!Started {
    if (e != error.Abort or !inside) return e;
    if (state.status == 0) state.status = 1;
    return .{ .done = null };
}

/// 括弧 [node] の中身がコマンド1つなら、そのコマンド（`( ( cmd ) )` は1段ずつ外す）。ほかは null
fn innerOf(node: *Node) ?*Node {
    if (node.kind != .subshell) return null;
    const body = node.kind.subshell;
    if (body.kind != .list) return body;
    const item = body.kind.list.first orelse return null;
    if (item.next != null or item.background) return null;
    // 並びにはリダイレクトが付かないので、そのまま中のコマンドへ
    return item.node;
}

/// 括弧 [outer] の中の単純なコマンド [inner] を親で起こすと、親のシェルの状態を変えうるか
/// （`${x:=w}`・算術の代入・コマンド置換。迷うものは変えうるとみなす）。
/// [inner] の語と、[outer] の中から [inner] までの各段のリダイレクトを見る（一番外のリダイレクトは、括弧を外す前も親で展開していた）
fn mayChangeParent(outer: *Node, inner: *Node) bool {
    const s = inner.kind.simple;
    if (wordsMayChange(s.assigns) or wordsMayChange(s.words)) return true;
    var n = outer;
    while (n != inner) {
        n = innerOf(n).?;
        if (redirsMayChange(n.redirs)) return true;
    }
    return false;
}

fn redirsMayChange(first: ?*parse.Redir) bool {
    var r = first;
    while (r) |x| : (r = x.next) {
        switch (x.op) {
            .heredoc, .heredoc_strip => if (!x.quoted and textMayChange(x.body)) return true,
            else => if (textMayChange(x.target)) return true,
        }
    }
    return false;
}

fn wordsMayChange(first: ?*parse.Word) bool {
    var w = first;
    while (w) |x| : (w = x.next) {
        if (textMayChange(x.text)) return true;
    }
    return false;
}

fn textMayChange(t: []const u8) bool {
    var brace = false;
    var i: usize = 0;
    while (i < t.len) : (i += 1) {
        if (t[i] == '`') return true;
        if (t[i] == '$' and i + 1 < t.len) {
            if (t[i + 1] == '(') return true;
            if (t[i + 1] == '{') brace = true;
        }
    }
    return brace and util.indexOf(t, '=') != null;
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

// ---- パイプラインの途中の、シェルの中で動かす段の出力 ----

/// [buf] にためた出力の行き先 [fd]（パイプの書く端）。ためるのは [STREAM_CHUNK] までで、超えたら空きを待って書く
const Stream = struct {
    buf: *Buffer,
    fd: io.Fd,
    /// パイプラインの子の終わり（空きを待つ間も見て、Ctrl+C で止まった子があれば残りを止める）
    watch: Watch,
    /// 読む側が閉じた・書けない・子が Ctrl+C で止まった（それより後の出力は捨てる）
    broken: bool = false,
};

/// 今動かしている途中の段（なければ null）
var stream: ?*Stream = null;
/// 途中の段の出力をためる大きさの上限。これより小さい出力は、ためて子を待つ間に書く（シェルが止まらない）
const STREAM_CHUNK = 64 * 1024;
const STREAM_TAG: u64 = 3 << 32;

fn streamOut(st: *Stream, s: []const u8) bool {
    if (st.broken) return false;
    const b = st.buf;
    if (b.len + s.len <= STREAM_CHUNK) {
        if (b.append(s)) |_| return true else |_| {}
    }
    // ためきれない: ためた分を書いてから
    if (b.len > 0) {
        if (!pumpOut(st, b.data[0..b.len])) return false;
        b.len = 0;
    }
    if (s.len <= STREAM_CHUNK) {
        if (b.append(s)) |_| return true else |_| {}
    }
    return pumpOut(st, s);
}

/// [data] を全部書くまで、空きを待って書く。待つ間も、積んであるヒアドキュメントなどを書き（子と待ち合わないよう）、
/// 子の終わりを見る。読む側が閉じた・書けない・子が Ctrl+C で止まった（残りの子も止める）なら false
fn pumpOut(st: *Stream, data: []const u8) bool {
    // 読む子が止まっていても Ctrl+C で抜けられるよう、子を前景にする
    if (!st.watch.fg_given) st.watch.giveForeground();
    var subs: [MAX_SUBS]wasi.Subscription = undefined;
    var events: [MAX_SUBS]wasi.Event = undefined;
    var off: usize = 0;
    while (off < data.len) {
        subs[0] = .{ .userdata = STREAM_TAG, .tag = wasi.EVENTTYPE_FD_WRITE, .u = .{ .fd = .{ .fd = st.fd } } };
        var n: usize = 1;
        // 読まない子が途中にいると、最後の子が Ctrl+C で止まっても空きができない。止まったら残りも止めて抜ける
        n += st.watch.check(subs[n..]);
        if (st.watch.interrupted) {
            st.broken = true;
            return false;
        }
        for (pending[0..npending], 0..) |p, k| {
            if (!p.open) continue;
            subs[n] = .{ .userdata = PENDING_TAG + k, .tag = wasi.EVENTTYPE_FD_WRITE, .u = .{ .fd = .{ .fd = p.fd } } };
            n += 1;
        }
        var got: wasi.Size = 0;
        kibi.errno.check(wasi.poll_oneoff(&subs, &events, @intCast(n), &got)) catch {
            st.broken = true;
            return false;
        };
        for (events[0..got]) |ev| {
            if (ev.userdata == STREAM_TAG) {
                if (ev.@"error" != 0 or ev.flags & wasi.EVENT_FD_READWRITE_HANGUP != 0) {
                    st.broken = true;
                    return false;
                }
                const rest = data[off..];
                const room: usize = if (ev.nbytes > 0) @intCast(@min(ev.nbytes, rest.len)) else @min(rest.len, PIPE_CAPACITY);
                const w = io.write(st.fd, rest[0..room]) catch {
                    st.broken = true;
                    return false;
                };
                off += w;
            } else if (ev.userdata >= PENDING_TAG) {
                pumpWrite(&pending[@intCast(ev.userdata - PENDING_TAG)], ev);
            }
            // 子の終わりは、次の check で見る
        }
    }
    return true;
}

/// パイプラインなどの子の終わりを見る（子を待つ間と、途中の段の出力の空きを待つ間）
const Watch = struct {
    /// 子（段の位置ごと。シェルの中で動かす段は null）
    children: []const ?process.Child,
    done: [MAX_STAGES]bool = @splat(false),
    /// どれかが Ctrl+C・Ctrl+\ で止まった
    interrupted: bool = false,
    /// 最後の段の終了コード（最後の段が子で、終わったとき）
    last_status: ?u8 = null,
    /// TTY の前景を子に渡した（終わったら、シェルに戻す）
    fg_given: bool = false,
    /// 前景にした子の位置
    fg_idx: usize = 0,

    /// まだ動いている子のうち最後のもの
    fn lastRunning(self: *const Watch) ?usize {
        var k = self.children.len;
        while (k > 0) {
            k -= 1;
            if (self.children[k] != null and !self.done[k]) return k;
        }
        return null;
    }

    /// 動いている子のうち最後のものを TTY の前景にする（標準入力が TTY で、このシェルが前景のとき）
    fn giveForeground(self: *Watch) void {
        const k = self.lastRunning() orelse return;
        if (!tty.isTty(io.STDIN)) return;
        const fg = tty.getForeground(io.STDIN) catch 0;
        // 会話的なシェルは、前景がなければ自分のものとして渡す
        if (fg == process.selfPid() or (state.interactive and fg == 0)) {
            tty.setForeground(io.STDIN, self.children[k].?.pid()) catch {};
            self.fg_given = true;
            self.fg_idx = k;
        }
    }

    /// 終わった子を印し、動いている子を [subs] に（userdata は段の位置で FD_READ）足して、足した数を返す。
    /// Ctrl+C・Ctrl+\ で止まった子があれば、残りの子も止める。前景の子が終わったら、動いている最後の子に前景を移す
    /// （終わった子が前景のままだと、Ctrl+C がどこにも届かない）
    fn check(self: *Watch, subs: []wasi.Subscription) usize {
        const last_idx = self.children.len - 1;
        for (self.children, 0..) |maybe, i| {
            const c = maybe orelse continue;
            if (self.done[i]) continue;
            const st = c.status() catch {
                self.done[i] = true;
                continue;
            };
            const s = st orelse continue;
            self.done[i] = true;
            if (i == last_idx) self.last_status = statusOf(s);
            if (s == .killed) {
                if (s.killed) |sig| {
                    if (sig == .interrupt or sig == .quit) self.interrupted = true;
                }
            }
        }
        if (self.interrupted) {
            for (self.children, 0..) |maybe, i| {
                if (!self.done[i]) if (maybe) |c| c.kill(.interrupt) catch {};
            }
        }
        if (self.fg_given and self.done[self.fg_idx]) {
            if (self.lastRunning()) |k| {
                tty.setForeground(io.STDIN, self.children[k].?.pid()) catch {};
                self.fg_idx = k;
            }
        }
        var n: usize = 0;
        for (self.children, 0..) |maybe, i| {
            const c = maybe orelse continue;
            if (self.done[i]) continue;
            subs[n] = .{ .userdata = i, .tag = wasi.EVENTTYPE_FD_READ, .u = .{ .fd = .{ .fd = c.handle } } };
            n += 1;
        }
        return n;
    }
};

/// 子を待つ間に読む、コマンド置換の出力
const Sink = struct { fd: io.Fd, buf: *Buffer, done: bool = false };

const SINK_TAG: u64 = 1 << 32;
const PENDING_TAG: u64 = 2 << 32;
const MAX_SUBS = MAX_STAGES + MAX_PENDING + 1;

/// 子がすべて終わるまで待ち、最後の子の終了コードを返す（ハンドルを閉じる）。
/// 待つ間に、積んであるヒアドキュメントなどを少しずつ書き、[from] から後に積んだものは最後に片付ける。
/// 標準入力が TTY で、このシェルが前景なら、子として起こした段のうち動いている最後のものを前景にする。
/// どれかが Ctrl+C で止まったら残りも止める
pub fn waitChildren(children: []?process.Child, from: usize) u8 {
    return waitIo(children, from, null) catch 2;
}

/// [waitChildren] に加えて、[sink] を書く側が閉じるまで読む。読んだものを置けなければ、子を止めて `OutOfMemory`
fn waitIo(children: []?process.Child, from: usize, sink: ?*Sink) Error!u8 {
    var w = Watch{ .children = children };
    // 最後の段がシェルの中で動いたときも、Ctrl+C が届くよう、子のうち最後のものを前景にする
    w.giveForeground();
    var failure: ?Error = null;
    var subs: [MAX_SUBS]wasi.Subscription = undefined;
    var events: [MAX_SUBS]wasi.Event = undefined;
    var chunk: [1024]u8 = undefined;
    while (failure == null) {
        var n = w.check(&subs);
        if (sink) |s| {
            if (!s.done) {
                subs[n] = .{ .userdata = SINK_TAG, .tag = wasi.EVENTTYPE_FD_READ, .u = .{ .fd = .{ .fd = s.fd } } };
                n += 1;
            }
        }
        // 書くものだけが残ったら終わる（読む側は子なので、子が終われば書けない）
        if (n == 0) break;
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
            if (!w.done[i]) if (maybe) |c| c.kill(.terminate) catch {};
        }
    }
    for (children) |maybe| if (maybe) |c| c.release();
    finishPending(from);
    if (w.fg_given) tty.setForeground(io.STDIN, process.selfPid()) catch {};
    if (failure) |e| return e;
    if (w.interrupted and w.fg_given and !state.interactive) {
        // Ctrl+C はシェルのスクリプトごと止める
        process.killPid(process.selfPid(), .interrupt) catch {};
    }
    return w.last_status orelse state.status;
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
    var fg_given = false;
    for (deferred[0..i], 0..) |d, k| {
        const dn = d orelse continue;
        if (deferred_out[k]) |t| {
            // 途中の段: 小さい出力は集めて、子を待つ間に少しずつ書く（パイプが埋まってもシェルが止まらない）。
            // 大きい出力は、ためすぎないよう、空きを待って書く
            deferred_out[k] = null;
            var buf = Buffer{};
            var st = Stream{ .buf = &buf, .fd = t, .watch = .{ .children = children[0..i] } };
            const prev = capture_buf;
            const prev_stream = stream;
            capture_buf = &buf;
            stream = &st;
            runStage(dn);
            capture_buf = prev;
            stream = prev_stream;
            fg_given = fg_given or st.watch.fg_given;
            if (st.broken) buf.len = 0;
            queueOwned(t, buf.take());
        } else {
            // 最後の段: シェルの標準出力にそのまま書く
            runStage(dn);
        }
        if (k == count - 1) last_pure_status = state.status;
    }
    // 空きを待つ間に子へ渡した前景を戻す（子を待つときに、また渡す）
    if (fg_given) tty.setForeground(io.STDIN, process.selfPid()) catch {};
    if (failed) {
        _ = waitChildren(children[0..@max(i, 1)], pm);
        state.status = 2;
        return;
    }
    const st = waitChildren(children[0..count], pm);
    state.status = last_pure_status orelse st;
}

/// シェルの中で動かす段を動かす。エラーはここで知らせ、`$?` にする（段が止まっても、パイプラインの残りは続ける）
fn runStage(node: *Node) void {
    runSimple(node) catch |e| {
        if (e == error.OutOfMemory) util.complain(&.{"out of memory"});
        if (state.status == 0) state.status = if (e == error.Abort) 1 else 2;
    };
}

fn closeDeferred(fds: []?io.Fd) void {
    for (fds) |*f| {
        if (f.*) |x| io.close(x) catch {};
        f.* = null;
    }
}

// ---- 後ろで動かす ----

pub const Job = struct {
    child: process.Child,
    pid: u32,
    status: ?u8 = null,
    /// `jobs` に見せるコマンド（長ければ切る）
    cmd_buf: [JOB_CMD_MAX]u8 = undefined,
    cmd_len: u8 = 0,

    pub fn command(self: *const Job) []const u8 {
        return self.cmd_buf[0..self.cmd_len];
    }
};
/// `jobs` に見せるコマンドの長さの上限（バイト）。超えたら切って `...` を付ける
pub const JOB_CMD_MAX = 80;
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
    // ヒアドキュメントは後ろのものを待たずに渡す（コマンドはサブシェルに包まず起こし、`$!` を本来のコマンドにする）
    const pm = pendingMark();
    const child = (try startNode(node, .{ .stdin = .closed })) orelse return;
    if (!handOffPending(pm)) {
        child.kill(.kill) catch {};
        child.release();
        state.status = 2;
        return;
    }
    var c = child;
    const hi = JOB_FD_BASE + @as(u32, @intCast(k));
    if (io.dupTo(c.handle, hi)) {
        io.close(c.handle) catch {};
        c = .{ .handle = hi };
    } else |_| {}
    const pid = c.pid();
    jobs[k] = .{ .child = c, .pid = pid };
    setJobCommand(&jobs[k].?, node.src);
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

/// [src] の前後の空白を除き、改行・タブを空白にして、ジョブのコマンドにする（長ければ切って `...` を付ける）
fn setJobCommand(j: *Job, src: []const u8) void {
    var a: usize = 0;
    var b = src.len;
    while (a < b and isBlank(src[a])) a += 1;
    while (b > a and isBlank(src[b - 1])) b -= 1;
    var text = src[a..b];
    var cut = false;
    if (text.len > JOB_CMD_MAX) {
        // UTF-8 の文字の途中で切らない
        var n: usize = JOB_CMD_MAX - 3;
        while (n > 0 and text[n] & 0xc0 == 0x80) n -= 1;
        text = text[0..n];
        cut = true;
    }
    for (text, 0..) |c, i| j.cmd_buf[i] = if (c == '\n' or c == '\t' or c == '\r') ' ' else c;
    var n = text.len;
    if (cut) {
        @memcpy(j.cmd_buf[n..][0..3], "...");
        n += 3;
    }
    j.cmd_len = @intCast(n);
}

fn isBlank(c: u8) bool {
    return c == ' ' or c == '\t' or c == '\n' or c == '\r';
}

/// `jobs [-l] [-p]`（#486）: 後ろのジョブの番号・状態・コマンドを書く（`-l` は pid も、`-p` は pid だけ）。
/// 会話的なシェルでは、終わったと書いたものを片付ける（終わったと知らせた後と同じ）
pub fn listJobs(with_pid: bool, pid_only: bool) Error!u8 {
    reapJobs(false);
    for (&jobs, 0..) |*slot, k| {
        const j = if (slot.*) |*x| x else continue;
        if (pid_only) {
            var b: [12]u8 = undefined;
            if (!(try out(io.formatUnsigned(&b, j.pid))) or !(try out("\n"))) return 1;
        } else if (!(try writeJob(k, j, with_pid, false))) return 1;
        if (j.status != null and state.interactive) slot.* = null;
    }
    return 0;
}

/// ジョブの1行（`[n] Done  cmd`・`[n] Exit 3  cmd`・`[n] Running  cmd &`）を書く。
/// [to_err] なら標準エラーに（終わったことの知らせ。#492）、でなければ標準出力に（`jobs`）。書けなければ false
fn writeJob(k: usize, j: *const Job, with_pid: bool, comptime to_err: bool) Error!bool {
    var b1: [12]u8 = undefined;
    var b2: [12]u8 = undefined;
    var b3: [12]u8 = undefined;
    const failed = j.status != null and j.status.? != 0;
    const st: []const u8 = if (j.status == null) "Running" else if (failed) "Exit" else "Done";
    const parts = [_][]const u8{
        "[",
        io.formatUnsigned(&b1, k + 1),
        "] ",
        if (with_pid) io.formatUnsigned(&b2, j.pid) else "",
        if (with_pid) " " else "",
        st,
        if (failed) " " else "",
        if (failed) io.formatUnsigned(&b3, j.status.?) else "",
        "  ",
        j.command(),
        if (j.status == null) " &\n" else "\n",
    };
    for (parts) |p| {
        if (to_err) {
            io.err(p);
        } else if (!(try out(p))) return false;
    }
    return true;
}

/// 後ろで動かすものに [from] から後に積んだもの（ヒアドキュメント）を、待たずに渡す。
/// パイプに入りきるものはその場で書き、入りきらないものは書くだけの子のシェルに任せる。任せられなければ false
fn handOffPending(from: usize) bool {
    if (from >= npending) return true;
    var ok = true;
    for (pending[from..npending]) |*p| {
        if (!p.open) continue;
        const rest = p.data[p.off..];
        if (ok) {
            // 作ったばかりの空のパイプなので、大きさまでは止まらずに書ける
            if (rest.len <= PIPE_CAPACITY) {
                io.writeAll(p.fd, rest) catch {};
            } else ok = spawnWriter(p.fd, rest);
        }
        closePending(p);
    }
    npending = from;
    return ok;
}

/// [data] を [fd] に書くだけの子のシェル（`printf %s "$1"`）を起こす（待たない。読む側が閉じたら止まる）
fn spawnWriter(fd: io.Fd, data: []const u8) bool {
    const argv = [_][]const u8{ "sh", "-c", "printf %s \"$1\"", "sh", data };
    const fds = [_]process.FdAssign{ process.FdAssign.closed(0), process.FdAssign.inherit(1, fd) };
    const c = process.spawn(self_path, &argv, .{ .env = &.{}, .fds = &fds }) catch |e| {
        util.complain(&.{ "cannot pass the here-document: ", kibi.errno.describe(e) });
        return false;
    };
    c.release();
    return true;
}

/// 終わった後ろのジョブの終了コードを受け取る（[report] なら終わったことを書く）
pub fn reapJobs(report: bool) void {
    for (&jobs, 0..) |*slot, k| {
        const j = if (slot.*) |*x| x else continue;
        if (j.status != null) continue;
        const st = (j.child.status() catch continue) orelse continue;
        j.status = statusOf(st);
        j.child.release();
        if (report) {
            // `jobs` と同じ形で、コマンドも書く（#492）
            _ = writeJob(k, j, false, true) catch {};
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
    // 二重引用符の中と同じく、`\` は `$`・`` ` ``・`\` の前でだけ効く（ST の `ESC \` などをそのまま残す）
    return expand.heredoc(raw) catch raw;
}
