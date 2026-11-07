//! `ls [-1adlF] [パス...]`: ディレクトリの中身を名前の順に書く（パスがなければ `.`）。
//!
//! - `-a`: `.` で始まる名前も（`.`・`..` を含む）
//! - `-d`: ディレクトリの中ではなく、ディレクトリ自体を（ファイルと同じに）書く
//! - `-l`: 1 行に 1 つ、`種類 大きさ 変更時刻（UTC） 名前`。種類は `-` ファイル・`d` ディレクトリ・`l` リンク・`c` デバイス・`?` ほか
//! - `-1`: 1 行に 1 つ（標準出力が TTY でなければ、もとからそう）
//! - `-F`: ディレクトリの名前の後ろに `/`
//!
//! 標準出力が TTY なら、画面の幅に合わせて段に並べる（上から下、左から右）。
//! 1 つのディレクトリの項目と、パスに並べたファイルは、それぞれ [MAX_ENTRIES] 個・名前の合計 [NAMES_SIZE] バイトまで
//! （超えたらそこまでを書いて知らせ、終了コードを 1 にする）
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;
const io = kibi.io;

const SYNOPSIS = "ls [-1adlF] [file...]";

const MAX_ENTRIES = 2048;
const NAMES_SIZE = 64 * 1024;

const Item = struct { name: []const u8, kind: fs.Kind };

var dir_buf: [4096]u8 = undefined;
var names: [NAMES_SIZE]u8 = undefined;
var names_len: usize = 0;
var items: [MAX_ENTRIES]Item = undefined;
var items_len: usize = 0;

var all = false;
var dir_self = false;
var long = false;
var one = false;
var classify = false;
var columns: usize = 0;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'a' => all = true,
        'd' => dir_self = true,
        'l' => long = true,
        '1' => one = true,
        'F' => classify = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    if (!long and !one and kibi.tty.isTty(io.STDOUT)) {
        columns = if (kibi.tty.getSize(io.STDOUT)) |s| s.columns else |_| 80;
    }
    const operands = opts.rest();
    const paths: []const []const u8 = if (operands.len == 0) &.{"."} else operands;
    var status: u8 = 0;

    // ファイルを先にまとめて書き、ディレクトリを後に 1 つずつ（`-d` ならディレクトリもファイルと同じに）
    var dirs: usize = 0;
    var full = false;
    resetItems();
    for (paths) |p| {
        const st = fs.stat(p) catch |e| {
            cli.warnErr(p, e);
            status = 1;
            continue;
        };
        if (st.kind == .directory and !dir_self) {
            dirs += 1;
            continue;
        }
        // 入りきらなくなったら後ろは書かずに、書いた後で知らせる（ディレクトリはこの後に書く。#510）
        if (full) continue;
        addItem(p, st.kind) catch {
            full = true;
        };
    }
    const files = items_len;
    if (files > 0) printItems(null);
    if (full) {
        cli.warn(null, "too many files (listing the first ones)");
        status = 1;
    }
    var printed = files > 0;
    for (paths) |p| {
        if (dir_self) break;
        const st = fs.stat(p) catch continue;
        if (st.kind != .directory) continue;
        if (printed) cli.stdout.byte('\n');
        if (paths.len > 1) {
            cli.stdout.write(p);
            cli.stdout.write(":\n");
        }
        if (!listDir(p)) status = 1;
        printed = true;
        if (cli.stdout.failed) break;
    }
    cli.stdout.flush();
    if (cli.stdout.failed) return 1;
    return status;
}

fn resetItems() void {
    items_len = 0;
    names_len = 0;
}

fn addItem(name: []const u8, kind: fs.Kind) error{Full}!void {
    if (items_len == MAX_ENTRIES or names_len + name.len > names.len) return error.Full;
    @memcpy(names[names_len..][0..name.len], name);
    items[items_len] = .{ .name = names[names_len..][0..name.len], .kind = kind };
    names_len += name.len;
    items_len += 1;
}

fn listDir(path: []const u8) bool {
    resetItems();
    const fd = fs.open(path, .{ .directory = true }) catch |e| {
        cli.warnErr(path, e);
        return false;
    };
    defer io.close(fd) catch {};
    var ok = true;
    if (all) {
        addItem(".", .directory) catch {};
        addItem("..", .directory) catch {};
    }
    var dir = fs.Dir.init(fd, &dir_buf);
    while (true) {
        const e = dir.next() catch |err| {
            cli.warnErr(path, err);
            ok = false;
            break;
        } orelse break;
        if (!all and e.name.len > 0 and e.name[0] == '.') continue;
        addItem(e.name, e.kind) catch {
            cli.warn(path, "too many entries (listing the first ones)");
            ok = false;
            break;
        };
    }
    printItems(path);
    return ok;
}

/// 名前の順に並べて書く。[dir] は `-l` で項目の情報を読むときの親（null ならパスそのもの）
fn printItems(dir: ?[]const u8) void {
    sortItems();
    if (long) {
        for (items[0..items_len]) |it| printLong(dir, it);
        return;
    }
    if (columns == 0) {
        for (items[0..items_len]) |it| {
            printName(it);
            cli.stdout.byte('\n');
        }
        return;
    }
    // 段に並べる: 一番長い名前 + 2 の幅で、画面に入るだけの段
    var widest: usize = 1;
    for (items[0..items_len]) |it| widest = @max(widest, nameWidth(it));
    const cell = widest + 2;
    const ncols = @max(1, columns / cell);
    const rows = (items_len + ncols - 1) / ncols;
    var r: usize = 0;
    while (r < rows) : (r += 1) {
        var c: usize = 0;
        while (c < ncols) : (c += 1) {
            const idx = c * rows + r;
            if (idx >= items_len) break;
            const it = items[idx];
            printName(it);
            const last = (c + 1) * rows + r >= items_len;
            if (!last) cli.stdout.pad(nameWidth(it), cell);
        }
        cli.stdout.byte('\n');
    }
}

fn nameWidth(it: Item) usize {
    return cli.charCount(it.name) + @intFromBool(classify and it.kind == .directory);
}

fn printName(it: Item) void {
    cli.stdout.write(it.name);
    if (classify and it.kind == .directory) cli.stdout.byte('/');
}

fn printLong(dir: ?[]const u8, it: Item) void {
    var buf: [fs.MAX_PATH]u8 = undefined;
    const path = if (dir) |d| cli.join(&buf, d, it.name) catch it.name else it.name;
    const st = fs.stat(path) catch fs.Stat{ .kind = it.kind, .size = 0, .mtime = 0, .ino = 0 };
    const kind: u8 = switch (st.kind) {
        .file => '-',
        .directory => 'd',
        .symlink => 'l',
        .device => 'c',
        .other => '?',
    };
    cli.stdout.byte(kind);
    cli.stdout.byte(' ');
    cli.stdout.uint(st.size, 8);
    cli.stdout.byte(' ');
    writeTime(st.mtime);
    cli.stdout.byte(' ');
    printName(it);
    cli.stdout.byte('\n');
}

/// 1970 年からのナノ秒を `YYYY-MM-DD HH:MM`（UTC）で書く
fn writeTime(ns: u64) void {
    const t = kibi.time.civil(ns / 1_000_000_000);
    two(t.year, 4);
    cli.stdout.byte('-');
    two(t.month, 2);
    cli.stdout.byte('-');
    two(t.day, 2);
    cli.stdout.byte(' ');
    two(t.hour, 2);
    cli.stdout.byte(':');
    two(t.minute, 2);
}

/// [v] を [width] 桁（左を 0 で埋める）で書く
fn two(v: u64, width: usize) void {
    var i = cli.digits(v);
    while (i < width) : (i += 1) cli.stdout.byte('0');
    cli.stdout.uint(v, 0);
}

fn sortItems() void {
    cli.sortBy(Item, items[0..items_len], itemLess);
}

fn itemLess(a: Item, b: Item) bool {
    return cli.lessThan(a.name, b.name);
}
