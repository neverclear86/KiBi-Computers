#!/usr/bin/env bash
# 開発クライアントの自動操作の口（#252）を呼ぶ。runClient を起動してから使う。
# 使い方: mc.sh <1.21.1|1.20.1> <endpoint> [JSON]
#   例: mc.sh 1.21.1 status
#       mc.sh 1.21.1 world '{"reset":true}'
#       mc.sh 1.21.1 command '{"command":"setblock ~2 ~ ~ minecraft:stone"}'
set -euo pipefail

case "${1:-}" in
  1.21.1) platform=platform-neoforge-1.21.1 ;;
  1.20.1) platform=platform-forge-1.20.1 ;;
  *) echo "usage: mc.sh <1.21.1|1.20.1> <endpoint> [JSON]" >&2; exit 2 ;;
esac
endpoint="${2:?endpoint is required}"
root="$(git rev-parse --show-toplevel)"
port_file="$root/$platform/run/dev-driver.port"
if [ ! -f "$port_file" ]; then
  echo "$port_file がありません（runClient は起動していますか？）" >&2
  exit 1
fi
port="$(cat "$port_file")"

case "$endpoint" in
  status|widgets) curl -sS "http://127.0.0.1:$port/$endpoint" ;;
  *) curl -sS -X POST "http://127.0.0.1:$port/$endpoint" -H 'Content-Type: application/json' -d "${3:-{\}}" ;;
esac
echo
