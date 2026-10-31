# Endive の基本性能の測定結果（#26）

`./gradlew :poc:endive-bench:benchAll` の出力（Java 17 / 21 / 25 で1回ずつ）。Endive 1.2.0。

- 時間は、ウォームアップ（3 秒以上）の後に 3 秒以上かつ 5 回以上測った中央値
- 「準備」は、WASM のパース（インタプリタ）、パースとコンパイル（ランタイムコンパイラ）、生成済みクラスの読み込み（ビルド時AOT・Redline）
- 準備・インスタンス生成とメモリは、1件ごとに新しい JVM で測る（初回はクラスの読み込みを含む）。ヒープは GC の後の差
- bench.wasm の1インスタンスの 7.5 MB は、ほぼベンチマーク用の静的な配列（線形メモリ）。Redline の線形メモリはヒープの外にあるので、ヒープ増には出ない
- Lua は毎回インスタンスを作り、`lua -` に標準入力からスクリプトを渡して最後まで実行した時間（WASI の準備を含む）
- Java 25 は MC の対象外。Redline の Panama（FFM）の runner を試すためだけに測った（Java 17 / 21 は jffi の runner）

## Java 17（OpenJDK 64-Bit Server VM 17.0.12+7-LTS）

- OS: Windows 10 amd64、CPU 32 スレッド
- JVM 引数: -XX:+UseG1GC -Xms2g -Xmx2g -Dfile.encoding=UTF-8 -Duser.country=JP -Duser.language=ja -Duser.variant
- Redline: ネイティブで実行（JffiMachineFactoryProvider）

### 準備・インスタンス生成とメモリ（それぞれ新しい JVM で測定）

| WASM | 方式 | 準備（初回） | 生成（初回） | 生成（2回目以降） | Metaspace 増 | ヒープ増（準備） | ヒープ増（1インスタンス） |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| bench | インタプリタ | 73.8 ms | 27.7 ms | 2.4 ms | 1.8 MB | 967 KB | 7.5 MB |
| bench | ランタイムコンパイラ | 278 ms | 22.1 ms | 2.4 ms | 4.5 MB | 1.5 MB | 7.5 MB |
| bench | ビルド時AOT | 69.1 ms | 25.5 ms | 2.4 ms | 1.6 MB | 918 KB | 7.5 MB |
| bench | Redline | 21.6 ms | 185 ms | 2.8 ms | 3.0 MB | 1.2 MB | 2 KB |
| lua | インタプリタ | 249 ms | 58.8 ms | 741 µs | 2.5 MB | 10.3 MB | 1.2 MB |
| lua | ランタイムコンパイラ | 888 ms | 142 ms | 590 µs | 7.1 MB | 1.7 MB | 1.2 MB |
| lua | ビルド時AOT | 93.7 ms | 45.1 ms | 815 µs | 2.3 MB | 1.2 MB | 1.2 MB |
| lua | Redline | 不可（例外処理に未対応） | | | | | |

ランタイムコンパイラで同じモジュールを毎回コンパイルし直したとき（参照を捨てて GC した後）:

- bench: 1回あたり 22.2 ms、Metaspace は 10 回で +1.1 MB、GC 後 +267 KB
- lua: 1回あたり 215 ms、Metaspace は 10 回で +15.8 MB、GC 後 +185 KB

### CPU ベンチマーク（1回あたりの中央値。カッコ内はインタプリタ比）

| ベンチマーク | インタプリタ | ランタイムコンパイラ | ビルド時AOT | Redline |
| --- | ---: | ---: | ---: | ---: |
| fib(27) 再帰 | 137 ms（1x） | 809 µs（169.9x） | 809 µs（169.9x） | 1.0 ms（134.3x） |
| sieve(2,000,000) | 1107 ms（1x） | 24.6 ms（45.0x） | 28.7 ms（38.5x） | 6.8 ms（163.2x） |
| matmul 128x128 (f64) | 353 ms（1x） | 8.0 ms（44.1x） | 7.9 ms（44.6x） | 2.2 ms（161.5x） |
| crc32 64KB x4 | 233 ms（1x） | 1.5 ms（154.0x） | 1.5 ms（154.3x） | 1.1 ms（204.7x） |

### Lua（lua.wasm、毎回インスタンスを作って最後まで実行。1回あたりの中央値）

| スクリプト | インタプリタ | ランタイムコンパイラ | ビルド時AOT |
| --- | ---: | ---: | ---: |
| （空のスクリプト） | 9.6 ms（1x） | 420 µs（22.8x） | 414 µs（23.2x） |
| fib.lua | 1855 ms（1x） | 177 ms（10.5x） | 179 ms（10.4x） |
| tables.lua | 5889 ms（1x） | 295 ms（19.9x） | 301 ms（19.6x） |
| strings.lua | 4521 ms（1x） | 255 ms（17.7x） | 255 ms（17.7x） |

### 割り込み

終わらないループ（`spin`）を `Thread.interrupt()` で止めるまでの時間（2 秒待って止まらなければ「止まらない」）:

- インタプリタ: 213 µs
- ランタイムコンパイラ: 145 µs
- ビルド時AOT: 166 µs
- Redline: 1.1 ms


## Java 21（OpenJDK 64-Bit Server VM 21.0.4+7-LTS）

- OS: Windows 10 amd64、CPU 32 スレッド
- JVM 引数: -XX:+UseG1GC -Xms2g -Xmx2g -Dfile.encoding=UTF-8 -Duser.country=JP -Duser.language=ja -Duser.variant
- Redline: ネイティブで実行（JffiMachineFactoryProvider）

### 準備・インスタンス生成とメモリ（それぞれ新しい JVM で測定）

| WASM | 方式 | 準備（初回） | 生成（初回） | 生成（2回目以降） | Metaspace 増 | ヒープ増（準備） | ヒープ増（1インスタンス） |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| bench | インタプリタ | 102 ms | 28.3 ms | 2.5 ms | 1.6 MB | 964 KB | 7.5 MB |
| bench | ランタイムコンパイラ | 297 ms | 21.7 ms | 2.4 ms | 4.3 MB | 1.5 MB | 7.5 MB |
| bench | ビルド時AOT | 96.7 ms | 25.5 ms | 2.4 ms | 1.4 MB | 915 KB | 7.5 MB |
| bench | Redline | 26.6 ms | 206 ms | 2.9 ms | 2.9 MB | 1.2 MB | 2 KB |
| lua | インタプリタ | 281 ms | 49.4 ms | 719 µs | 2.3 MB | 10.2 MB | 1.2 MB |
| lua | ランタイムコンパイラ | 905 ms | 133 ms | 577 µs | 6.9 MB | 1.7 MB | 1.2 MB |
| lua | ビルド時AOT | 125 ms | 48.8 ms | 813 µs | 2.2 MB | 1.2 MB | 1.2 MB |
| lua | Redline | 不可（例外処理に未対応） | | | | | |

ランタイムコンパイラで同じモジュールを毎回コンパイルし直したとき（参照を捨てて GC した後）:

- bench: 1回あたり 18.2 ms、Metaspace は 10 回で +1.1 MB、GC 後 +266 KB
- lua: 1回あたり 210 ms、Metaspace は 10 回で +16.0 MB、GC 後 +201 KB

### CPU ベンチマーク（1回あたりの中央値。カッコ内はインタプリタ比）

| ベンチマーク | インタプリタ | ランタイムコンパイラ | ビルド時AOT | Redline |
| --- | ---: | ---: | ---: | ---: |
| fib(27) 再帰 | 155 ms（1x） | 777 µs（200.1x） | 772 µs（201.4x） | 1.0 ms（152.5x） |
| sieve(2,000,000) | 1035 ms（1x） | 36.8 ms（28.1x） | 35.9 ms（28.9x） | 6.8 ms（152.7x） |
| matmul 128x128 (f64) | 420 ms（1x） | 9.0 ms（46.5x） | 8.0 ms（52.6x） | 2.2 ms（192.4x） |
| crc32 64KB x4 | 288 ms（1x） | 1.4 ms（202.3x） | 1.4 ms（202.4x） | 1.1 ms（252.9x） |

### Lua（lua.wasm、毎回インスタンスを作って最後まで実行。1回あたりの中央値）

| スクリプト | インタプリタ | ランタイムコンパイラ | ビルド時AOT |
| --- | ---: | ---: | ---: |
| （空のスクリプト） | 9.4 ms（1x） | 403 µs（23.3x） | 409 µs（23.0x） |
| fib.lua | 1875 ms（1x） | 181 ms（10.4x） | 181 ms（10.3x） |
| tables.lua | 5759 ms（1x） | 294 ms（19.6x） | 299 ms（19.3x） |
| strings.lua | 4479 ms（1x） | 257 ms（17.5x） | 253 ms（17.7x） |

### 割り込み

終わらないループ（`spin`）を `Thread.interrupt()` で止めるまでの時間（2 秒待って止まらなければ「止まらない」）:

- インタプリタ: 177 µs
- ランタイムコンパイラ: 136 µs
- ビルド時AOT: 144 µs
- Redline: 100 ms


## Java 25（OpenJDK 64-Bit Server VM 25.0.3+9-LTS）

- OS: Windows 10 amd64、CPU 32 スレッド
- JVM 引数: -XX:+UseG1GC --enable-native-access=ALL-UNNAMED -Xms2g -Xmx2g -Dfile.encoding=UTF-8 -Duser.country=JP -Duser.language=ja -Duser.variant
- Redline: ネイティブで実行（PanamaMachineFactoryProvider）

### 準備・インスタンス生成とメモリ（それぞれ新しい JVM で測定）

| WASM | 方式 | 準備（初回） | 生成（初回） | 生成（2回目以降） | Metaspace 増 | ヒープ増（準備） | ヒープ増（1インスタンス） |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| bench | インタプリタ | 82.0 ms | 28.1 ms | 2.4 ms | 1.7 MB | 1008 KB | 7.5 MB |
| bench | ランタイムコンパイラ | 286 ms | 20.5 ms | 2.4 ms | 4.5 MB | 1.5 MB | 7.5 MB |
| bench | ビルド時AOT | 71.2 ms | 25.4 ms | 2.4 ms | 1.6 MB | 958 KB | 7.5 MB |
| bench | Redline | 23.9 ms | 189 ms | 4.1 ms | 3.9 MB | 1.4 MB | 4 KB |
| lua | インタプリタ | 258 ms | 54.2 ms | 740 µs | 2.4 MB | 10.3 MB | 1.2 MB |
| lua | ランタイムコンパイラ | 745 ms | 38.5 ms | 519 µs | 7.1 MB | 1.7 MB | 1.2 MB |
| lua | ビルド時AOT | 96.5 ms | 49.1 ms | 775 µs | 2.3 MB | 1.2 MB | 1.2 MB |
| lua | Redline | 不可（例外処理に未対応） | | | | | |

ランタイムコンパイラで同じモジュールを毎回コンパイルし直したとき（参照を捨てて GC した後）:

- bench: 1回あたり 14.9 ms、Metaspace は 10 回で +1.0 MB、GC 後 +224 KB
- lua: 1回あたり 189 ms、Metaspace は 10 回で +17.3 MB、GC 後 +165 KB

### CPU ベンチマーク（1回あたりの中央値。カッコ内はインタプリタ比）

| ベンチマーク | インタプリタ | ランタイムコンパイラ | ビルド時AOT | Redline |
| --- | ---: | ---: | ---: | ---: |
| fib(27) 再帰 | 145 ms（1x） | 768 µs（188.5x） | 768 µs（188.5x） | 1.0 ms（142.0x） |
| sieve(2,000,000) | 1222 ms（1x） | 36.8 ms（33.2x） | 44.9 ms（27.2x） | 6.8 ms（180.1x） |
| matmul 128x128 (f64) | 384 ms（1x） | 8.0 ms（47.8x） | 8.3 ms（46.4x） | 2.2 ms（175.6x） |
| crc32 64KB x4 | 258 ms（1x） | 1.5 ms（175.1x） | 1.5 ms（175.4x） | 1.1 ms（226.7x） |

### Lua（lua.wasm、毎回インスタンスを作って最後まで実行。1回あたりの中央値）

| スクリプト | インタプリタ | ランタイムコンパイラ | ビルド時AOT |
| --- | ---: | ---: | ---: |
| （空のスクリプト） | 9.2 ms（1x） | 384 µs（23.9x） | 392 µs（23.5x） |
| fib.lua | 1745 ms（1x） | 190 ms（9.2x） | 189 ms（9.2x） |
| tables.lua | 5505 ms（1x） | 309 ms（17.8x） | 317 ms（17.4x） |
| strings.lua | 4269 ms（1x） | 258 ms（16.5x） | 252 ms（16.9x） |

### 割り込み

終わらないループ（`spin`）を `Thread.interrupt()` で止めるまでの時間（2 秒待って止まらなければ「止まらない」）:

- インタプリタ: 231 µs
- ランタイムコンパイラ: 137 µs
- ビルド時AOT: 110 µs
- Redline: 173 µs


