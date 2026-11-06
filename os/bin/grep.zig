//! `grep [-EFcHhilnqsvx] [-e パターン]... [パターン] [ファイル...]`: パターンに合う行を書く。
//!
//! - `-E` 拡張正規表現、`-F` ただの文字列（正規表現で読めるものは `grep/regex.zig`）
//! - `-i` 大文字と小文字を区別しない、`-v` 合わない行、`-x` 行全体が合う行
//! - `-c` 行の数だけ、`-l` 合ったファイルの名前だけ、`-q` 何も書かない、`-n` 行の番号を付ける
//! - `-H`・`-h` ファイルの名前を付ける・付けない（既定はファイルが 2 つ以上なら付ける）、`-s` 読めないファイルを知らせない
//!
//! 終了コードは、合う行があれば 0、なければ 1、エラーがあれば 2（`-q` で合っていれば 0）。
//! 1 行は [LINE_MAX] バイトまで（長い行はそこで切って、続きを別の行として見る）
const kibi = @import("kibi");
const cli = kibi.cli;
const regex = @import("grep/regex.zig");

const SYNOPSIS = "grep [-EFcHhilnqsvx] [-e pattern]... [pattern] [file...]";

const LINE_MAX = 64 * 1024;
const MAX_PATTERNS = 16;

var line_buf: [LINE_MAX]u8 = undefined;
var patterns: [MAX_PATTERNS][]const u8 = undefined;
var compiled: [MAX_PATTERNS]regex.Regex = undefined;
var npatterns: usize = 0;

var extended = false;
var fixed = false;
var icase = false;
var invert = false;
var whole = false;
var count_only = false;
var list_only = false;
var quiet = false;
var number = false;
var silent = false;
var with_name: ?bool = null;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 2;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'E' => extended = true,
        'F' => fixed = true,
        'G' => extended = false,
        'i' => icase = true,
        'v' => invert = true,
        'x' => whole = true,
        'c' => count_only = true,
        'l' => list_only = true,
        'q' => quiet = true,
        'n' => number = true,
        's' => silent = true,
        'H' => with_name = true,
        'h' => with_name = false,
        'e' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- e", SYNOPSIS);
            if (!addPattern(v)) return cli.usage("too many patterns", SYNOPSIS);
        },
        else => return cli.badOption(c, SYNOPSIS),
    };
    var rest = opts.rest();
    if (npatterns == 0) {
        if (rest.len == 0) return cli.usage("missing pattern", SYNOPSIS);
        // 改行で区切った複数のパターンも受け付ける
        if (!addPattern(rest[0])) return cli.usage("too many patterns", SYNOPSIS);
        rest = rest[1..];
    }
    if (!fixed) {
        for (patterns[0..npatterns], 0..) |p, i| {
            compiled[i].compile(p, extended, icase) catch |e| {
                cli.warn(p, switch (e) {
                    error.Unsupported => "groups, alternation, intervals and back-references are not supported yet",
                    error.BadPattern => "invalid regular expression",
                    error.TooLong => "regular expression too long",
                });
                return 2;
            };
            if (whole) {
                compiled[i].bol = true;
                compiled[i].eol = true;
            }
        }
    }
    const files: []const []const u8 = if (rest.len == 0) &.{"-"} else rest;
    const show_name = with_name orelse (files.len > 1);
    var matched = false;
    var failed = false;
    for (files) |f| {
        const r = grepFile(f, show_name);
        if (r == null) {
            failed = true;
            continue;
        }
        if (r.? > 0) matched = true;
        if (quiet and matched) break;
        if (cli.stdout.failed) break;
    }
    cli.stdout.flush();
    if (quiet and matched) return 0;
    if (failed) return 2;
    return if (matched) 0 else 1;
}

fn addPattern(v: []const u8) bool {
    var start: usize = 0;
    var i: usize = 0;
    while (i <= v.len) : (i += 1) {
        if (i < v.len and v[i] != '\n') continue;
        if (npatterns == MAX_PATTERNS) return false;
        patterns[npatterns] = v[start..i];
        npatterns += 1;
        start = i + 1;
    }
    return true;
}

/// 合った行の数。読めなければ null
fn grepFile(path: []const u8, show_name: bool) ?u64 {
    const name = if (cli.eql(path, "-")) "(standard input)" else path;
    const fd = cli.openInput(path) catch |e| {
        if (!silent) cli.warnErr(path, e);
        return null;
    };
    defer cli.closeInput(fd);
    var reader = cli.LineReader.init(fd, &line_buf);
    var count: u64 = 0;
    var lineno: u64 = 0;
    while (true) {
        const line = (reader.next() catch |e| {
            if (!silent) cli.warnErr(path, e);
            return null;
        }) orelse break;
        lineno += 1;
        if (selected(line.text) == invert) continue;
        count += 1;
        if (quiet) return count;
        if (list_only) {
            cli.stdout.write(name);
            cli.stdout.byte('\n');
            return count;
        }
        if (count_only) continue;
        if (show_name) {
            cli.stdout.write(name);
            cli.stdout.byte(':');
        }
        if (number) {
            cli.stdout.uint(lineno, 0);
            cli.stdout.byte(':');
        }
        cli.stdout.write(line.text);
        cli.stdout.byte('\n');
        if (cli.stdout.failed) return count;
    }
    if (count_only) {
        if (show_name) {
            cli.stdout.write(name);
            cli.stdout.byte(':');
        }
        cli.stdout.uint(count, 0);
        cli.stdout.byte('\n');
    }
    return count;
}

/// どれかのパターンに合うか
fn selected(text: []const u8) bool {
    for (0..npatterns) |i| {
        if (fixed) {
            if (fixedMatch(patterns[i], text)) return true;
        } else if (compiled[i].search(text)) return true;
    }
    return false;
}

fn fixedMatch(p: []const u8, text: []const u8) bool {
    if (whole) return p.len == text.len and same(p, text);
    if (p.len > text.len) return false;
    var s: usize = 0;
    while (s + p.len <= text.len) : (s += 1) {
        if (same(p, text[s..][0..p.len])) return true;
    }
    return false;
}

fn same(a: []const u8, b: []const u8) bool {
    for (a, b) |x, y| {
        if (x != y and !(icase and regex.lower(x) == regex.lower(y))) return false;
    }
    return true;
}
