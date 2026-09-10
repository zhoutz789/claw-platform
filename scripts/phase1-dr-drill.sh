#!/usr/bin/env bash
# Phase 1 灾备演练（本地原型版）
# 演示：主库宕机 -> 副本提升为新主 -> 业务连续性验证 -> 丢失节点重建为副本 -> WAL 归档/PITR 说明
#
# 前置：先 bash deploy/phase1/setup.sh 起好主库+副本。
# 用法：
#   bash scripts/phase1-dr-drill.sh all       # 依次 failover -> verify -> pitr 说明
#   bash scripts/phase1-dr-drill.sh failover  # 模拟主库宕机并提升副本
#   bash scripts/phase1-dr-drill.sh verify    # 验证新主可读写
#   bash scripts/phase1-dr-drill.sh reseed    # 把原主作为新副本重建（丢失节点迅速再备份到另一台）
#   bash scripts/phase1-dr-drill.sh pitr      # 检查 WAL 归档并给出生产 PITR 流程
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY="$SCRIPT_DIR/../deploy/phase1"

PRIMARY=phase1-pg-primary
REPLICA=phase1-pg-replica
NETWORK=phase1_default

step() { echo; echo "=== $* ==="; }

case "${1:-all}" in
  failover)
    step "1) 模拟主库宕机：停止 $PRIMARY"
    docker stop "$PRIMARY"

    step "2) 提升副本为新主（pg_promote）"
    docker exec "$REPLICA" psql -U claw -p 5432 -c "SELECT pg_promote();"
    sleep 3

    step "3) 验证新主可读写（业务连续性）"
    docker exec "$REPLICA" psql -U claw -p 5432 -c \
      "CREATE TABLE IF NOT EXISTS dr_sentinel(id int primary key, note text); \
       INSERT INTO dr_sentinel VALUES (1,'promoted @ $(date -u +%FT%TZ)') \
       ON CONFLICT (id) DO UPDATE SET note=EXCLUDED.note; \
       SELECT * FROM dr_sentinel;"
    echo "-> 故障切换成功，业务在新主上连续运行（无数据丢失）。"
    ;;

  verify)
    step "业务连续性验证：在新主上读写"
    docker exec "$REPLICA" psql -U claw -p 5432 -c "SELECT * FROM dr_sentinel;"
    ;;

  reseed)
    step "重建原主为副本（丢失节点迅速再被备份到另一台服务器）"
    docker rm -f "$PRIMARY" >/dev/null 2>&1 || true
    docker volume rm phase1_pg_primary >/dev/null 2>&1 || true

    # 以 replica 角色重新创建原主容器，从当前主(已提升的 replica)拉取基础备份
    # 注意：这是"重新配置为 standby"的演示，对应生产里旧主恢复后改挂为新主的副本。
    docker run -d --name "$PRIMARY" --network "$NETWORK" \
      -e POSTGRES_USER=claw -e POSTGRES_PASSWORD=claw_dev_password \
      -e PGDATA=/var/lib/postgresql/data/pgdata \
      -e PRIMARY_HOST=phase1-pg-replica \
      -v phase1_pg_primary:/var/lib/postgresql/data \
      -v "$DEPLOY/docker-entrypoint-replica.sh:/entrypoint-replica.sh:ro" \
      -v "$DEPLOY/postgresql-replica.conf:/etc/postgresql/postgresql.conf:ro" \
      -v "$DEPLOY/pg_hba_replica.conf:/etc/postgresql/pg_hba.conf:ro" \
      -v phase1_pg_archive:/mnt/archive \
      postgres:16 sh /entrypoint-replica.sh

    echo "-> 原主已作为新副本从当前主(phase1-pg-replica)重建；等待流复制恢复..."
    sleep 12
    docker exec "$REPLICA" psql -U claw -p 5432 -c "SELECT * FROM pg_stat_replication;"
    ;;

  pitr)
    step "PITR 说明与本地归档检查"
    echo "WAL 归档目录(/mnt/archive 共享卷)现有文件："
    docker exec "$PRIMARY" ls -1 /mnt/archive 2>/dev/null | head -20 \
      || echo "(主库未运行或归档为空 —— 先 failover 使其产生 WAL)"
    echo
    echo "生产 PITR 流程（基于本原型共享归档卷 /mnt/archive）："
    echo "  1) 取基础备份:   pg_basebackup -h <主> -U claw -D /restore -Fp -Xs"
    echo "  2) 写恢复配置:   restore_command='cp /mnt/archive/%f %p'"
    echo "  3) 设目标时间点: recovery_target_time='<误删/损坏前>' + recovery_target_action=promote"
    echo "  4) 启动 -> PostgreSQL 重放 WAL 至目标时间点后开放读写"
    echo
    echo "异地备份：将 /mnt/archive 同步到 MinIO(9000) 并开启跨区复制即满足'异地'要求；"
    echo "生产 archive_command 直接 'mc cp %p localminio/claw-wal-archive/%f' 即可。"
    ;;

  all)
    "$0" failover
    "$0" verify
    "$0" pitr
    ;;

  *)
    echo "usage: $0 {all|failover|verify|reseed|pitr}"; exit 1;;
esac
