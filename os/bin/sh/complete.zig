//! Tab の補完の候補（#102）。決めごとは docs/adr/0102-shell-line-editing.md。
//!
//! 行の編集（`edit.zig`）が、カーソルの前の語を引用を外して渡し、ここが候補を集める。
//! 候補の名前は静的な領域に写す（行を読む間だけ使う）。
const kibi = @import("kibi");
const util = @import("util.zig");
const vars = @import("vars.zig");
const builtins = @import("builtins.zig");

const fs = kibi.fs;
const io = kibi.io;
const eql = util.eql;
const startsWith = util.startsWith;

/// 候補の数の上限（超えた分は捨てる）
pub const MAX_CANDIDATES = 1024;
const NAMES_SIZE = 64 * 1024;

pub const Candidate = struct {
    /// 語の全体を置き換える文字列（引用を外した形。例: `/tmp/alpha.txt`）
    text: []const u8,
    /// 一覧に出す名前（パスの最後の部分）
    name: []const u8,
    /// ディレクトリ（確定すると `/` を足す）
    dir: bool,
};

pub var items: [MAX_CANDIDATES]Candidate = undefined;
pub var count: usize = 0;
/// 上限で捨てた候補がある
pub var truncated = false;

var names: [NAMES_SIZE]u8 = undefined;
var names_len: usize = 0;
var dir_buf: [4096]u8 = undefined;

fn reset() void {
    count = 0;
    names_len = 0;
    truncated = false;
}

/// [head] と [name] をつないで候補にする（[name] は一覧に出す部分）
fn push(head: []const u8, name: []const u8, dir: bool) void {
    if (count >= MAX_CANDIDATES or names_len + head.len + name.len > NAMES_SIZE) {
        truncated = true;
        return;
    }
    const start = names_len;
    @memcpy(names[names_len..][0..head.len], head);
    names_len += head.len;
    @memcpy(names[names_len..][0..name.len], name);
    names_len += name.len;
    const text = names[start..names_len];
    items[count] = .{ .text = text, .name = text[head.len..], .dir = dir };
    count += 1;
}

/// 語 [word]（引用を外したもの）の候補を集める。[command] はコマンドの位置（最初の語）か。
/// 結果は [items] に、名前の順・重なりなしで入る
pub fn collect(word: []const u8, command: bool) void {
    reset();
    if (word.len > 0 and word[0] == '$' and util.indexOf(word, '/') == null) {
        variables(word[1..]);
    } else if (command and util.indexOf(word, '/') == null) {
        commands(word);
    } else {
        paths(word);
    }
    sortUnique();
}

fn variables(prefix: []const u8) void {
    for (vars.all()) |v| {
        if (v.value == null) continue;
        if (startsWith(v.name, prefix)) push("$", v.name, false);
    }
}

fn commands(prefix: []const u8) void {
    for (builtins.all_names) |n| if (startsWith(n, prefix)) push("", n, false);
    for (vars.allFuncs()) |f| {
        if (!f.dead and startsWith(f.name, prefix)) push("", f.name, false);
    }
    const path = vars.get("PATH") orelse "/bin:/sbin";
    var rest = path;
    while (true) {
        const end = util.indexOf(rest, ':') orelse rest.len;
        const dir = if (end == 0) "." else rest[0..end];
        listDir(dir, "", prefix, .programs);
        if (end == rest.len) break;
        rest = rest[end + 1 ..];
    }
}

const Filter = enum { all, programs };

fn paths(word: []const u8) void {
    var slash: ?usize = null;
    for (word, 0..) |c, i| {
        if (c == '/') slash = i;
    }
    const head = if (slash) |s| word[0 .. s + 1] else "";
    const prefix = if (slash) |s| word[s + 1 ..] else word;
    var buf: [fs.MAX_PATH]u8 = undefined;
    const dir = expandTilde(&buf, head) orelse return;
    listDir(if (dir.len == 0) "." else dir, head, prefix, .all);
}

/// 先頭の `~/`・`~` を `HOME` にする（ほかの `~user` は扱わない）
fn expandTilde(buf: []u8, head: []const u8) ?[]const u8 {
    if (!startsWith(head, "~/")) return head;
    const home = vars.get("HOME") orelse return null;
    const rest = head[1..];
    if (home.len + rest.len > buf.len) return null;
    @memcpy(buf[0..home.len], home);
    @memcpy(buf[home.len..][0..rest.len], rest);
    return buf[0 .. home.len + rest.len];
}

fn listDir(dir: []const u8, head: []const u8, prefix: []const u8, filter: Filter) void {
    const fd = fs.open(dir, .{ .directory = true }) catch return;
    defer io.close(fd) catch {};
    var d = fs.Dir.init(fd, &dir_buf);
    const hidden = prefix.len > 0 and prefix[0] == '.';
    while (true) {
        const e = (d.next() catch return) orelse return;
        if (!startsWith(e.name, prefix)) continue;
        if (!hidden and e.name.len > 0 and e.name[0] == '.') continue;
        var is_dir = e.kind == .directory;
        if (e.kind == .symlink) {
            var pb: [fs.MAX_PATH]u8 = undefined;
            if (join(&pb, dir, e.name)) |p| {
                if (fs.stat(p)) |st| is_dir = st.kind == .directory else |_| {}
            }
        }
        switch (filter) {
            .all => push(head, e.name, is_dir),
            .programs => if (!is_dir) push(head, e.name, false),
        }
    }
}

fn join(buf: []u8, dir: []const u8, name: []const u8) ?[]const u8 {
    if (dir.len + 1 + name.len > buf.len) return null;
    @memcpy(buf[0..dir.len], dir);
    buf[dir.len] = '/';
    @memcpy(buf[dir.len + 1 ..][0..name.len], name);
    return buf[0 .. dir.len + 1 + name.len];
}

fn less(a: []const u8, b: []const u8) bool {
    const n = @min(a.len, b.len);
    for (a[0..n], b[0..n]) |x, y| {
        if (x != y) return x < y;
    }
    return a.len < b.len;
}

/// 名前の順に並べ、同じ文字列を 1 つにする（ディレクトリが勝つ）
fn sortUnique() void {
    // 挿入ソート（候補は多くない）
    var i: usize = 1;
    while (i < count) : (i += 1) {
        const v = items[i];
        var j = i;
        while (j > 0 and less(v.text, items[j - 1].text)) : (j -= 1) items[j] = items[j - 1];
        items[j] = v;
    }
    var w: usize = 0;
    for (items[0..count]) |c| {
        if (w > 0 and eql(items[w - 1].text, c.text)) {
            items[w - 1].dir = items[w - 1].dir or c.dir;
            continue;
        }
        items[w] = c;
        w += 1;
    }
    count = w;
}

/// 候補すべてに共通する頭の長さ（UTF-8 の文字の途中では切らない）
pub fn commonPrefix() usize {
    if (count == 0) return 0;
    var n = items[0].text.len;
    for (items[1..count]) |c| {
        var k: usize = 0;
        while (k < n and k < c.text.len and c.text[k] == items[0].text[k]) k += 1;
        n = k;
    }
    const t = items[0].text;
    while (n > 0 and n < t.len and t[n] & 0xc0 == 0x80) n -= 1;
    return n;
}
