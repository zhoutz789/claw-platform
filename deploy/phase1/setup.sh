#!/usr/bin/env bash
# Phase 1 HA 原型一键启动
set -euo pipefail
cd "$(dirname "$0")"

echo "==> 启动 PostgreSQL 主库 + 流复制副本 + MinIO(异地归档模拟)"
docker compose up -d

echo "==> 等待主库就绪..."
for i in $(seq 1 30); do
  if docker exec phase1-pg-primary pg_isready -U claw -d claw >/dev/null 2>&1; then
    echo "主库就绪"; break
  fi
  sleep 2
done

echo "==> 等待副本引导/接入流复制（首次需 pg_basebackup，稍候）..."
sleep 8
docker exec phase1-pg-replica pg_isready -U claw -d claw >/dev/null 2>&1 && echo "副本就绪" || echo "副本未就绪（查看日志: docker logs phase1-pg-replica）"

echo
echo "连接信息:  主库 6432 / 副本(只读) 6433 / MinIO 控制台 9001"
echo "复制状态:  docker exec phase1-pg-primary psql -U claw -c \"SELECT * FROM pg_stat_replication;\""
echo "灾备演练:  bash ../../scripts/phase1-dr-drill.sh all"
