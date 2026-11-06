//! 組み込みのコマンド（#101）
const kibi = @import("kibi");
const util = @import("util.zig");
const mem = @import("mem.zig");
const expand = @import("expand.zig");
const vars = @import("vars.zig");
const state = @import("state.zig");
const exec = @import("exec.zig");
const history = @import("history.zig");

const io = kibi.io;
const fs = kibi.fs;
const process = kibi.process;
const Error = util.Error;
const eql = util.eql;

pub const Kind = enum { none, special, regular };

/// POSIX の特別な組み込み（代入が残る。関数より先に見る）
const specials = [_][]const u8{ ":", ".", "eval", "exec", "exit", "export", "readonly", "return", "set", "shift", "unset", "break", "continue", "local" };
const regulars = [_][]const u8{ "true", "false", "echo", "printf", "cd", "pwd", "read", "test", "[", "wait", "command", "type", "source", "history" };
/// 組み込みの名前すべて（補完に使う）
pub const all_names = specials ++ regulars;
/// 標準入力を読まず、シェルの状態を変えないもの（パイプラインの段・コマンド置換をシェルの中で動かせる）
/// `history` は会話的なシェルの履歴を読むので、子のシェルでは動かさない（`history -c` だけは状態を変える）
const pures = [_][]const u8{ ":", "true", "false", "echo", "printf", "pwd", "test", "[", "history" };

pub fn kindOf(name: []const u8) Kind {
    for (specials) |s| if (eql(s, name)) return .special;
    for (regulars) |s| if (eql(s, name)) return .regular;
    return .none;
}

pub fn isPure(name: []const u8) bool {
    for (pures) |s| if (eql(s, name)) return true;
    return false;
}

pub fn run(argv: []const []const u8) Error!u8 {
    const name = argv[0];
    const args = argv[1..];
    if (eql(name, ":") or eql(name, "true")) return 0;
    if (eql(name, "false")) return 1;
    if (eql(name, "echo")) return echo(args);
    if (eql(name, "printf")) return printf(args);
    if (eql(name, "cd")) return cd(args);
    if (eql(name, "pwd")) return pwd();
    if (eql(name, "export")) return exportBuiltin(args, false);
    if (eql(name, "readonly")) return exportBuiltin(args, true);
    if (eql(name, "unset")) return unset(args);
    if (eql(name, "set")) return set(args);
    if (eql(name, "shift")) return shift(args);
    if (eql(name, "exit")) return exit(args);
    if (eql(name, "return")) return returnBuiltin(args);
    if (eql(name, "break")) return loopControl(args, .break_);
    if (eql(name, "continue")) return loopControl(args, .continue_);
    if (eql(name, "eval")) return eval(args);
    if (eql(name, ".") or eql(name, "source")) return dot(args);
    if (eql(name, "read")) return read(args);
    if (eql(name, "test")) return testBuiltin(args, false);
    if (eql(name, "[")) return testBuiltin(args, true);
    if (eql(name, "wait")) return wait(args);
    if (eql(name, "local")) return local(args);
    if (eql(name, "command")) return commandV(args);
    if (eql(name, "type")) return typeBuiltin(args);
    if (eql(name, "history")) return historyBuiltin(args);
    return 127;
}

fn say(parts: []const []const u8) Error!bool {
    for (parts) |p| if (!(try exec.out(p))) return false;
    return true;
}

fn complain2(cmd: []const u8, what: []const u8) u8 {
    util.complain(&.{ cmd, ": ", what });
    return 1;
}

// ---- echo・printf ----

fn echo(args: []const []const u8) Error!u8 {
    var words = args;
    var newline = true;
    if (words.len > 0 and eql(words[0], "-n")) {
        newline = false;
        words = words[1..];
    }
    for (words, 0..) |w, i| {
        if (i > 0 and !(try exec.out(" "))) return 1;
        if (!(try exec.out(w))) return 1;
    }
    if (newline and !(try exec.out("\n"))) return 1;
    return 0;
}

/// `\` の並びを 1 文字にする。[pos] を進める。`\c` なら null（出力をやめる）
fn escapeAt(s: []const u8, pos: *usize, buf: *[1]u8, octal_zero: bool) ?[]const u8 {
    const i = pos.*;
    if (i + 1 >= s.len) {
        pos.* = i + 1;
        return "\\";
    }
    const c = s[i + 1];
    pos.* = i + 2;
    const r: u8 = switch (c) {
        'n' => '\n',
        't' => '\t',
        'r' => '\r',
        'a' => 7,
        'b' => 8,
        'f' => 12,
        'v' => 11,
        '\\' => '\\',
        'c' => return null,
        '0'...'7' => blk: {
            var v: u32 = 0;
            var j = i + 1;
            // %b は \0nnn、書式は \nnn
            if (octal_zero and c == '0') j += 1;
            var k: usize = 0;
            while (k < 3 and j < s.len and s[j] >= '0' and s[j] <= '7') : (k += 1) {
                v = v * 8 + (s[j] - '0');
                j += 1;
            }
            pos.* = j;
            break :blk @truncate(v);
        },
        else => {
            pos.* = i + 1;
            return "\\";
        },
    };
    buf[0] = r;
    return buf[0..1];
}

fn printf(args: []const []const u8) Error!u8 {
    if (args.len == 0) {
        util.complain(&.{"printf: usage: printf format [arguments]"});
        return 2;
    }
    const fmt = args[0];
    var rest = args[1..];
    var status: u8 = 0;
    var o = mem.List(u8).init(&mem.str);
    while (true) {
        const before = rest.len;
        var i: usize = 0;
        while (i < fmt.len) {
            const c = fmt[i];
            if (c == '\\') {
                var b: [1]u8 = undefined;
                const s = escapeAt(fmt, &i, &b, false) orelse {
                    _ = try exec.out(o.items);
                    return status;
                };
                try o.appendSlice(s);
                continue;
            }
            if (c != '%') {
                try o.append(c);
                i += 1;
                continue;
            }
            i += 1;
            if (i < fmt.len and fmt[i] == '%') {
                try o.append('%');
                i += 1;
                continue;
            }
            // 指定: フラグ・幅・精度
            var left = false;
            var zero = false;
            var plus = false;
            var space = false;
            while (i < fmt.len and (fmt[i] == '-' or fmt[i] == '0' or fmt[i] == '+' or fmt[i] == ' ' or fmt[i] == '#')) : (i += 1) {
                switch (fmt[i]) {
                    '-' => left = true,
                    '0' => zero = true,
                    '+' => plus = true,
                    ' ' => space = true,
                    else => {},
                }
            }
            var width: usize = 0;
            if (i < fmt.len and fmt[i] == '*') {
                i += 1;
                if (rest.len > 0) {
                    width = @intCast(@max(0, util.parseSigned(rest[0]) orelse 0));
                    rest = rest[1..];
                }
            } else {
                while (i < fmt.len and util.isDigit(fmt[i])) : (i += 1) width = width * 10 + (fmt[i] - '0');
            }
            var precision: ?usize = null;
            if (i < fmt.len and fmt[i] == '.') {
                i += 1;
                var p: usize = 0;
                while (i < fmt.len and util.isDigit(fmt[i])) : (i += 1) p = p * 10 + (fmt[i] - '0');
                precision = p;
            }
            if (i >= fmt.len) break;
            const conv = fmt[i];
            i += 1;
            const arg: ?[]const u8 = if (rest.len > 0) rest[0] else null;
            if (rest.len > 0) rest = rest[1..];
            var piece = mem.List(u8).init(&mem.ast);
            var numeric = false;
            switch (conv) {
                's' => {
                    var s = arg orelse "";
                    if (precision) |p| s = s[0..@min(p, s.len)];
                    try piece.appendSlice(s);
                },
                'b' => {
                    const s = arg orelse "";
                    var k: usize = 0;
                    while (k < s.len) {
                        if (s[k] == '\\') {
                            var b: [1]u8 = undefined;
                            const e = escapeAt(s, &k, &b, true) orelse {
                                try pad(&o, piece.items, width, left, false);
                                _ = try exec.out(o.items);
                                return status;
                            };
                            try piece.appendSlice(e);
                        } else {
                            try piece.append(s[k]);
                            k += 1;
                        }
                    }
                },
                'c' => {
                    if (arg) |s| if (s.len > 0) try piece.append(s[0]);
                },
                'd', 'i', 'u', 'x', 'X', 'o' => {
                    numeric = true;
                    var v: i64 = 0;
                    if (arg) |s| {
                        if (s.len >= 2 and (s[0] == '\'' or s[0] == '"')) {
                            v = s[1];
                        } else {
                            v = util.parseSigned(s) orelse parseHexOrOct(s) orelse blk: {
                                util.complain(&.{ "printf: ", s, ": invalid number" });
                                status = 1;
                                break :blk 0;
                            };
                        }
                    }
                    var nb: [24]u8 = undefined;
                    if (conv == 'd' or conv == 'i') {
                        if (v >= 0 and plus) try piece.append('+') else if (v >= 0 and space) try piece.append(' ');
                        try piece.appendSlice(util.formatSigned(&nb, v));
                    } else {
                        const u: u64 = @bitCast(v);
                        const base: u64 = switch (conv) {
                            'o' => 8,
                            'u' => 10,
                            else => 16,
                        };
                        try piece.appendSlice(formatBase(&nb, u, base, conv == 'X'));
                    }
                },
                else => {
                    util.complain(&.{ "printf: unknown conversion: %", fmt[i - 1 .. i] });
                    _ = try exec.out(o.items);
                    return 1;
                },
            }
            try pad(&o, piece.items, width, left, zero and numeric and !left);
        }
        // 引数が残っていれば、書式を繰り返す
        if (rest.len == 0 or rest.len == before) break;
    }
    if (!(try exec.out(o.items))) return 1;
    return status;
}

fn parseHexOrOct(s: []const u8) ?i64 {
    if (s.len > 2 and s[0] == '0' and (s[1] == 'x' or s[1] == 'X')) {
        var v: i64 = 0;
        for (s[2..]) |c| {
            const d: i64 = if (util.isDigit(c)) c - '0' else if (c >= 'a' and c <= 'f') c - 'a' + 10 else if (c >= 'A' and c <= 'F') c - 'A' + 10 else return null;
            v = v *% 16 +% d;
        }
        return v;
    }
    return null;
}

fn formatBase(buf: []u8, value: u64, base: u64, upper: bool) []const u8 {
    const digits = if (upper) "0123456789ABCDEF" else "0123456789abcdef";
    var tmp: [24]u8 = undefined;
    var v = value;
    var i: usize = tmp.len;
    while (true) {
        i -= 1;
        tmp[i] = digits[@intCast(v % base)];
        v /= base;
        if (v == 0) break;
    }
    const n = tmp.len - i;
    @memcpy(buf[0..n], tmp[i..]);
    return buf[0..n];
}

fn pad(o: *mem.List(u8), s: []const u8, width: usize, left: bool, zero: bool) Error!void {
    const fill = if (width > s.len) width - s.len else 0;
    if (left) {
        try o.appendSlice(s);
        var k: usize = 0;
        while (k < fill) : (k += 1) try o.append(' ');
        return;
    }
    if (zero and s.len > 0 and (s[0] == '-' or s[0] == '+')) {
        try o.append(s[0]);
        var k: usize = 0;
        while (k < fill) : (k += 1) try o.append('0');
        try o.appendSlice(s[1..]);
        return;
    }
    var k: usize = 0;
    while (k < fill) : (k += 1) try o.append(if (zero) '0' else ' ');
    try o.appendSlice(s);
}

// ---- cd・pwd ----

fn cd(args: []const []const u8) Error!u8 {
    var target: []const u8 = undefined;
    var print = false;
    if (args.len == 0) {
        target = vars.get("HOME") orelse return complain2("cd", "HOME not set");
    } else if (eql(args[0], "-")) {
        target = vars.get("OLDPWD") orelse return complain2("cd", "OLDPWD not set");
        print = true;
    } else target = args[0];
    const old = try mem.str.dupe(fs.cwd());
    fs.chdir(target) catch |e| {
        util.complain(&.{ "cd: ", target, ": ", kibi.errno.describe(e) });
        return 1;
    };
    try vars.set("OLDPWD", old);
    try vars.set("PWD", fs.cwd());
    if (print) _ = try say(&.{ fs.cwd(), "\n" });
    return 0;
}

fn pwd() Error!u8 {
    if (!(try say(&.{ fs.cwd(), "\n" }))) return 1;
    return 0;
}

// ---- 変数 ----

fn exportBuiltin(args: []const []const u8, readonly: bool) Error!u8 {
    const cmd = if (readonly) "readonly" else "export";
    if (args.len == 0 or (args.len == 1 and eql(args[0], "-p"))) {
        for (vars.all()) |v| {
            if (readonly and !v.readonly) continue;
            if (!readonly and !v.exported) continue;
            var o = mem.List(u8).init(&mem.str);
            try o.appendSlice(cmd);
            try o.append(' ');
            try o.appendSlice(v.name);
            if (v.value) |val| {
                try o.append('=');
                try exec.quoteInto(&o, val);
            }
            try o.append('\n');
            if (!(try exec.out(o.items))) return 1;
        }
        return 0;
    }
    var status: u8 = 0;
    for (args) |a| {
        if (eql(a, "-p")) continue;
        const eq = util.indexOf(a, '=');
        const name = if (eq) |e| a[0..e] else a;
        if (!util.isName(name)) {
            util.complain(&.{ cmd, ": ", a, ": bad variable name" });
            status = 1;
            continue;
        }
        if (eq) |e| try vars.set(name, a[e + 1 ..]);
        if (readonly) try vars.setReadonly(name) else try vars.setExported(name, true);
    }
    return status;
}

fn unset(args: []const []const u8) Error!u8 {
    var functions = false;
    var rest = args;
    if (rest.len > 0 and (eql(rest[0], "-f") or eql(rest[0], "-v"))) {
        functions = eql(rest[0], "-f");
        rest = rest[1..];
    }
    for (rest) |name| {
        if (functions) {
            vars.unsetFunc(name);
        } else {
            try vars.unset(name);
        }
    }
    return 0;
}

fn local(args: []const []const u8) Error!u8 {
    if (state.func_depth == 0) return complain2("local", "not in a function");
    for (args) |a| {
        const eq = util.indexOf(a, '=');
        const name = if (eq) |e| a[0..e] else a;
        if (!util.isName(name)) {
            util.complain(&.{ "local: ", a, ": bad variable name" });
            return 1;
        }
        try vars.makeLocal(name, exec.local_frame);
        if (eq) |e| try vars.set(name, a[e + 1 ..]);
    }
    return 0;
}

const options = [_]struct { c: u8, name: []const u8, flag: *bool }{
    .{ .c = 'e', .name = "errexit", .flag = &state.errexit },
    .{ .c = 'u', .name = "nounset", .flag = &state.nounset },
    .{ .c = 'x', .name = "xtrace", .flag = &state.xtrace },
    .{ .c = 'f', .name = "noglob", .flag = &state.noglob },
    .{ .c = 'C', .name = "noclobber", .flag = &state.noclobber },
    .{ .c = 'v', .name = "verbose", .flag = &state.verbose },
};

/// オプションの文字 [c] を [on] にする。知らなければ false
pub fn setOption(c: u8, on: bool) bool {
    for (options) |o| {
        if (o.c == c) {
            o.flag.* = on;
            return true;
        }
    }
    return false;
}

fn set(args: []const []const u8) Error!u8 {
    if (args.len == 0) {
        for (vars.all()) |v| {
            const val = v.value orelse continue;
            var o = mem.List(u8).init(&mem.str);
            try o.appendSlice(v.name);
            try o.append('=');
            try exec.quoteInto(&o, val);
            try o.append('\n');
            if (!(try exec.out(o.items))) return 1;
        }
        return 0;
    }
    var i: usize = 0;
    while (i < args.len) : (i += 1) {
        const a = args[i];
        if (eql(a, "--")) {
            i += 1;
            break;
        }
        if (a.len < 2 or (a[0] != '-' and a[0] != '+')) break;
        const on = a[0] == '-';
        if (a[1] == 'o') {
            if (i + 1 >= args.len) {
                for (options) |o| {
                    _ = try say(&.{ o.name, if (o.flag.*) "\ton\n" else "\toff\n" });
                }
                return 0;
            }
            i += 1;
            var found = false;
            for (options) |o| {
                if (eql(o.name, args[i])) {
                    o.flag.* = on;
                    found = true;
                }
            }
            if (!found) {
                util.complain(&.{ "set: ", args[i], ": unknown option" });
                return 2;
            }
            continue;
        }
        for (a[1..]) |c| {
            if (!setOption(c, on)) {
                util.complain(&.{ "set: ", a, ": unknown option" });
                return 2;
            }
        }
    }
    if (i < args.len or (args.len > 0 and eql(args[args.len - 1], "--"))) {
        const old = vars.params;
        try vars.setParams(args[i..]);
        vars.freeParams(old);
    }
    return 0;
}

fn shift(args: []const []const u8) Error!u8 {
    var n: usize = 1;
    if (args.len > 0) {
        const v = io.parseUnsigned(args[0]) orelse return complain2("shift", "numeric argument required");
        n = @intCast(v);
    }
    if (n > vars.params.len) return complain2("shift", "can't shift that many");
    try vars.shiftParams(n);
    return 0;
}

// ---- 流れ ----

fn exit(args: []const []const u8) Error!u8 {
    var code = state.status;
    if (args.len > 0) {
        const v = util.parseSigned(args[0]) orelse {
            util.complain(&.{ "exit: ", args[0], ": numeric argument required" });
            process.exit(2);
        };
        code = @truncate(@as(u64, @bitCast(v)));
    }
    process.exit(code);
}

fn returnBuiltin(args: []const []const u8) Error!u8 {
    if (state.func_depth == 0 and state.dot_depth == 0) return complain2("return", "can only return from a function or sourced script");
    var code = state.status;
    if (args.len > 0) {
        const v = util.parseSigned(args[0]) orelse return complain2("return", "numeric argument required");
        code = @truncate(@as(u64, @bitCast(v)));
    }
    state.flow = .return_;
    return code;
}

fn loopControl(args: []const []const u8, which: state.Flow) Error!u8 {
    var n: u32 = 1;
    if (args.len > 0) {
        const v = io.parseUnsigned(args[0]) orelse 0;
        if (v == 0) return complain2(if (which == .break_) "break" else "continue", "bad loop count");
        n = @intCast(@min(v, 1000));
    }
    if (state.loop_depth == 0) return 0;
    state.flow = which;
    state.flow_levels = @min(n, state.loop_depth);
    return 0;
}

fn eval(args: []const []const u8) Error!u8 {
    var o = mem.List(u8).init(&mem.ast);
    for (args, 0..) |a, i| {
        if (i > 0) try o.append(' ');
        try o.appendSlice(a);
    }
    state.status = 0;
    try exec.runText(o.items, "eval", 1);
    return state.status;
}

/// `.` で読めるファイルの大きさの上限（残る領域の半分）
const MAX_DOT_SIZE = mem.HEAP_SIZE / 2;

fn dot(args: []const []const u8) Error!u8 {
    if (args.len == 0) {
        util.complain(&.{".: filename argument required"});
        return 2;
    }
    var buf: [fs.MAX_PATH]u8 = undefined;
    const path = exec.findProgram(args[0], &buf) orelse args[0];
    const fd = fs.open(path, .{}) catch |e| {
        util.complain(&.{ ".: ", args[0], ": ", kibi.errno.describe(e) });
        return 1;
    };
    const st = fs.fstat(fd) catch |e| {
        io.close(fd) catch {};
        util.complain(&.{ ".: ", args[0], ": ", kibi.errno.describe(e) });
        return 1;
    };
    // 読み込み先は変数・関数と同じ残る領域なので、その半分までにする
    if (st.size > MAX_DOT_SIZE) {
        io.close(fd) catch {};
        return complain2(".", "file too large");
    }
    if (st.size == 0) {
        // 空のファイルは何もしない（長さ 0 の区画は返せないので取らない）
        io.close(fd) catch {};
        state.status = 0;
        return 0;
    }
    const text = mem.heapAlloc(@intCast(st.size)) catch {
        io.close(fd) catch {};
        util.complain(&.{ ".: ", args[0], ": not enough memory to read the file" });
        return 1;
    };
    defer mem.heapFree(text);
    const n = io.readAll(fd, text) catch 0;
    io.close(fd) catch {};

    const old_params = vars.params;
    const has_args = args.len > 1;
    if (has_args) try vars.setParams(args[1..]);
    defer if (has_args) {
        vars.freeParams(vars.params);
        vars.params = old_params;
    };
    state.dot_depth += 1;
    defer state.dot_depth -= 1;
    state.status = 0;
    try exec.runText(text[0..n], args[0], 1);
    if (state.flow == .return_) state.flow = .none;
    return state.status;
}

fn wait(args: []const []const u8) Error!u8 {
    var pids = mem.List(u32).init(&mem.ast);
    for (args) |a| {
        const v = io.parseUnsigned(a) orelse {
            util.complain(&.{ "wait: ", a, ": not a pid" });
            return 2;
        };
        try pids.append(@intCast(v & 0xffff_ffff));
    }
    return exec.waitJobs(pids.items);
}

fn commandV(args: []const []const u8) Error!u8 {
    // `command -v name...`（`command name` は exec.zig が扱う）
    var status: u8 = 0;
    const verbose = args.len > 0 and eql(args[0], "-V");
    for (args[1..]) |name| {
        if (!(try describe(name, verbose))) status = 1;
    }
    return status;
}

fn typeBuiltin(args: []const []const u8) Error!u8 {
    var status: u8 = 0;
    for (args) |name| {
        if (!(try describe(name, true))) status = 1;
    }
    return status;
}

/// コマンド [name] が何かを書く。見つからなければ false
fn describe(name: []const u8, verbose: bool) Error!bool {
    if (vars.findFunc(name) != null) {
        _ = try say(if (verbose) &.{ name, " is a function\n" } else &.{ name, "\n" });
        return true;
    }
    if (kindOf(name) != .none) {
        _ = try say(if (verbose) &.{ name, " is a shell builtin\n" } else &.{ name, "\n" });
        return true;
    }
    var buf: [fs.MAX_PATH]u8 = undefined;
    if (exec.findProgram(name, &buf)) |p| {
        if (util.indexOf(name, '/') != null) {
            _ = fs.stat(p) catch {
                if (verbose) util.complain(&.{ name, ": not found" });
                return false;
            };
        }
        _ = try say(if (verbose) &.{ name, " is ", p, "\n" } else &.{ p, "\n" });
        return true;
    }
    if (verbose) util.complain(&.{ name, ": not found" });
    return false;
}

// ---- history（#102） ----

fn historyBuiltin(args: []const []const u8) Error!u8 {
    if (args.len > 0) {
        if (eql(args[0], "-c") and args.len == 1) {
            history.clear();
            return 0;
        }
        util.complain(&.{ "history: ", args[0], ": unknown option (usage: history [-c])" });
        return 2;
    }
    var i: usize = 0;
    while (i < history.len()) : (i += 1) {
        var nb: [24]u8 = undefined;
        const num = io.formatUnsigned(&nb, history.number(i));
        var spaces: [5]u8 = @splat(' ');
        const p = if (num.len < spaces.len) spaces[0 .. spaces.len - num.len] else spaces[0..0];
        if (!(try say(&.{ p, num, "  ", history.get(i), "\n" }))) return 1;
    }
    return 0;
}

// ---- read ----

fn read(args: []const []const u8) Error!u8 {
    var raw = false;
    var names = args;
    while (names.len > 0 and names[0].len > 1 and names[0][0] == '-') {
        if (eql(names[0], "-r")) {
            raw = true;
        } else if (eql(names[0], "--")) {
            names = names[1..];
            break;
        } else {
            util.complain(&.{ "read: ", names[0], ": unknown option" });
            return 2;
        }
        names = names[1..];
    }
    for (names) |n| if (!util.isName(n)) {
        util.complain(&.{ "read: ", n, ": bad variable name" });
        return 2;
    };
    if (names.len == 0) names = &.{"REPLY"};

    // 1 バイトずつ読む（後のコマンドの分を読みすぎない）。引用された文字は [quoted] に印
    var line = mem.List(u8).init(&mem.str);
    var quoted = mem.List(bool).init(&mem.ast);
    var got_newline = false;
    var any = false;
    while (true) {
        var c: [1]u8 = undefined;
        const n = io.read(io.STDIN, &c) catch 0;
        if (n == 0) break;
        any = true;
        if (c[0] == '\n') {
            got_newline = true;
            break;
        }
        if (c[0] == '\\' and !raw) {
            const n2 = io.read(io.STDIN, &c) catch 0;
            if (n2 == 0) break;
            if (c[0] == '\n') continue;
            try line.append(c[0]);
            try quoted.append(true);
            continue;
        }
        try line.append(c[0]);
        try quoted.append(false);
    }
    const ifs = vars.get("IFS") orelse " \t\n";
    const s = line.items;
    const q = quoted.items;
    var i: usize = 0;
    const isIfs = struct {
        fn f(set_: []const u8, c: u8) bool {
            return util.indexOf(set_, c) != null;
        }
    }.f;
    const isWs = struct {
        fn f(c: u8) bool {
            return c == ' ' or c == '\t' or c == '\n';
        }
    }.f;
    // 頭の空白
    while (i < s.len and !q[i] and isIfs(ifs, s[i]) and isWs(s[i])) i += 1;
    for (names, 0..) |name, k| {
        if (k == names.len - 1) {
            // 残り全部（後ろの IFS の空白を落とす）
            var end = s.len;
            while (end > i and !q[end - 1] and isIfs(ifs, s[end - 1]) and isWs(s[end - 1])) end -= 1;
            try vars.set(name, s[@min(i, end)..end]);
            break;
        }
        const start = i;
        while (i < s.len and (q[i] or !isIfs(ifs, s[i]))) i += 1;
        try vars.set(name, s[start..i]);
        // 区切り: 空白、1 つの空白でない区切り、空白
        while (i < s.len and !q[i] and isIfs(ifs, s[i]) and isWs(s[i])) i += 1;
        if (i < s.len and !q[i] and isIfs(ifs, s[i]) and !isWs(s[i])) i += 1;
        while (i < s.len and !q[i] and isIfs(ifs, s[i]) and isWs(s[i])) i += 1;
    }
    if (!got_newline) return if (any) 1 else 1;
    return 0;
}

// ---- test・[ ----

const Test = struct {
    args: []const []const u8,
    i: usize = 0,
    err: bool = false,

    fn peek(self: *Test) ?[]const u8 {
        return if (self.i < self.args.len) self.args[self.i] else null;
    }

    fn next(self: *Test) ?[]const u8 {
        const a = self.peek() orelse return null;
        self.i += 1;
        return a;
    }

    fn orExpr(self: *Test) bool {
        var v = self.andExpr();
        while (self.peek()) |a| {
            if (!eql(a, "-o")) break;
            self.i += 1;
            const r = self.andExpr();
            v = v or r;
        }
        return v;
    }

    fn andExpr(self: *Test) bool {
        var v = self.notExpr();
        while (self.peek()) |a| {
            if (!eql(a, "-a")) break;
            self.i += 1;
            const r = self.notExpr();
            v = v and r;
        }
        return v;
    }

    fn notExpr(self: *Test) bool {
        if (self.peek()) |a| {
            // `! x`（ただし `! = x` のような 2 項の左は除く）
            if (eql(a, "!") and !(self.i + 1 < self.args.len and isBinary(self.args[self.i + 1]) and self.i + 2 < self.args.len)) {
                self.i += 1;
                return !self.notExpr();
            }
        }
        return self.primary();
    }

    fn primary(self: *Test) bool {
        const a = self.next() orelse {
            self.err = true;
            return false;
        };
        if (eql(a, "(") and !(self.i < self.args.len and isBinary(self.args[self.i]) and self.i + 1 < self.args.len)) {
            const v = self.orExpr();
            const close = self.next();
            if (close == null or !eql(close.?, ")")) self.err = true;
            return v;
        }
        // 2 項
        if (self.peek()) |op| {
            if (isBinary(op) and self.i + 1 < self.args.len) {
                self.i += 1;
                const b = self.next().?;
                return self.binary(a, op, b);
            }
        }
        // 1 項
        if (a.len == 2 and a[0] == '-' and isUnary(a[1])) {
            if (self.next()) |operand| return self.unary(a[1], operand);
            // 引数がなければ、ただの文字列
            return true;
        }
        return a.len > 0;
    }

    fn isUnary(c: u8) bool {
        return util.indexOf("bcdefghLnprsStuwxz", c) != null;
    }

    fn isBinary(op: []const u8) bool {
        const ops = [_][]const u8{ "=", "!=", "==", "-eq", "-ne", "-lt", "-le", "-gt", "-ge", "-nt", "-ot", "-ef", "<", ">" };
        for (ops) |o| if (eql(o, op)) return true;
        return false;
    }

    fn unary(self: *Test, c: u8, s: []const u8) bool {
        switch (c) {
            'n' => return s.len > 0,
            'z' => return s.len == 0,
            't' => {
                const fd = io.parseUnsigned(s) orelse {
                    self.err = true;
                    return false;
                };
                return kibi.tty.isTty(@intCast(fd & 0xffff_ffff));
            },
            else => {},
        }
        const st = fs.stat(s) catch return false;
        return switch (c) {
            'e' => true,
            'f' => st.kind == .file,
            'd' => st.kind == .directory,
            'h', 'L' => st.kind == .symlink,
            'c', 'b' => st.kind == .device,
            's' => st.size > 0,
            // 持ち主・権限はまだない（#110）。あれば読める・書ける・動かせるとする
            'r', 'w', 'x' => true,
            else => false,
        };
    }

    fn binary(self: *Test, a: []const u8, op: []const u8, b: []const u8) bool {
        if (eql(op, "=") or eql(op, "==")) return eql(a, b);
        if (eql(op, "!=")) return !eql(a, b);
        if (eql(op, "<")) return lessThan(a, b);
        if (eql(op, ">")) return lessThan(b, a);
        if (eql(op, "-nt") or eql(op, "-ot") or eql(op, "-ef")) {
            const sa = fs.stat(a) catch return false;
            const sb = fs.stat(b) catch return false;
            if (eql(op, "-nt")) return sa.mtime > sb.mtime;
            if (eql(op, "-ot")) return sa.mtime < sb.mtime;
            return sa.ino == sb.ino;
        }
        const x = util.parseSigned(a) orelse {
            util.complain(&.{ "test: ", a, ": integer expression expected" });
            self.err = true;
            return false;
        };
        const y = util.parseSigned(b) orelse {
            util.complain(&.{ "test: ", b, ": integer expression expected" });
            self.err = true;
            return false;
        };
        if (eql(op, "-eq")) return x == y;
        if (eql(op, "-ne")) return x != y;
        if (eql(op, "-lt")) return x < y;
        if (eql(op, "-le")) return x <= y;
        if (eql(op, "-gt")) return x > y;
        return x >= y;
    }
};

fn lessThan(a: []const u8, b: []const u8) bool {
    const n = @min(a.len, b.len);
    for (a[0..n], b[0..n]) |x, y| if (x != y) return x < y;
    return a.len < b.len;
}

fn testBuiltin(args0: []const []const u8, bracket: bool) Error!u8 {
    var args = args0;
    if (bracket) {
        if (args.len == 0 or !eql(args[args.len - 1], "]")) {
            util.complain(&.{"[: missing ']'"});
            return 2;
        }
        args = args[0 .. args.len - 1];
    }
    if (args.len == 0) return 1;
    var t = Test{ .args = args };
    const v = t.orExpr();
    if (t.err or t.i != args.len) {
        if (!t.err) util.complain(&.{ "test: ", args[t.i], ": unexpected argument" });
        return 2;
    }
    return if (v) 0 else 1;
}
