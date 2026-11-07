//! `env [-i] [-u 名前]... [名前=値]... [コマンド [引数...]]`: 環境変数を変えてコマンドを動かす。コマンドがなければ、変えた後の環境変数を書く。
//!
//! - `-i`（`-` も同じ）: 親の環境変数を引き継がない。`-u 名前`: その変数を除く
//! - コマンドは、変えた後の `PATH`（なければ `/bin:/sbin`）から探す。終了コードはコマンドのもの（見つからない 127、起こせない 126）
const kibi = @import("kibi");
const cli = kibi.cli;
const process = kibi.process;

const SYNOPSIS = "env [-i] [-u name]... [name=value]... [command [arg...]]";

/// 環境変数の数の上限（親の分と足した分）
const MAX_ENV = process.MAX_ARGS * 2;

var entries: [MAX_ENV][]const u8 = undefined;
var entries_len: usize = 0;
var pairs: [MAX_ENV]process.EnvEntry = undefined;
var path_buf: [kibi.fs.MAX_PATH]u8 = undefined;

pub fn main() u8 {
    const argv = process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    var clear = false;
    var unset: [process.MAX_ARGS][]const u8 = undefined;
    var unset_len: usize = 0;
    while (opts.next()) |c| switch (c) {
        'i' => clear = true,
        'u' => {
            const v = opts.value() orelse return cli.usage("option requires an argument -- u", SYNOPSIS);
            unset[unset_len] = v;
            unset_len += 1;
        },
        else => return cli.badOption(c, SYNOPSIS),
    };
    var rest = opts.rest();
    if (rest.len > 0 and cli.eql(rest[0], "-")) {
        clear = true;
        rest = rest[1..];
    }
    if (!clear) {
        const env = process.environ() catch |e| {
            cli.warnErr(null, e);
            return 1;
        };
        for (env) |entry| {
            if (!isUnset(entry, unset[0..unset_len])) add(entry);
        }
    }
    while (rest.len > 0 and indexOf(rest[0], '=') != null and rest[0][0] != '=') : (rest = rest[1..]) {
        if (entries_len == MAX_ENV) {
            cli.warnErr(null, error.TOOBIG);
            return 1;
        }
        set(rest[0]);
    }
    if (rest.len == 0) {
        for (entries[0..entries_len]) |entry| {
            cli.stdout.write(entry);
            cli.stdout.byte('\n');
        }
        cli.stdout.flush();
        return 0;
    }
    for (entries[0..entries_len], 0..) |entry, i| {
        const eq = indexOf(entry, '=').?;
        pairs[i] = .{ .name = str(entry[0..eq]), .value = str(entry[eq + 1 ..]) };
    }
    const path = cli.findProgram(&path_buf, rest[0], lookup("PATH") orelse cli.DEFAULT_PATH) orelse {
        cli.warnErr(rest[0], error.NOENT);
        return 127;
    };
    const status = cli.run(path, rest, .{ .env = pairs[0..entries_len] }) catch |e| {
        cli.warnErr(rest[0], e);
        return cli.spawnFailureCode(e);
    };
    return cli.exitCode(status);
}

fn str(s: []const u8) process.String {
    return .{ .ptr = s.ptr, .len = @intCast(s.len) };
}

fn indexOf(s: []const u8, c: u8) ?usize {
    for (s, 0..) |x, i| if (x == c) return i;
    return null;
}

fn nameOf(entry: []const u8) []const u8 {
    return entry[0 .. indexOf(entry, '=') orelse entry.len];
}

fn isUnset(entry: []const u8, names: []const []const u8) bool {
    for (names) |n| if (cli.eql(nameOf(entry), n)) return true;
    return false;
}

fn add(entry: []const u8) void {
    if (entries_len < MAX_ENV and indexOf(entry, '=') != null) {
        entries[entries_len] = entry;
        entries_len += 1;
    }
}

/// `名前=値` を入れる（同じ名前があれば置き換える）
fn set(entry: []const u8) void {
    const name = nameOf(entry);
    for (entries[0..entries_len]) |*e| {
        if (cli.eql(nameOf(e.*), name)) {
            e.* = entry;
            return;
        }
    }
    add(entry);
}

fn lookup(name: []const u8) ?[]const u8 {
    for (entries[0..entries_len]) |e| {
        if (cli.eql(nameOf(e), name)) return e[name.len + 1 ..];
    }
    return null;
}
