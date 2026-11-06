//! `kill [-s シグナル | -シグナル] pid...`・`kill -l`: プロセスにシグナルを送る（既定は TERM）。
//!
//! シグナルはカーネルが持つ 4 つ（`INT`・`QUIT`・`KILL`・`TERM`）。名前（`SIG` は付けても付けなくてもよい。大小は問わない）か番号（2・3・9・15）で書く
const kibi = @import("kibi");
const cli = kibi.cli;
const process = kibi.process;

const SYNOPSIS = "kill [-s signal | -signal] pid... | kill -l";

const Named = struct { name: []const u8, number: u8, signal: process.Signal };
const signals = [_]Named{
    .{ .name = "INT", .number = 2, .signal = .interrupt },
    .{ .name = "QUIT", .number = 3, .signal = .quit },
    .{ .name = "KILL", .number = 9, .signal = .kill },
    .{ .name = "TERM", .number = 15, .signal = .terminate },
};

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var sig: process.Signal = .terminate;
    var rest: []const []const u8 = if (argv.len > 0) argv[1..] else argv;
    if (rest.len > 0 and cli.eql(rest[0], "-l")) {
        for (signals) |s| {
            cli.stdout.write(s.name);
            cli.stdout.byte('\n');
        }
        cli.stdout.flush();
        return 0;
    }
    if (rest.len > 0 and cli.eql(rest[0], "-s")) {
        if (rest.len < 2) return cli.usage("option requires an argument -- s", SYNOPSIS);
        sig = parseSignal(rest[1]) orelse return badSignal(rest[1]);
        rest = rest[2..];
    } else if (rest.len > 0 and rest[0].len > 1 and rest[0][0] == '-' and !cli.eql(rest[0], "--")) {
        sig = parseSignal(rest[0][1..]) orelse return badSignal(rest[0][1..]);
        rest = rest[1..];
    }
    if (rest.len > 0 and cli.eql(rest[0], "--")) rest = rest[1..];
    if (rest.len == 0) return cli.usage("missing pid", SYNOPSIS);
    var status: u8 = 0;
    for (rest) |p| {
        const pid = kibi.io.parseUnsigned(p) orelse {
            cli.warn(p, "arguments must be process IDs");
            status = 1;
            continue;
        };
        if (pid > 0xffff_ffff) {
            cli.warnErr(p, error.SRCH);
            status = 1;
            continue;
        }
        process.killPid(@intCast(pid), sig) catch |e| {
            cli.warnErr(p, e);
            status = 1;
        };
    }
    return status;
}

fn parseSignal(s0: []const u8) ?process.Signal {
    const s = if (s0.len > 3 and eqlUpper(s0[0..3], "SIG")) s0[3..] else s0;
    if (kibi.io.parseUnsigned(s)) |n| {
        for (signals) |x| if (x.number == n) return x.signal;
        return null;
    }
    for (signals) |x| if (eqlUpper(s, x.name)) return x.signal;
    return null;
}

fn eqlUpper(s: []const u8, upper: []const u8) bool {
    if (s.len != upper.len) return false;
    for (s, upper) |a, b| {
        const u = if (a >= 'a' and a <= 'z') a - 32 else a;
        if (u != b) return false;
    }
    return true;
}

fn badSignal(s: []const u8) u8 {
    cli.warn(s, "invalid signal");
    return 1;
}
