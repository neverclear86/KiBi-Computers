//! `man [-L 言語] [節] 名前...`・`man -k 語...`: マニュアルのページを書く（#106。決めごとは docs/adr/0106-man.md）。
//!
//! - ページは `MANPATH`（`:` 区切り。既定は `/usr/share/man`）の `[言語/]man<節>/<名前>.<節>`。書式は Markdown の小さなサブセット
//!   （`man/render.zig`）で、画面の幅に合わせてその場で折る
//! - 言語は `-L`、なければ `LC_ALL` > `LC_MESSAGES` > `LANG`。`ja_JP.UTF-8` なら `ja_JP.UTF-8` → `ja_JP` → `ja` → 既定（英語）の順に探す。
//!   `C`・`POSIX`・空・未設定は英語。その言語のページがなければ英語を出す
//! - 節を書かなければ 1 → 8 → 3 → 5 → 7 の順に探す（3 は Lua の API。#94）
//! - 標準入力と標準出力がどちらも TTY なら、ページを送りながら見せる（`man/pager.zig`）。ほかは全部を書いて終わる（TTY でなければ
//!   SGR を書かず、幅は 80）
//! - 幅は `MANWIDTH`（20〜1024）があればそれ、なければ画面の幅（TTY でなければ 80）
//! - `-k`: 各ページの NAME の節（最初の `##` の節の最初の行）に語を含むものを `名前(節) - 説明` で書く（大小を区別しない）
//!
//! 終了コード: 成功 0、見つからないページがある・`-k` で何もない 1、使い方の誤り 2
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;
const fs = kibi.fs;
const tty = kibi.tty;
const render = @import("man/render.zig");
const pager = @import("man/pager.zig");

const SYNOPSIS = "man [-L lang] [section] name... | man -k keyword...";

const DEFAULT_MANPATH = "/usr/share/man";
/// 節を書かないときに探す順
const DEFAULT_SECTIONS = [_][]const u8{ "1", "8", "3", "5", "7" };
/// 1 ページの大きさの上限
const MAX_PAGE = 64 * 1024;
/// 続けて見せるページの合計の上限
const SRC_SIZE = 128 * 1024;
const MAX_PAGES = 16;

var src_buf: [SRC_SIZE]u8 = undefined;
var src_len: usize = 0;
var pages: [MAX_PAGES][]const u8 = undefined;
var npages: usize = 0;
var color = false;

pub fn main() u8 {
    cli.setName("man");
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var lang_opt: ?[]const u8 = null;
    var apropos = false;
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'L' => lang_opt = opts.value() orelse return cli.usage("option requires an argument -- L", SYNOPSIS),
        'k' => apropos = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    var rest = opts.rest();
    if (rest.len == 0) return cli.usage("which manual page do you want?", SYNOPSIS);

    var langs: Langs = .{};
    langs.init(lang_opt orelse localeValue());
    const manpath = kibi.process.getenv("MANPATH") orelse "";

    if (apropos) {
        const r = keyword(rest, &langs, if (manpath.len > 0) manpath else DEFAULT_MANPATH);
        cli.stdout.flush();
        return r;
    }

    var section: ?[]const u8 = null;
    if (rest.len > 1 and isSection(rest[0])) {
        section = rest[0];
        rest = rest[1..];
    }
    var status: u8 = 0;
    for (rest) |name| {
        if (!load(name, section, &langs, if (manpath.len > 0) manpath else DEFAULT_MANPATH)) status = 1;
    }
    if (npages == 0) return status;

    const term_out = tty.isTty(io.STDOUT);
    var size: tty.Size = .{ .columns = 80, .rows = 25, .generation = 0 };
    if (term_out) size = tty.getSize(io.STDOUT) catch size;
    color = term_out;
    if (kibi.process.getenv("MANWIDTH")) |v| fixed_width = io.parseUnsigned(v);
    layout(size.columns);
    if (term_out and tty.isTty(io.STDIN) and render.line_count + 1 > size.rows) {
        pager.run(layout, .{ .count = lineCount, .get = render.line }, size);
    } else {
        var i: usize = 0;
        while (i < render.line_count) : (i += 1) {
            cli.stdout.write(render.line(i));
            cli.stdout.write("\n");
        }
    }
    cli.stdout.flush();
    return status;
}

fn lineCount() usize {
    return render.line_count;
}

/// `MANWIDTH` で決めた幅（画面の幅より先に使う）
var fixed_width: ?u64 = null;

/// 読んだページを幅 [columns]（TTY でなければ 80。`MANWIDTH` があればその値）で組む
fn layout(columns: usize) void {
    render.reset();
    const want: u64 = fixed_width orelse columns;
    const width: usize = @intCast(@min(@max(want, 20), 1024));
    for (pages[0..npages], 0..) |p, i| {
        if (i > 0) render.separator();
        render.render(p, .{ .width = width, .color = color });
    }
    if (render.truncated) {
        // 領域に入らなかった分は書かない（知らせは 1 回だけ。組み直しでは知らせない）
        if (!warned_truncated) cli.warn(null, "page too long; output truncated");
        warned_truncated = true;
    }
}

var warned_truncated = false;

// ---- 言語 ----

/// 探す言語の並び（最後に既定の ""）
const Langs = struct {
    items: [4][]const u8 = undefined,
    len: usize = 0,

    fn add(self: *Langs, v: []const u8) void {
        if (!safeComponent(v)) return;
        for (self.items[0..self.len]) |x| if (cli.eql(x, v)) return;
        self.items[self.len] = v;
        self.len += 1;
    }

    fn init(self: *Langs, value: []const u8) void {
        self.len = 0;
        if (!(value.len == 0 or cli.eql(value, "C") or cli.eql(value, "POSIX") or cli.startsWith(value, "C."))) {
            self.add(value);
            // 文字コード・修飾を除く（`ja_JP.UTF-8@x` → `ja_JP`）
            var end: usize = 0;
            while (end < value.len and value[end] != '.' and value[end] != '@') end += 1;
            self.add(value[0..end]);
            // 地域を除く（`ja_JP` → `ja`）
            var lang: usize = 0;
            while (lang < end and value[lang] != '_') lang += 1;
            self.add(value[0..lang]);
        }
        self.items[self.len] = "";
        self.len += 1;
    }

    fn slice(self: *const Langs) []const []const u8 {
        return self.items[0..self.len];
    }
};

/// `LC_ALL` > `LC_MESSAGES` > `LANG`（空のものは飛ばす）
fn localeValue() []const u8 {
    for ([_][]const u8{ "LC_ALL", "LC_MESSAGES", "LANG" }) |name| {
        if (kibi.process.getenv(name)) |v| if (v.len > 0) return v;
    }
    return "";
}

/// パスの 1 つの部分として使ってよいか（空・`/` を含む・`.` で始まるものは断る。`../` でページの外を読ませない）
fn safeComponent(s: []const u8) bool {
    if (s.len == 0 or s.len > 255 or s[0] == '.') return false;
    for (s) |c| if (c == '/' or c < 0x20 or c == 0x7f) return false;
    return true;
}

/// 節の名前か（数字で始まり、英数字だけ。`1`・`3p` など）
fn isSection(s: []const u8) bool {
    if (s.len == 0 or s.len > 8 or s[0] < '0' or s[0] > '9') return false;
    for (s) |c| {
        const ok = (c >= '0' and c <= '9') or (c >= 'a' and c <= 'z');
        if (!ok) return false;
    }
    return true;
}

// ---- ページを探す ----

var path_buf: [fs.MAX_PATH]u8 = undefined;

/// `dir/[lang/]man<sec>/<name>.<sec>` を [path_buf] に組む
fn pagePath(dir: []const u8, lang: []const u8, sec: []const u8, name: []const u8) ?[]const u8 {
    var n: usize = 0;
    const parts = [_][]const u8{ dir, "/", lang, if (lang.len > 0) "/" else "", "man", sec, "/", name, ".", sec };
    for (parts) |p| {
        if (n + p.len > path_buf.len) return null;
        @memcpy(path_buf[n..][0..p.len], p);
        n += p.len;
    }
    return path_buf[0..n];
}

/// [readPage] の結果
const Read = union(enum) {
    /// 普通のファイルがない（開けない・ほかの種類。次の候補を探す）
    missing,
    /// あったが読めなかった（理由は知らせ済み。探すのをやめる）
    failed,
    page: []const u8,
};

/// 普通のファイル [path] を [src_buf] の後ろに読む
fn readPage(path: []const u8, name: []const u8) Read {
    const fd = fs.open(path, .{}) catch return .missing;
    defer io.close(fd) catch {};
    const st = fs.fstat(fd) catch return .missing;
    if (st.kind != .file) return .missing;
    if (st.size > MAX_PAGE) {
        cli.warn(name, "manual page too large");
        return .failed;
    }
    const room = src_buf.len - src_len;
    if (st.size > room) {
        cli.warn(name, "too many manual pages at once (total size limit)");
        return .failed;
    }
    const n = io.readAll(fd, src_buf[src_len..][0..@min(MAX_PAGE, room)]) catch |e| {
        cli.warnErr(path, e);
        return .failed;
    };
    if (n == 0) {
        cli.warn(name, "manual page is empty");
        return .failed;
    }
    const page = src_buf[src_len..][0..n];
    src_len += n;
    return .{ .page = page };
}

/// MANPATH の各ディレクトリ（`:` 区切り。空の要素は飛ばす）
const PathIter = struct {
    rest: []const u8,
    done: bool = false,

    fn next(self: *PathIter) ?[]const u8 {
        while (!self.done) {
            var end: usize = 0;
            while (end < self.rest.len and self.rest[end] != ':') end += 1;
            const d = self.rest[0..end];
            if (end == self.rest.len) self.done = true else self.rest = self.rest[end + 1 ..];
            if (d.len > 0) return d;
        }
        return null;
    }
};

/// [name] のページを探して読む。見つからなければ知らせて false
fn load(name: []const u8, section: ?[]const u8, langs: *const Langs, manpath: []const u8) bool {
    if (safeComponent(name)) {
        const one = [_][]const u8{section orelse ""};
        const secs: []const []const u8 = if (section != null) &one else &DEFAULT_SECTIONS;
        // 節の順を先に（`man kill` は日本語の kill(1) がなくても英語の kill(1) を、ほかの節の日本語より先に出す）
        for (secs) |sec| {
            for (langs.slice()) |lang| {
                var it: PathIter = .{ .rest = manpath };
                while (it.next()) |dir| {
                    const path = pagePath(dir, lang, sec, name) orelse continue;
                    const page = switch (readPage(path, name)) {
                        .missing => continue,
                        .failed => return false,
                        .page => |p| p,
                    };
                    if (npages >= pages.len) {
                        cli.warn(name, "too many pages");
                        return false;
                    }
                    pages[npages] = page;
                    npages += 1;
                    return true;
                }
            }
        }
    }
    cli.stdout.flush();
    io.err("man: no manual entry for ");
    var w: cli.Writer = .{ .fd = io.STDERR, .buf = &err_buf };
    w.printable(name);
    w.flush();
    if (section) |s| {
        io.err(" in section ");
        io.err(s);
    }
    io.err("\n");
    return false;
}

var err_buf: [256]u8 = undefined;

// ---- -k ----

const MAX_HITS = 2048;
var hit_buf: [64 * 1024]u8 = undefined;
var hit_len: usize = 0;
/// 見つけたページ（`名前(節) - 説明`）
var hits: [MAX_HITS][]const u8 = undefined;
var nhits: usize = 0;
/// 合ったページの `名前.節`（言語の重なりを除く）
var seen: [MAX_HITS][]const u8 = undefined;
var nseen: usize = 0;
var seen_buf: [32 * 1024]u8 = undefined;
var seen_len: usize = 0;
var dir_buf: [4096]u8 = undefined;
var sub_buf: [4096]u8 = undefined;
var base_buf: [fs.MAX_PATH]u8 = undefined;
var sec_path_buf: [fs.MAX_PATH]u8 = undefined;
var file_path_buf: [fs.MAX_PATH]u8 = undefined;
var head_buf: [8192]u8 = undefined;

fn keyword(words: []const []const u8, langs: *const Langs, manpath: []const u8) u8 {
    for (langs.slice()) |lang| {
        var it: PathIter = .{ .rest = manpath };
        while (it.next()) |dir| {
            const base = if (lang.len > 0) (cli.join(&base_buf, dir, lang) catch continue) else dir;
            scanBase(base, words);
        }
    }
    if (nhits == 0) {
        cli.stdout.flush();
        io.err("man: ");
        var w: cli.Writer = .{ .fd = io.STDERR, .buf = &err_buf };
        w.printable(words[0]);
        w.flush();
        io.err(": nothing appropriate\n");
        return 1;
    }
    cli.sort(hits[0..nhits]);
    for (hits[0..nhits]) |h| {
        cli.stdout.printable(h);
        cli.stdout.byte('\n');
    }
    return 0;
}

/// `base/man*/` の各ページを見る
fn scanBase(base: []const u8, words: []const []const u8) void {
    const fd = fs.open(base, .{ .directory = true }) catch return;
    defer io.close(fd) catch {};
    var d = fs.Dir.init(fd, &dir_buf);
    while (d.next() catch null) |e| {
        if (e.kind != .directory or !cli.startsWith(e.name, "man") or !isSection(e.name[3..])) continue;
        const sec = e.name[3..];
        const sec_path = cli.join(&sec_path_buf, base, e.name) catch continue;
        const sfd = fs.open(sec_path, .{ .directory = true }) catch continue;
        defer io.close(sfd) catch {};
        var sd = fs.Dir.init(sfd, &sub_buf);
        while (sd.next() catch null) |f| {
            if (f.kind != .file) continue;
            // `名前.節` だけ（節が違うもの・`.` で始まるものは飛ばす）
            if (f.name.len <= sec.len + 1 or f.name[0] == '.') continue;
            const dot = f.name.len - sec.len - 1;
            if (f.name[dot] != '.' or !cli.eql(f.name[dot + 1 ..], sec)) continue;
            // 先に見た言語で合ったページは飛ばす（合わなかったときは、次の言語（英語）のページでも探す）
            if (isSeen(f.name)) continue;
            const fpath = cli.join(&file_path_buf, sec_path, f.name) catch continue;
            const desc = nameLine(fpath) orelse continue;
            if (!matchesAny(f.name[0..dot], desc, words)) continue;
            markSeen(f.name);
            addHit(f.name[0..dot], sec, desc);
        }
    }
}

fn isSeen(key: []const u8) bool {
    for (seen[0..nseen]) |s| if (cli.eql(s, key)) return true;
    return false;
}

fn markSeen(key: []const u8) void {
    if (nseen >= seen.len or seen_len + key.len > seen_buf.len) return;
    @memcpy(seen_buf[seen_len..][0..key.len], key);
    seen[nseen] = seen_buf[seen_len..][0..key.len];
    seen_len += key.len;
    nseen += 1;
}

/// ページの NAME の節（最初の `##` の節）の最初の行（頭の 8 KiB だけ読む）
fn nameLine(path: []const u8) ?[]const u8 {
    const fd = fs.open(path, .{}) catch return null;
    defer io.close(fd) catch {};
    const n = io.readAll(fd, &head_buf) catch return null;
    const s = head_buf[0..n];
    var pos: usize = 0;
    var in_name = false;
    while (pos < s.len) {
        var end = pos;
        while (end < s.len and s[end] != '\n') end += 1;
        var l = s[pos..end];
        pos = end + 1;
        if (l.len > 0 and l[l.len - 1] == '\r') l = l[0 .. l.len - 1];
        if (cli.startsWith(l, "## ")) {
            if (in_name) return null;
            in_name = true;
            continue;
        }
        if (in_name and l.len > 0) return l;
    }
    return null;
}

fn lower(c: u8) u8 {
    return if (c >= 'A' and c <= 'Z') c + 32 else c;
}

fn contains(hay: []const u8, needle: []const u8) bool {
    if (needle.len == 0) return true;
    if (needle.len > hay.len) return false;
    var i: usize = 0;
    while (i + needle.len <= hay.len) : (i += 1) {
        var ok = true;
        for (needle, 0..) |c, k| {
            if (lower(hay[i + k]) != lower(c)) {
                ok = false;
                break;
            }
        }
        if (ok) return true;
    }
    return false;
}

fn matchesAny(name: []const u8, desc: []const u8, words: []const []const u8) bool {
    for (words) |w| if (contains(name, w) or contains(desc, w)) return true;
    return false;
}

/// `名前(節) - 説明` を足す（NAME の行が `名前 - 説明` なら説明だけを使う）
fn addHit(name: []const u8, sec: []const u8, line: []const u8) void {
    var desc = line;
    var i: usize = 0;
    while (i + 3 <= line.len) : (i += 1) {
        if (cli.eql(line[i .. i + 3], " - ")) {
            desc = line[i + 3 ..];
            break;
        }
    }
    const parts = [_][]const u8{ name, "(", sec, ") - ", desc };
    var total: usize = 0;
    for (parts) |p| total += p.len;
    if (nhits >= hits.len or hit_len + total > hit_buf.len) return;
    const start = hit_len;
    for (parts) |p| {
        @memcpy(hit_buf[hit_len..][0..p.len], p);
        hit_len += p.len;
    }
    hits[nhits] = hit_buf[start..hit_len];
    nhits += 1;
}
