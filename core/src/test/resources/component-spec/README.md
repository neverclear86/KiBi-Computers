# Component Model の仕様のテスト

[WebAssembly/component-model](https://github.com/WebAssembly/component-model) のコミット
`dd1fefc0c618382359feca6f2c758a2b3ef21c36` の `test/values/*.wast`・`test/linking/*.wast`（#263）と `test/resources/*.wast`（#265）を写したもの。
ファイル名の `values-`・`linking-`・`resources-` は元のディレクトリ。中身は書き換えていない。

- `values/post-return.wast` は仕様の `nyi.txt`（まだどこも通らないもの）にあり、同梱の wasm-tools でも読めないので入れていない。
- ライセンスは Apache License 2.0（`LICENSE-APACHE`。元のリポジトリの `LICENSE` のとおり）。

`ComponentSpecTest` が `wasm-tools json-from-wast` で読んで動かす。
