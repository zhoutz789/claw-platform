#!/usr/bin/env bash
# ============================================================================
# Claw 版本化部署脚本（单主机 Docker Compose）
#
# 用法：
#   ./scripts/deploy-version.sh build <版本号>      # 本地构建 backend + web 镜像并打 tag
#   ./scripts/deploy-version.sh deploy <版本号>      # 切换部署到指定版本（拉取/本地镜像 + up -d + 健康检查）
#   ./scripts/deploy-version.sh rollback             # 回滚到上一个版本
#   ./scripts/deploy-version.sh current             # 显示当前/上一个部署版本
#   ./scripts/deploy-version.sh status              # 显示各容器状态
#
# 设计要点：
#   - 版本号 = git tag（如 v1.2.3）。镜像命名：claw-backend:<版本> / claw-web:<版本>
#   - 切换仅改动 deploy/.env.prod 的 CLAW_VERSION，再 `docker compose up -d`，
#     实现"改一个变量即可换版本"。
#   - 数据库由 Flyway 前向迁移管理：升级若带新迁移(V91+)，回滚前必须先恢复该次升级前的
#     DB 快照，否则旧应用镜像可能不兼容已应用的新 schema。脚本会在 deploy 前打印提示。
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILE="deploy/docker-compose.prod.yml"
ENV_FILE="deploy/.env.prod"
STATE_FILE="deploy/.claw-deploy-state"
REGISTRY="${DOCKER_REGISTRY:-}"

log()  { printf '\033[36m[deploy]\033[0m %s\n' "$*"; }
err()  { printf '\033[31m[ERROR]\033[0m %s\n' "$*" >&2; }
ok()   { printf '\033[32m[OK]\033[0m %s\n' "$*"; }

ensure_env() {
  if [[ ! -f "$ENV_FILE" ]]; then
    err "找不到 $ENV_FILE，请先 `cp deploy/.env.prod.example deploy/.env.prod` 并填好密钥。"
    exit 1
  fi
}

set_version() {
  local v="$1"
  # 仅替换 CLAW_VERSION= 一行，其余配置不动
  if grep -q '^CLAW_VERSION=' "$ENV_FILE"; then
    sed -i.bak -E "s/^CLAW_VERSION=.*/CLAW_VERSION=$v/" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
  else
    printf 'CLAW_VERSION=%s\n' "$v" >> "$ENV_FILE"
  fi
}

read_version() { grep -E '^CLAW_VERSION=' "$ENV_FILE" | cut -d= -f2- | tr -d '[:space:]'; }

save_state() {
  local cur="$1"
  local prev; prev="$(read_version)"
  # 把旧 current 变成 previous（仅当不同）
  if [[ -f "$STATE_FILE" ]]; then
    prev="$(grep '^CURRENT=' "$STATE_FILE" | cut -d= -f2-)"
  fi
  [[ "$prev" == "$cur" ]] && prev=""
  { echo "CURRENT=$cur"; echo "PREVIOUS=$prev"; } > "$STATE_FILE"
}

compose_up() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d
}

wait_healthy() {
  local svc="$1" tries=20
  log "等待 $svc 健康（最多 ${tries} 次）…"
  for ((i=1; i<=tries; i++)); do
    if docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps "$svc" \
       | grep -q "(healthy)"; then
      ok "$svc 已健康"
      return 0
    fi
    sleep 5
  done
  err "$svc 在超时内未达健康，请检查日志：docker compose -f $COMPOSE_FILE logs $svc"
  return 1
}

cmd_build() {
  local v="${1:-}"; [[ -z "$v" ]] && { err "用法: $0 build <版本号>"; exit 1; }
  log "构建 backend:$v 与 web:$v …"
  docker build -f backend/Dockerfile -t "${REGISTRY:-claw}/claw-backend:$v" backend
  docker build -f web/Dockerfile    -t "${REGISTRY:-claw}/claw-web:$v"    web
  ok "构建完成：claw-backend:$v, claw-web:$v"
}

cmd_deploy() {
  local v="${1:-}"; [[ -z "$v" ]] && { err "用法: $0 deploy <版本号>"; exit 1; }
  ensure_env
  log "部署版本 -> $v"
  set_version "$v"
  if [[ -n "$REGISTRY" ]]; then
    log "从仓库拉取镜像 $REGISTRY/claw-{backend,web}:$v"
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" pull backend web
  else
    log "使用本地镜像（若未构建请先 $0 build $v）"
  fi
  compose_up
  wait_healthy backend && wait_healthy web
  save_state "$v"
  ok "部署完成：当前版本 $v"
  log "提示：若本次升级包含新 Flyway 迁移(V91+)，请确认已在升级前备份 DB 快照以便回滚。"
}

cmd_rollback() {
  ensure_env
  if [[ ! -f "$STATE_FILE" ]]; then err "无历史状态，无法回滚。"; exit 1; fi
  local prev; prev="$(grep '^PREVIOUS=' "$STATE_FILE" | cut -d= -f2-)"
  if [[ -z "$prev" ]]; then err "无上一个版本可回滚（PREVIOUS 为空）。"; exit 1; fi
  log "回滚到上一个版本: $prev"
  cmd_deploy "$prev"
}

cmd_current() {
  ensure_env
  local cur; cur="$(read_version)"
  local prev=""; [[ -f "$STATE_FILE" ]] && prev="$(grep '^PREVIOUS=' "$STATE_FILE" | cut -d= -f2-)"
  log "当前版本: ${cur:-<未设置>}"
  log "上一个版本: ${prev:-<无>}"
}

cmd_status() {
  ensure_env
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps
}

case "${1:-}" in
  build)    cmd_build "${2:-}";;
  deploy)   cmd_deploy "${2:-}";;
  rollback) cmd_rollback;;
  current)  cmd_current;;
  status)   cmd_status;;
  *) echo "用法: $0 {build|deploy|rollback|current|status} [版本号]"; exit 1;;
esac
