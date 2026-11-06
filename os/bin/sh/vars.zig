//! シェルの変数・関数・位置パラメーター（#101）。どれも残る領域（`mem.heap*`）に置く。
const kibi = @import("kibi");
const util = @import("util.zig");
const mem = @import("mem.zig");

const Error = util.Error;
const eql = util.eql;

pub const Var = struct {
    name: []const u8,
    /// null は値のない変数（`export X` だけ・`readonly X` だけ）
    value: ?[]const u8,
    exported: bool = false,
    readonly: bool = false,
};

/// 変数の数の上限
pub const MAX_VARS = 1024;
var table: [MAX_VARS]Var = undefined;
var count: usize = 0;

pub fn all() []Var {
    return table[0..count];
}

pub fn find(name: []const u8) ?*Var {
    for (table[0..count]) |*v| {
        if (eql(v.name, name)) return v;
    }
    return null;
}

/// 値（なければ null）
pub fn get(name: []const u8) ?[]const u8 {
    const v = find(name) orelse return null;
    return v.value;
}

fn readonlyError(name: []const u8) Error {
    util.complain(&.{ name, ": readonly variable" });
    return error.Abort;
}

/// 変数を作る（なければ）
fn ensure(name: []const u8) Error!*Var {
    if (find(name)) |v| return v;
    if (count == MAX_VARS) {
        util.complain(&.{"too many variables"});
        return error.Abort;
    }
    const n = try mem.heapDupe(name);
    table[count] = .{ .name = n, .value = null };
    count += 1;
    return &table[count - 1];
}

/// 値を入れる。読み取り専用なら `Abort`
pub fn set(name: []const u8, value: []const u8) Error!void {
    const v = try ensure(name);
    if (v.readonly) return readonlyError(name);
    const nv = try mem.heapDupe(value);
    if (v.value) |old| mem.heapFree(old);
    v.value = nv;
}

pub fn setExported(name: []const u8, on: bool) Error!void {
    const v = try ensure(name);
    v.exported = on;
}

pub fn setReadonly(name: []const u8) Error!void {
    const v = try ensure(name);
    v.readonly = true;
}

/// 消す。読み取り専用なら `Abort`
pub fn unset(name: []const u8) Error!void {
    for (table[0..count], 0..) |*v, i| {
        if (!eql(v.name, name)) continue;
        if (v.readonly) return readonlyError(name);
        if (v.value) |old| mem.heapFree(old);
        mem.heapFree(v.name);
        table[i] = table[count - 1];
        count -= 1;
        return;
    }
}

/// 子に渡す環境変数（値のある、export された変数）。[extra] は前に置いて優先する（`NAME=v cmd` の一時の代入）
pub fn environ(arena: *mem.Arena, extra: []const kibi.process.EnvEntry) Error![]kibi.process.EnvEntry {
    var list = mem.List(kibi.process.EnvEntry).init(arena);
    try list.appendSlice(extra);
    outer: for (table[0..count]) |*v| {
        if (!v.exported) continue;
        const value = v.value orelse continue;
        for (extra) |e| if (eql(e.name.ptr[0..e.name.len], v.name)) continue :outer;
        try list.append(.{ .name = str(v.name), .value = str(value) });
    }
    return list.items;
}

pub fn str(s: []const u8) kibi.process.String {
    return .{ .ptr = s.ptr, .len = @intCast(s.len) };
}

// ---- local（関数の中だけの変数） ----

const Saved = struct {
    name: []const u8,
    /// 前の値（変数がなかったら had = false）
    had: bool,
    value: ?[]const u8,
    exported: bool,
};

const MAX_SAVED = 512;
var saved: [MAX_SAVED]Saved = undefined;
var saved_len: usize = 0;

pub fn savedMark() usize {
    return saved_len;
}

/// [name] を今の関数が終わるまでの変数にする（前の値を覚える）
pub fn makeLocal(name: []const u8, frame: usize) Error!void {
    // 同じ関数で 2 度目なら何もしない
    for (saved[frame..saved_len]) |s| if (eql(s.name, name)) return;
    if (saved_len == MAX_SAVED) {
        util.complain(&.{"too many local variables"});
        return error.Abort;
    }
    const v = find(name);
    if (v) |x| if (x.readonly) return readonlyError(name);
    saved[saved_len] = .{
        .name = try mem.heapDupe(name),
        .had = v != null,
        .value = if (v) |x| (if (x.value) |val| try mem.heapDupe(val) else null) else null,
        .exported = if (v) |x| x.exported else false,
    };
    saved_len += 1;
}

/// [frame] より後に覚えた変数を戻す
pub fn restoreLocals(frame: usize) void {
    while (saved_len > frame) {
        saved_len -= 1;
        const s = saved[saved_len];
        if (s.had) {
            const v = ensure(s.name) catch null;
            if (v) |x| {
                if (x.value) |old| mem.heapFree(old);
                x.value = s.value;
                x.exported = s.exported;
            } else if (s.value) |val| mem.heapFree(val);
        } else {
            if (find(s.name)) |x| {
                if (!x.readonly) unset(s.name) catch {};
            }
            if (s.value) |val| mem.heapFree(val);
        }
        mem.heapFree(s.name);
    }
}

// ---- 関数 ----

pub const Func = struct {
    name: []const u8,
    /// 本体（複合コマンドとそのリダイレクト）の文字列
    text: []const u8,
    /// 動いている呼び出しの数（動いている間に定義し直されても、文字列を返さない）
    busy: u32 = 0,
    /// 定義し直された・消された（busy が 0 になったら返す）
    dead: bool = false,
};

pub const MAX_FUNCS = 256;
var funcs: [MAX_FUNCS]*Func = undefined;
var funcs_len: usize = 0;

pub fn allFuncs() []*Func {
    return funcs[0..funcs_len];
}

pub fn findFunc(name: []const u8) ?*Func {
    for (funcs[0..funcs_len]) |f| if (eql(f.name, name)) return f;
    return null;
}

pub fn defineFunc(name: []const u8, text: []const u8) Error!void {
    const n = try mem.heapDupe(name);
    const t = try mem.heapDupe(text);
    const b = try mem.heapAlloc(@sizeOf(Func));
    const f: *Func = @ptrCast(@alignCast(b.ptr));
    f.* = .{ .name = n, .text = t };
    for (funcs[0..funcs_len]) |*slot| {
        if (eql(slot.*.name, name)) {
            retire(slot.*);
            slot.* = f;
            return;
        }
    }
    if (funcs_len == MAX_FUNCS) {
        util.complain(&.{"too many functions"});
        return error.Abort;
    }
    funcs[funcs_len] = f;
    funcs_len += 1;
}

pub fn unsetFunc(name: []const u8) void {
    for (funcs[0..funcs_len], 0..) |f, i| {
        if (!eql(f.name, name)) continue;
        retire(f);
        funcs[i] = funcs[funcs_len - 1];
        funcs_len -= 1;
        return;
    }
}

fn retire(f: *Func) void {
    f.dead = true;
    if (f.busy == 0) freeFunc(f);
}

/// 呼び出しが終わった
pub fn releaseFunc(f: *Func) void {
    f.busy -= 1;
    if (f.busy == 0 and f.dead) freeFunc(f);
}

fn freeFunc(f: *Func) void {
    mem.heapFree(f.name);
    mem.heapFree(f.text);
    const p: [*]const u8 = @ptrCast(f);
    mem.heapFree(p[0..@sizeOf(Func)]);
}

// ---- 位置パラメーター ----

/// `$1` 以降
pub var params: [][]const u8 = &[_][]const u8{};
/// `$0`
pub var arg0: []const u8 = "sh";

/// 位置パラメーターを [list] にする（前のものは返さない。呼ぶ側が [saveParams] で持つか、[freeParams] で返す）
pub fn setParams(list: []const []const u8) Error!void {
    params = try mem.heapDupeList(list);
}

pub fn freeParams(list: [][]const u8) void {
    mem.heapFreeList(list);
}

/// `shift n`
pub fn shiftParams(n: usize) Error!void {
    const old = params;
    params = try mem.heapDupeList(old[n..]);
    freeParams(old);
}
