//! コマンドを書くための小物（#103・#475）: 文字列・知らせ・まとめて書く出力・オプションの読み方・行の読み方・パスの組み立て・
//! ほかのプログラムを動かすこと（`env`・`xargs`）。
//!
//! 知らせは `プログラムの名前: 対象: 説明` の形で標準エラー出力に書く。名前は [setName] で決める（既定は `argv[0]` の最後の部分）。
const io = @import("io.zig");
const errno = @import("errno.zig");
const process = @import("process.zig");
const fs = @import("fs.zig");
const tty = @import("tty.zig");

pub const Error = errno.Error;

/// 使い方の誤りの終了コード
pub const USAGE: u8 = 2;

// ---- 文字列 ----

pub fn eql(a: []const u8, b: []const u8) bool {
    if (a.len != b.len) return false;
    for (a, b) |x, y| if (x != y) return false;
    return true;
}

pub fn startsWith(s: []const u8, p: []const u8) bool {
    return s.len >= p.len and eql(s[0..p.len], p);
}

/// バイトの辞書順で [a] が [b] より前か
pub fn lessThan(a: []const u8, b: []const u8) bool {
    const n = @min(a.len, b.len);
    for (a[0..n], b[0..n]) |x, y| {
        if (x != y) return x < y;
    }
    return a.len < b.len;
}

/// 名前の順に並べる
pub fn sort(items: [][]const u8) void {
    sortBy([]const u8, items, lessThan);
}

/// [less] の順に並べる（シェルソート。数千個までを想定。安定ではない）
pub fn sortBy(comptime T: type, items: []T, comptime less: fn (T, T) bool) void {
    var gap: usize = items.len / 2;
    while (gap > 0) : (gap /= 2) {
        var i = gap;
        while (i < items.len) : (i += 1) {
            const v = items[i];
            var j = i;
            while (j >= gap and less(v, items[j - gap])) : (j -= gap) items[j] = items[j - gap];
            items[j] = v;
        }
    }
}

/// UTF-8 の文字の数（続きのバイトを数えない。表示の幅のおおよそ）
pub fn charCount(s: []const u8) usize {
    var n: usize = 0;
    for (s) |c| n += @intFromBool(c & 0xc0 != 0x80);
    return n;
}

/// パスの最後の部分（末尾の `/` は除く。`/` だけなら `/`）
pub fn basename(path: []const u8) []const u8 {
    var end = path.len;
    while (end > 1 and path[end - 1] == '/') end -= 1;
    var start = end;
    while (start > 0 and path[start - 1] != '/') start -= 1;
    if (start == end) return if (path.len > 0 and path[0] == '/') "/" else path;
    return path[start..end];
}

/// パスの親の部分（`dirname`。末尾の `/` は除く。`/` を含まなければ `.`、根の直下なら `/`）
pub fn dirname(path: []const u8) []const u8 {
    var end = path.len;
    while (end > 1 and path[end - 1] == '/') end -= 1;
    while (end > 0 and path[end - 1] != '/') end -= 1;
    if (end == 0) return ".";
    while (end > 1 and path[end - 1] == '/') end -= 1;
    return path[0..end];
}

/// [dir] と [name] を `/` でつないで [buf] に書く
pub fn join(buf: []u8, dir: []const u8, name: []const u8) Error![]const u8 {
    const slash = dir.len > 0 and dir[dir.len - 1] != '/';
    const n = dir.len + @intFromBool(slash) + name.len;
    if (n > buf.len) return error.NAMETOOLONG;
    @memcpy(buf[0..dir.len], dir);
    if (slash) buf[dir.len] = '/';
    @memcpy(buf[n - name.len .. n], name);
    return buf[0..n];
}

/// 書き足して使うパス（再帰で名前を足し、戻るときに切る）
pub const PathBuf = struct {
    buf: [fs.MAX_PATH]u8 = undefined,
    len: usize = 0,

    pub fn set(self: *PathBuf, path: []const u8) Error!void {
        if (path.len > self.buf.len) return error.NAMETOOLONG;
        @memcpy(self.buf[0..path.len], path);
        self.len = path.len;
    }

    /// 名前を足す。戻すときは前の長さを [truncate] に渡す
    pub fn push(self: *PathBuf, name: []const u8) Error!usize {
        const old = self.len;
        const joined = try join(self.buf[0..], self.buf[0..old], name);
        self.len = joined.len;
        return old;
    }

    pub fn truncate(self: *PathBuf, len: usize) void {
        self.len = len;
    }

    pub fn slice(self: *const PathBuf) []const u8 {
        return self.buf[0..self.len];
    }
};

// ---- 知らせ ----

var name_buf: []const u8 = "";

/// 知らせに使うプログラムの名前を決める
pub fn setName(name: []const u8) void {
    name_buf = name;
}

fn progName() []const u8 {
    if (name_buf.len > 0) return name_buf;
    const argv = process.args() catch return "?";
    return if (argv.len > 0) basename(argv[0]) else "?";
}

/// `名前: [対象: ]説明` を標準エラー出力に書く
pub fn warn(subject: ?[]const u8, message: []const u8) void {
    stdout.flush();
    io.err(progName());
    io.err(": ");
    if (subject) |s| {
        io.err(s);
        io.err(": ");
    }
    io.err(message);
    io.err("\n");
}

/// エラーの説明（strerror 相当）で [warn] する
pub fn warnErr(subject: ?[]const u8, err: Error) void {
    warn(subject, errno.describe(err));
}

/// 使い方の誤りを知らせて [USAGE] を返す
pub fn usage(message: []const u8, synopsis: []const u8) u8 {
    warn(null, message);
    io.err("usage: ");
    io.err(synopsis);
    io.err("\n");
    return USAGE;
}

/// 知らないオプションを知らせて [USAGE] を返す
pub fn badOption(c: u8, synopsis: []const u8) u8 {
    var msg = "unknown option -?".*;
    msg[msg.len - 1] = c;
    return usage(&msg, synopsis);
}

// ---- まとめて書く出力 ----

/// 小さな書き込みをまとめて fd に書く。書けなかったら（読み手が閉じたパイプなど）[failed] を立て、以後は捨てる
pub const Writer = struct {
    fd: io.Fd,
    buf: []u8,
    len: usize = 0,
    failed: bool = false,

    pub fn write(self: *Writer, bytes: []const u8) void {
        if (self.failed) return;
        if (self.len + bytes.len > self.buf.len) {
            self.flush();
            if (bytes.len >= self.buf.len) {
                io.writeAll(self.fd, bytes) catch {
                    self.failed = true;
                };
                return;
            }
        }
        @memcpy(self.buf[self.len..][0..bytes.len], bytes);
        self.len += bytes.len;
    }

    pub fn byte(self: *Writer, c: u8) void {
        self.write(&[_]u8{c});
    }

    /// 符号なし整数を 10 進で、[width] に満たなければ左を空白で埋めて書く
    pub fn uint(self: *Writer, v: u64, width: usize) void {
        var tmp: [20]u8 = undefined;
        const s = io.formatUnsigned(&tmp, v);
        self.pad(s.len, width);
        self.write(s);
    }

    /// [used] が [width] に満たない分の空白を書く
    pub fn pad(self: *Writer, used: usize, width: usize) void {
        var i = used;
        while (i < width) : (i += 1) self.byte(' ');
    }

    pub fn flush(self: *Writer) void {
        if (self.failed or self.len == 0) {
            self.len = 0;
            return;
        }
        io.writeAll(self.fd, self.buf[0..self.len]) catch {
            self.failed = true;
        };
        self.len = 0;
    }
};

var stdout_buf: [4096]u8 = undefined;
/// 標準出力（終わる前に `flush` する）
pub var stdout: Writer = .{ .fd = io.STDOUT, .buf = &stdout_buf };

/// 10 進の桁数
pub fn digits(v: u64) usize {
    var n: usize = 1;
    var x = v;
    while (x >= 10) : (x /= 10) n += 1;
    return n;
}

// ---- オプション ----

/// 短いオプション（`-a`・`-abc`・`-n 5`・`-n5`）を順に読む。`--` か、`-` で始まらない引数（`-` だけを含む）でやめる
pub const Args = struct {
    argv: []const []const u8,
    i: usize = 1,
    /// 今読んでいる引数の中の位置（0 なら次の引数から）
    pos: usize = 0,

    pub fn init(argv: []const []const u8) Args {
        return .{ .argv = argv, .i = @min(1, argv.len) };
    }

    /// 次のオプションの文字。もうなければ null（残りは [rest]）
    pub fn next(self: *Args) ?u8 {
        if (self.pos == 0) {
            if (self.i >= self.argv.len) return null;
            const a = self.argv[self.i];
            if (eql(a, "--")) {
                self.i += 1;
                return null;
            }
            if (a.len < 2 or a[0] != '-') return null;
            self.pos = 1;
        }
        const a = self.argv[self.i];
        const c = a[self.pos];
        self.pos += 1;
        if (self.pos >= a.len) {
            self.i += 1;
            self.pos = 0;
        }
        return c;
    }

    /// 今のオプションの値（同じ引数の残りか、次の引数）。なければ null
    pub fn value(self: *Args) ?[]const u8 {
        if (self.pos > 0) {
            const v = self.argv[self.i][self.pos..];
            self.i += 1;
            self.pos = 0;
            return v;
        }
        if (self.i >= self.argv.len) return null;
        const v = self.argv[self.i];
        self.i += 1;
        return v;
    }

    /// オプションの後ろの引数
    pub fn rest(self: *const Args) []const []const u8 {
        return self.argv[@min(self.i, self.argv.len)..];
    }
};

// ---- 行の読み方 ----

/// fd から 1 行ずつ読む。[buf] より長い行は、[buf] の大きさで切って返す（続きは次の行として返す）
pub const LineReader = struct {
    fd: io.Fd,
    buf: []u8,
    start: usize = 0,
    end: usize = 0,
    eof: bool = false,

    pub fn init(fd: io.Fd, buf: []u8) LineReader {
        return .{ .fd = fd, .buf = buf };
    }

    pub const Line = struct {
        /// 改行を除いた中身
        text: []const u8,
        /// 改行で終わっていた
        newline: bool,
    };

    /// 次の行。もうなければ null
    pub fn next(self: *LineReader) Error!?Line {
        while (true) {
            var i = self.start;
            while (i < self.end) : (i += 1) {
                if (self.buf[i] == '\n') {
                    const line = self.buf[self.start..i];
                    self.start = i + 1;
                    return .{ .text = line, .newline = true };
                }
            }
            if (self.eof or self.end - self.start == self.buf.len) {
                if (self.start == self.end) return null;
                const line = self.buf[self.start..self.end];
                self.start = self.end;
                return .{ .text = line, .newline = false };
            }
            // 残りを前に寄せて読み足す
            const left = self.end - self.start;
            if (self.start > 0) {
                var k: usize = 0;
                while (k < left) : (k += 1) self.buf[k] = self.buf[self.start + k];
                self.start = 0;
                self.end = left;
            }
            const n = try io.read(self.fd, self.buf[self.end..]);
            if (n == 0) self.eof = true;
            self.end += n;
        }
    }
};

/// 入力を開く（`-` は標準入力）
pub fn openInput(path: []const u8) Error!io.Fd {
    if (eql(path, "-")) return io.STDIN;
    const fd = try fs.open(path, .{});
    errdefer io.close(fd) catch {};
    const st = try fs.fstat(fd);
    if (st.kind == .directory) return error.ISDIR;
    return fd;
}

/// [openInput] で開いた fd を閉じる（標準入力は閉じない）
pub fn closeInput(fd: io.Fd) void {
    if (fd != io.STDIN) io.close(fd) catch {};
}

// ---- ほかのプログラムを動かす ----

/// 既定の `PATH`（環境変数がないとき。シェルと同じ）
pub const DEFAULT_PATH = "/bin:/sbin";

/// [name] のプログラムを探して、絶対パスを [buf] に書く。`/` を含めばそのまま（cwd につなぐ）、
/// 含まなければ [search]（`:` で区切ったディレクトリ。空の要素は `.`）から普通のファイルを探す。見つからなければ null
pub fn findProgram(buf: []u8, name: []const u8, search: []const u8) ?[]const u8 {
    for (name) |c| {
        if (c == '/') return fs.absolute(buf, name) catch null;
    }
    if (name.len == 0) return null;
    var rest = search;
    while (true) {
        var end: usize = 0;
        while (end < rest.len and rest[end] != ':') end += 1;
        const dir = if (end == 0) "." else rest[0..end];
        var tmp: [fs.MAX_PATH]u8 = undefined;
        if (join(&tmp, dir, name)) |joined| {
            if (fs.stat(joined)) |st| {
                if (st.kind == .file) return fs.absolute(buf, joined) catch null;
            } else |_| {}
        } else |_| {}
        if (end == rest.len) break;
        rest = rest[end + 1 ..];
    }
    return null;
}

/// 子の終わり方を終了コードにする（シェルの `$?` と同じ。シグナルは 128 + 番号、クラッシュは 139）
pub fn exitCode(s: process.ExitStatus) u8 {
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

/// 子を起こせなかったときの終了コード（見つからない 127、ほか 126。シェルと同じ）
pub fn spawnFailureCode(err: Error) u8 {
    return if (err == error.NOENT) 127 else 126;
}

/// 引数の数の上限（[run]）
pub const MAX_RUN_ARGS = process.MAX_SPAWN_ARGS - 1;

/// [path]（絶対パス）を引数 [argv] で動かし、終わるまで待つ。WASM でないファイル（`NOEXEC`）は `/bin/sh` のスクリプトとして動かす。
/// 自分が TTY の前景なら、待つ間は子を前景にする（Ctrl+C が子に届く）。終わったら自分に戻す。
/// TTY は標準入力・標準エラー・標準出力の順に、最初に TTY だったものを見る（`seq 3 | xargs sleep 9` のように
/// 標準入力がパイプでも、シェルが前景にした自分の子に Ctrl+C を届けるため。#482）。
/// 前景にした子が Ctrl+C・Ctrl+\ で止まったときは、自分も同じシグナルで止まる
pub fn run(path: []const u8, argv: []const []const u8, options: process.SpawnOptions) Error!process.ExitStatus {
    const child = process.spawn(path, argv, options) catch |e| blk: {
        if (e != error.NOEXEC) return e;
        if (argv.len > MAX_RUN_ARGS) return error.TOOBIG;
        var list: [process.MAX_SPAWN_ARGS][]const u8 = undefined;
        list[0] = "sh";
        list[1] = path;
        for (argv[@min(1, argv.len)..], 0..) |a, i| list[2 + i] = a;
        break :blk try process.spawn("/bin/sh", list[0 .. 1 + @max(argv.len, 1)], options);
    };
    defer child.release();
    const me = process.selfPid();
    const term = ttyFd();
    const fg = if (term) |fd| (tty.getForeground(fd) catch 0) == me else false;
    if (!fg) return child.wait();
    tty.setForeground(term.?, child.pid()) catch {};
    const s = child.wait() catch |e| {
        tty.setForeground(term.?, me) catch {};
        return e;
    };
    tty.setForeground(term.?, me) catch {};
    // 前景の子が Ctrl+C・Ctrl+\ で止まったら、自分も同じシグナルで止まる（Unix で同じプロセスグループにいるのと同じ。
    // シェルはこれを見て、パイプラインの残りも止める）。無視しているなら戻る
    switch (s) {
        .killed => |sig| if (sig) |x| if (x == .interrupt or x == .quit) process.killPid(me, x) catch {},
        else => {},
    }
    return s;
}

/// 標準入力・標準エラー・標準出力のうち、最初に TTY だったもの（なければ null）
fn ttyFd() ?io.Fd {
    for ([_]io.Fd{ io.STDIN, io.STDERR, io.STDOUT }) |fd| {
        if (tty.isTty(fd)) return fd;
    }
    return null;
}
