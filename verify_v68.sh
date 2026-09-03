#!/bin/zsh
# ─────────────────────────────────────────────────────────────
# V68 真库迁移验证（合并前 gate）
# 前置：本机 Docker 已启动。本脚本会拉起 PG16/Redis/RabbitMQ，创建 claw_it
#       测试库，并运行全部 *IT 集成测试（默认连本地 TimescaleDB，见
#       backend/src/test/.../AbstractIntegrationTest）。
# 用法：
#   ./verify_v68.sh           完整验证（起栈 + 建库 + 跑 *IT）
#   ./verify_v68.sh offline   仅离线迁移链完整性检查（无需 Docker，本沙箱可用）
# ─────────────────────────────────────────────────────────────
set -e

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
COMPOSE="$REPO_ROOT/deploy/docker-compose.yml"
MIG_DIR="$BACKEND_DIR/src/main/resources/db/migration"

# ---- JDK：优先 JDK21（项目目标），回退 JDK26 + ByteBuddy 实验开关 ----
if [ -d "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
  unset JAVA_TOOL_OPTIONS
  echo "JDK: 21 ($JAVA_HOME)"
else
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home"
  export JAVA_TOOL_OPTIONS="-Dnet.bytebuddy.experimental=true"
  echo "JDK: 26 + ByteBuddy 实验开关 ($JAVA_HOME)"
fi
export PATH="$JAVA_HOME/bin:$PATH"

MVN=/Users/zhoutianzhi/myLibrary/apache-maven-3.8.1/bin/mvn
MAVEN_REPO=/Users/zhoutianzhi/myLibrary/apache-maven-3.8.1/resp
IT_TESTS="SchemaMigrationIT,AdminInventoryControllerMeStatsIT,ConsignmentCustodyIT,LedgerDoubleEntryIT,ProjectLedgerIT"

# ─────────────────────────────────────────────────────────────
# 离线迁移链完整性检查（无需 Docker）
# ─────────────────────────────────────────────────────────────
offline_check() {
  echo "=== [离线] 迁移链完整性检查 ==="
  local files=("$MIG_DIR"/V*.sql)
  local n=${#files[@]}
  echo "迁移脚本数: $n"
  local max=0 v
  for f in "${files[@]}"; do
    v=$(basename "$f" | sed -E 's/^V([0-9]+).*/\1/')
    [ "$v" -gt "$max" ] && max=$v
  done
  echo "最大版本号: V$max"
  local missing=""
  for ((i=1;i<=max;i++)); do
    [ -f "$MIG_DIR/V${i}__"*.sql ] || missing="$missing V$i"
  done
  if [ -z "$missing" ]; then
    echo "✅ 版本连续无缺号 (V1..V$max)"
  else
    echo "❌ 缺号:$missing"
    return 1
  fi
  echo "（注：SQL 语法需真实 PG 才能最终校验；此步仅保证脚本齐全且连续，"
  echo "      对应 SchemaMigrationIT.countMigrationScripts() 的期望值）"
}

offline_check

if [ "${1:-full}" = "offline" ]; then
  echo "=== 离线模式结束 ==="
  exit 0
fi

# ─────────────────────────────────────────────────────────────
# 全量验证：需要 Docker
# ─────────────────────────────────────────────────────────────
echo "=== [全量] 检查 Docker ==="
if ! docker info >/dev/null 2>&1; then
  echo "❌ Docker 不可用（daemon 未启动或被沙箱限制）。"
  echo "   请在本机（macOS + Docker Desktop）运行：./verify_v68.sh"
  echo "   当前沙箱 Docker 被杀，无法闭环 V68；可先用 ./verify_v68.sh offline 看迁移链。"
  exit 1
fi

echo "=== [全量] 拉起中间件栈 (PG16/Redis/RabbitMQ/EMQX) ==="
docker compose -f "$COMPOSE" up -d
echo "等待 PostgreSQL 健康..."
for i in $(seq 1 30); do
  if docker exec claw-postgres pg_isready -U claw -d claw >/dev/null 2>&1; then break; fi
  sleep 2
done

echo "=== [全量] 创建测试库 claw_it（如不存在）==="
docker exec claw-postgres psql -U claw -d claw -c "CREATE DATABASE claw_it;" 2>/dev/null \
  || echo "（claw_it 已存在，跳过）"

echo "等待 Redis / RabbitMQ 就绪..."
sleep 5

echo "=== [全量] 运行 *IT 集成测试 ==="
cd "$BACKEND_DIR"
"$MVN" -Dmaven.repo.local="$MAVEN_REPO" test -Dtest="$IT_TESTS" > /tmp/v68_it.log 2>&1
rc=$?
cat /tmp/v68_it.log
if [ "$rc" -eq 0 ]; then
  echo "✅ V68 真库迁移验证通过（全部 *IT 绿灯），可合入 main。"
else
  echo "❌ V68 验证失败（rc=$rc），详见 /tmp/v68_it.log"
  echo "   常见原因：claw_it 库/claw 模式未就绪、Redis/RabbitMQ 未起、某 V 脚本 SQL 语法错误。"
  exit 1
fi
