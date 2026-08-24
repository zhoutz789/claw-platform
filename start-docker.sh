#!/bin/zsh
# ─────────────────────────────────────────────────────────────
# Claw 本地部署（Docker 中间件 + 本机运行应用，推荐）
# 前置：Docker 已安装并启动；先 `docker compose -f deploy/docker-compose.yml up -d`
# 用法：
#   ./start-docker.sh            # 用默认 profile 启动（连 Docker 内的 PG/Redis/RabbitMQ）
#   ./start-docker.sh stop       # 停止应用（不影响 Docker 服务）
#   ./start-docker.sh build      # 仅重新构建默认 jar
# ─────────────────────────────────────────────────────────────
set -e

JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home
MVN=/Users/zhoutianzhi/myLibrary/apache-maven-3.8.1/bin/mvn
BACKEND_DIR="$(cd "$(dirname "$0")/backend" && pwd)"
JAR="$BACKEND_DIR/target/claw-server-0.1.0-SNAPSHOT.jar"
LOG=/tmp/claw-app.log
PIDFILE=/tmp/claw-app.pid
PORT=8080

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
# Mockito/Byte Buddy 在 JDK26 需实验开关
export JAVA_TOOL_OPTIONS="-Dnet.bytebuddy.experimental=true"

case "${1:-start}" in
  build)
    echo "用默认 profile 构建 jar..."
    (cd "$BACKEND_DIR" && "$MVN" -q -DskipTests package)
    echo "构建完成: $JAR"
    exit 0
    ;;
  stop)
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      kill "$(cat "$PIDFILE")" && echo "已停止应用 (PID $(cat "$PIDFILE"))"
      rm -f "$PIDFILE"
    else
      echo "没有正在运行的应用实例"
    fi
    exit 0
    ;;
esac

# 构建（若不存在）
if [ ! -f "$JAR" ]; then
  echo "未找到 jar，开始构建..."
  (cd "$BACKEND_DIR" && "$MVN" -q -DskipTests package)
fi

# 停旧实例
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "发现旧实例，先停止..."
  kill "$(cat "$PIDFILE")" 2>/dev/null || true
  sleep 2
fi

# 注意：默认 application.yml 已指向 Docker 映射的 localhost:5432/6379/5672
# 运行环境可能注入 SERVER__PORT=0（随机端口），这里固定 8080
echo "启动 Claw 后端 (默认 profile, 端口 $PORT) ..."
nohup java -jar "$JAR" --server.port=$PORT > "$LOG" 2>&1 &
echo $! > "$PIDFILE"
echo "已启动 PID $(cat "$PIDFILE")，日志: $LOG"
echo "约 8-12 秒后访问: http://localhost:$PORT/swagger-ui.html"
