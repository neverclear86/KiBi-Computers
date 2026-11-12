//! `df [-h|-k] [-i] [パス...]`: マウントの容量と空きを書く（`kibi:fs/mounts` の `list-mounts`。#453・#454）。
//!
//! 列は `Filesystem 1K-blocks Used Available Use% Mounted on`（`-h` は 1024 を単位にした `Size Used Avail`、`-i` は項目の数
//! `Inodes IUsed IFree IUse%`）。Filesystem はマウントの source（`disk:<識別子>`・`tmp`・`dev`・`disk:image:<名前>`）。
//! - 使用量は管理の分（項目ごとの分など）を含むので、`du` の合計とは合わない。`tmp` の空きは目安（RAM はプロセスとも分け合う）
//! - 容量を返さないマウント（起動・復元の途中など）は、数の欄を `-` にする。全体が 0（`dev`）なら `Use%` は `-`。上限のない項目の数（`tmp`）も `-`
//! - `Use%` は切り上げ。KiB も切り上げ
//! - パスを書くと、そのパスがあるマウントだけを書く（`stat` の `dev` が `id` と等しいもの。マウントの祖先の合成のディレクトリは
//!   `dev` が 0 なので、位置の文字列が最も長く一致するマウントにする）
const kibi = @import("kibi");
const cli = kibi.cli;
const fs = kibi.fs;
const mounts = kibi.mounts;

const SYNOPSIS = "df [-h|-k] [-i] [file...]";

/// 書く行の上限（マウントの数は 10 件程度。パスを並べたときも、これを超えた分は書かない）
const MAX_ROWS = 256;
const COLS = 6;

/// 数の欄を書いておく場所（1 欄 24 バイト）
var num_buf: [MAX_ROWS * 4][24]u8 = undefined;
var num_used: usize = 0;
var rows: [MAX_ROWS + 1][COLS][]const u8 = undefined;
var row_count: usize = 0;
var truncated = false;

var human = false;
var inodes = false;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'h' => human = true,
        'k' => human = false,
        'i' => inodes = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const list = mounts.listMounts();
    rows[0] = if (inodes)
        .{ "Filesystem", "Inodes", "IUsed", "IFree", "IUse%", "Mounted on" }
    else if (human)
        .{ "Filesystem", "Size", "Used", "Avail", "Use%", "Mounted on" }
    else
        .{ "Filesystem", "1K-blocks", "Used", "Available", "Use%", "Mounted on" };
    row_count = 1;
    var ok = true;
    const operands = opts.rest();
    if (operands.len == 0) {
        for (list) |*m| addRow(m);
    } else {
        for (operands) |p| {
            const m = find(list, p) catch |e| {
                cli.warnErr(p, e);
                ok = false;
                continue;
            };
            addRow(m);
        }
    }
    // パスを書いてどれも見つからなければ、見出しも書かない
    if (row_count > 1 or operands.len == 0) print();
    if (truncated) {
        cli.warn(null, "too many rows");
        ok = false;
    }
    return @intFromBool(!ok or cli.stdout.failed);
}

/// [path] があるマウント
fn find(list: []const mounts.Mount, path: []const u8) kibi.Error!*const mounts.Mount {
    const st = try fs.stat(path);
    if (st.dev != 0) {
        for (list) |*m| if (m.id == st.dev) return m;
    }
    // 合成のディレクトリ（dev 0）か、一覧を取った後に足されたマウント: 位置の文字列が最も長く一致するもの
    var buf: [fs.MAX_PATH]u8 = undefined;
    const abs = try fs.absolute(&buf, path);
    var best: ?*const mounts.Mount = null;
    for (list) |*m| {
        const point = m.point.slice();
        if (!under(abs, point)) continue;
        if (best == null or point.len >= best.?.point.len) best = m;
    }
    return best orelse error.NOENT;
}

/// [path] が [point] かその下か（どちらも畳んだ絶対パス）
fn under(path: []const u8, point: []const u8) bool {
    if (cli.eql(point, "/")) return true;
    if (!cli.startsWith(path, point)) return false;
    return path.len == point.len or path[point.len] == '/';
}

fn addRow(m: *const mounts.Mount) void {
    if (row_count > MAX_ROWS) {
        truncated = true;
        return;
    }
    var r: [COLS][]const u8 = .{ m.source.slice(), "-", "-", "-", "-", m.point.slice() };
    if (m.usage()) |u| {
        if (inodes) {
            if (u.totalEntries()) |total| {
                r[1] = number(total);
                r[3] = number(total -| u.used_entries);
                r[4] = percent(u.used_entries, total);
            }
            r[2] = number(u.used_entries);
        } else {
            r[1] = size(u.total_bytes);
            r[2] = size(u.used_bytes);
            r[3] = size(u.available_bytes);
            r[4] = percent(u.used_bytes, u.total_bytes);
        }
    }
    rows[row_count] = r;
    row_count += 1;
}

fn slot() []u8 {
    const s = &num_buf[num_used];
    num_used += 1;
    return s;
}

fn number(v: u64) []const u8 {
    return kibi.io.formatUnsigned(slot(), v);
}

/// バイト数を欄にする（既定は KiB の切り上げ、`-h` は単位付き）
fn size(bytes: u64) []const u8 {
    if (!human) return number(bytes / 1024 + @intFromBool(bytes % 1024 != 0));
    return humanSize(slot(), bytes);
}

/// 1024 を単位にした読みやすい形（`512`・`1.5K`・`12M`）。10 未満は小数 1 桁、ほかは整数（どちらも切り上げ）
fn humanSize(buf: []u8, bytes: u64) []const u8 {
    const units = "KMGTPE";
    if (bytes < 1024) return kibi.io.formatUnsigned(buf, bytes);
    var unit: usize = 0;
    var div: u128 = 1024;
    while (unit + 1 < units.len and @as(u128, bytes) >= div * 1024) {
        div *= 1024;
        unit += 1;
    }
    const tenths: u128 = (@as(u128, bytes) * 10 + div - 1) / div;
    var n: usize = 0;
    if (tenths < 100) {
        n = kibi.io.formatUnsigned(buf, @intCast(tenths / 10)).len;
        buf[n] = '.';
        buf[n + 1] = @intCast('0' + tenths % 10);
        n += 2;
    } else {
        const whole: u128 = (@as(u128, bytes) + div - 1) / div;
        n = kibi.io.formatUnsigned(buf, @intCast(whole)).len;
    }
    buf[n] = units[unit];
    return buf[0 .. n + 1];
}

/// 使用率（切り上げ）。全体が 0 なら `-`
fn percent(used: u64, total: u64) []const u8 {
    if (total == 0) return "-";
    const p: u128 = (@as(u128, used) * 100 + total - 1) / total;
    const buf = slot();
    const n = kibi.io.formatUnsigned(buf, @intCast(p)).len;
    buf[n] = '%';
    return buf[0 .. n + 1];
}

/// 欄の幅をそろえて書く（Filesystem は左寄せ、数は右寄せ、最後の欄はそろえない）
fn print() void {
    var width: [COLS]usize = @splat(0);
    for (rows[0..row_count]) |r| {
        for (r, 0..) |cell, i| width[i] = @max(width[i], cli.charCount(cell));
    }
    const out = &cli.stdout;
    for (rows[0..row_count]) |r| {
        out.printable(r[0]);
        out.pad(cli.charCount(r[0]), width[0]);
        for (r[1 .. COLS - 1], 1..) |cell, i| {
            out.byte(' ');
            out.pad(cell.len, width[i]);
            out.write(cell);
        }
        out.byte(' ');
        out.printable(r[COLS - 1]);
        out.byte('\n');
        if (out.failed) break;
    }
    out.flush();
}
