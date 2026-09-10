#!/bin/sh
# Phase 1 原型 · 主库自举入口
# 官方 postgres 镜像的 docker-entrypoint.sh 在覆盖 command 后不会自动 initdb，
# 因此这里自己处理：PGDATA 为空时 initdb + 建库 + 设密码，然后以自定义配置启动。
set -eu

PGDATA_DIR="${PGDATA:-/var/lib/postgresql/data/pgdata}"
CONF=/etc/postgresql/postgresql.conf
HBA=/etc/postgresql/pg_hba.conf

if [ -z "$(ls -A "$PGDATA_DIR" 2>/dev/null)" ]; then
  echo "[primary] PGDATA 为空 -> initdb (超级用户=claw, 本地 trust)"
  initdb -D "$PGDATA_DIR" -U claw --auth=trust

  echo "[primary] 临时启动以创建数据库并设置密码"
  # 仅用 unix socket 临时启动，避免占用 5432
  pg_ctl -D "$PGDATA_DIR" -w -o "-c config_file=$CONF -c hba_file=$HBA -p 5433 -k /tmp -c listen_addresses=''" start

  psql -h /tmp -p 5433 -U claw -v ON_ERROR_STOP=1 <<SQL
ALTER USER claw WITH PASSWORD '${POSTGRES_PASSWORD}';
CREATE DATABASE claw;
SQL

  pg_ctl -D "$PGDATA_DIR" -m fast stop
  echo "[primary] 初始化完成"
fi

exec postgres -c config_file=$CONF -c hba_file=$HBA
