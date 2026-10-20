#!/usr/bin/env bash
# ResQ Day21 regression runner. Runs existing Day20 end-to-end tests after
# changes to the RabbitMQ consumer/publisher, optionally Day21 MQ smoke tests.
# Does NOT edit source files, change application configuration or purge queues.
set -uo pipefail

ROOT="${RESQ_HOME:-$HOME/resq}"
TIMEOUT_SECONDS="${RESQ_TEST_TIMEOUT_SECONDS:-900}"
RUN_MQ=0
MQ_ONLY=0

usage() {
  cat <<'HELP'
用法：
  bash scripts/day21_regression.sh            # 复跑 Day20 的真实预约回归测试
  bash scripts/day21_regression.sh --all      # Day20 回归 + Day21 MQ 冒烟测试
  bash scripts/day21_regression.sh --mq-only  # 仅复跑 Day21 MQ 冒烟测试

本脚本复用已存在的：
  scripts/day20-test.sh
  scripts/day20-cancel-test.sh
  scripts/day21_mq_smoke.py（仅 --all / --mq-only）

提示：
  * 请先启动 ResQ、RabbitMQ、MySQL、Redis。
  * Day20 脚本会创建测试预约并触发业务写入，请只在本地开发环境运行。
  * 若运行配置为 600 秒确认期限，真实超时测试可能需等待 10 分钟以上。
  * --all 会额外产生一条合成消费记录和一条 failedQueue 测试死信。
  * 本次测试不等同于真实的“ACK 丢失”或“事务回滚故障注入”。
HELP
}

case "${1:-}" in
  "") ;;
  --all) RUN_MQ=1 ;;
  --mq-only) RUN_MQ=1; MQ_ONLY=1 ;;
  -h|--help) usage; exit 0 ;;
  *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
esac

if [[ ! "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || (( TIMEOUT_SECONDS < 30 )); then
  echo "[FAIL] RESQ_TEST_TIMEOUT_SECONDS 必须是 >= 30 的整数（秒）" >&2
  exit 2
fi

cd "$ROOT" || { echo "[FAIL] 找不到项目目录：$ROOT" >&2; exit 2; }

command -v timeout >/dev/null || { echo "[FAIL] 缺少 timeout 命令" >&2; exit 2; }
command -v tee >/dev/null || { echo "[FAIL] 缺少 tee 命令" >&2; exit 2; }

# A port check is only an early warning; each actual test validates its business conditions.
if ! timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' 2>/dev/null; then
  echo "[FAIL] 本地 8080 端口无法连接，请先启动 ResQ（mvn spring-boot:run）" >&2
  exit 2
fi

if (( RUN_MQ )); then
  if [[ ! -f scripts/day21_mq_smoke.py ]]; then
    echo "[FAIL] 缺少 scripts/day21_mq_smoke.py；请先放入昨天测试成功的文件" >&2
    exit 2
  fi
  command -v python3 >/dev/null || { echo "[FAIL] 缺少 python3" >&2; exit 2; }
  echo "[检查] MQ 冒烟测试使用 sudo -n mysql，请先验证 sudo 授权。"
  if ! sudo -v; then
    echo "[FAIL] 无法获得 sudo 授权" >&2
    exit 2
  fi
fi

if (( ! MQ_ONLY )); then
  for file in scripts/day20-test.sh scripts/day20-cancel-test.sh; do
    if [[ ! -f "$file" ]]; then
      echo "[FAIL] 缺少既有 Day20 测试脚本：$file" >&2
      exit 2
    fi
  done
fi

LOG_DIR="${RESQ_TEST_LOG_DIR:-/tmp/resq-day21-regression-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$LOG_DIR" || { echo "[FAIL] 无法创建日志目录：$LOG_DIR" >&2; exit 2; }

PASSED=0
FAILED=0
run_stage() {
  local name="$1" log="$2"
  shift 2
  echo
  echo "============================================================"
  echo "[RUN] $name"
  printf '[命令]'; printf ' %q' "$@"; printf '\n'
  echo "[日志] $log"
  echo "============================================================"
  timeout --signal=TERM --kill-after=5 "${TIMEOUT_SECONDS}s" "$@" 2>&1 | tee "$log"
  local ret="${PIPESTATUS[0]}"
  if (( ret == 0 )); then
    echo "[PASS] $name（脚本退出码 0；具体验收条件以原脚本断言为准）"
    PASSED=$((PASSED + 1))
  else
    if (( ret == 124 )); then
      echo "[FAIL] $name：超过 ${TIMEOUT_SECONDS} 秒" >&2
    else
      echo "[FAIL] $name：退出码 $ret" >&2
    fi
    FAILED=$((FAILED + 1))
    echo "[停止] 后续测试不再执行，请先查看日志：$log" >&2
    return 1
  fi
}

if (( ! MQ_ONLY )); then
  run_stage "Day20 正常超时与确认回归" "$LOG_DIR/day20-expire.log" \
    bash scripts/day20-test.sh || exit 1
  run_stage "Day20 取消后的超时消息回归" "$LOG_DIR/day20-cancel.log" \
    bash scripts/day20-cancel-test.sh || exit 1
fi

if (( RUN_MQ )); then
  run_stage "Day21 消息去重及失败队列冒烟" "$LOG_DIR/day21-mq.log" \
    python3 scripts/day21_mq_smoke.py || exit 1
fi

echo
printf '[完成] 通过 %d 项；失败 %d 项。\n' "$PASSED" "$FAILED"
echo "日志目录：$LOG_DIR"
echo "注意：通过回归测试不等于已验证 ACK 丢失、数据库故障注入或 Publisher Confirm。"
