//! `seq [-w] [-s 区切り] [最初 [増分]] 最後`: 数を順に書く（最初・増分の既定は 1）。
//!
//! 数は 10 進の小数（`-1.5`・`.5`。指数はない）。小数の桁は「最初」と「増分」の多い方にそろえる（最大 9 桁）。
//! `-w` は 0 で埋めて幅をそろえる（幅は最初と最後の書き方の長い方）。`-s` は数の間の区切り（既定は改行。最後は改行）。
//! 負の数（`seq -1 1`）は、`-` の次が英字でなければオプションと見なさない
const kibi = @import("kibi");
const cli = kibi.cli;

const SYNOPSIS = "seq [-w] [-s separator] [first [increment]] last";

const MAX_DECIMALS = 9;

/// 小数（[mantissa] / 10^[decimals]）
const Num = struct { mantissa: i64, decimals: u8 };

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var equal_width = false;
    var sep: []const u8 = "\n";
    var i: usize = 1;
    while (i < argv.len) {
        const a = argv[i];
        if (cli.eql(a, "--")) {
            i += 1;
            break;
        }
        if (a.len < 2 or a[0] != '-' or !isLetter(a[1])) break;
        i += 1;
        var k: usize = 1;
        while (k < a.len) : (k += 1) switch (a[k]) {
            'w' => equal_width = true,
            's' => {
                if (k + 1 < a.len) {
                    sep = a[k + 1 ..];
                } else if (i < argv.len) {
                    sep = argv[i];
                    i += 1;
                } else return cli.usage("option requires an argument -- s", SYNOPSIS);
                break;
            },
            else => |c| return cli.badOption(c, SYNOPSIS),
        };
    }
    const ops = argv[@min(i, argv.len)..];
    if (ops.len == 0) return cli.usage("missing operand", SYNOPSIS);
    if (ops.len > 3) return cli.usage("extra operand", SYNOPSIS);
    var nums: [3]Num = undefined;
    for (ops, 0..) |a, k| {
        nums[k] = parse(a) orelse {
            cli.warn(a, "invalid number");
            return cli.USAGE;
        };
    }
    const one = Num{ .mantissa = 1, .decimals = 0 };
    const first = if (ops.len >= 2) nums[0] else one;
    const incr = if (ops.len == 3) nums[1] else one;
    const last = nums[ops.len - 1];
    const prec = @max(first.decimals, incr.decimals);
    const f = scale(first, prec) orelse return tooLarge(ops[0]);
    const step = scale(incr, prec) orelse return tooLarge(ops[1]);
    // 最後は桁を切り捨てずに比べる（`seq 1 1.5` は 1 まで）
    const cmp_prec = @max(prec, last.decimals);
    const f_cmp = scale(first, cmp_prec) orelse return tooLarge(ops[0]);
    const step_cmp = scale(incr, cmp_prec) orelse return tooLarge(ops[1]);
    const l_cmp = scale(last, cmp_prec) orelse return tooLarge(ops[ops.len - 1]);
    if (step == 0) {
        cli.warn(ops[1], "invalid zero increment");
        return cli.USAGE;
    }
    var width: usize = 0;
    if (equal_width) {
        const l = scale(last, prec) orelse return tooLarge(ops[ops.len - 1]);
        width = @max(textLen(f, prec), textLen(l, prec));
    }
    var v = f;
    var v_cmp = f_cmp;
    var n: u64 = 0;
    while (if (step > 0) v_cmp <= l_cmp else v_cmp >= l_cmp) {
        if (n > 0) cli.stdout.write(sep);
        writeNum(v, prec, width);
        if (cli.stdout.failed) return 0;
        n += 1;
        const next = @addWithOverflow(v, step);
        const next_cmp = @addWithOverflow(v_cmp, step_cmp);
        if (next[1] != 0 or next_cmp[1] != 0) break;
        v = next[0];
        v_cmp = next_cmp[0];
    }
    if (n > 0) cli.stdout.byte('\n');
    cli.stdout.flush();
    return 0;
}

fn tooLarge(a: []const u8) u8 {
    cli.warn(a, "number out of range");
    return cli.USAGE;
}

fn isLetter(c: u8) bool {
    return (c >= 'a' and c <= 'z') or (c >= 'A' and c <= 'Z');
}

fn pow10(n: u8) i64 {
    var v: i64 = 1;
    var k: u8 = 0;
    while (k < n) : (k += 1) v *= 10;
    return v;
}

/// [n] を小数 [prec] 桁の整数にする（桁が多ければ切り捨てる。あふれたら null）
fn scale(n: Num, prec: u8) ?i64 {
    if (prec < n.decimals) return @divTrunc(n.mantissa, pow10(n.decimals - prec));
    const m = @mulWithOverflow(n.mantissa, pow10(prec - n.decimals));
    return if (m[1] != 0) null else m[0];
}

fn parse(s: []const u8) ?Num {
    var i: usize = 0;
    var neg = false;
    if (i < s.len and (s[i] == '-' or s[i] == '+')) {
        neg = s[i] == '-';
        i += 1;
    }
    var m: i64 = 0;
    var decimals: u8 = 0;
    var digits: usize = 0;
    var dot = false;
    while (i < s.len) : (i += 1) {
        const c = s[i];
        if (c == '.') {
            if (dot) return null;
            dot = true;
            continue;
        }
        if (c < '0' or c > '9') return null;
        if (dot) {
            if (decimals == MAX_DECIMALS) continue;
            decimals += 1;
        }
        digits += 1;
        const mul = @mulWithOverflow(m, 10);
        if (mul[1] != 0) return null;
        const add = @addWithOverflow(mul[0], @as(i64, c - '0'));
        if (add[1] != 0) return null;
        m = add[0];
    }
    if (digits == 0) return null;
    return .{ .mantissa = if (neg) -m else m, .decimals = decimals };
}

/// [v]（小数 [prec] 桁）を書いたときの長さ
fn textLen(v: i64, prec: u8) usize {
    const mag: u64 = @abs(v);
    const p: u64 = @intCast(pow10(prec));
    var n = cli.digits(mag / p) + @intFromBool(v < 0);
    if (prec > 0) n += 1 + prec;
    return n;
}

fn writeNum(v: i64, prec: u8, width: usize) void {
    const w = &cli.stdout;
    const mag: u64 = @abs(v);
    const p: u64 = @intCast(pow10(prec));
    if (v < 0) w.byte('-');
    var len = textLen(v, prec);
    while (len < width) : (len += 1) w.byte('0');
    w.uint(mag / p, 0);
    if (prec > 0) {
        w.byte('.');
        const frac = mag % p;
        var d = cli.digits(frac);
        while (d < prec) : (d += 1) w.byte('0');
        w.uint(frac, 0);
    }
}
