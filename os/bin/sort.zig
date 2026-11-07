//! `sort [-bfnru] [-k 欄[,欄]] [-t 文字] [-o ファイル] [ファイル...]`: 行を並べて書く（`-` は標準入力）。
//!
//! - 既定はバイトの辞書順。`-n` は先頭の数（`-`・小数を含む。数でなければ 0）の順、`-f` は英字の大小を区別しない、`-r` は逆順、
//!   `-b` は鍵の先頭の空白を除く。鍵が同じなら、行全体をバイト順に比べる（`-u` のときを除く）
//! - `-u`: 鍵が同じ行は最初の 1 行だけ書く
//! - `-k N[,M]`: N 番目から M 番目（既定は行の終わり）の欄を鍵にする（1 から。文字の位置 `N.C` と、鍵ごとの修飾はない）。
//!   欄の区切りは `-t` の文字、なければ空白の並び（このとき欄は前の空白を含めない）
//! - `-o`: 全部読んでから書くので、入力と同じファイルでもよい
//! - 入力は全部メモリに読む（合わせて [DATA_SIZE] バイト・[MAX_LINES] 行まで）。読むたびに RAM を数え、足りなければ知らせて 1
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;
const fs = kibi.fs;

const SYNOPSIS = "sort [-bfnru] [-k field[,field]] [-t char] [-o file] [file...]";

/// 読み込む入力の合計の上限（バイト）
const DATA_SIZE = 4 * 1024 * 1024;
/// 行の数の上限
const MAX_LINES = 256 * 1024;
/// RAM を数える単位
const CHUNK = 64 * 1024;

const Line = struct { start: u32, len: u32 };

var data: [DATA_SIZE]u8 = undefined;
var data_len: usize = 0;
var data_committed: usize = 0;
var lines: [MAX_LINES]Line = undefined;
var tmp: [MAX_LINES]Line = undefined;
var lines_committed: usize = 0;
var out_buf: [4096]u8 = undefined;

var numeric = false;
var fold = false;
var reverse = false;
var blanks = false;
var unique = false;
var key_first: usize = 0;
var key_last: usize = 0;
var separator: ?u8 = null;

const Full = error{ OutOfMemory, TooBig };

fn fullMessage(e: Full) []const u8 {
    return if (e == error.OutOfMemory) "out of memory" else "input too large";
}

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var output: ?[]const u8 = null;
    while (opts.next()) |c| switch (c) {
        'b' => blanks = true,
        'f' => fold = true,
        'n' => numeric = true,
        'r' => reverse = true,
        'u' => unique = true,
        'k' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- k", SYNOPSIS);
            if (!parseKey(v)) return cli.usage("invalid key (use -k N[,M])", SYNOPSIS);
        },
        't' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- t", SYNOPSIS);
            if (v.len != 1) return cli.usage("the separator must be one character", SYNOPSIS);
            separator = v[0];
        },
        'o' => output = opts.value() orelse return cli.usage("option requires an argument -- o", SYNOPSIS),
        else => return cli.badOption(c, SYNOPSIS),
    };
    const files = opts.rest();
    const names: []const []const u8 = if (files.len == 0) &.{"-"} else files;
    var status: u8 = 0;
    for (names) |f| {
        const fd = cli.openInput(f) catch |e| {
            cli.warnErr(f, e);
            status = 1;
            continue;
        };
        defer cli.closeInput(fd);
        readAll(fd) catch |e| switch (e) {
            error.OutOfMemory, error.TooBig => |x| {
                cli.warn(f, fullMessage(x));
                return 1;
            },
            else => |x| {
                cli.warnErr(f, x);
                status = 1;
            },
        };
        // ファイルの最後の行に改行がなければ足す
        if (data_len > 0 and data[data_len - 1] != '\n') {
            ensureData(data_len + 1) catch |e| {
                cli.warn(f, fullMessage(e));
                return 1;
            };
            data[data_len] = '\n';
            data_len += 1;
        }
    }
    const n = splitLines() catch |e| {
        cli.warn(null, if (e == error.OutOfMemory) "out of memory" else "too many lines");
        return 1;
    };
    mergeSort(n);

    var w = cli.Writer{ .fd = io.STDOUT, .buf = &out_buf };
    if (output) |path| {
        w.fd = fs.open(path, .{ .read = false, .write = true, .create = true, .truncate = true }) catch |e| {
            cli.warnErr(path, e);
            return 1;
        };
    }
    var prev: ?Line = null;
    for (lines[0..n]) |l| {
        if (unique) {
            if (prev) |p| {
                if (compareKeys(p, l) == 0) continue;
            }
            prev = l;
        }
        w.write(text(l));
        w.byte('\n');
    }
    w.flush();
    if (output) |path| {
        if (w.failed) {
            cli.warnErr(path, error.IO);
            status = 1;
        }
        io.close(w.fd) catch {};
    }
    return status;
}

fn parseKey(v: []const u8) bool {
    var comma: ?usize = null;
    for (v, 0..) |c, i| {
        if (c == ',') comma = i;
    }
    const first = io.parseUnsigned(v[0 .. comma orelse v.len]) orelse return false;
    if (first == 0 or first > 1 << 20) return false;
    key_first = @intCast(first);
    key_last = 0;
    if (comma) |i| {
        const last = io.parseUnsigned(v[i + 1 ..]) orelse return false;
        if (last < first or last > 1 << 20) return false;
        key_last = @intCast(last);
    }
    return true;
}

/// [data] の [need] バイトまでを RAM として数える
fn ensureData(need: usize) Full!void {
    if (need > DATA_SIZE) return error.TooBig;
    while (data_committed < need) {
        const end = @min(data_committed + CHUNK, DATA_SIZE);
        if (!kibi.memory.commit(data[data_committed..end], 0)) return error.OutOfMemory;
        data_committed = end;
    }
}

fn readAll(fd: io.Fd) (Full || kibi.Error)!void {
    while (true) {
        if (data_len == data_committed) try ensureData(data_len + 1);
        const got = try io.read(fd, data[data_len..data_committed]);
        if (got == 0) return;
        data_len += got;
    }
}

fn lineBytes(arr: *[MAX_LINES]Line, from: usize, to: usize) []const u8 {
    const p: [*]const u8 = @ptrCast(&arr[from]);
    return p[0 .. (to - from) * @sizeOf(Line)];
}

/// 読んだものを行に分ける。行の数
fn splitLines() Full!usize {
    var n: usize = 0;
    var start: usize = 0;
    for (data[0..data_len], 0..) |c, i| {
        if (c != '\n') continue;
        if (n == MAX_LINES) return error.TooBig;
        if (n == lines_committed) {
            const end = @min(n + CHUNK / @sizeOf(Line), MAX_LINES);
            if (!kibi.memory.commit(lineBytes(&lines, n, end), 0)) return error.OutOfMemory;
            if (!kibi.memory.commit(lineBytes(&tmp, n, end), 0)) return error.OutOfMemory;
            lines_committed = end;
        }
        lines[n] = .{ .start = @intCast(start), .len = @intCast(i - start) };
        n += 1;
        start = i + 1;
    }
    return n;
}

fn text(l: Line) []const u8 {
    return data[l.start..][0..l.len];
}

// ---- 並べる ----

/// 安定な併合ソート（下から。[tmp] を使う）
fn mergeSort(n: usize) void {
    var src: []Line = lines[0..n];
    var dst: []Line = tmp[0..n];
    var width: usize = 1;
    while (width < n) : (width *= 2) {
        var lo: usize = 0;
        while (lo < n) : (lo += 2 * width) {
            const mid = @min(lo + width, n);
            const hi = @min(lo + 2 * width, n);
            var i = lo;
            var j = mid;
            var k = lo;
            while (k < hi) : (k += 1) {
                // 同じなら前の方（i）を先に（安定）
                if (i < mid and (j >= hi or order(src[j], src[i]) >= 0)) {
                    dst[k] = src[i];
                    i += 1;
                } else {
                    dst[k] = src[j];
                    j += 1;
                }
            }
        }
        const t = src;
        src = dst;
        dst = t;
    }
    if (src.ptr != lines[0..].ptr) @memcpy(lines[0..n], src);
}

/// 並べる順（負なら [a] が先）
fn order(a: Line, b: Line) i32 {
    var r = compareKeys(a, b);
    if (r == 0 and !unique) r = compareBytes(text(a), text(b), false);
    return if (reverse) -r else r;
}

fn compareKeys(a: Line, b: Line) i32 {
    const ka = key(text(a));
    const kb = key(text(b));
    if (numeric) return compareNumbers(ka, kb);
    return compareBytes(ka, kb, fold);
}

fn isBlank(c: u8) bool {
    return c == ' ' or c == '\t';
}

fn skipBlanks(s: []const u8) []const u8 {
    var i: usize = 0;
    while (i < s.len and isBlank(s[i])) i += 1;
    return s[i..];
}

/// 行の鍵の部分
fn key(line: []const u8) []const u8 {
    if (key_first == 0) return if (blanks) skipBlanks(line) else line;
    const start = fieldStart(line, key_first) orelse return line[line.len..];
    var end = line.len;
    if (key_last != 0) end = fieldEnd(line, key_last);
    if (end < start) end = start;
    const k = line[start..end];
    return if (blanks) skipBlanks(k) else k;
}

/// [n] 番目の欄の始まり（なければ null）
fn fieldStart(line: []const u8, n: usize) ?usize {
    var i: usize = 0;
    var field: usize = 1;
    if (separator) |sep| {
        while (field < n) : (field += 1) {
            while (i < line.len and line[i] != sep) i += 1;
            if (i == line.len) return null;
            i += 1;
        }
        return i;
    }
    while (true) {
        while (i < line.len and isBlank(line[i])) i += 1;
        if (i == line.len) return null;
        if (field == n) return i;
        while (i < line.len and !isBlank(line[i])) i += 1;
        field += 1;
    }
}

/// [n] 番目の欄の終わり
fn fieldEnd(line: []const u8, n: usize) usize {
    const start = fieldStart(line, n) orelse return line.len;
    var i = start;
    if (separator) |sep| {
        while (i < line.len and line[i] != sep) i += 1;
    } else {
        while (i < line.len and !isBlank(line[i])) i += 1;
    }
    return i;
}

fn lower(c: u8) u8 {
    return if (c >= 'A' and c <= 'Z') c + 32 else c;
}

fn compareBytes(a: []const u8, b: []const u8, fold_case: bool) i32 {
    const n = @min(a.len, b.len);
    for (a[0..n], b[0..n]) |x0, y0| {
        const x = if (fold_case) lower(x0) else x0;
        const y = if (fold_case) lower(y0) else y0;
        if (x != y) return if (x < y) -1 else 1;
    }
    if (a.len == b.len) return 0;
    return if (a.len < b.len) -1 else 1;
}

/// 先頭の数（`-`、整数部（頭の 0 を除く）、小数部（末尾の 0 を除く））
const Number = struct { neg: bool, int: []const u8, frac: []const u8 };

fn parseNumber(s0: []const u8) Number {
    const s = skipBlanks(s0);
    var i: usize = 0;
    const neg = i < s.len and s[i] == '-';
    if (neg) i += 1;
    while (i < s.len and s[i] == '0') i += 1;
    const int_start = i;
    while (i < s.len and s[i] >= '0' and s[i] <= '9') i += 1;
    const int = s[int_start..i];
    var frac: []const u8 = "";
    if (i < s.len and s[i] == '.') {
        i += 1;
        const frac_start = i;
        while (i < s.len and s[i] >= '0' and s[i] <= '9') i += 1;
        frac = s[frac_start..i];
        while (frac.len > 0 and frac[frac.len - 1] == '0') frac = frac[0 .. frac.len - 1];
    }
    // -0 は 0
    return .{ .neg = neg and (int.len > 0 or frac.len > 0), .int = int, .frac = frac };
}

fn compareNumbers(a: []const u8, b: []const u8) i32 {
    const x = parseNumber(a);
    const y = parseNumber(b);
    if (x.neg != y.neg) return if (x.neg) -1 else 1;
    var r: i32 = undefined;
    if (x.int.len != y.int.len) {
        r = if (x.int.len < y.int.len) -1 else 1;
    } else {
        r = compareBytes(x.int, y.int, false);
        if (r == 0) r = compareBytes(x.frac, y.frac, false);
    }
    return if (x.neg) -r else r;
}
