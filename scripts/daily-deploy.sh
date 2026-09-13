#!/usr/bin/env bash
# ============================================================================
# Claw 每日自动部署脚本（运行在腾讯云服务器本机）
#
# 用法：由 cron 每日调用，例如  0 3 * * * /root/claw-platform/scripts/daily-deploy.sh
#
# 行为：
#   - 仅当 GitHub(origin/main) 出现新提交时才操作；无更新直接退出（几乎零开销）
#   - 后端/前端源码(backend/ web/)变更 → 先重建镜像再部署
#   - 仅 compose/脚本(deploy/ scripts/)变更 → 直接重新部署（不重建镜像）
#   - 仅文档等非运行时代码变更 → 跳过部署
#
# 前置条件（已在服务器配好）：
#   - git 已配置 ghproxy 镜像，使服务器能拉取 github.com
#   - 已 git config --global --add safe.directory /root/claw-platform
#   - 本脚本以能访问 docker 的用户（如 root）运行
#
# 环境变量覆盖：
#   CLAW_DEPLOY_DIR  仓库目录，默认 /root/claw-platform
#   CLAW_DEPLOY_LOG  日志文件，默认 /var/log/claw-daily-deploy.log
#   CLAW_DEPLOY_BRANCH 分支，默认 main
# ============================================================================
set -uo pipefail

REPO_DIR="${CLAW_DEPLOY_DIR:-/root/claw-platform}"
LOG_FILE="${CLAW_DEPLOY_LOG:-/var/log/claw-daily-deploy.log}"
BRANCH="${CLAW_DEPLOY_BRANCH:-main}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [daily-deploy] $*" >> "$LOG_FILE"; }

log "=== start ==="
if [ ! -d "$REPO_DIR/.git" ]; then
  log "ERROR: $REPO_DIR 不是 git 仓库，退出"
  exit 1
fi
cd "$REPO_DIR" || { log "ERROR: 无法进入 $REPO_DIR"; exit 1; }

LOCAL=$(git rev-parse HEAD)
git fetch origin "$BRANCH" >> "$LOG_FILE" 2>&1
REMOTE=$(git rev-parse "origin/$BRANCH")

if [ "$LOCAL" = "$REMOTE" ]; then
  log "已是最新 ($LOCAL)，无需操作"
  exit 0
fi

log "发现新提交：$LOCAL -> $REMOTE，更新中"
git merge --ff-only "origin/$BRANCH" >> "$LOG_FILE" 2>&1 || git pull origin "$BRANCH" >> "$LOG_FILE" 2>&1

CHANGED=$(git diff --name-only "$LOCAL" "$REMOTE")
NEED_BUILD=0
NEED_DEPLOY=0
if printf '%s\n' "$CHANGED" | grep -qE '^(backend/|web/)'; then NEED_BUILD=1; NEED_DEPLOY=1; fi
if printf '%s\n' "$CHANGED" | grep -qE '^(deploy/|scripts/)'; then NEED_DEPLOY=1; fi

if [ "$NEED_BUILD" = "1" ]; then
  log "检测到前后端源码变更，重建镜像"
  ./scripts/deploy-version.sh build latest >> "$LOG_FILE" 2>&1
fi

if [ "$NEED_DEPLOY" = "1" ]; then
  log "重新部署"
  ./scripts/deploy-version.sh deploy latest >> "$LOG_FILE" 2>&1
  ./scripts/deploy-version.sh status >> "$LOG_FILE" 2>&1
  log "=== done: deployed $REMOTE ==="
else
  log "仅文档类变更，跳过部署"
fi
