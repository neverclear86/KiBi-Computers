//! `lsdev [-l] [デバイス…]`・`lsdev -w`: つながっているデバイスを書く・接続と切断を見続ける（`kibi:device`。#108、ADR 0108）。
//!
//! - 1 行に 1 台、`address 種類`（カーネルの一覧の順）。デバイスを書くと、当たるものだけを書く（指定の規則は devcall と同じ:
//!   address 全体・address の頭・種類（`screen` は `kibicomputers:screen` に当たる）。英大文字は小文字にそろえる）。
//!   当たるもののない指定は `no such device` を知らせて 1
//! - `-l`: 各デバイスの下に、メソッドを 1 行に 1 つ、字下げして書く。メインスレッドのメソッドには ` (main)` を付ける
//! - `-w`: 今の一覧を書いた後、接続を `+ address 種類`、切断を `- address 種類` で書き続ける（Ctrl+C で終わる）。
//!   キューを先に開いてから一覧を読む（間の接続を落とさない）。一覧を正とし、イベントは差分の知らせとして使う:
//!   source が空のものだけをカーネルのものとして扱い、`restored` を受けたとき・あふれて捨てた分があるときは一覧を読み直して差を書く
//!   （復元の後に重ねて届く `device-attached` は、知っている address なので書かない。ADR 0067）。待つのは `poll_oneoff` だけ
const kibi = @import("kibi");
const cli = kibi.cli;
const device = kibi.device;
const events = kibi.events;
const canon = kibi.canon;

const SYNOPSIS = "lsdev [-l] [device ...] | lsdev -w";

/// 写しておく台数の最大（クリエイティブの CPU の接続数）
const MAX_DEVICES = 256;

const Entry = struct {
    address: [device.ADDRESS_BYTES]u8 = undefined,
    address_len: u8 = 0,
    kind: [device.MAX_KIND_BYTES]u8 = undefined,
    kind_len: u8 = 0,

    fn set(self: *Entry, address: []const u8, kind: []const u8) void {
        const a = @min(address.len, self.address.len);
        @memcpy(self.address[0..a], address[0..a]);
        self.address_len = @intCast(a);
        const k = @min(kind.len, self.kind.len);
        @memcpy(self.kind[0..k], kind[0..k]);
        self.kind_len = @intCast(k);
    }

    fn addr(self: *const Entry) []const u8 {
        return self.address[0..self.address_len];
    }

    fn kindName(self: *const Entry) []const u8 {
        return self.kind[0..self.kind_len];
    }
};

/// 一覧の写し（`canon.reset` の後も読めるように。`-w` は [fresh] と入れ替えて差を取る）
const Table = struct {
    items: [MAX_DEVICES]Entry = undefined,
    len: usize = 0,

    /// `list-devices` を読んで写す
    fn load(self: *Table) void {
        canon.reset();
        const list = device.listDevices();
        self.len = @min(list.len, MAX_DEVICES);
        for (list[0..self.len], 0..) |*d, i| self.items[i].set(d.address.slice(), d.kind.slice());
        canon.reset();
    }

    fn find(self: *const Table, address: []const u8) ?usize {
        for (self.items[0..self.len], 0..) |*e, i| {
            if (cli.eql(e.addr(), address)) return i;
        }
        return null;
    }

    fn add(self: *Table, address: []const u8, kind: []const u8) bool {
        if (self.len >= MAX_DEVICES) return false;
        self.items[self.len].set(address, kind);
        self.len += 1;
        return true;
    }

    fn remove(self: *Table, i: usize) void {
        var k = i;
        while (k + 1 < self.len) : (k += 1) self.items[k] = self.items[k + 1];
        self.len -= 1;
    }
};

var table: Table = .{};
var fresh: Table = .{};

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var long = false;
    var watch = false;
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'l' => long = true,
        'w' => watch = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    const operands = opts.rest();
    if (watch) {
        if (long or operands.len > 0) return cli.usage("-w takes no other options or operands", SYNOPSIS);
        return watchDevices();
    }

    table.load();
    var status: u8 = 0;
    // 指定に当たるものだけを書く（指定がなければ全部）
    var shown: [MAX_DEVICES]bool = undefined;
    for (shown[0..table.len]) |*s| s.* = operands.len == 0;
    for (operands) |op| {
        var buf: [device.MAX_KIND_BYTES]u8 = undefined;
        var any = false;
        if (device.lowerAddress(&buf, op)) |spec| {
            for (table.items[0..table.len], 0..) |*e, i| {
                if (device.match(e.addr(), e.kindName(), spec) != .none) {
                    shown[i] = true;
                    any = true;
                }
            }
        }
        if (!any) {
            warnPrintable(op, "no such device");
            status = 1;
        }
    }
    const out = &cli.stdout;
    for (table.items[0..table.len], 0..) |*e, i| {
        if (!shown[i]) continue;
        line(out, null, e);
        if (long) writeMethods(out, e);
        if (out.failed) break;
    }
    out.flush();
    return if (out.failed) 1 else status;
}

/// `[印 ]address 種類` の 1 行
fn line(out: *cli.Writer, mark: ?u8, e: *const Entry) void {
    if (mark) |m| {
        out.byte(m);
        out.byte(' ');
    }
    out.write(e.addr());
    out.byte(' ');
    out.printable(e.kindName());
    out.byte('\n');
}

/// デバイスを開いてメソッドを書く（1 台ごとに領域を戻す。開けなければ（間に外れた）書かない）
fn writeMethods(out: *cli.Writer, e: *const Entry) void {
    canon.reset();
    const dev = switch (device.Device.open(e.addr())) {
        .ok => |d| d,
        .err => return,
    };
    defer dev.close();
    for (dev.methods()) |*m| {
        out.write("  ");
        out.printable(m.name.slice());
        if (m.mainThread()) out.write(" (main)");
        out.byte('\n');
    }
    canon.reset();
}

/// 指定（argv の文字列）を制御文字を `?` にして知らせる
fn warnPrintable(subject: []const u8, message: []const u8) void {
    var buf: [128]u8 = undefined;
    const n = @min(subject.len, buf.len);
    for (subject[0..n], 0..) |c, i| buf[i] = if (c < 0x20 or c == 0x7f) '?' else c;
    cli.warn(buf[0..n], message);
}

const ATTACHED = "device-attached";
const DETACHED = "device-detached";

fn watchDevices() u8 {
    // キューを先に開いてから一覧を読む（逆だと、その間の接続を落とす）。絞るなら `restored` も入れる（ADR 0087）
    const q = events.Queue.open(&.{ ATTACHED, DETACHED, events.RESTORED }) catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    defer q.close();
    const out = &cli.stdout;
    table.load();
    for (table.items[0..table.len]) |*e| line(out, null, e);
    out.flush();
    while (!out.failed) {
        events.waitQueue(q) catch |e| {
            cli.warnErr(null, e);
            return 1;
        };
        var reload = false;
        while (true) {
            const t = q.takeOne() catch |e| {
                cli.warnErr(null, e);
                return 1;
            };
            if (t.dropped > 0) reload = true;
            const ev = t.event orelse break;
            if (ev.isRestored()) {
                reload = true;
                continue;
            }
            // デバイスが同じ名前で出したもの（source が address）は無視する
            if (ev.source.len != 0) continue;
            apply(out, ev);
        }
        if (reload) resync(out);
        out.flush();
    }
    return 1;
}

/// 接続・切断のイベント 1 件を表に当てて書く（知っている接続・知らない切断は書かない）
fn apply(out: *cli.Writer, ev: *const events.Event) void {
    const vs = ev.values();
    if (vs.len < 2 or vs[0].tag != events.VALUE_TEXT or vs[1].tag != events.VALUE_TEXT) return;
    const address = vs[0].val.text.slice();
    const kind = vs[1].val.text.slice();
    const name = ev.kind.slice();
    if (cli.eql(name, ATTACHED)) {
        if (table.find(address) != null) return;
        if (table.add(address, kind)) line(out, '+', &table.items[table.len - 1]);
    } else if (cli.eql(name, DETACHED)) {
        const i = table.find(address) orelse return;
        line(out, '-', &table.items[i]);
        table.remove(i);
    }
}

/// 一覧を読み直して、表との差を書く
fn resync(out: *cli.Writer) void {
    fresh.load();
    for (table.items[0..table.len]) |*e| {
        if (fresh.find(e.addr()) == null) line(out, '-', e);
    }
    for (fresh.items[0..fresh.len]) |*e| {
        if (table.find(e.addr()) == null) line(out, '+', e);
    }
    table = fresh;
}
