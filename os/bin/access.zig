//! `access [add NAME | remove NAME|UUID | transfer NAME]`: コンピューターの持ち主・メンバーを見る・変更を依頼する
//! （`kibi:computer/access`。#110、ADR 0110）。
//!
//! - 引数なし: 状態・持ち主・メンバー（名前と UUID）・保留（名前で足して、本人がまだ使っていないもの）を書く
//! - `add`・`remove`・`transfer`: 依頼を出すだけ。反映は、ゲーム内で持ち主（か OP）が承認したとき。出せたら
//!   `access: requested: ...; the owner approves it in the game` を書いて 0
//! - 知らせ: 承認待ちが多すぎる（`AGAIN`）・このコンピューターでは使えない（`NOTSUP`）・名前の形が違う（`INVAL`）は 1、使い方の誤りは 2
//!
//! ファイルの持ち主・権限とは関係ない（マシンの中では権限を分けない。ADR 0046）
const kibi = @import("kibi");
const cli = kibi.cli;
const access = kibi.access;

const SYNOPSIS = "access [add NAME | remove NAME|UUID | transfer NAME]";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| return cli.badOption(c, SYNOPSIS);
    const operands = opts.rest();
    if (operands.len == 0) return show();
    const verb = operands[0];
    const Kind = enum { add, remove, transfer };
    const kind: Kind = if (cli.eql(verb, "add"))
        .add
    else if (cli.eql(verb, "remove"))
        .remove
    else if (cli.eql(verb, "transfer"))
        .transfer
    else
        return cli.usage("unknown command", SYNOPSIS);
    if (operands.len < 2) return cli.usage("missing operand", SYNOPSIS);
    if (operands.len > 2) return cli.usage("extra operand", SYNOPSIS);
    const arg = operands[1];
    const result = switch (kind) {
        .add => access.requestAdd(arg),
        .remove => access.requestRemove(arg),
        .transfer => access.requestTransfer(arg),
    };
    result catch |e| {
        cli.warn(arg, describe(e, kind == .remove));
        return 1;
    };
    const what = switch (kind) {
        .add => "add ",
        .remove => "remove ",
        .transfer => "transfer to ",
    };
    cli.stdout.write("access: requested: ");
    cli.stdout.write(what);
    cli.stdout.printable(arg);
    cli.stdout.write("; the owner approves it in the game\n");
    cli.stdout.flush();
    return 0;
}

fn describe(e: kibi.Error, remove: bool) []const u8 {
    return switch (e) {
        error.AGAIN => "too many requests are waiting for approval",
        error.NOTSUP => "not supported on this computer",
        error.INVAL => if (remove) "not a player name or UUID" else "not a player name",
        else => kibi.errno.describe(e),
    };
}

fn show() u8 {
    kibi.canon.reset();
    const info = access.getAccess() catch |e| {
        cli.warn(null, describe(e, false));
        return 1;
    };
    const out = &cli.stdout;
    out.write("state: ");
    out.write(switch (info.state) {
        .unowned => "unowned",
        .owned => "owned",
        .locked => "locked",
        _ => "?",
    });
    out.write("\n");
    if (info.owner()) |o| {
        out.write("owner: ");
        player(o);
    }
    const members = info.members();
    if (members.len > 0) {
        out.write("members:\n");
        for (members) |*m| {
            out.write("  ");
            player(m);
        }
    }
    const invites = info.invites();
    if (invites.len > 0) {
        out.write("invites:\n");
        for (invites) |s| {
            out.write("  ");
            out.printable(s.slice());
            out.write("\n");
        }
    }
    out.flush();
    return 0;
}

/// `名前 UUID` の 1 行
fn player(p: *const access.Player) void {
    cli.stdout.printable(p.name.slice());
    cli.stdout.write(" ");
    cli.stdout.printable(p.id.slice());
    cli.stdout.write("\n");
}
