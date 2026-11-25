#!/usr/bin/env bash
# 等待服务就绪后执行端到端回放。
# 若 BASE_URL 指向本机且服务未启动，默认自动打包并启动应用，回放结束后关闭该自启进程。
# 用法：scripts/verify.sh [base_url]
#   BASE_URL=http://127.0.0.1:8080 bash scripts/verify.sh
#   AUTO_START=0 bash scripts/verify.sh   # 只等待已启动的外部服务
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BASE_URL="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
export BASE_URL

# 使用任务约束中预装的运行时（若默认 PATH 未指向它们）
if [ -d /opt/jdk-25 ]; then
  export JAVA_HOME="/opt/jdk-25"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
PYTHON_BIN="${PYTHON_BIN:-python3}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  PYTHON_BIN="/opt/python-3.12/bin/python3"
fi

health_ok() {
  curl -fsS --max-time 3 "${BASE_URL}/api/health" >/dev/null 2>&1
}

STARTED_PID=""
cleanup() {
  if [ -n "$STARTED_PID" ]; then
    kill "$STARTED_PID" >/dev/null 2>&1 || true
    wait "$STARTED_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "等待 ${BASE_URL}/api/health ..."
READY=0
for _ in $(seq 1 15); do
  if health_ok; then
    READY=1
    echo "服务已就绪（使用已运行的实例）。"
    break
  fi
  sleep 2
done

if [ "$READY" != "1" ]; then
  HOSTPART="$(printf '%s' "$BASE_URL" | sed -E 's#^[a-zA-Z]+://([^:/]+).*#\1#')"
  if [ "${AUTO_START:-1}" = "1" ] && { [ "$HOSTPART" = "127.0.0.1" ] || [ "$HOSTPART" = "localhost" ]; }; then
    echo "未发现运行中的服务，开始本地构建并后台启动（AUTO_START=0 可关闭自启）..."
    cd "$ROOT_DIR"
    MVN_BIN="mvn"
    [ -x /opt/maven/bin/mvn ] && MVN_BIN="/opt/maven/bin/mvn"
    "$MVN_BIN" -s settings.xml -q -DskipTests package
    "$JAVA_HOME/bin/java" -jar target/water-repair-service.jar > /tmp/wr-verify-app.log 2>&1 &
    STARTED_PID=$!
    for _ in $(seq 1 60); do
      if health_ok; then
        READY=1
        echo "自启服务已就绪（pid=$STARTED_PID）。"
        break
      fi
      sleep 2
    done
  else
    for _ in $(seq 1 45); do
      if health_ok; then
        READY=1
        echo "服务已就绪。"
        break
      fi
      sleep 2
    done
  fi
fi

if [ "$READY" != "1" ]; then
  echo "服务未能在限定时间内就绪。" >&2
  if [ -f /tmp/wr-verify-app.log ]; then
    echo "---- /tmp/wr-verify-app.log 尾部 ----" >&2
    tail -40 /tmp/wr-verify-app.log >&2 || true
  fi
  exit 2
fi

set +e
"$PYTHON_BIN" "$SCRIPT_DIR/replay.py"
RC=$?
set -e
exit "$RC"
