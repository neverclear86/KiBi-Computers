//! `pwd`: 今の作業ディレクトリを書く（シェルには組み込みの `pwd` もある。`-L`・`-P` は受け付けるだけ）
const kibi = @import("kibi");
const cli = kibi.cli;

pub fn main() u8 {
    const argv = kibi.process.args() catch |e| {
        cli.warnErr(null, e);
        return 1;
    };
    var opts = cli.Args.init(argv);
    while (opts.next()) |c| if (c != 'L' and c != 'P') return cli.badOption(c, "pwd [-L|-P]");
    cli.stdout.write(kibi.fs.cwd());
    cli.stdout.byte('\n');
    cli.stdout.flush();
    return @intFromBool(cli.stdout.failed);
}
