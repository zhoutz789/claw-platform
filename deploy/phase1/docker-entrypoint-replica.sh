#!/bin/sh
# Phase 1 原型 · 副本自举入口
# 数据目录为空时，用 pg_basebackup -R 从主库拉取基础备份并自动写为 standby
# （-R 会生成 standby.signal + postgresql.auto.conf 中的 primary_conninfo）。
set -eu

PGDATA_DIR="${PGDATA:-/var/lib/postgresql/data/pgdata}"
CONF=/etc/postgresql/postgresql.conf
HBA=/etc/postgresql/pg_hba.conf
PH="${PRIMARY_HOST:-pg-primary}"

if [ -z "$(ls -A "$PGDATA_DIR" 2>/dev/null)" ]; then
  echo "[replica] PGDATA 为空 -> 通过 pg_basebackup 从 $PH 引导为 standby"
  export PGPASSWORD="$POSTGRES_PASSWORD"

  # 写 .pgpass，供 walreceiver 在流复制时自动认证（primary_conninfo 不含密码）
  PGPASS="$HOME/.pgpass"
  echo "$PH:5432:replication:claw:${POSTGRES_PASSWORD}" > "$PGPASS"
  echo "$PH:5432:*:claw:${POSTGRES_PASSWORD}" >> "$PGPASS"
  chmod 600 "$PGPASS"

  ok=0
  for i in $(seq 1 30); do
    if pg_basebackup -h "$PH" -p 5432 -U claw -D "$PGDATA_DIR" -R -Fp -Xs -P 2>/tmp/pbb.err; then
      echo "[replica] bootstrap 成功"
      ok=1
      break
    fi
    echo "[replica] pg_basebackup 第 $i 次失败，3s 后重试"
    sleep 3
  done

  if [ "$ok" -ne 1 ]; then
    echo "[replica] FATAL: 无法引导 standby" >&2
    cat /tmp/pbb.err >&2 || true
    exit 1
  fi
fi

exec postgres -c config_file=$CONF -c hba_file=$HBA
