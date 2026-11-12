//! `ps [-e|-A]`: マシンのプロセスを書く（`kibi:process` の `list-processes`。#452・#454）。
//!
//! 列は `PID PPID STAT CMD`。並びはカーネルが返す pid の昇順のまま。STAT は `I`（起動の途中）・`R`（動いている・燃料待ち）・
//! `S`（イベント・ホストの仕事を待っている）・`Z`（終わって刈り取られていない。CMD の後ろに `<defunct>`）。
//! CMD は argv を空白でつないだもの（カーネルが先頭から 16 個・255 バイトまでに切っている）。制御文字は `?` にする。
//! 既定では親なしのゾンビ（終わっていて親がいない）を隠し、`-e`（`-A` も同じ）で全部を出す
const kibi = @import("kibi");
const cli = kibi.cli;
const process = kibi.process;

const SYNOPSIS = "ps [-e|-A]";

pub fn main() u8 {
    const argv = process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var all = false;
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| switch (c) {
        'e', 'A' => all = true,
        else => return cli.badOption(c, SYNOPSIS),
    };
    if (opts.rest().len > 0) return cli.usage("extra operand", SYNOPSIS);

    const list = process.listProcesses();
    var pid_w: usize = 3;
    var ppid_w: usize = 4;
    for (list) |*p| {
        if (!all and p.isOrphanZombie()) continue;
        pid_w = @max(pid_w, cli.digits(p.pid));
        ppid_w = @max(ppid_w, cli.digits(p.parent_pid));
    }
    const out = &cli.stdout;
    out.pad(3, pid_w);
    out.write("PID ");
    out.pad(4, ppid_w);
    out.write("PPID STAT CMD\n");
    for (list) |*p| {
        if (!all and p.isOrphanZombie()) continue;
        out.uint(p.pid, pid_w);
        out.byte(' ');
        out.uint(p.parent_pid, ppid_w);
        out.byte(' ');
        out.write(switch (p.state.tag) {
            .starting => "I",
            .running => "R",
            .waiting => "S",
            .finished => "Z",
            _ => "?",
        });
        out.write("    ");
        const args = p.args();
        if (args.len == 0) out.byte('-');
        for (args, 0..) |a, i| {
            if (i > 0) out.byte(' ');
            out.printable(a.ptr[0..a.len]);
        }
        if (p.state.tag == .finished) out.write(" <defunct>");
        out.byte('\n');
        if (out.failed) break;
    }
    out.flush();
    return @intFromBool(out.failed);
}
