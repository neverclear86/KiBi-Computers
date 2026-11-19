---
title: kibi:process
description: プロセスの生成・待機・シグナル、カーネルの作業ディレクトリ、プロセスの一覧。
---

`kibi:process@0.1.0` の `process` は、子プロセスを作る・待つ・止める口と、カーネルの作業ディレクトリ（cwd）、マシンのプロセスの一覧です。fork はありません。子は VFS の実行ファイルから作り、引数・環境変数・cwd・fd は作るときに1回だけ決めます。

```wit
// kibi-process/process.wit
package kibi:process@0.1.0;
interface process {
    use kibi:kernel/types@0.1.0.{error-code};
    enum signal {
        interrupt,
        quit,
        terminate,
        kill,
    }
    variant exit-status {
        exited(u32),
        killed(option<signal>),
        crashed,
    }
    variant process-state {
        starting,
        running,
        waiting,
        finished(exit-status),
    }
    record process-info {
        pid: u32,
        parent-pid: u32,
        state: process-state,
        args: list<string>,
    }
    variant fd-source {
        parent(u32),
        closed,
    }
    record fd-assign {
        fd: u32,
        source: fd-source,
    }
    record spawn-options {
        args: list<string>,
        env: option<list<tuple<string, string>>>,
        cwd: option<string>,
        fds: list<fd-assign>,
    }
    resource process {
        spawn: static func(path: string, options: spawn-options) -> result<process, error-code>;
        pid: func() -> u32;
        status: func() -> result<option<exit-status>, error-code>;
        kill: func(sig: signal) -> result<_, error-code>;
    }
    kill-pid: func(pid: u32, sig: signal) -> result<_, error-code>;
    list-processes: func() -> list<process-info>;
    self-pid: func() -> u32;
    parent-pid: func() -> u32;
    set-signal-ignored: func(sig: signal, ignored: bool) -> result<_, error-code>;
    get-cwd: func() -> string;
    set-cwd: func(path: string) -> result<_, error-code>;
}
```

## 振る舞い

### spawn

- `path` は VFS の実行ファイルです。相対パスは呼んだプロセスのカーネルの cwd から辿ります。`PATH` からは探しません（シェルの役目です）
- 実行ファイルの読み込み（コンパイル）が終わるまで待つ、**止まりうる import** です。同じ中身のプログラムは読み込んだ結果をサーバー全体で共有するので、2回目からはすぐに終わることがあります
- 子の ABI は実行ファイルの形で決まり、親とは関係ありません。読み込みで断る条件は [WASI p1](./wasi-p1.md#読み込みで断るもの) と同じで、`no-exec` などをそのまま返します
- 子のハンドルは、呼んだプロセスの fd の表に、読む権利で入ります。満杯なら子を作らずに `mfile`、マシンのプロセスの数が上限なら `nfile` です
- `args` が空なら、argv は `path` の文字列1つです
- `env` が `none` なら親の環境変数を、`some` なら丸ごとその並びを使います
- `cwd` が `none` なら、親のカーネルの cwd です（libc の `chdir` の後の値ではありません）
- `fds`: fd 0・1・2 は、書かなければ親の同じ fd を引き継ぎます。fd 3 以降は、書いたものだけを渡します。preopen のディレクトリも、渡すなら明示してください（子の preopen は fd 3 から詰めて並べます）。`closed` は割り当てません

### status と待ち方

- `status` は待たずに終わり方を返します（まだなら `none`）。親が `some` を受け取ると、その子を刈り取ります。刈り取った後も、同じハンドルは同じ値を返し続けます
- 子が終わるのを待つには、ハンドルを `poll_oneoff` の `FD_READ` で待ちます。`FD_WRITE` は `bad-descriptor`、`fd_read` は `invalid` です
- 親だけが刈り取ります。fd を渡されたほかのプロセスは刈り取りません
- 親が子のハンドルを最後の1つまで閉じると、子は親なしになります（終わっていれば、その場で刈り取られます）

### シグナル

- シグナルにハンドラーはなく、効き方は「無視」か「止める」だけです。`kill` は無視できません
- `kill`・`kill-pid` は待ちません。止まったことは `status`・`poll_oneoff` で知ります。自分に送って止まるなら、そのまま止まります
- 届けた・無視された・もう終わっていた、はどれも成功です。相手がいなければ `no-such-process`、init には `not-permitted` です
- ハンドルの `kill` は、ハンドルの子がもういない（pid が別のプロセスに使い回された）なら、送らずに成功します
- `set-signal-ignored` は子に引き継ぎません

### 作業ディレクトリ

- `get-cwd`・`set-cwd` は、カーネルの cwd を読み書きします。子の `cwd` の既定と、相対パスの基点に使います
- p1 の `PWD`（[WASI p1](./wasi-p1.md#作業ディレクトリ)）と、p2 の `get-environment` の `PWD`・`initial-cwd`（[WASI p2・p3](./p2-p3.md)）は、最初の値のままです。libc の `chdir` と `set-cwd` は別のものなので、両方を合わせたいときは両方を呼んでください

### list-processes

- マシンの全プロセス（自分、まだ刈り取られていない終わったもの、親なしのゾンビを含む）を、pid の昇順で返します。待ちません。`cabi_realloc` の中からも呼べます。結果は呼んだ時点の写しです
- `process-state`: 読み込みの途中は `starting`、動ける・動いている（燃料待ちを含む）は `running`、イベントなどを待っているのは `waiting`、終わって刈り取られていないものは `finished`
- `args` は argv の先頭から、最大 16 個・UTF-8 の合計 255 バイトまで（仮の値）です。収まらない要素は UTF-8 の文字の境目で切って入れ、そこで止めます。切って空になる要素は入れません。切ったことの印はありません

## p1 向けの import

モジュール名: `kibi:process/process@0.1.0`

<!-- check:imports kibi:process/process@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `[static]process.spawn` | `(path_ptr: i32, path_len: i32, args_ptr: i32, args_len: i32, env_is_some: i32, env_ptr: i32, env_len: i32, cwd_is_some: i32, cwd_ptr: i32, cwd_len: i32, fds_ptr: i32, fds_len: i32, ret_ptr: i32) -> ()` | — | 待つ |
| `[method]process.pid` | `(self: i32) -> i32` | — | — |
| `[method]process.status` | `(self: i32, ret_ptr: i32) -> ()` | — | — |
| `[method]process.kill` | `(self: i32, sig: i32, ret_ptr: i32) -> ()` | — | — |
| `[resource-drop]process` | `(self: i32) -> ()` | — | — |
| `kill-pid` | `(pid: i32, sig: i32, ret_ptr: i32) -> ()` | — | — |
| `list-processes` | `(ret_ptr: i32) -> ()` | 要る | — |
| `self-pid` | `() -> i32` | — | — |
| `parent-pid` | `() -> i32` | — | — |
| `set-signal-ignored` | `(sig: i32, ignored: i32, ret_ptr: i32) -> ()` | — | — |
| `get-cwd` | `(ret_ptr: i32) -> ()` | 要る | — |
| `set-cwd` | `(path_ptr: i32, path_len: i32, ret_ptr: i32) -> ()` | — | — |
<!-- /check -->

`[method]process.pid` と `[resource-drop]process` に不正なハンドルを渡すとトラップです。`status`・`kill` は `bad-descriptor` を返します。`kill` は `sig` を先に確かめるので、`sig` の判別子もハンドルも不正なら `invalid` です。

## メモリ上の配置

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `string`・`list<T>` | `ptr @0`、`len @4` | 8 / 4 |
| `tuple<string, string>`（`env` の要素） | 名前 `ptr @0`・`len @4`、値 `ptr @8`・`len @12` | 16 / 4 |
| `fd-assign` | `fd: u32 @0`、`source` の判別子 `u8 @4`（`parent` = 0、`closed` = 1）、`parent` の fd `u32 @8` | 12 / 4 |
| `result<process, error-code>` | 判別子 `u8 @0`、ok ならハンドル `u32 @4`、err なら `u8 @4` | 8 / 4 |
| `result<_, error-code>` | 判別子 `u8 @0`、err なら `u8 @1` | 2 / 1 |
| `exit-status` | 判別子 `u8 @0`（`exited` = 0、`killed` = 1、`crashed` = 2）、`exited` なら `u32 @4`、`killed` なら `option` の判別子 `u8 @4`・`signal` の `u8 @5` | 8 / 4 |
| `result<option<exit-status>, error-code>` | 判別子 `u8 @0`。ok なら `option` の判別子 `u8 @4`・`exit-status @8`、err なら `u8 @4` | 16 / 4 |
| `process-state` | 判別子 `u8 @0`（`starting` = 0、`running` = 1、`waiting` = 2、`finished` = 3）、`finished` なら `exit-status @4` | 12 / 4 |
| `process-info` | `pid @0`、`parent-pid @4`、`state @8`、`args @20` | 28 / 4 |

`signal` の判別子は `interrupt` = 0、`quit` = 1、`terminate` = 2、`kill` = 3 です。

## spawn の引数の検査

引数の順（`path` → `args` → `env` → `cwd` → `fds`）に確かめ、最初に当たったものを返します。

| 条件 | 結果 |
| --- | --- |
| `path`・`cwd` が 4096 バイトを超える | `name-too-long` |
| `args`・`env`・`fds` の配列の ptr が 4 に揃っていない（長さ 0 でも。`env` は `none` なら見ません） | トラップ |
| `args` が 4096 個、`env` が 4096 組を超える（仮の値。要素を読む前） | `too-big` |
| 文字列の合計が上限（仮の値）を超える（中身を読む前） | `too-big` |
| `fds` が 256 個を超える（仮の値） | `invalid` |
| 文字列が UTF-8 でない、`option`・`fd-source` の判別子が不正、`fds` の fd が重複 | `invalid` |
| 引数の場所が線形メモリの外 | `fault` |
| `args`・`env` の文字列に NUL がある、`env` の名前が空・`=` を含む | `invalid` |
| `fds` の `fd`・`parent` の fd が 2^31 以上 | `bad-descriptor` |
| 実行ファイルがない・普通のファイルでない・動かせない形、シバンを 4 回を超えてたどった | `no-entry`・`access`・`no-exec`・`loop` など |
| このマシンの読み込み中・待ちの読み込みが上限（仮の値） | `again` |
| `cwd` がない・ディレクトリでない | `no-entry`・`not-directory` |
| 実行ファイルを読み込むあいだに、親の作業ディレクトリが変わった（`cwd` か `fds` の preopen を指定したとき） | `again` |
| `fds` の `fd` が 256 以上（0〜2 と 3〜255 だけ書けます）、`parent` が開いていない | `bad-descriptor` |

`again` は一時的な失敗です。少し待ってから同じ `spawn` をやり直してください。

`signal` の判別子が 0〜3 の外なら `invalid`、`set-signal-ignored` に `kill` を渡すと `invalid` です。`set-cwd` は、ない場所なら `no-entry`、ディレクトリでなければ `not-directory` です。

この順は Component Model の層では変わることがあります（[共通の規則](./kibi-abi.md#エラー)）。
