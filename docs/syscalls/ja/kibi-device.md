---
title: kibi:device
description: つないでいるデバイスの一覧と、メソッドの呼び出し（結果まで待つ呼び出しと、待たずに積む呼び出し）。
---

`kibi:device@0.1.0` は、コンピューターにつないでいるデバイス（画面、ディスクドライブ、他 MOD のブロックなど）を列挙し、メソッドを呼ぶ口です。デバイスは `/dev/device/<アドレス>` のファイルとしても見えます（`path_open` で開いた fd は、`device` のハンドルとして使えます）。

## types

```wit
// kibi-device/types.wit
package kibi:device@0.1.0;
interface types {
    use kibi:kernel/types@0.1.0.{error-code};
    variant value {
        none,
        integer(s64),
        float(f64),
        boolean(bool),
        text(string),
        bytes(list<u8>),
    }
    record device-info {
        address: string,
        kind: string,
    }
    record method-info {
        name: string,
        main-thread: bool,
    }
    variant call-error {
        kernel(error-code),
        detached,
        no-such-method,
        bad-arguments(string),
        failed(string),
    }
}
```

- `value` は入れ子のない値です。case の名前は `kibi:kernel/events` の `event-value` と同じですが、判別子の番号は違います（`none` = 0 から）
- `device-info.address` は小文字の UUID（36 文字）、`kind` は `<名前空間>:<名前>`（例: `kibicomputers:screen`）です
- `method-info.main-thread` が真のメソッドは、ゲームのメインスレッドで次の tick の中で実行するので、結果まで時間がかかります

## bus

```wit
// kibi-device/bus.wit
package kibi:device@0.1.0;
interface bus {
    use types.{value, device-info, method-info, call-error};
    list-devices: func() -> list<device-info>;
    resource device {
        open: static func(address: string) -> result<device, call-error>;
        info: func() -> device-info;
        methods: func() -> list<method-info>;
        invoke: func(method: string, args: list<value>) -> result<list<value>, call-error>;
        start: func(method: string, args: list<value>) -> result<pending-call, call-error>;
    }
    resource pending-call {
        take: func() -> option<result<list<value>, call-error>>;
    }
}
```

- `list-devices` は待ちません。接続を変えたときだけ作り直す一覧を写します。つなぐ・外すは `kibi:kernel` のイベント `device-attached`・`device-detached` で知らせます（[イベントの種類](./kibi-kernel.md#イベントの種類)）
- `device.open`: アドレスが 36 バイトでない・見つからないなら `detached` です。開いたハンドルの権利は読む権利だけです
- `device.info` は、外れた後も元の値を返します。`methods` は、外れている・種類が変わったなら空です
- `device.invoke` は**止まりうる import** です。結果が出るまで待ちます（メインスレッドのメソッドは次の tick まで）。プロセスのスレッドでその場で動くメソッドは、すぐに結果を返します
- `device.start` は待たずに呼び出しを積み、`pending-call` を返します。1プロセスに 16 個まで（仮の値）で、超えると `kernel(again)` です。`invoke` はこの数に入りません
- `pending-call` は、終わると `poll_oneoff` の `FD_READ` でそろいます。`take` は待たずに結果を返し（まだなら `none`）、閉じません。何度呼んでも同じ結果です。終わる前に閉じると、呼び出しを取り消します
- デバイスが外れても、ハンドルは残ります。そのハンドルでの呼び出しは `detached` です

### 保存から戻ったとき

- `invoke` で待っていたメインスレッドのメソッドのうち、保存の時点で実行中だったものは `failed("interrupted")` になります。まだ始まっていなかったものは、戻った後に実行されます。どちらの場合も、メソッドは二重には実行されません
- `start` で積んだ呼び出しも同じ規則です

## p1 向けの import

モジュール名: `kibi:device/bus@0.1.0`

<!-- check:imports kibi:device/bus@0.1.0 -->
| 関数 | core の型 | cabi_realloc | 待つ |
| --- | --- | --- | --- |
| `list-devices` | `(ret_ptr: i32) -> ()` | 要る | — |
| `[static]device.open` | `(addr_ptr: i32, addr_len: i32, ret_ptr: i32) -> ()` | 要る | — |
| `[method]device.info` | `(self: i32, ret_ptr: i32) -> ()` | 要る | — |
| `[method]device.methods` | `(self: i32, ret_ptr: i32) -> ()` | 要る | — |
| `[method]device.invoke` | `(self: i32, m_ptr: i32, m_len: i32, a_ptr: i32, a_len: i32, ret_ptr: i32) -> ()` | 要る | 待つ |
| `[method]device.start` | `(self: i32, m_ptr: i32, m_len: i32, a_ptr: i32, a_len: i32, ret_ptr: i32) -> ()` | 要る | — |
| `[method]pending-call.take` | `(self: i32, ret_ptr: i32) -> ()` | 要る | — |
| `[resource-drop]device` | `(self: i32) -> ()` | — | — |
| `[resource-drop]pending-call` | `(self: i32) -> ()` | — | — |
<!-- /check -->

`open`・`start` も、`call-error` に `string` があるので `cabi_realloc` が要ります。`kibi:device/types` には関数がないので、import はありません。

## メモリ上の配置

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `value` | 判別子 `u8 @0`、値 `@8`（`integer`・`float` は 8 バイト、`boolean` は `u8`、`text`・`bytes` は `ptr @8`・`len @12`） | 16 / 8 |
| `device-info` | `address` の `ptr @0`・`len @4`、`kind` の `ptr @8`・`len @12` | 16 / 4 |
| `method-info` | `name` の `ptr @0`・`len @4`、`main-thread: u8 @8` | 12 / 4 |
| `call-error` | 判別子 `u8 @0`、`kernel` なら `error-code` の `u8 @4`、`bad-arguments`・`failed` なら `ptr @4`・`len @8` | 12 / 4 |
| `result<list<value>, call-error>` | 判別子 `u8 @0`、ok なら `ptr @4`・`len @8`、err なら `call-error @4` | 16 / 4 |
| `result<device, call-error>`・`result<pending-call, call-error>` | 判別子 `u8 @0`、ok ならハンドル `u32 @4`、err なら `call-error @4` | 16 / 4 |
| `option<result<list<value>, call-error>>` | 判別子 `u8 @0`、`result @4` | 20 / 4 |

`value` の判別子は `none` = 0、`integer` = 1、`float` = 2、`boolean` = 3、`text` = 4、`bytes` = 5、`call-error` の判別子は `kernel` = 0、`detached` = 1、`no-such-method` = 2、`bad-arguments` = 3、`failed` = 4 です。

## 引数とハンドルの誤り

`invoke`・`start` は、引数の順（`method` → `args`）に確かめ、その後にハンドルを確かめます。

| 条件 | 結果 |
| --- | --- |
| `method` が 64 バイトを超える（読む前） | `kernel(name-too-long)` |
| `args` の ptr が 8 に揃っていない（長さ 0 でも。`args` のほかの検査より先） | トラップ |
| `args` が 32 個を超える（要素を読む前） | `kernel(too-big)` |
| `text`・`bytes` の長さの合計が 64 KiB を超える（中身を読む前） | `kernel(too-big)` |
| `value` の判別子が 0〜5 の外、UTF-8 の誤り | `kernel(invalid)` |
| 引数の場所が線形メモリの外 | `kernel(fault)` |
| `invoke`・`start`・`take` の不正なハンドル | `kernel(bad-descriptor)` |
| `info`・`methods`・`[resource-drop]*` の不正なハンドル | トラップ |
| fd の表が満杯（`open`・`start`、メインスレッドのメソッドの `invoke`） | `kernel(mfile)` |

`boolean` は 0 でなければ真です。返す文字列は UTF-8 です。上限の数は仮の値です。
