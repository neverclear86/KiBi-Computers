// 独自拡張 kibi:computer/memory（#408・#73。WIT は core/src/main/wit/kibi-computer/memory.wit）を、WASI p1 のプログラムから呼ぶための宣言。
// p1 向けの import（ADR 0073 の規則で、モジュール "kibi:computer/memory@0.1.0"）として出している。
//
// RAM は、初めて書き込んだ 4 KiB の区画の数で数える（#407）。
#ifndef KIBI_MEMORY_H
#define KIBI_MEMORY_H

#include <stdint.h>

// 残りの RAM（バイト）。プロセスの上限とマシンの RAM のうち、小さい方の残り。
__attribute__((import_module("kibi:computer/memory@0.1.0"), import_name("remaining")))
uint64_t kibi_memory_remaining(void);

// [ptr, ptr+len) の区画を今のうちに数える（以後そこへ書いても、RAM の上限で止まらない）。
// 数えた後の残りが keep 未満になるなら、何も数えずに 0。新しく数える区画がなければ 1。範囲が線形メモリの外なら 0。
// 成功なら 1。
__attribute__((import_module("kibi:computer/memory@0.1.0"), import_name("commit")))
int32_t kibi_memory_commit(void *ptr, uint32_t len, uint64_t keep);

#endif
