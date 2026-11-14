---
title: kibi:fs
description: ディスクのマウント・アンマウントと、マウントとディスクの一覧・容量。
---

`kibi:fs@0.1.0` の `mounts` は、ディスクのマウントの操作です。ファイルそのものの読み書きは WASI（`path_open`・`fd_read` など）を使います。どの関数も待ちません。

```wit
// kibi-fs/mounts.wit
package kibi:fs@0.1.0;
interface mounts {
    use kibi:kernel/types@0.1.0.{error-code};
    mount: func(source: string, point: string, read-only: bool) -> result<_, error-code>;
    unmount: func(point: string) -> result<_, error-code>;
    enum fs-kind {
        disk,
        image,
        tmp,
        dev,
    }
    record fs-usage {
        total-bytes: u64,
        used-bytes: u64,
        available-bytes: u64,
        total-entries: option<u32>,
        used-entries: u32,
    }
    record mount-info {
        point: string,
        source: string,
        kind: fs-kind,
        read-only: bool,
        id: u32,
        usage: option<fs-usage>,
    }
    record disk-info {
        source: string,
        capacity-bytes: u64,
        max-entries: u32,
        mount-point: option<string>,
    }
    list-mounts: func() -> list<mount-info>;
    list-disks: func() -> list<disk-info>;
}
```

## 振る舞い

- 挿したディスクは、カーネルが `/mnt/<識別子の先頭 8 文字>` に自動でマウントし、イベント `disk-inserted`・`disk-removed` を出します（[イベントの種類](./kibi-kernel.md#イベントの種類)）
- `mount` の `source` は `disk:<識別子>` です。`point` の相対パスは、呼んだプロセスのカーネルの cwd から辿ります。`point` がなくてもかまいません（その位置と祖先は、合成のディレクトリとして見えます）
- `read-only` が偽でも、読み取り専用のディスクは読み取り専用でマウントします
- 開いたファイルがあっても `unmount` できます。そのファイルの以後の操作は `io` です
- `mount` は毎回ディスクを開くので、ほかの呼び出しより多くの燃料を使います（ディスクの項目の数に比例。仮の値）
- `list-mounts` はマウントを足した順に、`list-disks` は挿さっているディスクをスロットの順に返します。どちらも `cabi_realloc` の中からも呼べます。2つは別の呼び出しなので、間に抜き差しがあると結果が食い違うことがあります
- 使用量は管理の分を含むので、ファイルの大きさの合計（`du`）とは合いません。`tmp` の空きは RAM の残りの目安で、書けることは保証しません

## エラー

| 条件 | 結果 |
| --- | --- |
| `source` が `disk:<識別子>` でない・同梱イメージ（`disk:image:*`）・今挿さっていない | `no-entry` |
| そのディスクがもうマウントされている・開いている途中 | `busy` |
| `point` にもうマウントがある | `busy` |
| `point` がディレクトリでないもの・途中がディレクトリでない | `not-directory` |
| マシンが動いていない・ディスクを開けない・開く途中で抜かれた | `io` |
| `unmount` の `point` が `/`・`/tmp`・`/dev`、または下にほかのマウントがある | `busy` |
| `unmount` の `point` がマウントの位置でない | `invalid` |
| 文字列が線形メモリの外 | `fault` |
| `source` が 4096 バイトを超える・UTF-8 でない | `invalid` |
| `point` が長すぎる（4096 バイト） | `name-too-long` |

## p1 向けの import

モジュール名: `kibi:fs/mounts@0.1.0`

<!-- check:imports kibi:fs/mounts@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `mount` | `(src_ptr: i32, src_len: i32, pt_ptr: i32, pt_len: i32, read_only: i32, ret_ptr: i32) -> ()` | — | — |
| `unmount` | `(pt_ptr: i32, pt_len: i32, ret_ptr: i32) -> ()` | — | — |
| `list-mounts` | `(ret_ptr: i32) -> ()` | 要る | — |
| `list-disks` | `(ret_ptr: i32) -> ()` | 要る | — |
<!-- /check -->

## メモリ上の配置

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `result<_, error-code>` | 判別子 `u8 @0`、err なら `u8 @1` | 2 / 1 |
| `list<T>`（`list-mounts`・`list-disks` の書き先） | `ptr @0`、`len @4` | 8 / 4 |
| `fs-usage` | `total-bytes: u64 @0`、`used-bytes @8`、`available-bytes @16`、`total-entries` の判別子 `u8 @24`・値 `u32 @28`、`used-entries: u32 @32` | 40 / 8 |
| `mount-info` | `point @0`・`@4`、`source @8`・`@12`、`kind: u8 @16`、`read-only: u8 @17`、`id: u32 @20`、`usage` の判別子 `u8 @24`・`fs-usage @32` | 72 / 8 |
| `disk-info` | `source @0`・`@4`、`capacity-bytes: u64 @8`、`max-entries: u32 @16`、`mount-point` の判別子 `u8 @20`・`ptr @24`・`len @28` | 32 / 8 |

`fs-kind` の判別子は `disk` = 0、`image` = 1、`tmp` = 2、`dev` = 3 です。詰め物と使わない値の場所は 0 で埋めます。
