#!/bin/zsh
# ─────────────────────────────────────────────────────────────
# Claw 本地部署一键启动脚本（零安装：内置 H2 内存库，无需 Docker/PostgreSQL/Redis/RabbitMQ）
# 用法：
#   ./start-local.sh            # 后台启动，固定端口 8080
#   ./start-local.sh stop       # 停止
#   ./start-local.sh status     # 查看状态
# ─────────────────────────────────────────────────────────────
set -e

JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home
MVN=/Users/zhoutianzhi/myLibrary/apache-maven-3.8.1/bin/mvn
BACKEND_DIR="$(cd "$(dirname "$0")/backend" && pwd)"
JAR="$BACKEND_DIR/target/claw-server-0.1.0-SNAPSHOT.jar"
LOG=/tmp/claw-app.log
PIDFILE=/tmp/claw-app.pid
PORT=8080

# 运行环境会注入 SERVER__PORT=0 → 随机端口，这里强制覆盖为 8080
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Dnet.bytebuddy.experimental=true"

case "${1:-start}" in
  stop)
    if [ -f "$PIDFILE" ]; then
      kill "$(cat "$PIDFILE")" 2>/dev/null && echo "已停止 (PID $(cat "$PIDFILE"))"
      rm -f "$PIDFILE"
    else
      echo "没有正在运行的实例"
    fi
    exit 0
    ;;
  status)
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "运行中: PID $(cat "$PIDFILE")  http://localhost:$PORT"
      curl -s --max-time 3 "http://localhost:$PORT/actuator/health" || true
    else
      echo "未运行"
    fi
    exit 0
    ;;
esac

# 构建（若 jar 不存在或想重新打包）
if [ ! -f "$JAR" ]; then
  echo "未找到 jar，开始用 local profile 构建..."
  (cd "$BACKEND_DIR" && "$MVN" -q -Plocal -DskipTests package)
fi

# 若已存在实例，先停掉
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "发现旧实例，先停止..."
  kill "$(cat "$PIDFILE")" 2>/dev/null || true
  sleep 2
fi

echo "启动 Claw 后端 (local profile, 端口 $PORT) ..."
nohup java -jar "$JAR" --spring.profiles.active=local --server.port=$PORT > "$LOG" 2>&1 &
echo $! > "$PIDFILE"
echo "已启动 PID $(cat "$PIDFILE")，日志: $LOG"
echo "稍候 8-10 秒后访问: http://localhost:$PORT/swagger-ui.html"
