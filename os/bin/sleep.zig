//! `sleep 時間...`: 時間だけ待つ。時間は秒（小数も書ける。`0.5`・`.25`）で、後ろに `s`・`m`・`h`・`d` を付けると秒・分・時・日。
//! 2 つ以上なら合計を待つ。待つ間は `poll_oneoff` の時計の待ち（待機点。CPU を使わない）
const kibi = @import("kibi");
const cli = kibi.cli;

const SYNOPSIS = "sleep seconds[s|m|h|d]...";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    // 負の数（`-1`）はオプションと区別できないので、知らないオプションとして断る
    if (opts.next()) |c| return cli.badOption(c, SYNOPSIS);
    const operands = opts.rest();
    if (operands.len == 0) return cli.usage("missing operand", SYNOPSIS);
    var total: u64 = 0;
    for (operands) |a| {
        const ns = parseDuration(a) orelse {
            cli.warn(a, "invalid time interval");
            return cli.USAGE;
        };
        total +|= ns;
    }
    kibi.time.sleep(total) catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    return 0;
}

/// `秒[.小数][単位]` をナノ秒にする（大きすぎれば上限に張り付く）。書き方が違えば null
fn parseDuration(s: []const u8) ?u64 {
    var end = s.len;
    var unit: u64 = 1;
    if (end > 0) {
        switch (s[end - 1]) {
            's' => end -= 1,
            'm' => {
                unit = 60;
                end -= 1;
            },
            'h' => {
                unit = 3600;
                end -= 1;
            },
            'd' => {
                unit = 86400;
                end -= 1;
            },
            else => {},
        }
    }
    const num = s[0..end];
    var whole: u64 = 0;
    var frac: u64 = 0; // ナノ秒の桁（9 桁）
    var scale: u64 = 100_000_000;
    var digits: usize = 0;
    var dot = false;
    for (num) |c| {
        if (c == '.') {
            if (dot) return null;
            dot = true;
            continue;
        }
        if (c < '0' or c > '9') return null;
        digits += 1;
        const d: u64 = c - '0';
        if (dot) {
            frac += d * scale;
            scale /= 10;
        } else {
            whole = whole *| 10 +| d;
        }
    }
    if (digits == 0) return null;
    return (whole *| unit *| 1_000_000_000) +| (frac *| unit);
}
