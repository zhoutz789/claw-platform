#!/bin/bash
# ==============================================================================
# 爪平台 真库端到端冒烟（E2E Smoke）
#
# 用途：在真实 PostgreSQL 上启动应用，自动抓取 OpenAPI 全部 GET 接口逐个请求，
#       统计状态码，锁定 5xx（真 bug）。
#
# 为什么需要它：
#   本项目单测是 Mockito、不启 Spring 上下文、SQL 不真跑，测不出"实体列名写错"
#   "表缺列"这类问题；而 local profile 是 H2 + ddl-auto:update，Hibernate 自动建表
#   会把洞掩盖掉。真库是 ddl-auto:none + Flyway，缺什么硬报什么。
#   历史上多起 P0（缺表、列名映射错、bean 冲突）都是靠这类真库验证才暴露的。
#
# 判定口径：
#   5xx = 真 bug，脚本以非 0 退出（可直接用于 CI 门禁）
#   4xx = 多为路径参数替换成 1 后查不到记录，属正常业务异常，列出供人工判读
#
# 用法：
#   ./scripts/e2e-smoke.sh              # 完整流程（起容器 → 构建 → 冒烟 → 清理）
#   ./scripts/e2e-smoke.sh --keep       # 结束后保留容器，便于人工排查
#   ./scripts/e2e-smoke.sh --no-build   # 跳过构建，复用已有 jar
#   ./scripts/e2e-smoke.sh --reuse-db   # 复用已有容器（不重建库）
#   ./scripts/e2e-smoke.sh --help       # 查看用法
#
# 依赖：Docker、JDK 21、python3
# ==============================================================================
set -uo pipefail

KEEP=0; DO_BUILD=1; REUSE_DB=0
for arg in "$@"; do
  case $arg in
    --keep) KEEP=1 ;;
    --no-build) DO_BUILD=0 ;;
    --reuse-db) REUSE_DB=1 ;;
    -h|--help)
      sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      echo "未知参数：$arg（用 --help 查看用法）"; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 无条件覆盖：外层若已导出旧版 JAVA_HOME（如 1.8），用 ${JAVA_HOME:-...} 不会生效，
# 会拿 Java 8 去跑 JDK21 编译的 jar，报 UnsupportedClassVersionError
# (class file version 61.0 / only recognizes up to 52.0)。
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
PY="${PY:-/Users/zhoutianzhi/.workbuddy/binaries/python/versions/3.13.12/bin/python3}"
[ -x "$PY" ] || PY="$(command -v python3)"

PG_NAME=claw-smoke-pg
REDIS_NAME=claw-smoke-redis
PG_PORT=55490
REDIS_PORT=56390
APP_PORT=8095
LOG=/tmp/claw_e2e_smoke.log
APP_PID=""

cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null
  if [ "$KEEP" -eq 0 ]; then
    docker rm -f "$PG_NAME" "$REDIS_NAME" > /dev/null 2>&1
    echo ""
    echo "已清理容器（加 --keep 可保留）"
  else
    echo ""
    echo "已保留容器：$PG_NAME(:$PG_PORT) $REDIS_NAME(:$REDIS_PORT)"
    echo "应用日志：$LOG"
  fi
}
trap cleanup EXIT

echo "=============================================="
echo " 爪平台 真库端到端冒烟"
echo "=============================================="

# ---------- 1. 起容器 ----------
if [ "$REUSE_DB" -eq 1 ] && docker ps --format '{{.Names}}' | grep -q "^$PG_NAME$"; then
  echo "[1/5] 复用已有数据库容器 $PG_NAME"
else
  echo "[1/5] 启动 PG16 + Redis 容器..."
  docker rm -f "$PG_NAME" "$REDIS_NAME" > /dev/null 2>&1
  docker run -d --name "$PG_NAME" \
    -e POSTGRES_USER=claw -e POSTGRES_PASSWORD=claw_dev_password -e POSTGRES_DB=claw \
    -p $PG_PORT:5432 timescale/timescaledb:latest-pg16 > /dev/null
  docker run -d --name "$REDIS_NAME" -p $REDIS_PORT:6379 redis:7-alpine > /dev/null
  for i in $(seq 1 30); do
    sleep 1
    docker exec "$PG_NAME" pg_isready -U claw > /dev/null 2>&1 && break
  done
  docker exec "$PG_NAME" pg_isready -U claw > /dev/null 2>&1 \
    && echo "      数据库就绪" \
    || { echo "      数据库启动失败，退出"; exit 1; }
fi

# ---------- 2. 构建 ----------
if [ "$DO_BUILD" -eq 1 ]; then
  echo "[2/5] 构建 jar（mvn -o clean package -DskipTests）..."
  (cd backend && mvn -o clean package -DskipTests > /tmp/claw_e2e_build.log 2>&1)
  if [ $? -ne 0 ]; then
    echo "      构建失败，详见 /tmp/claw_e2e_build.log"
    grep -E "^\[ERROR\]" /tmp/claw_e2e_build.log | head -20
    exit 1
  fi
  echo "      构建成功"
else
  echo "[2/5] 跳过构建（--no-build）"
fi

JAR=$(ls backend/target/claw-server-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$JAR" ] || { echo "      未找到 jar，退出"; exit 1; }

# ---------- 3. 启动应用 ----------
echo "[3/5] 启动应用（真库 profile, ddl-auto=none + Flyway）..."
nohup java -Dclaw.security.dev-open-access=true -jar "$JAR" \
  --server.port=$APP_PORT \
  --spring.datasource.url=jdbc:postgresql://localhost:$PG_PORT/claw \
  --spring.datasource.username=claw \
  --spring.datasource.password=claw_dev_password \
  --spring.data.redis.host=localhost \
  --spring.data.redis.port=$REDIS_PORT \
  > "$LOG" 2>&1 &
APP_PID=$!

for i in $(seq 1 60); do
  sleep 2
  grep -qE "Started [A-Za-z]+Application|APPLICATION FAILED TO START|Application run failed" "$LOG" 2>/dev/null && break
done

if grep -qE "APPLICATION FAILED TO START|Application run failed" "$LOG"; then
  echo ""
  echo "### 应用启动失败 ###"
  grep -E "Caused by|APPLICATION FAILED" "$LOG" | head -15
  exit 1
fi
if ! grep -q "Started [A-Za-z]*Application" "$LOG"; then
  echo "### 启动超时（120s）###"; tail -20 "$LOG"; exit 1
fi

MIGRATED=$(grep -oE "Successfully applied [0-9]+ migrations" "$LOG" | tail -1 | grep -oE "[0-9]+")
echo "      启动成功；Flyway 已应用 ${MIGRATED:-?} 个迁移"

# ---------- 4. 抓接口并冒烟 ----------
echo "[4/5] 抓取 OpenAPI 接口清单..."
curl -s --max-time 20 "http://localhost:$APP_PORT/v3/api-docs" -o /tmp/claw_e2e_api.json
[ -s /tmp/claw_e2e_api.json ] || { echo "      无法获取 OpenAPI 文档，退出"; exit 1; }

$PY - <<'PYEOF'
import json, re
d = json.load(open('/tmp/claw_e2e_api.json'))
paths = sorted({re.sub(r'\{[^}]+\}', '1', p)
                for p, ops in d.get('paths', {}).items()
                if 'get' in ops and not p.startswith('/actuator')})
# 注意：必须补尾换行。bash 的 `read` 在 EOF 遇到无换行结尾的最后一行时返回非 0，
# 循环体不会执行 —— 会静默丢掉最后一条路径。
open('/tmp/claw_e2e_paths.txt', 'w').write('\n'.join(paths) + '\n')
print(f"      GET 接口 {len(paths)} 个")
PYEOF

echo "[5/5] 冒烟中..."
: > /tmp/claw_e2e_results.txt
# `|| [ -n "$p" ]` 兜底：文件末行无换行时 read 返回非 0 但 $p 有值，不吃掉这条路径
while IFS= read -r p || [ -n "$p" ]; do
  [ -z "$p" ] && continue
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 12 "http://localhost:$APP_PORT$p")
  echo "$code $p" >> /tmp/claw_e2e_results.txt
done < /tmp/claw_e2e_paths.txt

# 对 5xx 带必填参数重试一次，排除"缺参数"误报
if grep -q "^5" /tmp/claw_e2e_results.txt; then
  $PY - <<'PYEOF'
import json, re
d = json.load(open('/tmp/claw_e2e_api.json'))
targets = [l.split(' ', 1)[1].strip() for l in open('/tmp/claw_e2e_results.txt') if l.startswith('5')]

def default_for(s):
    if 'enum' in s and s['enum']: return s['enum'][0]
    fmt = s.get('format', '')
    if fmt == 'uuid': return '00000000-0000-0000-0000-000000000001'
    if fmt in ('date-time', 'date'): return '2026-01-01T00:00:00Z'
    if s.get('type') == 'integer': return '1'
    if s.get('type') == 'boolean': return 'true'
    return '1'

out = []
for t in targets:
    found = next((p for p in d['paths'] if re.sub(r'\{[^}]+\}', '1', p) == t), None)
    if not found:
        continue
    op = d['paths'][found].get('get', {})
    url, qs = found, []
    for q in op.get('parameters', []):
        if not q.get('required'):
            continue
        if q.get('in') == 'query':
            qs.append(f"{q['name']}={default_for(q.get('schema', {}))}")
        elif q.get('in') == 'path':
            url = url.replace('{' + q['name'] + '}', default_for(q.get('schema', {})))
    url = re.sub(r'\{[^}]+\}', '1', url)
    if qs:
        url += '?' + '&'.join(qs)
    out.append(url)
open('/tmp/claw_e2e_retry.txt', 'w').write('\n'.join(out) + '\n')
PYEOF

  : > /tmp/claw_e2e_retry_results.txt
  while IFS= read -r p || [ -n "$p" ]; do
    [ -z "$p" ] && continue
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 12 "http://localhost:$APP_PORT$p")
    echo "$code $p" >> /tmp/claw_e2e_retry_results.txt
  done < /tmp/claw_e2e_retry.txt
fi

# ---------- 结果 ----------
echo ""
echo "=============================================="
echo " 冒烟结果"
echo "=============================================="
echo "状态码分布："
awk '{print $1}' /tmp/claw_e2e_results.txt | sort | uniq -c | sort -rn

REAL_BUGS=0
echo ""
if [ -s /tmp/claw_e2e_retry_results.txt ]; then
  echo "5xx 带参重试后仍失败（真 bug）："
  if grep -q "^5" /tmp/claw_e2e_retry_results.txt; then
    grep "^5" /tmp/claw_e2e_retry_results.txt | sort
    REAL_BUGS=$(grep -c "^5" /tmp/claw_e2e_retry_results.txt)
  else
    echo "  （无）"
  fi
elif grep -q "^5" /tmp/claw_e2e_results.txt; then
  echo "5xx（真 bug）："
  grep "^5" /tmp/claw_e2e_results.txt | sort
  REAL_BUGS=$(grep -c "^5" /tmp/claw_e2e_results.txt)
fi

echo ""
echo "4xx 清单（多为参数/空数据，需人工判读）："
grep "^4" /tmp/claw_e2e_results.txt | sort | head -30
[ "$(grep -c '^4' /tmp/claw_e2e_results.txt)" -gt 30 ] && echo "  ...（共 $(grep -c '^4' /tmp/claw_e2e_results.txt) 条）"

echo ""
echo "=============================================="
if [ "$REAL_BUGS" -gt 0 ]; then
  echo " 结论：FAIL —— ${REAL_BUGS} 个接口返回 5xx"
  echo " 排查：应用日志 $LOG"
  exit 1
fi
echo " 结论：PASS —— 无 5xx"
exit 0
