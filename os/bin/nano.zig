//! `/bin/nano`（#105）: nano 風の小さなテキストエディタ。Lua をその場で書く・直す用途。
//!
//! ```
//! nano [+行[,桁]] [ファイル]
//! ```
//!
//! 決めごと（キーの表・画面・上限・保存の手順・今の制限）は docs/adr/0105-nano.md。ソースは `os/bin/nano/` に分けている:
//! `buffer.zig`（テキスト）、`view.zig`（表示）、`state.zig`（状態）。
//!
//! 待っている間は標準入力の読み込み（待機点）で止まるだけにする（時計で起きない）。
//! キーは読んだ分を全部処理してから、まとめて 1 回描く（貼り付けで 1 文字ごとに描かない）。
const kibi = @import("kibi");
const buffer = @import("nano/buffer.zig");
const view = @import("nano/view.zig");
const st = @import("nano/state.zig");

const io = kibi.io;
const fs = kibi.fs;
const cli = kibi.cli;
const tty = kibi.tty;
const keys = kibi.keys;
const Key = keys.Key;

const SYNOPSIS = "nano [+LINE[,COLUMN]] [FILE]";

var reader = keys.Reader.init(io.STDIN);
/// 入力が終わった（TTY が閉じた）
var eof = false;
var quit = false;

/// 切り取った行（^K）。続けて ^K を押すと足していく
var cut_mem: [buffer.MAX_LEN]u8 = undefined;
var cut_len: usize = 0;
var cut_committed: usize = 0;
/// 直前のキーが ^K だった
var last_cut = false;

/// 前に探した語
var search_buf: [256]u8 = undefined;
var search_len: usize = 0;

// ---- キーの案内 ----

const main_shortcuts = [_]st.Shortcut{
    .{ .key = "^G", .label = "Help" },       .{ .key = "^X", .label = "Exit" },
    .{ .key = "^O", .label = "Write Out" },  .{ .key = "^S", .label = "Save" },
    .{ .key = "^K", .label = "Cut" },        .{ .key = "^U", .label = "Paste" },
    .{ .key = "^W", .label = "Where Is" },   .{ .key = "M-W", .label = "Next" },
    .{ .key = "^C", .label = "Location" },   .{ .key = "^_", .label = "Go To Line" },
    .{ .key = "^Y", .label = "Prev Page" },  .{ .key = "PgDn", .label = "Next Page" },
};
const cancel_shortcuts = [_]st.Shortcut{
    .{ .key = "^C", .label = "Cancel" }, .{ .key = "Enter", .label = "Accept" },
};
const yes_no_shortcuts = [_]st.Shortcut{
    .{ .key = "Y", .label = "Yes" },     .{ .key = "N", .label = "No" },
    .{ .key = "^C", .label = "Cancel" },
};
const help_shortcuts = [_]st.Shortcut{
    .{ .key = "^X", .label = "Exit Help" }, .{ .key = "^Y", .label = "Prev Page" },
    .{ .key = "PgDn", .label = "Next Page" },
};

/// 次のキー。読んだ分を処理し終えていたら、描いてから待つ
fn nextKey() ?Key {
    if (eof) return null;
    if (!reader.pending()) view.render();
    const k = reader.next();
    if (k == null) eof = true;
    return k;
}

// ---- 入力欄 ----

/// 状態の行で 1 行を入力させる。Enter で入力、^C・Esc で null
fn prompt(label: []const u8, initial: []const u8) ?[]const u8 {
    st.mode = .prompt;
    st.prompt_label = label;
    st.shortcuts = &cancel_shortcuts;
    defer {
        st.mode = .edit;
        st.shortcuts = &main_shortcuts;
    }
    st.prompt_len = @min(initial.len, st.prompt_buf.len);
    @memcpy(st.prompt_buf[0..st.prompt_len], initial[0..st.prompt_len]);
    while (nextKey()) |k| {
        switch (k.kind) {
            .text => {
                const s = k.slice();
                if (st.prompt_len + s.len <= st.prompt_buf.len) {
                    @memcpy(st.prompt_buf[st.prompt_len..][0..s.len], s);
                    st.prompt_len += s.len;
                }
            },
            .escape => return null,
            .ctrl => switch (k.c) {
                0x03 => return null,
                '\r', '\n' => return st.promptText(),
                0x7f, 0x08 => {
                    while (st.prompt_len > 0) {
                        st.prompt_len -= 1;
                        if (!kibi.text.isContinuation(st.prompt_buf[st.prompt_len])) break;
                    }
                },
                0x15 => st.prompt_len = 0,
                else => {},
            },
            else => {},
        }
    }
    return null;
}

const Answer = enum { yes, no, cancel };

fn ask(label: []const u8) Answer {
    st.mode = .prompt;
    st.prompt_label = label;
    st.prompt_len = 0;
    st.shortcuts = &yes_no_shortcuts;
    defer {
        st.mode = .edit;
        st.shortcuts = &main_shortcuts;
    }
    while (nextKey()) |k| {
        switch (k.kind) {
            .text => switch (k.bytes[0]) {
                'y', 'Y' => return .yes,
                'n', 'N' => return .no,
                else => {},
            },
            .escape => return .cancel,
            .ctrl => if (k.c == 0x03) return .cancel,
            else => {},
        }
    }
    return .cancel;
}

fn showHelp() void {
    st.mode = .help;
    st.help_top = 0;
    st.shortcuts = &help_shortcuts;
    defer {
        st.mode = .edit;
        st.shortcuts = &main_shortcuts;
    }
    const step = @max(1, view.textRows() -| 2);
    while (nextKey()) |k| {
        switch (k.kind) {
            .up => st.help_top -|= 1,
            .down => st.help_top += 1,
            .page_up => st.help_top -|= step,
            .page_down => st.help_top += step,
            .ctrl => switch (k.c) {
                0x10 => st.help_top -|= 1,
                0x0e => st.help_top += 1,
                0x19 => st.help_top -|= step,
                0x16 => st.help_top += step,
                0x18, 0x07, 0x03, '\r' => return,
                else => {},
            },
            .escape, .function => return,
            .text => if (k.bytes[0] == 'q' or k.bytes[0] == 'Q') return,
            else => {},
        }
    }
}

// ---- 動く ----

fn lineStartOfCursor() usize {
    return buffer.lineStart(st.cur);
}

fn ensureWant() void {
    if (st.want_dirty) {
        st.want = view.columnOf(lineStartOfCursor(), st.cur);
        st.want_dirty = false;
    }
}

fn moveLeft() void {
    const s = lineStartOfCursor();
    if (st.cur > s) {
        st.cur = view.prevGlyph(s, st.cur);
    } else if (st.cur > 0) {
        st.cur -= 1;
        st.cur_line -= 1;
    }
    st.want_dirty = true;
}

fn moveRight() void {
    const g = view.glyph(st.cur, 0);
    if (g.kind != .end) {
        st.cur += g.len;
    } else if (st.cur < buffer.len) {
        st.cur += 1;
        st.cur_line += 1;
    }
    st.want_dirty = true;
}

/// 上下に [n] 行動く。動いた行数
fn moveVertical(up: bool, n: usize) usize {
    ensureWant();
    var s = lineStartOfCursor();
    var moved: usize = 0;
    while (moved < n) : (moved += 1) {
        s = (if (up) buffer.prevLine(s) else buffer.nextLine(s)) orelse break;
    }
    if (up) st.cur_line -= moved else st.cur_line += moved;
    st.cur = view.positionAt(s, st.want);
    return moved;
}

fn page(down: bool) void {
    const n = @max(1, view.textRows() -| 2);
    const moved = moveVertical(!down, n);
    var k: usize = 0;
    while (k < moved) : (k += 1) {
        if (down) {
            st.top = buffer.nextLine(st.top) orelse break;
            st.top_line += 1;
        } else {
            st.top = buffer.prevLine(st.top) orelse break;
            st.top_line -= 1;
        }
    }
}

fn goHome() void {
    st.cur = lineStartOfCursor();
    st.want_dirty = true;
}

fn goEnd() void {
    st.cur = buffer.lineEnd(st.cur);
    st.want_dirty = true;
}

/// テキストの位置 [pos] へ動く（行の番号は、今の位置から数える）
fn jumpTo(pos: usize) void {
    if (pos > st.cur) {
        var i = st.cur;
        while (i < pos) : (i += 1) st.cur_line += @intFromBool(buffer.at(i) == '\n');
    } else {
        var i = pos;
        while (i < st.cur) : (i += 1) st.cur_line -= @intFromBool(buffer.at(i) == '\n');
    }
    st.cur = pos;
    st.want_dirty = true;
    st.center = true;
}

/// [line] 行目（1 から）の [col] 桁目（1 から）へ
fn gotoLine(line: usize, col: usize) void {
    const target = @max(1, @min(line, buffer.lines)) - 1;
    var s = lineStartOfCursor();
    var l = st.cur_line;
    while (l < target) : (l += 1) s = buffer.nextLine(s) orelse break;
    while (l > target) : (l -= 1) s = buffer.prevLine(s) orelse break;
    st.cur = view.positionAt(s, col -| 1);
    st.cur_line = l;
    st.want_dirty = true;
    st.center = true;
}

// ---- 変える ----

fn reportFull(e: buffer.Error) void {
    st.setMsg(switch (e) {
        error.TooBig => "The buffer is full (4 MiB)",
        error.OutOfMemory => "Not enough memory",
    });
}

fn insert(bytes: []const u8) void {
    buffer.insert(st.cur, bytes) catch |e| return reportFull(e);
    // 画面の上の行より前に足したときは、上の行の位置がずれるので、カーソルの行から求め直す
    // （同じかたまりの中で上へ出てから入力したとき。[removeRange] と同じ扱い）
    const before_top = st.cur < st.top;
    st.cur += bytes.len;
    for (bytes) |c| st.cur_line += @intFromBool(c == '\n');
    st.modified = true;
    st.want_dirty = true;
    if (before_top) {
        st.top = buffer.lineStart(st.cur);
        st.top_line = st.cur_line;
    }
}

/// [a, b) を消す（カーソルは [a] へ）
fn removeRange(a: usize, b: usize) void {
    if (b <= a) return;
    if (a < st.cur) {
        var i = a;
        while (i < @min(b, st.cur)) : (i += 1) st.cur_line -= @intFromBool(buffer.at(i) == '\n');
    }
    buffer.remove(a, b);
    st.cur = a;
    st.modified = true;
    st.want_dirty = true;
    if (a < st.top) {
        st.top = buffer.lineStart(st.cur);
        st.top_line = st.cur_line;
    }
}

fn backspace() void {
    const s = lineStartOfCursor();
    if (st.cur > s) {
        removeRange(view.prevGlyph(s, st.cur), st.cur);
    } else if (st.cur > 0) {
        removeRange(st.cur - 1, st.cur);
    }
}

fn deleteForward() void {
    const g = view.glyph(st.cur, 0);
    if (g.kind != .end) {
        removeRange(st.cur, st.cur + g.len);
    } else if (st.cur < buffer.len) {
        removeRange(st.cur, st.cur + 1);
    }
}

fn ensureCut(n: usize) bool {
    while (cut_committed < n) {
        const end = @min(cut_committed + buffer.CHUNK, cut_mem.len);
        if (!kibi.memory.commit(cut_mem[cut_committed..end], 0)) return false;
        cut_committed = end;
    }
    return true;
}

fn cutLine() void {
    const s = lineStartOfCursor();
    const e = buffer.lineEnd(s);
    const stop = if (e < buffer.len) e + 1 else e;
    if (!last_cut) cut_len = 0;
    if (stop == s) return;
    const n = stop - s;
    if (n > cut_mem.len - cut_len or !ensureCut(cut_len + n)) {
        st.setMsg("Not enough memory to cut");
        return;
    }
    _ = buffer.copy(s, cut_mem[cut_len..][0..n]);
    cut_len += n;
    removeRange(s, stop);
}

fn paste() void {
    if (cut_len == 0) {
        st.setMsg("Cutbuffer is empty");
        return;
    }
    insert(cut_mem[0..cut_len]);
}

// ---- 探す・位置 ----

fn search(again: bool) void {
    var needle: []const u8 = search_buf[0..search_len];
    if (!again) {
        var label_buf: [300]u8 = undefined;
        var label: []const u8 = "Search: ";
        if (search_len > 0) {
            const pre = "Search [";
            const shown = search_buf[0..@min(search_len, 40)];
            const post = "]: ";
            @memcpy(label_buf[0..pre.len], pre);
            @memcpy(label_buf[pre.len..][0..shown.len], shown);
            @memcpy(label_buf[pre.len + shown.len ..][0..post.len], post);
            label = label_buf[0 .. pre.len + shown.len + post.len];
        }
        const input = prompt(label, "") orelse {
            st.setMsg("Cancelled");
            return;
        };
        if (input.len > 0) {
            search_len = @min(input.len, search_buf.len);
            @memcpy(search_buf[0..search_len], input[0..search_len]);
            needle = search_buf[0..search_len];
        }
    }
    if (needle.len == 0) {
        st.setMsg("No current search pattern");
        return;
    }
    const from = if (st.cur + 1 <= buffer.len) st.cur + 1 else 0;
    const pos = buffer.find(needle, from) orelse {
        st.clearMsg();
        st.msgAdd("\"");
        st.msgAdd(needle);
        st.msgAdd("\" not found");
        return;
    };
    if (pos == st.cur) {
        st.setMsg("This is the only occurrence");
    } else if (pos < st.cur) {
        st.setMsg("Search Wrapped");
    }
    jumpTo(pos);
}

fn showPosition() void {
    const s = lineStartOfCursor();
    const e = buffer.lineEnd(s);
    const col = view.columnOf(s, st.cur) + 1;
    const width = view.columnOf(s, e) + 1;
    st.clearMsg();
    st.msgAdd("line ");
    st.msgNum(st.cur_line + 1);
    st.msgAdd("/");
    st.msgNum(buffer.lines);
    st.msgAdd(" (");
    st.msgNum((st.cur_line + 1) * 100 / buffer.lines);
    st.msgAdd("%), col ");
    st.msgNum(col);
    st.msgAdd("/");
    st.msgNum(width);
    st.msgAdd(" (");
    st.msgNum(col * 100 / width);
    st.msgAdd("%), byte ");
    st.msgNum(st.cur);
    st.msgAdd("/");
    st.msgNum(buffer.len);
}

fn askGoto() void {
    const input = prompt("Enter line number, column number: ", "") orelse {
        st.setMsg("Cancelled");
        return;
    };
    var comma: ?usize = null;
    for (input, 0..) |c, i| {
        if (c == ',') {
            comma = i;
            break;
        }
    }
    const line = io.parseUnsigned(trim(input[0 .. comma orelse input.len]));
    const col: ?u64 = if (comma) |i| io.parseUnsigned(trim(input[i + 1 ..])) else 1;
    if (line == null or col == null or line.? == 0 or col.? == 0) {
        st.setMsg("Invalid line or column number");
        return;
    }
    gotoLine(@intCast(@min(line.?, 1 << 31)), @intCast(@min(col.?, 1 << 31)));
}

fn trim(s: []const u8) []const u8 {
    var a: usize = 0;
    var b = s.len;
    while (a < b and s[a] == ' ') a += 1;
    while (b > a and s[b - 1] == ' ') b -= 1;
    return s[a..b];
}

// ---- 保存 ----

/// 書いた行の数（nano と同じく、改行で終わらない最後の行も 1 行に数える）
fn lineCount() usize {
    const last_open = buffer.len > 0 and buffer.at(buffer.len - 1) != '\n';
    return buffer.lines - 1 + @intFromBool(last_open);
}

fn writeDirect(path: []const u8) kibi.Error!void {
    const fd = try fs.open(path, .{ .read = false, .write = true, .create = true, .truncate = true });
    defer io.close(fd) catch {};
    try buffer.writeTo(fd);
}

/// 同じディレクトリの一時ファイルに書いてから、名前を変えて置き換える
fn writeReplace(path: []const u8) kibi.Error!void {
    var abs_buf: [fs.MAX_PATH]u8 = undefined;
    const abs = try fs.absolute(&abs_buf, path);
    var tmp_buf: [fs.MAX_PATH]u8 = undefined;
    const dir = cli.dirname(abs);
    const base = cli.basename(abs);
    var attempt: u8 = 0;
    while (true) : (attempt += 1) {
        // `<dir>/.<名前>.nano<n>`
        var name_buf: [fs.MAX_PATH]u8 = undefined;
        const suffix = ".nano";
        const need = 1 + base.len + suffix.len + 1;
        if (need > name_buf.len) return error.NAMETOOLONG;
        name_buf[0] = '.';
        @memcpy(name_buf[1..][0..base.len], base);
        @memcpy(name_buf[1 + base.len ..][0..suffix.len], suffix);
        name_buf[need - 1] = '0' + attempt;
        const tmp = try cli.join(&tmp_buf, dir, name_buf[0..need]);
        const fd = fs.open(tmp, .{ .read = false, .write = true, .create = true, .exclusive = true }) catch |e| {
            if (e == error.EXIST and attempt < 9) continue;
            return e;
        };
        buffer.writeTo(fd) catch |e| {
            io.close(fd) catch {};
            fs.unlink(tmp) catch {};
            return e;
        };
        io.close(fd) catch |e| {
            fs.unlink(tmp) catch {};
            return e;
        };
        fs.rename(tmp, abs) catch |e| {
            fs.unlink(tmp) catch {};
            return e;
        };
        return;
    }
}

/// [path] に書く。書けたら true（書けなければ知らせを出し、バッファはそのまま）
fn save(path: []const u8) bool {
    var link = false;
    var old_size: ?u64 = null;
    if (fs.lstat(path)) |s| {
        if (s.kind == .directory) return saveFailed(path, error.ISDIR);
        link = s.kind == .symlink;
        old_size = s.size;
    } else |e| {
        if (e != error.NOENT) return saveFailed(path, e);
    }
    if (link) {
        // シンボリックリンクは置き換えると切れるので、リンクの先にそのまま書く
        writeDirect(path) catch |e| return saveFailed(path, e);
    } else {
        writeReplace(path) catch |e| {
            // 一時ファイルの分の容量・名前がないとき、元より大きくならないなら上書きで書く
            const fallback = (e == error.NOSPC or e == error.DQUOT or e == error.NAMETOOLONG) and
                old_size != null and buffer.len <= old_size.?;
            if (!fallback) return saveFailed(path, e);
            writeDirect(path) catch |e2| return saveFailed(path, e2);
        };
    }
    st.modified = false;
    st.setName(path);
    st.clearMsg();
    st.msgAdd("Wrote ");
    st.msgNum(lineCount());
    st.msgAdd(if (lineCount() == 1) " line" else " lines");
    return true;
}

fn saveFailed(path: []const u8, e: kibi.Error) bool {
    st.clearMsg();
    st.msgAdd("Error writing ");
    st.msgAdd(path);
    st.msgAdd(": ");
    st.msgAdd(kibi.errno.describe(e));
    return false;
}

/// 名前を聞いて書く（^O）。書けたら true
fn writeOut() bool {
    const input = prompt("File Name to Write: ", st.name()) orelse {
        st.setMsg("Cancelled");
        return false;
    };
    if (input.len == 0) {
        st.setMsg("Cancelled");
        return false;
    }
    var path_buf: [fs.MAX_PATH]u8 = undefined;
    @memcpy(path_buf[0..input.len], input);
    const path = path_buf[0..input.len];
    if (!cli.eql(path, st.name())) {
        if (fs.stat(path)) |_| {
            if (ask("File exists -- OVERWRITE? ") != .yes) {
                st.setMsg("Cancelled");
                return false;
            }
        } else |_| {}
    }
    return save(path);
}

/// ^S。名前がなければ ^O と同じ
fn saveCurrent() bool {
    if (st.name_len == 0) return writeOut();
    var path_buf: [fs.MAX_PATH]u8 = undefined;
    const n = st.name_len;
    @memcpy(path_buf[0..n], st.name());
    return save(path_buf[0..n]);
}

fn exitEditor() void {
    if (!st.modified) {
        quit = true;
        return;
    }
    switch (ask("Save modified buffer? ")) {
        .yes => if (saveCurrent()) {
            quit = true;
        },
        .no => quit = true,
        .cancel => st.setMsg("Cancelled"),
    }
}

/// 入力が終わったとき、保存していない編集を `<名前>.save` に残す。
/// 同じ名前のファイルがあれば上書きせず、`<名前>.save.1`、`.save.2` … と番号を付けて避ける
fn emergencySave() void {
    if (!st.modified) return;
    var buf: [fs.MAX_PATH]u8 = undefined;
    const base: []const u8 = if (st.name_len > 0) st.name() else "nano";
    const suffix = ".save";
    if (base.len + suffix.len > buf.len) return;
    @memcpy(buf[0..base.len], base);
    @memcpy(buf[base.len..][0..suffix.len], suffix);
    const stem = base.len + suffix.len;
    var n: u32 = 0;
    while (n <= 999) : (n += 1) {
        var len = stem;
        if (n > 0) {
            var digits: [4]u8 = undefined;
            var d: usize = digits.len;
            var v = n;
            while (v > 0) : (v /= 10) {
                d -= 1;
                digits[d] = '0' + @as(u8, @intCast(v % 10));
            }
            const dl = digits.len - d;
            if (stem + 1 + dl > buf.len) return;
            buf[stem] = '.';
            @memcpy(buf[stem + 1 ..][0..dl], digits[d..]);
            len = stem + 1 + dl;
        }
        const fd = fs.open(buf[0..len], .{ .read = false, .write = true, .create = true, .exclusive = true }) catch |e| {
            if (e == error.EXIST) continue;
            return;
        };
        defer io.close(fd) catch {};
        buffer.writeTo(fd) catch {};
        return;
    }
}

// ---- キー ----

fn unbound() void {
    st.setMsg("Unbound key");
}

fn handle(k: Key) void {
    const was_cut = last_cut;
    last_cut = false;
    switch (k.kind) {
        .text => insert(k.slice()),
        .up => if (k.mods.ctrl) unbound() else {
            _ = moveVertical(true, 1);
        },
        .down => if (k.mods.ctrl) unbound() else {
            _ = moveVertical(false, 1);
        },
        .left => moveLeft(),
        .right => moveRight(),
        .home => if (k.mods.ctrl) jumpTo(0) else goHome(),
        .end => if (k.mods.ctrl) jumpTo(buffer.len) else goEnd(),
        .page_up => page(false),
        .page_down => page(true),
        .delete => deleteForward(),
        .insert => {},
        .function => if (k.c == 1) showHelp() else unbound(),
        .escape, .unknown => {},
        .alt => switch (k.c) {
            'w', 'W' => search(true),
            'g', 'G' => askGoto(),
            '\\' => jumpTo(0),
            '/' => jumpTo(buffer.len),
            else => unbound(),
        },
        .ctrl => switch (k.c) {
            0x07 => showHelp(),
            0x18 => exitEditor(),
            0x0f => {
                _ = writeOut();
            },
            0x13 => {
                _ = saveCurrent();
            },
            0x17 => search(false),
            0x0b => {
                last_cut = was_cut;
                cutLine();
                last_cut = true;
            },
            0x15 => paste(),
            0x1f => askGoto(),
            0x03 => showPosition(),
            0x01 => goHome(),
            0x05 => goEnd(),
            0x19 => page(false),
            0x16 => page(true),
            0x06 => moveRight(),
            0x02 => moveLeft(),
            0x10 => {
                _ = moveVertical(true, 1);
            },
            0x0e => {
                _ = moveVertical(false, 1);
            },
            0x04 => deleteForward(),
            0x08, 0x7f => backspace(),
            '\t' => insert("\t"),
            '\r', '\n' => insert("\n"),
            else => unbound(),
        },
    }
}

// ---- 始まり ----

fn openFile(path: []const u8) u8 {
    const s = fs.stat(path) catch |e| {
        if (e == error.NOENT) {
            st.setMsg("New File");
            return 0;
        }
        cli.warnErr(path, e);
        return 1;
    };
    if (s.kind == .directory) {
        cli.warnErr(path, error.ISDIR);
        return 1;
    }
    const fd = fs.open(path, .{}) catch |e| {
        cli.warnErr(path, e);
        return 1;
    };
    defer io.close(fd) catch {};
    buffer.load(fd) catch |e| {
        switch (e) {
            error.TooBig => cli.warn(path, "file is too large (the limit is 4 MiB)"),
            error.OutOfMemory => cli.warn(path, "not enough memory to open the file"),
            else => |x| cli.warnErr(path, x),
        }
        return 1;
    };
    st.clearMsg();
    st.msgAdd("Read ");
    st.msgNum(lineCount());
    st.msgAdd(if (lineCount() == 1) " line" else " lines");
    return 0;
}

pub fn main() u8 {
    cli.setName("nano");
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var file: ?[]const u8 = null;
    var start_line: ?u64 = null;
    var start_col: u64 = 1;
    var options = true;
    for (argv[1..]) |a| {
        if (options and cli.eql(a, "--")) {
            options = false;
        } else if (options and a.len > 1 and a[0] == '+') {
            const v = a[1..];
            var comma: ?usize = null;
            for (v, 0..) |c, i| {
                if (c == ',') {
                    comma = i;
                    break;
                }
            }
            start_line = io.parseUnsigned(v[0 .. comma orelse v.len]) orelse return cli.usage("invalid line number", SYNOPSIS);
            if (comma) |i| start_col = io.parseUnsigned(v[i + 1 ..]) orelse return cli.usage("invalid column number", SYNOPSIS);
        } else if (options and a.len > 1 and a[0] == '-') {
            if (cli.eql(a, "-h") or cli.eql(a, "--help")) {
                io.out("usage: " ++ SYNOPSIS ++ "\n");
                return 0;
            }
            return cli.badOption(if (a[1] == '-') '-' else a[1], SYNOPSIS);
        } else {
            if (file != null) return cli.usage("too many files", SYNOPSIS);
            file = a;
        }
    }
    if (!tty.isTty(io.STDIN) or !tty.isTty(io.STDOUT)) {
        cli.warn(null, "standard input and output must be a terminal");
        return 1;
    }
    if (file) |path| {
        const r = openFile(path);
        if (r != 0) return r;
        st.setName(path);
    }
    if (start_line) |l| {
        gotoLine(@intCast(@min(@max(l, 1), 1 << 31)), @intCast(@min(@max(start_col, 1), 1 << 31)));
    }
    st.shortcuts = &main_shortcuts;

    const saved = tty.getMode(io.STDIN) catch tty.Mode.cooked;
    tty.setMode(io.STDIN, .{ .nl_to_crnl = true }) catch {};
    defer tty.setMode(io.STDIN, saved) catch {};
    view.enter();
    defer view.leave();

    while (!quit) {
        const k = nextKey() orelse break;
        st.clearMsg();
        handle(k);
    }
    if (eof) emergencySave();
    return 0;
}
