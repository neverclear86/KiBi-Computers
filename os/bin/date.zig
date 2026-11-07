//! `date [-u] [+書式]`: 今の日時を書く。時刻はいつも UTC（タイムゾーンはまだない。`-u` は受け付けるだけ）。
//!
//! 既定の書式は `%a %b %e %H:%M:%S UTC %Y`（`Sat Nov  7 12:34:56 UTC 2026`）。書式で使えるもの:
//! `%Y %C %y %m %d %e %H %I %M %S %p %j %a %A %b %h %B %u %w %s %N %F %T %D %R %Z %z %n %t %%`。
//! ほかの `%x` はそのまま書く。日時を変える（`date 時刻`）口はない
const kibi = @import("kibi");
const cli = kibi.cli;
const time = kibi.time;

const SYNOPSIS = "date [-u] [+format]";

const DEFAULT_FORMAT = "%a %b %e %H:%M:%S %Z %Y";

const days = [_][]const u8{ "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
const months = [_][]const u8{ "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December" };

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'u' => {},
        else => return cli.badOption(c, SYNOPSIS),
    };
    const rest = opts.rest();
    var format: []const u8 = DEFAULT_FORMAT;
    if (rest.len > 1) return cli.usage("extra operand", SYNOPSIS);
    if (rest.len == 1) {
        if (rest[0].len == 0 or rest[0][0] != '+') {
            cli.warn(rest[0], "setting the date is not supported");
            return 1;
        }
        format = rest[0][1..];
    }
    const ns = time.realtime() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    writeFormat(format, ns);
    cli.stdout.byte('\n');
    cli.stdout.flush();
    return 0;
}

fn writeFormat(format: []const u8, ns: u64) void {
    const secs = ns / 1_000_000_000;
    const t = time.civil(secs);
    const w = &cli.stdout;
    var i: usize = 0;
    while (i < format.len) : (i += 1) {
        const c = format[i];
        if (c != '%' or i + 1 == format.len) {
            w.byte(c);
            continue;
        }
        i += 1;
        switch (format[i]) {
            'Y' => w.uint(t.year, 0),
            'C' => num(t.year / 100, 2),
            'y' => num(t.year % 100, 2),
            'm' => num(t.month, 2),
            'd' => num(t.day, 2),
            'e' => w.uint(t.day, 2),
            'H' => num(t.hour, 2),
            'I' => num(if (t.hour % 12 == 0) 12 else t.hour % 12, 2),
            'M' => num(t.minute, 2),
            'S' => num(t.second, 2),
            'p' => w.write(if (t.hour < 12) "AM" else "PM"),
            'j' => num(t.yday, 3),
            'a' => w.write(days[t.weekday][0..3]),
            'A' => w.write(days[t.weekday]),
            'b', 'h' => w.write(months[t.month - 1][0..3]),
            'B' => w.write(months[t.month - 1]),
            'u' => w.uint(if (t.weekday == 0) 7 else t.weekday, 0),
            'w' => w.uint(t.weekday, 0),
            's' => w.uint(secs, 0),
            'N' => num(ns % 1_000_000_000, 9),
            'F' => writeFormat("%Y-%m-%d", ns),
            'T' => writeFormat("%H:%M:%S", ns),
            'D' => writeFormat("%m/%d/%y", ns),
            'R' => writeFormat("%H:%M", ns),
            'Z' => w.write("UTC"),
            'z' => w.write("+0000"),
            'n' => w.byte('\n'),
            't' => w.byte('\t'),
            '%' => w.byte('%'),
            else => |x| {
                w.byte('%');
                w.byte(x);
            },
        }
    }
}

/// [v] を [width] 桁（左を 0 で埋める）で書く
fn num(v: u64, width: usize) void {
    var i = cli.digits(v);
    while (i < width) : (i += 1) cli.stdout.byte('0');
    cli.stdout.uint(v, 0);
}
