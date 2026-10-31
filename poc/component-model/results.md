# Component Model の実装方式の測定結果（#260）

`./gradlew -p poc/component-model :bench:run` の出力（Java 17、1回）。endive-cm のコミット `2e541b7`（2026-10-30）、Endive 1.2.0。

- endive-cm は Maven Central に出ていない（`999-SNAPSHOT`）ので、固定したコミットのソースを取得し、Gradle でビルドした（`settings.gradle.kts`）
- Windows では endive-cm の WIT の読み込みが壊れる（パッケージディレクトリの相対パスを `toString()` で仮想 FS に渡すので、`deps\x.wit` が1つのファイル名になる）。`wasi-p2` のビルドが通らないので、測定のために手元で直した（上流に報告する候補）
- 2 のコーパスは endive-cm 同梱の Rust（`wasm32-wasip2`）のプログラム。時間は各1回目（JIT が冷えた状態）で、`args` の行は最初の1回の読み込みを含む
- 3 の「計測コードを挿入したコンポーネント」は、#28 の `FuelInjector` でコンポーネントの中のコアモジュールをすべて書き換えたもの
- 6 は、`wasm-tools parse` で WAT から作った最小のコンポーネントを、`ComponentLinker` でインスタンス化して `run` を呼んだ結果
- 環境: Windows 10 / AMD Ryzen 9 3950X / Amazon Corretto 17.0.12

```text
== 1. Java
java.version=17.0.12 vendor=Amazon.com Inc.
== 2. コーパス（Rust wasm32-wasip2）を WasiPreview2 で実行
program               size[KiB]  parse[ms]  validate[ms]  run[ms]  result
args                       64.3      174.5        1103.3    115.8  ok
clock-sleep                71.3       44.9          75.3     27.8  ok
env                        55.3       37.2          56.8     32.3  ok
exit-failure               51.3       21.6          49.1     13.3  ok
exit-ok                    53.1       27.8          51.8      5.0  ok
file-read                  80.3       24.2          56.1     33.1  ok
file-write-readback        89.2       18.7          56.4     25.4  ok
hello-stdout               53.0       11.5          36.9     10.4  ok
random-bytes               55.3       13.4          47.9      6.9  ok
read-dir                   94.5       25.5          45.3     15.9  ok
stdin-echo                 69.1       17.0          34.5      6.6  ok
passed 11 / 11
hello-stdout warm (20 回の中央値): parse=10.89ms run=2.09ms
== 3. 燃料計測（ComponentStore.withCoreInstances + ExecutionListener）
hello-stdout の実行命令数: 3103
上限 1551 命令で実行: 停止 ← OutOfFuel: out of fuel
計測コードを挿入したコンポーネント（kibi.fuel の import が増える）: 停止 ← LinkageException: Failed to instantiate component: unknown import, could not find host function for import number: 0 named kibi.fuel ← UnlinkableException: unknown import, could not find host function for import number: 0 named kibi.fuel
== 3b. ランタイムコンパイラ（#29 の決定）でコアモジュールを動かす
hello-stdout をコンパイラで実行: 最後まで実行（戻り値 0）
hello-stdout 実行時間の中央値（インタプリタ）: 1.24ms
== 4. 中断（待機中のゲストへのスレッド割り込み）
割り込みから停止まで 0.4ms: 停止 ← WasmInterruptedException: Thread interrupted
== 5. 状態の取り出し（実行後のコアインスタンス）
コアインスタンス 3 個、線形メモリ 1 個、合計 17 ページ
グローバル数: [0, 10, 0]
== 6. 非同期（WASI p3 の土台）: canon の対応状況
sync lift（基準）                最後まで実行（戻り値 [42]）
async lift + task.return     停止 ← UnsupportedOperationException: Canon kind TASK_RETURN not yet supported
stream.new                   停止 ← UnsupportedOperationException: Canon kind STREAM_NEW not yet supported
waitable-set.new             停止 ← UnsupportedOperationException: Canon kind WAITABLE_SET_NEW not yet supported
== 7. 依存の大きさ（実行時クラスパスの jar）
other: kotlin-stdlib-2.4.21.jar                 1809.9 KiB
endive-cm: cm-parser.jar                          36.5 KiB
endive-cm: cm-wasi-p2.jar                        251.9 KiB
endive-cm: cm-runtime.jar                        137.7 KiB
endive-cm: cm-component-corpus.jar               319.7 KiB
endive-cm: cm-wasm-tools.jar                      27.3 KiB
other: jackson-core-2.22.4.jar                   585.5 KiB
other: jackson-databind-2.22.4.jar              1674.3 KiB
other: compiler-1.2.0.jar                        162.3 KiB
other: annotations-13.0.jar                       17.1 KiB
endive-cm: cm-canonical-abi.jar                   61.7 KiB
endive-cm: cm-types.jar                          206.3 KiB
run.endive:wasm-tools (validate 用)              5503.9 KiB
run.endive (既存)                                  467.9 KiB
netty (wasi:sockets)                            3311.4 KiB
other: zerofs-0.1.0.jar                          204.0 KiB
other: jackson-annotations-2.22.jar               82.2 KiB
other: asm-commons-9.11.1.jar                     76.1 KiB
other: asm-util-9.11.1.jar                        95.8 KiB
other: asm-analysis-9.11.1.jar                    38.7 KiB
other: asm-tree-9.11.1.jar                        50.7 KiB
other: asm-9.11.1.jar                            128.1 KiB
```
