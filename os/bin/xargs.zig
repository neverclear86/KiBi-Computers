//! `xargs [-0rt] [-n 数] [-I 置き換え] [コマンド [引数...]]`: 標準入力から読んだ語を引数に足して、コマンド（既定は `echo`）を動かす。
//!
//! - 語は空白・改行で区切る。`'...'`・`"..."` で囲むと空白を含められ、`\` は次の 1 文字をそのまま入れる（改行は囲みの中に書けない）
//! - `-0`: 語を NUL で区切る（引用・`\` は扱わない）。`find -print0` 向け
//! - `-n 数`: 1 回に渡す語の数の上限。`-I 置き換え`: 1 行を 1 つの語にし（先頭の空白は除く）、引数の中の「置き換え」をその語にして 1 行ごとに動かす
//! - `-r`: 語が 1 つもなければ動かさない（既定は 1 回動かす）。`-t`: 動かす前にコマンドの行を標準エラー出力に書く
//! - 1 回の引数は [MAX_ARGS_BYTES] バイト・`cli.MAX_RUN_ARGS` 個まで（超える分は次の回に回す）。1 つの語は [MAX_WORD] バイトまで
//! - 子の標準入力は閉じる（xargs が読む入力を子が横取りしない）
//! - 終了コード: 全部 0 なら 0、どれかが 1〜125 で終わったら 123。255 で終わった・シグナルで止まったら、そこでやめて 124・125。
//!   見つからない 127、起こせない 126（そこでやめる）
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;
const process = kibi.process;

const SYNOPSIS = "xargs [-0rt] [-n max-args] [-I replace] [command [arg...]]";

/// 1 回に渡す引数（コマンドと固定の引数を含む）の合計の上限（バイト。カーネルの上限 64 KiB の半分。環境変数の分を残す）
const MAX_ARGS_BYTES = 32 * 1024;
/// 1 つの語の上限
const MAX_WORD = 8 * 1024;

var in_buf: [4096]u8 = undefined;
var in_start: usize = 0;
var in_end: usize = 0;
var in_eof = false;

/// 読んだ語（この回の分）
var words_buf: [MAX_ARGS_BYTES]u8 = undefined;
var words_len: usize = 0;
var word_starts: [cli.MAX_RUN_ARGS]usize = undefined;
var word_lens: [cli.MAX_RUN_ARGS]usize = undefined;
var word_count: usize = 0;
var word: [MAX_WORD]u8 = undefined;

var run_argv: [process.MAX_SPAWN_ARGS][]const u8 = undefined;
var repl_buf: [MAX_ARGS_BYTES]u8 = undefined;
var path_buf: [kibi.fs.MAX_PATH]u8 = undefined;

var trace = false;
var status: u8 = 0;

const Mode = enum { words, nul, lines };

pub fn main() u8 {
    const argv = process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var mode: Mode = .words;
    var max_per: usize = cli.MAX_RUN_ARGS;
    var replace: ?[]const u8 = null;
    var no_empty = false;
    while (opts.next()) |c| switch (c) {
        '0' => mode = .nul,
        'r' => no_empty = true,
        't' => trace = true,
        'n' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- n", SYNOPSIS);
            const n = io.parseUnsigned(v) orelse 0;
            if (n == 0) return cli.usage("invalid number for -n", SYNOPSIS);
            max_per = @intCast(@min(n, cli.MAX_RUN_ARGS));
        },
        'I' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- I", SYNOPSIS);
            if (v.len == 0) return cli.usage("empty replace string", SYNOPSIS);
            replace = v;
        },
        else => return cli.badOption(c, SYNOPSIS),
    };
    if (replace != null) {
        if (mode == .words) mode = .lines;
        max_per = 1;
    }
    const rest = opts.rest();
    const fixed: []const []const u8 = if (rest.len == 0) &.{"echo"} else rest;
    if (fixed.len + 1 > cli.MAX_RUN_ARGS) {
        cli.warnErr(null, error.TOOBIG);
        return 1;
    }
    var fixed_bytes: usize = 0;
    for (fixed) |a| fixed_bytes += a.len + 1;
    if (fixed_bytes >= MAX_ARGS_BYTES) {
        cli.warnErr(null, error.TOOBIG);
        return 1;
    }
    const path = cli.findProgram(&path_buf, fixed[0], process.getenv("PATH") orelse cli.DEFAULT_PATH) orelse {
        cli.warnErr(fixed[0], error.NOENT);
        return 127;
    };
    const room_count = cli.MAX_RUN_ARGS - fixed.len;
    const room_bytes = MAX_ARGS_BYTES - fixed_bytes;
    var ran = false;
    while (true) {
        const w = nextWord(mode) catch |e| {
            cli.warnErr("standard input", e);
            return 1;
        } orelse break;
        if (w.len + 1 > room_bytes) {
            cli.warn(null, "argument too long");
            return 1;
        }
        if (word_count == room_count or word_count == max_per or words_len + w.len + 1 > room_bytes) {
            if (!runBatch(path, fixed, replace)) return status;
            ran = true;
        }
        word_starts[word_count] = words_len;
        word_lens[word_count] = w.len;
        @memcpy(words_buf[words_len..][0..w.len], w);
        words_len += w.len + 1;
        word_count += 1;
    }
    if (word_count > 0 or (!ran and !no_empty and replace == null)) {
        if (!runBatch(path, fixed, replace)) return status;
    }
    return status;
}

/// 今の語で 1 回動かす。続けてよければ true
fn runBatch(path: []const u8, fixed: []const []const u8, replace: ?[]const u8) bool {
    defer {
        word_count = 0;
        words_len = 0;
    }
    var n: usize = 0;
    if (replace) |r| {
        // 語は 1 つ（なければ置き換えない）
        const item = if (word_count > 0) wordAt(0) else "";
        var used: usize = 0;
        for (fixed) |a| {
            const s = substitute(repl_buf[used..], a, r, item) orelse {
                cli.warn(null, "argument too long");
                status = 1;
                return false;
            };
            run_argv[n] = s;
            n += 1;
            used += s.len;
        }
    } else {
        for (fixed) |a| {
            run_argv[n] = a;
            n += 1;
        }
        for (0..word_count) |i| {
            run_argv[n] = wordAt(i);
            n += 1;
        }
    }
    const args = run_argv[0..n];
    if (trace) {
        for (args, 0..) |a, i| {
            if (i > 0) io.err(" ");
            io.err(a);
        }
        io.err("\n");
    }
    const fds = [_]process.FdAssign{process.FdAssign.closed(0)};
    const s = cli.run(path, args, .{ .fds = &fds }) catch |e| {
        cli.warnErr(args[0], e);
        status = cli.spawnFailureCode(e);
        return false;
    };
    switch (s) {
        .exited => |c| {
            if (c == 255) {
                cli.warn(args[0], "exited with status 255; aborting");
                status = 124;
                return false;
            }
            if (c != 0) status = 123;
        },
        .killed, .crashed => {
            cli.warn(args[0], "terminated by signal");
            status = 125;
            return false;
        },
    }
    return true;
}

fn wordAt(i: usize) []const u8 {
    return words_buf[word_starts[i]..][0..word_lens[i]];
}

/// [arg] の中の [r] を全部 [item] にして [buf] に書く。入らなければ null
fn substitute(buf: []u8, arg: []const u8, r: []const u8, item: []const u8) ?[]const u8 {
    var n: usize = 0;
    var i: usize = 0;
    while (i < arg.len) {
        if (arg.len - i >= r.len and cli.eql(arg[i..][0..r.len], r)) {
            if (n + item.len > buf.len) return null;
            @memcpy(buf[n..][0..item.len], item);
            n += item.len;
            i += r.len;
        } else {
            if (n + 1 > buf.len) return null;
            buf[n] = arg[i];
            n += 1;
            i += 1;
        }
    }
    return buf[0..n];
}

// ---- 入力 ----

fn nextByte() kibi.Error!?u8 {
    if (in_start == in_end) {
        if (in_eof) return null;
        in_end = try io.read(io.STDIN, &in_buf);
        in_start = 0;
        if (in_end == 0) {
            in_eof = true;
            return null;
        }
    }
    const c = in_buf[in_start];
    in_start += 1;
    return c;
}

const WordError = kibi.Error || error{ Unmatched, TooLong };

fn put(n: *usize, c: u8) error{TooLong}!void {
    if (n.* == word.len) return error.TooLong;
    word[n.*] = c;
    n.* += 1;
}

/// 次の語。もうなければ null
fn nextWord(mode: Mode) kibi.Error!?[]const u8 {
    return readWord(mode) catch |e| switch (e) {
        error.Unmatched => {
            cli.warn(null, "unmatched quote");
            kibi.process.exit(1);
        },
        error.TooLong => {
            cli.warn(null, "argument too long");
            kibi.process.exit(1);
        },
        else => |x| return x,
    };
}

fn isBlank(c: u8) bool {
    return c == ' ' or c == '\t';
}

fn readWord(mode: Mode) WordError!?[]const u8 {
    var n: usize = 0;
    switch (mode) {
        .nul => {
            var any = false;
            while (try nextByte()) |c| {
                any = true;
                if (c == 0) return word[0..n];
                try put(&n, c);
            }
            return if (any) word[0..n] else null;
        },
        .lines => {
            // 空の行は飛ばす
            while (true) {
                var c = (try nextByte()) orelse return null;
                while (isBlank(c)) c = (try nextByte()) orelse return null;
                if (c == '\n') continue;
                try put(&n, c);
                while (try nextByte()) |d| {
                    if (d == '\n') break;
                    try put(&n, d);
                }
                return word[0..n];
            }
        },
        .words => {
            var c: u8 = undefined;
            while (true) {
                c = (try nextByte()) orelse return null;
                if (!isBlank(c) and c != '\n') break;
            }
            while (true) {
                switch (c) {
                    ' ', '\t', '\n' => return word[0..n],
                    '\'', '"' => {
                        const q = c;
                        while (true) {
                            const d = (try nextByte()) orelse return error.Unmatched;
                            if (d == '\n') return error.Unmatched;
                            if (d == q) break;
                            try put(&n, d);
                        }
                    },
                    '\\' => {
                        const d = (try nextByte()) orelse return word[0..n];
                        try put(&n, d);
                    },
                    else => try put(&n, c),
                }
                c = (try nextByte()) orelse return word[0..n];
            }
        },
    }
}
