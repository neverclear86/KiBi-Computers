#!/usr/bin/env bash
# コマンドを前景で動かし、終わらないまま <遅延> 秒たったら、動いている全 JVM のスレッドダンプ（jcmd Thread.print -l）を撮る（#927）。
# CI の段の上限（timeout-minutes）で Gradle ごと殺される前に、止まったテストの JVM（と Gradle のデーモン・worker）の様子を残す。
# JUnit の中のダンプ（core のテストの ThreadDumpOnTimeout）が拾えない止まり（@TestFactory の動的テスト、クラスの初期化、
# Gradle の worker そのもの、保険のタイムアウトが何本も積み重なったとき）を受け持つ。
#
# 使い方: with-thread-dump-watchdog.sh <遅延（秒）> <出力ディレクトリ> <名前> -- <コマンド...>
#   撮ったものは <出力ディレクトリ>/<名前>-<回>-<pid>.txt に書き、ログにも ::group:: で出す。
#   間隔（THREAD_DUMP_INTERVAL 秒。既定 60）を置いて THREAD_DUMP_ROUNDS 回（既定 2）撮る（2 回を比べて、進んでいないところが分かる）。
#   jcmd は JCMD、なければ $JAVA_HOME_17_X64/bin/jcmd（テストの JVM と同じ 17）、なければ PATH の jcmd を使う。
#   終了コードはコマンドのもの。見張りは、コマンドが終わったら（このスクリプトが抜けるときは必ず）止める。
set -uo pipefail

if [ $# -lt 5 ] || [ "$4" != "--" ]; then
  echo "usage: $0 <delay-seconds> <out-dir> <name> -- <command...>" >&2
  exit 2
fi
delay=$1
out_dir=$2
name=$3
shift 4

rounds=${THREAD_DUMP_ROUNDS:-2}
interval=${THREAD_DUMP_INTERVAL:-60}
if [ -n "${JCMD:-}" ]; then
  jcmd_bin=$JCMD
elif [ -n "${JAVA_HOME_17_X64:-}" ] && [ -x "$JAVA_HOME_17_X64/bin/jcmd" ]; then
  jcmd_bin=$JAVA_HOME_17_X64/bin/jcmd
else
  jcmd_bin=jcmd
fi

watch() {
  local sleeper=""
  trap '[ -n "$sleeper" ] && kill "$sleeper" 2>/dev/null; exit 0' TERM INT
  # 割り込める待ち（sleep を前景にすると、TERM が sleep の終わりまで届かない）
  sleep "$delay" & sleeper=$!; wait "$sleeper"; sleeper=""
  local round
  for round in $(seq 1 "$rounds"); do
    mkdir -p "$out_dir"
    echo "#927 スレッドダンプ: ${delay} 秒たってもコマンドが終わらないので、全 JVM を撮る（${round}/${rounds} 回目）"
    local pid rest
    while read -r pid rest; do
      case "$rest" in
        *JCmd*|*sun.tools.jcmd*|"") continue ;;
      esac
      local file="$out_dir/${name}-${round}-${pid}.txt"
      {
        echo "# pid ${pid}: ${rest}"
        echo "# $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        "$jcmd_bin" "$pid" Thread.print -l 2>&1
      } > "$file"
      echo "::group::#927 スレッドダンプ ${round}/${rounds}: pid ${pid} ${rest%% *}（全文: ${file}）"
      cat "$file"
      echo "::endgroup::"
    done < <("$jcmd_bin" -l 2>/dev/null)
    if [ "$round" -lt "$rounds" ]; then
      sleep "$interval" & sleeper=$!; wait "$sleeper"; sleeper=""
    fi
  done
}

watch &
watcher=$!
trap 'kill "$watcher" 2>/dev/null; wait "$watcher" 2>/dev/null' EXIT

"$@"
