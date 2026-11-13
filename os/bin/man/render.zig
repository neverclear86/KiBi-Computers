//! man ページ（Markdown の小さなサブセット。書式は docs/adr/0106-man.md）を、画面の幅に合わせた行にする（#106）。
//!
//! - 段落・箇条書きは [width] で折る。英語は空白で、日本語は全角の文字の前後ならどこでも折る（行頭禁則の簡単な規則つき）
//! - コードの塊は折り返さずに並べ、幅を超える行だけを文字の境目で切って次の行に送る（どの幅でも行の数が決まるように）
//! - [color] なら、太字・コードを SGR 1（太字）で囲む。行ごとに属性を閉じる（行だけを描き直しても崩れないように）
//!
//! できた行は静的な領域に足していく（[line] で取り出す）。領域が埋まったら、そこで切って [truncated] を立てる。
const kibi = @import("kibi");
const text = kibi.text;

/// できた行の領域の大きさ（SGR を含む）
const OUT_SIZE = 256 * 1024;
/// 行の数の上限
const MAX_LINES = 16 * 1024;
/// 1 つの段落の文字の数の上限（超えた分は、次の段落として続ける）
const MAX_CELLS = 16 * 1024;

var out_buf: [OUT_SIZE]u8 = undefined;
var out_len: usize = 0;
var line_ends: [MAX_LINES]u32 = undefined;
var line_start: usize = 0;

/// できた行の数
pub var line_count: usize = 0;
/// 領域が足りず、途中で切った
pub var truncated: bool = false;

/// 前に作った行を全部捨てる（幅を変えて作り直すとき）
pub fn reset() void {
    out_len = 0;
    line_count = 0;
    line_start = 0;
    truncated = false;
    prev = .nothing;
}

/// [i] 行目（改行を含まない）
pub fn line(i: usize) []const u8 {
    const start: usize = if (i == 0) 0 else line_ends[i - 1];
    return out_buf[start..line_ends[i]];
}

fn put(bytes: []const u8) void {
    if (truncated) return;
    if (out_len + bytes.len > out_buf.len) {
        truncated = true;
        return;
    }
    @memcpy(out_buf[out_len..][0..bytes.len], bytes);
    out_len += bytes.len;
}

fn spaces(n: usize) void {
    var i: usize = 0;
    while (i < n) : (i += 1) put(" ");
}

fn endLine() void {
    if (truncated) return;
    if (line_count >= line_ends.len) {
        truncated = true;
        return;
    }
    line_ends[line_count] = @intCast(out_len);
    line_count += 1;
    line_start = out_len;
}

fn blankLine() void {
    endLine();
}

// ---- 設定 ----

pub const Options = struct {
    /// 1 行の幅（セル）
    width: usize,
    /// 太字を SGR で書く
    color: bool,
};

var opt: Options = .{ .width = 80, .color = false };

/// 節の中の本文の字下げ
fn bodyIndent() usize {
    return if (opt.width >= 40) 4 else 2;
}

// ---- 段落の文字 ----

const Cell = struct {
    bytes: [4]u8,
    len: u8,
    width: u8,
    bold: bool,
    space: bool,
    cp: u21,
};

var cells: [MAX_CELLS]Cell = undefined;
var ncells: usize = 0;

/// 段落の中の状態（`**` とコードは行をまたいで続く）
var bold_on = false;
var code_on = false;
/// 次に足す文字の前で、行をつなぐ（全角の文字どうしの間でなければ空白を 1 つ入れる）
var pending_join = false;

fn addCell(c: Cell) void {
    if (ncells >= cells.len) return;
    cells[ncells] = c;
    ncells += 1;
}

fn addSpace() void {
    if (ncells == 0 or cells[ncells - 1].space) return;
    addCell(.{ .bytes = .{ ' ', 0, 0, 0 }, .len = 1, .width = 1, .bold = false, .space = true, .cp = ' ' });
}

fn addChar(bytes: []const u8, cp: u21) void {
    const w: u8 = text.width(cp);
    if (pending_join) {
        pending_join = false;
        if (ncells > 0 and !cells[ncells - 1].space and cells[ncells - 1].width != 2 and w != 2) addSpace();
    }
    var c: Cell = .{ .bytes = .{ 0, 0, 0, 0 }, .len = @intCast(bytes.len), .width = w, .bold = bold_on or code_on, .space = false, .cp = cp };
    @memcpy(c.bytes[0..bytes.len], bytes);
    addCell(c);
}

/// 行の中身（字下げ・印を除いたもの）を段落に足す。`**` は太字を切り替え、`` ` `` はコード（太字で書く）を切り替える。
/// `\` は次の 1 文字をそのまま入れる。不正な UTF-8 は U+FFFD、制御文字は `?` にする
fn addInline(s: []const u8) void {
    var i: usize = 0;
    while (i < s.len) {
        const c = s[i];
        if (c == ' ' or c == '\t') {
            if (!pending_join) addSpace();
            i += 1;
            continue;
        }
        if (c == '\\' and i + 1 < s.len and isPunct(s[i + 1])) {
            addChar(s[i + 1 .. i + 2], s[i + 1]);
            i += 2;
            continue;
        }
        if (c == '`') {
            code_on = !code_on;
            i += 1;
            continue;
        }
        if (!code_on and c == '*' and i + 1 < s.len and s[i + 1] == '*') {
            bold_on = !bold_on;
            i += 2;
            continue;
        }
        if (c < 0x20 or c == 0x7f) {
            addChar("?", '?');
            i += 1;
            continue;
        }
        const n = text.sequenceLength(c);
        if (n == 0 or i + n > s.len) {
            addChar(text.REPLACEMENT, 0xfffd);
            i += 1;
            continue;
        }
        if (text.decode(s[i .. i + n])) |cp| {
            addChar(s[i .. i + n], cp);
            i += n;
        } else {
            addChar(text.REPLACEMENT, 0xfffd);
            i += 1;
        }
    }
}

fn isPunct(c: u8) bool {
    return switch (c) {
        '\\', '`', '*', '_', '#', '-', '[', ']', '(', ')', '!', '.', '<', '>' => true,
        else => false,
    };
}

/// 行の頭に来てはいけない文字（閉じ括弧・句読点・長音など）
fn noBreakBefore(cp: u21) bool {
    return switch (cp) {
        0x3001, 0x3002, 0xff0c, 0xff0e, 0x30fb, 0xff1a, 0xff1b, 0xff01, 0xff1f, 0x30fc, 0x2026, 0x309d, 0x309e, 0x30fd, 0x30fe, 0x3005 => true,
        0xff09, 0x300d, 0x300f, 0x3011, 0x3015, 0x3009, 0x300b, 0xff3d, 0xff5d => true,
        0x3041, 0x3043, 0x3045, 0x3047, 0x3049, 0x3063, 0x3083, 0x3085, 0x3087, 0x308e => true,
        0x30a1, 0x30a3, 0x30a5, 0x30a7, 0x30a9, 0x30c3, 0x30e3, 0x30e5, 0x30e7, 0x30ee, 0x30f5, 0x30f6 => true,
        ')', ']', '}', ',', '.', ':', ';', '!', '?' => true,
        else => false,
    };
}

/// 行の終わりに来てはいけない文字（開き括弧）
fn noBreakAfter(cp: u21) bool {
    return switch (cp) {
        0xff08, 0x300c, 0x300e, 0x3010, 0x3014, 0x3008, 0x300a, 0xff3b, 0xff5b, '(', '[', '{' => true,
        else => false,
    };
}

/// [j] の文字の前で折れるか（空白は別に扱う）
fn canBreakBefore(j: usize) bool {
    if (j == 0) return false;
    const a = cells[j - 1];
    const b = cells[j];
    if (a.space or b.space or b.width == 0) return false;
    if (a.width != 2 and b.width != 2) return false;
    return !noBreakBefore(b.cp) and !noBreakAfter(a.cp);
}

/// [from]..[to] の文字を 1 行として書く（後ろの空白は落とす）
fn emitCells(from: usize, to: usize) void {
    var end = to;
    while (end > from and cells[end - 1].space) end -= 1;
    var bold = false;
    var i = from;
    while (i < end) : (i += 1) {
        const c = cells[i];
        if (opt.color and c.bold != bold and !c.space) {
            put(if (c.bold) "\x1b[1m" else "\x1b[0m");
            bold = c.bold;
        }
        put(c.bytes[0..c.len]);
    }
    if (bold) put("\x1b[0m");
    endLine();
}

/// 段落を折って書く。1 行目は [first] だけ字下げして [prefix] を書き、2 行目からは [rest] だけ字下げする
fn flowCells(first: usize, prefix: []const u8, rest: usize) void {
    var i: usize = 0;
    var first_line = true;
    while (true) {
        while (i < ncells and cells[i].space) i += 1;
        if (i >= ncells and !first_line) break;
        const indent = if (first_line) first + text_width(prefix) else rest;
        spaces(if (first_line) first else rest);
        if (first_line) put(prefix);
        first_line = false;
        const avail = if (opt.width > indent) opt.width - indent else 1;
        var w: usize = 0;
        var j = i;
        // 折れる位置（行の終わり・次の行の頭）
        var brk_end: ?usize = null;
        var brk_next: usize = 0;
        var overflow = false;
        while (j < ncells) : (j += 1) {
            const c = cells[j];
            if (c.space) {
                brk_end = j;
                brk_next = j + 1;
            } else if (j > i and canBreakBefore(j)) {
                brk_end = j;
                brk_next = j;
            }
            if (w + c.width > avail and !c.space) {
                overflow = true;
                break;
            }
            w += c.width;
        }
        if (!overflow) {
            emitCells(i, ncells);
            break;
        }
        if (brk_end) |e| {
            if (e > i) {
                emitCells(i, e);
                i = brk_next;
                continue;
            }
        }
        // 折れる位置がない: 文字の境目で切る（少なくとも 1 文字。幅 0 の文字は前の文字と一緒に）
        var end = if (j > i) j else i + 1;
        while (end < ncells and cells[end].width == 0) end += 1;
        emitCells(i, end);
        i = end;
    }
    ncells = 0;
}

fn text_width(s: []const u8) usize {
    var w: usize = 0;
    var i: usize = 0;
    while (i < s.len) {
        const n = text.sequenceLength(s[i]);
        if (n == 0 or i + n > s.len) {
            w += 1;
            i += 1;
            continue;
        }
        w += if (text.decode(s[i .. i + n])) |cp| text.width(cp) else 1;
        i += n;
    }
    return w;
}

// ---- 塊 ----

const Block = enum { nothing, title, heading, para, item, code };

/// 前に書いた塊（間の空行を決める）
var prev: Block = .nothing;

/// 新しい塊を始める。前の塊との間に空行を入れる（見出しの直後と、続く箇条書きの間には入れない）
fn begin(kind: Block) void {
    switch (prev) {
        .nothing, .heading => {},
        .item => if (kind != .item) blankLine(),
        else => blankLine(),
    }
    prev = kind;
}

/// 今まとめている段落の種類（.para か .item。なければ .nothing）
var open: Block = .nothing;
var in_section = false;

fn startPara(kind: Block) void {
    begin(kind);
    open = kind;
    ncells = 0;
    bold_on = false;
    code_on = false;
    pending_join = false;
}

fn flush() void {
    switch (open) {
        .para => flowCells(if (in_section) bodyIndent() else 0, "", if (in_section) bodyIndent() else 0),
        .item => {
            const b = if (in_section) bodyIndent() else 0;
            flowCells(b, "- ", b + 2);
        },
        else => {},
    }
    open = .nothing;
}

/// 見出しを書く（太字、折らない。幅を超えた分は文字の境目で次の行へ）
fn heading(s: []const u8, indent: usize) void {
    ncells = 0;
    bold_on = true;
    code_on = false;
    pending_join = false;
    addInline(s);
    for (cells[0..ncells]) |*c| c.bold = true;
    flowCells(indent, "", indent);
    bold_on = false;
}

/// コードの 1 行を書く（折らない。幅を超えたら文字の境目で切って、同じ字下げで続ける。タブは 4 桁ごとに）
fn codeLine(s: []const u8) void {
    const indent = if (in_section) bodyIndent() else 0;
    const avail = if (opt.width > indent) opt.width - indent else 1;
    spaces(indent);
    var w: usize = 0;
    var i: usize = 0;
    while (i < s.len) {
        const c = s[i];
        var bytes: []const u8 = undefined;
        var cw: usize = 1;
        var n: usize = 1;
        if (c == '\t') {
            const to = (w / 4 + 1) * 4;
            var k = w;
            while (k < to and k < avail) : (k += 1) put(" ");
            w = k;
            i += 1;
            continue;
        } else if (c < 0x20 or c == 0x7f) {
            bytes = "?";
        } else {
            const len = text.sequenceLength(c);
            if (len == 0 or i + len > s.len) {
                bytes = text.REPLACEMENT;
            } else if (text.decode(s[i .. i + len])) |cp| {
                bytes = s[i .. i + len];
                cw = text.width(cp);
                n = len;
            } else {
                bytes = text.REPLACEMENT;
            }
        }
        if (w + cw > avail and w > 0) {
            endLine();
            spaces(indent);
            w = 0;
        }
        put(bytes);
        w += cw;
        i += n;
    }
    endLine();
}

fn countIndent(s: []const u8) usize {
    var n: usize = 0;
    for (s) |c| {
        if (c == ' ') n += 1 else if (c == '\t') n += 4 else break;
    }
    return n;
}

fn trimLeft(s: []const u8) []const u8 {
    var i: usize = 0;
    while (i < s.len and (s[i] == ' ' or s[i] == '\t')) i += 1;
    return s[i..];
}

fn startsWith(s: []const u8, p: []const u8) bool {
    return s.len >= p.len and kibi.cli.eql(s[0..p.len], p);
}

/// 前のページとの間に空行を入れる（2 つ以上のページを続けて書くとき）
pub fn separator() void {
    if (line_count > 0) blankLine();
    prev = .nothing;
}

/// ページ [src] を [o] の幅で行にして足す
pub fn render(src: []const u8, o: Options) void {
    opt = o;
    in_section = false;
    open = .nothing;
    var fence = false;
    var code = false;
    var pos: usize = 0;
    while (pos < src.len) {
        var end = pos;
        while (end < src.len and src[end] != '\n') end += 1;
        var raw = src[pos..end];
        pos = end + 1;
        if (raw.len > 0 and raw[raw.len - 1] == '\r') raw = raw[0 .. raw.len - 1];
        const body = trimLeft(raw);

        if (fence) {
            if (startsWith(body, "```")) {
                fence = false;
            } else {
                codeLine(raw);
            }
            continue;
        }
        if (body.len == 0) {
            flush();
            code = false;
            continue;
        }
        if (startsWith(body, "```")) {
            flush();
            begin(.code);
            fence = true;
            continue;
        }
        if (open == .nothing and countIndent(raw) >= 4) {
            if (!code) begin(.code);
            code = true;
            // 字下げの 4 桁を除く
            var k: usize = 0;
            var cut: usize = 0;
            while (cut < 4 and k < raw.len) : (k += 1) {
                cut += if (raw[k] == '\t') 4 else 1;
            }
            codeLine(raw[k..]);
            continue;
        }
        code = false;
        if (startsWith(raw, "# ")) {
            flush();
            begin(.title);
            in_section = false;
            heading(trimLeft(raw[2..]), 0);
            continue;
        }
        if (startsWith(raw, "## ")) {
            flush();
            begin(.heading);
            in_section = false;
            heading(trimLeft(raw[3..]), 0);
            in_section = true;
            continue;
        }
        if (startsWith(raw, "### ")) {
            flush();
            begin(.heading);
            heading(trimLeft(raw[4..]), if (in_section) bodyIndent() / 2 else 0);
            continue;
        }
        if (countIndent(raw) < 2 and (startsWith(body, "- ") or startsWith(body, "* "))) {
            flush();
            startPara(.item);
            addInline(trimLeft(body[2..]));
            continue;
        }
        if (open == .nothing) {
            startPara(.para);
        } else {
            pending_join = true;
        }
        addInline(body);
    }
    flush();
}
