//! ディレクトリの木を写す・消す（#103。`cp -r`・`rm -r`・`mv` が使う）。
//!
//! 失敗は `cli.warn` で知らせ、続けられるものは続ける（戻り値は全部できたか）。
//! 中のシンボリックリンクはたどらない（リンクそのものを消す・写さない）。深さは [MAX_DEPTH] まで（超えたら `LOOP`）
const io = @import("io.zig");
const fs = @import("fs.zig");
const cli = @import("cli.zig");

pub const Error = @import("errno.zig").Error;

/// 木の深さの上限
pub const MAX_DEPTH = 32;
/// 深さごとのディレクトリの読み込みの領域
const DIR_BUF = 1024;

var dir_bufs: [MAX_DEPTH][DIR_BUF]u8 = undefined;
var copy_buf: [8192]u8 = undefined;
var path_a: cli.PathBuf = .{};
var path_b: cli.PathBuf = .{};

/// [path] を消す。ディレクトリなら中身ごと
pub fn remove(path: []const u8) bool {
    const st = fs.lstat(path) catch |e| {
        cli.warnErr(path, e);
        return false;
    };
    if (st.kind != .directory) {
        fs.unlink(path) catch |e| {
            cli.warnErr(path, e);
            return false;
        };
        return true;
    }
    path_a.set(path) catch |e| {
        cli.warnErr(path, e);
        return false;
    };
    return removeDir(0);
}

/// [path_a] のディレクトリを中身ごと消す。読みながら消すと読む位置がずれるので、1 つ消すたびに頭から読み直す
fn removeDir(depth: usize) bool {
    if (depth >= MAX_DEPTH) {
        cli.warnErr(path_a.slice(), error.LOOP);
        return false;
    }
    while (true) {
        const fd = fs.open(path_a.slice(), .{ .directory = true }) catch |e| {
            cli.warnErr(path_a.slice(), e);
            return false;
        };
        var dir = fs.Dir.init(fd, &dir_bufs[depth]);
        const first = dir.next() catch |e| {
            io.close(fd) catch {};
            cli.warnErr(path_a.slice(), e);
            return false;
        };
        const entry = first orelse {
            io.close(fd) catch {};
            break;
        };
        const old = path_a.push(entry.name) catch |e| {
            io.close(fd) catch {};
            cli.warnErr(path_a.slice(), e);
            return false;
        };
        io.close(fd) catch {};
        // 名前は dir_bufs[depth] の中にあり、下の深さは別の領域を使うので、ここまでに写し終えている
        const ok = if (entry.kind == .directory) removeDir(depth + 1) else blk: {
            fs.unlink(path_a.slice()) catch |e| {
                cli.warnErr(path_a.slice(), e);
                break :blk false;
            };
            break :blk true;
        };
        path_a.truncate(old);
        // 消せなければ、同じ項目を読み続けないようやめる
        if (!ok) return false;
    }
    fs.rmdir(path_a.slice()) catch |e| {
        cli.warnErr(path_a.slice(), e);
        return false;
    };
    return true;
}

/// [src] を [dst] に写す。ディレクトリは [recursive] なら中身ごと（なければ知らせて false）。
/// [dst] がディレクトリとしてあれば、その中に重ねて写す
pub fn copy(src: []const u8, dst: []const u8, recursive: bool) bool {
    const st = fs.stat(src) catch |e| {
        cli.warnErr(src, e);
        return false;
    };
    var a: [fs.MAX_PATH]u8 = undefined;
    var b: [fs.MAX_PATH]u8 = undefined;
    const abs_src = fs.absolute(&a, src) catch |e| {
        cli.warnErr(src, e);
        return false;
    };
    const abs_dst = fs.absolute(&b, dst) catch |e| {
        cli.warnErr(dst, e);
        return false;
    };
    if (cli.eql(abs_src, abs_dst)) {
        cli.warn(src, "source and destination are the same file");
        return false;
    }
    if (st.kind == .directory) {
        if (!recursive) {
            cli.warn(src, "is a directory (not copied)");
            return false;
        }
        if (abs_dst.len > abs_src.len and cli.startsWith(abs_dst, abs_src) and (abs_src.len == 1 or abs_dst[abs_src.len] == '/')) {
            cli.warn(src, "cannot copy a directory into itself");
            return false;
        }
    }
    path_a.set(abs_src) catch unreachable;
    path_b.set(abs_dst) catch unreachable;
    return copyAt(st.kind, 0);
}

/// [path_a] を [path_b] に写す
fn copyAt(kind: fs.Kind, depth: usize) bool {
    if (kind != .directory) return copyFile(path_a.slice(), path_b.slice());
    if (depth >= MAX_DEPTH) {
        cli.warnErr(path_a.slice(), error.LOOP);
        return false;
    }
    fs.mkdir(path_b.slice()) catch |e| {
        const is_dir = e == error.EXIST and (if (fs.stat(path_b.slice())) |s| s.kind == .directory else |_| false);
        if (!is_dir) {
            cli.warnErr(path_b.slice(), if (e == error.EXIST) error.NOTDIR else e);
            return false;
        }
    };
    const fd = fs.open(path_a.slice(), .{ .directory = true }) catch |e| {
        cli.warnErr(path_a.slice(), e);
        return false;
    };
    defer io.close(fd) catch {};
    var dir = fs.Dir.init(fd, &dir_bufs[depth]);
    var ok = true;
    while (true) {
        const entry = (dir.next() catch |e| {
            cli.warnErr(path_a.slice(), e);
            return false;
        }) orelse break;
        if (entry.kind == .symlink) {
            // リンクは作れる口がまだないので、写さない
            continue;
        }
        const old_a = path_a.push(entry.name) catch |e| {
            cli.warnErr(path_a.slice(), e);
            ok = false;
            continue;
        };
        const old_b = path_b.push(entry.name) catch |e| {
            path_a.truncate(old_a);
            cli.warnErr(path_b.slice(), e);
            ok = false;
            continue;
        };
        if (!copyAt(entry.kind, depth + 1)) ok = false;
        path_a.truncate(old_a);
        path_b.truncate(old_b);
    }
    return ok;
}

/// ファイルの中身を写す（[dst] はあれば長さを 0 にしてから）
pub fn copyFile(src: []const u8, dst: []const u8) bool {
    const in = fs.open(src, .{}) catch |e| {
        cli.warnErr(src, e);
        return false;
    };
    defer io.close(in) catch {};
    const out = fs.open(dst, .{ .read = false, .write = true, .create = true, .truncate = true }) catch |e| {
        cli.warnErr(dst, e);
        return false;
    };
    defer io.close(out) catch {};
    while (true) {
        const n = io.read(in, &copy_buf) catch |e| {
            cli.warnErr(src, e);
            return false;
        };
        if (n == 0) return true;
        io.writeAll(out, copy_buf[0..n]) catch |e| {
            cli.warnErr(dst, e);
            return false;
        };
    }
}
