//! ファイルとディレクトリ（WASI p1 の path_*・fd_readdir）の包み（#98）。
//!
//! パスはすべて、カーネルが fd 3 に置く根 `/` の preopen（`ROOT`）からの相対にして渡す。
//! 相対パスは cwd（起動時は環境変数 `PWD`。ADR 0332）につないで、`.`・`..` を畳んでから渡す。
const wasi = @import("wasi.zig");
const errno = @import("errno.zig");
const process = @import("process.zig");

pub const Error = errno.Error;
pub const Fd = wasi.Fd;

/// 根 `/` の preopen の fd（`SpawnOptions.ROOT_PREOPEN`）
pub const ROOT: Fd = 3;

/// パスの長さの上限（バイト）。超えると `NAMETOOLONG`
pub const MAX_PATH = 4096;

var cwd_buf: [MAX_PATH]u8 = undefined;
var cwd_len: ?usize = null;

/// 今の cwd（絶対パス）。起動時は `PWD`（なければ `/`）
pub fn cwd() []const u8 {
    if (cwd_len) |n| return cwd_buf[0..n];
    const pwd = process.getenv("PWD") orelse "/";
    const n = normalizeInto(&cwd_buf, "/", pwd) catch {
        cwd_buf[0] = '/';
        cwd_len = 1;
        return cwd_buf[0..1];
    };
    cwd_len = n.len;
    return n;
}

/// cwd を [path] に変える。ディレクトリでなければ `NOTDIR`、なければ `NOENT`
pub fn chdir(path: []const u8) Error!void {
    var buf: [MAX_PATH]u8 = undefined;
    const abs = try absolute(&buf, path);
    const st = try stat(abs);
    if (st.kind != .directory) return error.NOTDIR;
    try process.setCwdRaw(abs);
    @memcpy(cwd_buf[0..abs.len], abs);
    cwd_len = abs.len;
}

/// [path] を、cwd を基にした絶対パスにして [buf] に書く（`.`・`..` を畳む。根より上には出ない）
pub fn absolute(buf: []u8, path: []const u8) Error![]const u8 {
    return normalizeInto(buf, cwd(), path);
}

/// [base]（絶対パス）を基に [path] を畳んで [buf] に書く
pub fn normalizeInto(buf: []u8, base: []const u8, path: []const u8) Error![]const u8 {
    if (buf.len == 0) return error.NAMETOOLONG;
    buf[0] = '/';
    var n: usize = 1;
    const parts = [_][]const u8{ if (path.len > 0 and path[0] == '/') "" else base, path };
    for (parts) |part| {
        var i: usize = 0;
        while (i < part.len) {
            while (i < part.len and part[i] == '/') i += 1;
            const start = i;
            while (i < part.len and part[i] != '/') i += 1;
            const seg = part[start..i];
            if (seg.len == 0 or (seg.len == 1 and seg[0] == '.')) continue;
            if (seg.len == 2 and seg[0] == '.' and seg[1] == '.') {
                // 1 つ上へ（根なら根のまま）
                while (n > 1 and buf[n - 1] != '/') n -= 1;
                if (n > 1) n -= 1;
                continue;
            }
            const need = seg.len + @intFromBool(n > 1);
            if (n + need > buf.len) return error.NAMETOOLONG;
            if (n > 1) {
                buf[n] = '/';
                n += 1;
            }
            @memcpy(buf[n..][0..seg.len], seg);
            n += seg.len;
        }
    }
    return buf[0..n];
}

/// 根の preopen からの相対（絶対パス [abs] の先頭の `/` を落とす。根なら `.`）
fn rootRelative(abs: []const u8) []const u8 {
    return if (abs.len <= 1) "." else abs[1..];
}

fn resolve(buf: []u8, path: []const u8) Error![]const u8 {
    return rootRelative(try absolute(buf, path));
}

pub const Kind = enum { file, directory, symlink, device, other };

pub const Stat = struct {
    kind: Kind,
    size: u64,
    /// 変更時刻（ナノ秒）
    mtime: u64,
    ino: u64,
    /// ファイルシステムの番号（`mounts.Mount.id` と同じ値。マウントの祖先の合成のディレクトリは 0）
    dev: u64 = 0,
};

fn toStat(s: wasi.Filestat) Stat {
    const kind: Kind = switch (s.filetype) {
        .REGULAR_FILE => .file,
        .DIRECTORY => .directory,
        .SYMBOLIC_LINK => .symlink,
        .CHARACTER_DEVICE, .BLOCK_DEVICE => .device,
        else => .other,
    };
    return .{ .kind = kind, .size = s.size, .mtime = s.mtim, .ino = s.ino, .dev = s.dev };
}

/// [path] の情報（シンボリックリンクはたどる）
pub fn stat(path: []const u8) Error!Stat {
    var buf: [MAX_PATH]u8 = undefined;
    const rel = try resolve(&buf, path);
    var s: wasi.Filestat = undefined;
    try errno.check(wasi.path_filestat_get(ROOT, wasi.LOOKUP_SYMLINK_FOLLOW, rel.ptr, @intCast(rel.len), &s));
    return toStat(s);
}

/// [path] の情報（最後の部分がシンボリックリンクなら、たどらずにリンクそのもの）
pub fn lstat(path: []const u8) Error!Stat {
    var buf: [MAX_PATH]u8 = undefined;
    const rel = try resolve(&buf, path);
    var s: wasi.Filestat = undefined;
    try errno.check(wasi.path_filestat_get(ROOT, 0, rel.ptr, @intCast(rel.len), &s));
    return toStat(s);
}

/// 開いた fd の情報
pub fn fstat(fd: Fd) Error!Stat {
    var s: wasi.Filestat = undefined;
    try errno.check(wasi.fd_filestat_get(fd, &s));
    return toStat(s);
}

pub const OpenFlags = struct {
    read: bool = true,
    write: bool = false,
    /// なければ作る
    create: bool = false,
    /// [create] と合わせて、あれば `EXIST`
    exclusive: bool = false,
    /// 長さを 0 にする
    truncate: bool = false,
    /// 書くたびに末尾へ
    append: bool = false,
    /// ディレクトリとして開く
    directory: bool = false,
};

/// [path] を開く。閉じるのは `io.close`
pub fn open(path: []const u8, flags: OpenFlags) Error!Fd {
    var buf: [MAX_PATH]u8 = undefined;
    const rel = try resolve(&buf, path);
    var oflags: u16 = 0;
    if (flags.create) oflags |= wasi.O_CREAT;
    if (flags.exclusive) oflags |= wasi.O_EXCL;
    if (flags.truncate) oflags |= wasi.O_TRUNC;
    if (flags.directory) oflags |= wasi.O_DIRECTORY;
    var rights: wasi.Rights = wasi.RIGHT_FD_FILESTAT_GET | wasi.RIGHT_FD_SEEK | wasi.RIGHT_FD_TELL;
    if (flags.read) rights |= wasi.RIGHT_FD_READ;
    if (flags.write) rights |= wasi.RIGHT_FD_WRITE;
    if (flags.directory) rights |= wasi.RIGHT_FD_READDIR | wasi.RIGHT_FD_READ;
    const fdflags: u16 = if (flags.append) wasi.FDFLAG_APPEND else 0;
    var fd: Fd = 0;
    try errno.check(wasi.path_open(ROOT, wasi.LOOKUP_SYMLINK_FOLLOW, rel.ptr, @intCast(rel.len), oflags, rights, wasi.RIGHTS_ALL, fdflags, &fd));
    return fd;
}

/// [path] の参照・変更の時刻を今にする（シンボリックリンクはたどる）
pub fn touchTimes(path: []const u8) Error!void {
    var buf: [MAX_PATH]u8 = undefined;
    const rel = try resolve(&buf, path);
    try errno.check(wasi.path_filestat_set_times(ROOT, wasi.LOOKUP_SYMLINK_FOLLOW, rel.ptr, @intCast(rel.len), 0, 0, wasi.FST_ATIM_NOW | wasi.FST_MTIM_NOW));
}

pub fn mkdir(path: []const u8) Error!void {
    var buf: [MAX_PATH]u8 = undefined;
    const rel = try resolve(&buf, path);
    try errno.check(wasi.path_create_directory(ROOT, rel.ptr, @intCast(rel.len)));
}

pub fn rmdir(path: []const u8) Error!void {
    var buf: [MAX_PATH]u8 = undefined;
    const rel = try resolve(&buf, path);
    try errno.check(wasi.path_remove_directory(ROOT, rel.ptr, @intCast(rel.len)));
}

pub fn unlink(path: []const u8) Error!void {
    var buf: [MAX_PATH]u8 = undefined;
    const rel = try resolve(&buf, path);
    try errno.check(wasi.path_unlink_file(ROOT, rel.ptr, @intCast(rel.len)));
}

pub fn rename(from: []const u8, to: []const u8) Error!void {
    var a: [MAX_PATH]u8 = undefined;
    var b: [MAX_PATH]u8 = undefined;
    const ra = try resolve(&a, from);
    const rb = try resolve(&b, to);
    try errno.check(wasi.path_rename(ROOT, ra.ptr, @intCast(ra.len), ROOT, rb.ptr, @intCast(rb.len)));
}

/// ディレクトリの項目
pub const Entry = struct { name: []const u8, kind: Kind, ino: u64 };

/// ディレクトリの中身を順に読む（`.`・`..` は飛ばす）。[buf] は読み込みに使う領域で、
/// 少なくとも `MIN_BUF`（項目の頭 24 バイト）に、いちばん長い名前を足した大きさにする。
/// 頭より小さければ `next` は INVAL、名前が入りきらなければ NAMETOOLONG を返す
pub const Dir = struct {
    /// buf の最小の大きさ（WASI の dirent の頭）。名前の分はこれに足す
    pub const MIN_BUF = @sizeOf(wasi.Dirent);

    fd: Fd,
    buf: []u8,
    cookie: u64 = 0,
    /// buf の中の、読んだ量と次に見る位置
    used: usize = 0,
    pos: usize = 0,
    /// 前の読み込みで buf が埋まらなかった（それ以上ない）
    eof: bool = false,

    pub fn init(fd: Fd, buf: []u8) Dir {
        return .{ .fd = fd, .buf = buf };
    }

    /// 次の項目。もうなければ null
    pub fn next(self: *Dir) Error!?Entry {
        // 頭が入らない buf では、いつまでも次へ進めない
        if (self.buf.len < MIN_BUF) return error.INVAL;
        while (true) {
            if (self.pos + @sizeOf(wasi.Dirent) <= self.used) {
                const d: *align(1) const wasi.Dirent = @ptrCast(self.buf[self.pos..].ptr);
                const name_start = self.pos + @sizeOf(wasi.Dirent);
                if (name_start + d.namlen <= self.used) {
                    self.pos = name_start + d.namlen;
                    self.cookie = d.next;
                    const name = self.buf[name_start..][0..d.namlen];
                    if (isDot(name)) continue;
                    const kind: Kind = switch (d.filetype) {
                        .REGULAR_FILE => .file,
                        .DIRECTORY => .directory,
                        .SYMBOLIC_LINK => .symlink,
                        .CHARACTER_DEVICE, .BLOCK_DEVICE => .device,
                        else => .other,
                    };
                    return .{ .name = name, .kind = kind, .ino = d.ino };
                }
                // 名前が途中で切れた。buf が 1 項目より小さければ読めない
                if (self.pos == 0) return error.NAMETOOLONG;
            }
            if (self.eof) return null;
            var used: wasi.Size = 0;
            try errno.check(wasi.fd_readdir(self.fd, self.buf.ptr, @intCast(self.buf.len), self.cookie, &used));
            self.used = used;
            self.pos = 0;
            self.eof = used < self.buf.len;
            if (used == 0) return null;
        }
    }

    fn isDot(name: []const u8) bool {
        return (name.len == 1 and name[0] == '.') or (name.len == 2 and name[0] == '.' and name[1] == '.');
    }
};
