//! シェルのメモリ（#101）。Zig の標準ライブラリのアロケーターは使わない（ADR 0098）。
//!
//! - `Arena`: 積み上げ式。構文木・展開の途中の文字列・argv など、1 つのコマンドの間だけ要るものを置く。
//!   `mark` で位置を覚え、`release` で戻す（入れ子の実行も、内側が先に戻すので崩れない）
//! - `heap`: 変数・関数・位置パラメーターなど、コマンドをまたいで残るもの。先頭から探す空きリスト（隣の空きとはつなぐ）
//!
//! どちらも静的な領域に置く（.bss はゼロなので、触るまで RAM に数えない。#407）。
const util = @import("util.zig");

pub const Error = util.Error;

pub const Arena = struct {
    buf: []u8,
    top: usize = 0,

    pub fn mark(self: *Arena) usize {
        return self.top;
    }

    pub fn release(self: *Arena, m: usize) void {
        if (m <= self.top) self.top = m;
    }

    pub fn alloc(self: *Arena, n: usize, alignment: usize) Error![]u8 {
        const base = @intFromPtr(self.buf.ptr);
        const start = alignUp(base + self.top, alignment) - base;
        if (start + n > self.buf.len) return error.OutOfMemory;
        self.top = start + n;
        return self.buf[start..][0..n];
    }

    pub fn create(self: *Arena, comptime T: type) Error!*T {
        const b = try self.alloc(@sizeOf(T), @alignOf(T));
        return @ptrCast(@alignCast(b.ptr));
    }

    pub fn slice(self: *Arena, comptime T: type, n: usize) Error![]T {
        if (n == 0) return &[_]T{};
        const b = try self.alloc(@sizeOf(T) * n, @alignOf(T));
        const p: [*]T = @ptrCast(@alignCast(b.ptr));
        return p[0..n];
    }

    pub fn dupe(self: *Arena, s: []const u8) Error![]u8 {
        const b = try self.alloc(s.len, 1);
        @memcpy(b, s);
        return b;
    }

    /// 2 つの文字列をつないだもの
    pub fn concat(self: *Arena, a: []const u8, b: []const u8) Error![]u8 {
        const r = try self.alloc(a.len + b.len, 1);
        @memcpy(r[0..a.len], a);
        @memcpy(r[a.len..], b);
        return r;
    }
};

/// 末尾に足していく列。
///
/// 写し直すときは容量を倍々に取る（#502）。語ごとにほかのものと交互に積まれる列（argv など）でも、
/// 写し直しは要素の数の対数回で済み、使う量は最後の大きさのおよそ 2 倍に収まる（以前は 2 乗で増えた）。
/// 容量の残りはこの列のものなので、値として写した列に、両方から足してはいけない。
pub fn List(comptime T: type) type {
    return struct {
        const Self = @This();
        arena: *Arena,
        items: []T = &[_]T{},
        /// 取ってある要素の数（`items.len` 以上）
        cap: usize = 0,

        pub fn init(arena: *Arena) Self {
            return .{ .arena = arena };
        }

        pub fn append(self: *Self, v: T) Error!void {
            try self.appendSlice(&[_]T{v});
        }

        pub fn appendSlice(self: *Self, vs: []const T) Error!void {
            if (vs.len == 0) return;
            const old = self.items.len;
            const need = old + vs.len;
            if (need > self.cap) try self.grow(need);
            const p: [*]T = @ptrCast(self.items.ptr);
            self.items = p[0..need];
            @memcpy(self.items[old..], vs);
        }

        /// 少なくとも [need] 個入るようにする
        fn grow(self: *Self, need: usize) Error!void {
            const a = self.arena;
            const base = @intFromPtr(a.buf.ptr);
            const end = @intFromPtr(self.items.ptr) + self.cap * @sizeOf(T);
            if (self.cap > 0 and end == base + a.top) {
                // 末尾にいるので、写さずに伸ばす（要るだけ）
                const more = (need - self.cap) * @sizeOf(T);
                if (a.top + more > a.buf.len) return error.OutOfMemory;
                a.top += more;
                self.cap = need;
                return;
            }
            // 倍々に取る（初めは要るだけ。1 つしか入らない列が多いので）。取れなければ要るだけ
            const want = if (self.cap == 0) need else @max(need, self.cap * 2);
            const n = a.slice(T, want) catch try a.slice(T, need);
            @memcpy(n[0..self.items.len], self.items);
            self.items = n[0..self.items.len];
            self.cap = n.len;
        }
    };
}

fn alignUp(v: usize, a: usize) usize {
    return (v + a - 1) & ~(a - 1);
}

// ---- 積み上げの領域 ----

/// 構文木・argv など
var ast_buf: [512 * 1024]u8 align(8) = undefined;
/// 展開の途中の文字列
var str_buf: [512 * 1024]u8 align(8) = undefined;

pub var ast: Arena = .{ .buf = &ast_buf };
pub var str: Arena = .{ .buf = &str_buf };

pub const Mark = struct { a: usize, s: usize };

pub fn mark() Mark {
    return .{ .a = ast.mark(), .s = str.mark() };
}

pub fn release(m: Mark) void {
    ast.release(m.a);
    str.release(m.s);
}

// ---- 残るもの ----

pub const HEAP_SIZE = 512 * 1024;
var heap_buf: [HEAP_SIZE]u8 align(8) = undefined;
/// 使った（区画を切り出した）ところの終わり
var heap_end: usize = 0;

/// 区画の頭（8 バイト）。size は頭を含む
const Header = extern struct { size: u32, free: u32 };

fn headerAt(off: usize) *Header {
    return @ptrCast(@alignCast(&heap_buf[off]));
}

/// [n] バイトを取る。取れなければ `OutOfMemory`
pub fn heapAlloc(n: usize) Error![]u8 {
    const need = alignUp(n + @sizeOf(Header), 8);
    var off: usize = 0;
    while (off < heap_end) {
        const h = headerAt(off);
        if (h.free != 0) {
            // 後ろの空きとつなぐ
            while (off + h.size < heap_end and headerAt(off + h.size).free != 0) h.size += headerAt(off + h.size).size;
            if (off + h.size == heap_end) {
                // 最後の空きは、使った終わりを戻す
                heap_end = off;
                break;
            }
            if (h.size >= need) {
                if (h.size - need >= 32) {
                    const rest = headerAt(off + need);
                    rest.* = .{ .size = h.size - @as(u32, @intCast(need)), .free = 1 };
                    h.size = @intCast(need);
                }
                h.free = 0;
                return heap_buf[off + @sizeOf(Header) ..][0..n];
            }
        }
        off += h.size;
    }
    if (heap_end + need > HEAP_SIZE) return error.OutOfMemory;
    const h = headerAt(heap_end);
    h.* = .{ .size = @intCast(need), .free = 0 };
    const r = heap_buf[heap_end + @sizeOf(Header) ..][0..n];
    heap_end += need;
    return r;
}

/// [heapAlloc] で取ったものを返す（空なら何もしない）
pub fn heapFree(b: []const u8) void {
    if (b.len == 0) return;
    const off = @intFromPtr(b.ptr) - @intFromPtr(&heap_buf) - @sizeOf(Header);
    headerAt(off).free = 1;
}

/// [s] を残る領域に写す（空なら長さ 0 のものを返す。heapFree は何もしない）
pub fn heapDupe(s: []const u8) Error![]u8 {
    if (s.len == 0) return &[_]u8{};
    const b = try heapAlloc(s.len);
    @memcpy(b, s);
    return b;
}

/// 文字列の列を残る領域に写す（列と中身を 1 つの区画に入れる。返すのは heapFreeList で返す）
pub fn heapDupeList(items: []const []const u8) Error![][]const u8 {
    var total: usize = items.len * @sizeOf([]const u8);
    for (items) |s| total += s.len;
    if (items.len == 0) return &[_][]const u8{};
    const b = try heapAlloc(total);
    const list: [*][]const u8 = @ptrCast(@alignCast(b.ptr));
    var off: usize = items.len * @sizeOf([]const u8);
    for (items, 0..) |s, i| {
        @memcpy(b[off..][0..s.len], s);
        list[i] = b[off..][0..s.len];
        off += s.len;
    }
    return list[0..items.len];
}

pub fn heapFreeList(list: []const []const u8) void {
    if (list.len == 0) return;
    const p: [*]const u8 = @ptrCast(list.ptr);
    heapFree(p[0..1]);
}
