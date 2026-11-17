//! `access [add NAME | remove NAME|UUID | transfer NAME]`: コンピューターの持ち主・メンバーを見る・変更を依頼する
//! （`kibi:computer/access`。#110、ADR 0110）。
//!
//! - 引数なし: 状態・持ち主・メンバー（名前と UUID）・保留（名前で足して、本人がまだ使っていないもの）を書く
//! - `add`・`remove`・`transfer`: 依頼を出すだけ。反映は、ゲーム内で承認されたとき（持ち主か OP。持ち主のいないコンピューターの
//!   `transfer` は相手本人、`locked` は OP だけ）。出せたら `access: requested: ...; ...approves it in the game` を書いて 0
//! - 必ず捨てられる依頼は出さない: 持ち主のいない（`unowned`）・読めなかった（`locked`）コンピューターの `add`・`remove` は、
//!   ゲームが届けのときに捨てる（ADR 0110）。同期のエラーは増やせないので、先に `get-access` で状態を見て、書いて 1 で終わる。
//!   `transfer` の相手がオンラインかは、ゲームが届けのときに見る（オフラインなら捨てる）ので、ここでは分からない。そう書き添える
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
    const state = blk: {
        defer kibi.canon.reset();
        const info = access.getAccess() catch |e| {
            cli.warn(arg, describe(e, false));
            return 1;
        };
        break :blk info.state;
    };
    if (kind != .transfer) {
        switch (state) {
            .owned => {},
            .unowned => {
                cli.warn(arg, "the computer has no owner; only transfer works");
                return 1;
            },
            .locked => {
                cli.warn(arg, "the computer is locked (its saved record could not be read); only transfer works");
                return 1;
            },
            _ => {},
        }
    }
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
    cli.stdout.write("; ");
    cli.stdout.write(switch (state) {
        .unowned => "the player approves it in the game",
        .locked => "an operator approves it in the game",
        else => "the owner (or an operator) approves it in the game",
    });
    if (kind == .transfer) cli.stdout.write("; it is dropped unless the player is online");
    cli.stdout.write("\n");
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
