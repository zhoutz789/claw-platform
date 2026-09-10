#!/usr/bin/env bash
# Phase 1 HA 原型停止/清理
# 默认保留数据卷；加 --purge 删除所有数据（含归档 WAL）。
set -euo pipefail
cd "$(dirname "$0")"

PURGE=""
if [ "${1:-}" = "--purge" ]; then PURGE="--volumes"; fi

docker compose down $PURGE
echo "已停止。${PURGE:+数据卷已删除}${PURGE:-数据卷保留（再次启动沿用）。}"
