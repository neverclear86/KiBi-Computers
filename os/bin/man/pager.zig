//! man のページ送り（#106）。代替画面に入り、画面の行数 − 1 行ずつ本文を描き、最後の行に `--More--(nn%)` を出す。
//!
//! TTY は raw（出力の LF → CR LF だけ残す。nano と同じ）にする。シグナルも切るので、Ctrl+C・`q` を自分で終わりとして扱う
//! （シグナルで殺されて raw のまま残らないように）。キーを待つ間は、標準入力の読み込みか、標準入力と「保存から戻った」の
//! キューの `poll_oneoff`（#87）で止まるだけ（時計で起きない）。戻ったら、代替画面に入り直して描き直す（ADR 0087）。
//! 画面の大きさが変わっていたら、次に描くときに組み直す（幅が変われば折り直す）。
const kibi = @import("kibi");
const cli = kibi.cli;
const io = kibi.io;
const tty = kibi.tty;
const keys = kibi.keys;

/// 幅 [columns] で全部の行を組み直す（呼び手が持つ）
pub const Layout = *const fn (columns: usize) void;
/// 行の数と行を取り出す
pub const Lines = struct {
    count: *const fn () usize,
    get: *const fn (i: usize) []const u8,
};

var reader = keys.Reader.init(io.STDIN);
var restored: ?kibi.events.Queue = null;
var size: tty.Size = .{ .columns = 80, .rows = 25, .generation = 0 };
var top: usize = 0;

fn out(s: []const u8) void {
    cli.stdout.write(s);
}

fn enter() void {
    // 代替画面に入り、右端で折り返さない（1 行が画面の 1 行になる）
    out("\x1b[?1049h\x1b[?7l");
}

fn leave() void {
    out("\x1b[0m\x1b[?7h\x1b[?1049l");
    cli.stdout.flush();
}

/// 本文に使う行の数（最後の行は案内）
fn pageRows() usize {
    return if (size.rows > 1) size.rows - 1 else 1;
}

fn maxTop(lines: Lines) usize {
    const n = lines.count();
    const page = pageRows();
    return if (n > page) n - page else 0;
}

fn draw(lines: Lines) void {
    const n = lines.count();
    const page = pageRows();
    if (top > maxTop(lines)) top = maxTop(lines);
    out("\x1b[H\x1b[2J");
    var i: usize = 0;
    while (i < page and top + i < n) : (i += 1) {
        out(lines.get(top + i));
        out("\x1b[0m\n");
    }
    // 最後の行へ（本文が短くても同じ位置に案内を出す）
    out("\x1b[");
    cli.stdout.uint(size.rows, 0);
    out(";1H\x1b[7m");
    if (top + page >= n) {
        out("(END)");
    } else {
        out("--More--(");
        cli.stdout.uint(@min((top + page) * 100 / @max(n, 1), 99), 0);
        out("%)");
    }
    out("\x1b[0m\x1b[K");
    cli.stdout.flush();
}

/// 大きさを読み直す。幅が変わったら組み直す（いちばん上の行は、全体の中の割合を保つ）
fn refreshSize(layout: Layout, lines: Lines) void {
    const s = tty.getSize(io.STDOUT) catch return;
    if (s.generation == size.generation and s.columns == size.columns and s.rows == size.rows) return;
    const old_cols = size.columns;
    size = s;
    if (s.columns != old_cols) {
        const before = lines.count();
        layout(s.columns);
        const after = lines.count();
        if (before > 0) top = top * after / before;
    }
}

/// 読んだバイトが残っていなければ、入力か「保存から戻った」を待つ。戻ったら代替画面に入り直して描き直す
fn awaitInput(lines: Lines) void {
    while (restored) |q| {
        if (!reader.idle()) return;
        const ready = kibi.events.waitInputOrQueue(io.STDIN, q) catch {
            restored = null; // 不正なハンドルは閉じずに手放す（drop はトラップする。nano と同じ）
            return;
        };
        if (ready.queue) {
            const redraw = q.drainRestored() catch {
                restored = null;
                return;
            };
            if (redraw) {
                enter();
                draw(lines);
            }
        }
        if (ready.input) return;
    }
}

/// ページを送りながら見せる。q・Ctrl+C・入力の終わりで戻る
pub fn run(layout: Layout, lines: Lines, initial: tty.Size) void {
    size = initial;
    top = 0;
    const saved = tty.getMode(io.STDIN) catch tty.Mode.cooked;
    tty.setMode(io.STDIN, .{ .nl_to_crnl = true }) catch {};
    defer tty.setMode(io.STDIN, saved) catch {};
    enter();
    defer leave();
    restored = kibi.events.openRestored();
    defer if (restored) |q| q.close();

    while (true) {
        refreshSize(layout, lines);
        if (!reader.pending()) {
            draw(lines);
            awaitInput(lines);
        }
        const k = reader.next() orelse return;
        refreshSize(layout, lines);
        const page = pageRows();
        const last = maxTop(lines);
        switch (k.kind) {
            .text => switch (k.slice()[0]) {
                'q', 'Q' => return,
                ' ', 'f' => {
                    if (top >= last) return;
                    top = @min(top + page, last);
                },
                'j', 'e' => top = @min(top + 1, last),
                'k', 'y' => top -|= 1,
                'b' => top -|= page,
                'd' => top = @min(top + page / 2, last),
                'u' => top -|= page / 2,
                'g', '<' => top = 0,
                'G', '>' => top = last,
                else => {},
            },
            .ctrl => switch (k.c) {
                0x03 => return, // Ctrl+C
                '\r', '\n', 0x0e, 0x05 => top = @min(top + 1, last), // Enter・^N・^E
                0x10, 0x19 => top -|= 1, // ^P・^Y
                0x06, 0x16 => top = @min(top + page, last), // ^F・^V
                0x02 => top -|= page, // ^B
                0x04 => top = @min(top + page / 2, last), // ^D
                0x15 => top -|= page / 2, // ^U
                else => {},
            },
            .down => top = @min(top + 1, last),
            .up => top -|= 1,
            .page_down => top = @min(top + page, last),
            .page_up => top -|= page,
            .home => top = 0,
            .end => top = last,
            else => {},
        }
    }
}
