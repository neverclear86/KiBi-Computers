//! `printenv [名前...]`: 環境変数を書く。名前がなければ全部を `名前=値` で、あれば値だけを 1 行ずつ。
//! なかった名前が 1 つでもあれば終了コード 1（知らせは書かない）
const kibi = @import("kibi");
const cli = kibi.cli;

const SYNOPSIS = "printenv [name...]";

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    if (opts.next()) |c| return cli.badOption(c, SYNOPSIS);
    const names = opts.rest();
    var status: u8 = 0;
    if (names.len == 0) {
        const env = kibi.process.environ() catch |e| {
            cli.warnErr(null, e);
            return 1;
        };
        for (env) |entry| {
            cli.stdout.write(entry);
            cli.stdout.byte('\n');
        }
    } else {
        for (names) |n| {
            const v = kibi.process.getenv(n) orelse {
                status = 1;
                continue;
            };
            cli.stdout.write(v);
            cli.stdout.byte('\n');
        }
    }
    cli.stdout.flush();
    return status;
}
