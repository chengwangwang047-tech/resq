#!/usr/bin/env python3
"""ResQ Day21: RabbitMQ integration smoke test (no Postman/JWT required).

Tests:
  1) Duplicate eventId produces exactly one committed consumption record.
  2) Malformed event is rejected after consumer retry and reaches failedQueue.

Requires running ResQ, RabbitMQ Management API and local sudo mysql access.
This writes ONE synthetic mq_consume_record row and ONE bad message to failedQueue.
It does not create or modify any reservation or resource_slot record.
"""

import base64
import json
import os
import secrets
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

BASE = os.getenv("MQ_MANAGEMENT", "http://127.0.0.1:15672").rstrip("/")
USERNAME = os.getenv("MQ_USER", "guest")
PASSWORD = os.getenv("MQ_PASSWORD", "guest")
VHOST = "%2F"  # RabbitMQ virtual host '/'
PROCESS_EXCHANGE = "resq.reservation.timeout.dead.exchange"
PROCESS_KEY = "reservation.timeout.expired"
PROCESS_QUEUE = "resq.reservation.timeout.dead.queue"
FAILED_EXCHANGE = "resq.reservation.timeout.failed.exchange"
FAILED_KEY = "reservation.timeout.failed"
FAILED_QUEUE = "resq.reservation.timeout.failed.queue"
POLICY = "resq-timeout-failure"


def api(method, path, data=None):
    body = None if data is None else json.dumps(data).encode("utf-8")
    auth = base64.b64encode(f"{USERNAME}:{PASSWORD}".encode()).decode("ascii")
    request = Request(BASE + "/api" + path, data=body, method=method)
    request.add_header("Authorization", "Basic " + auth)
    request.add_header("Content-Type", "application/json")
    try:
        with urlopen(request, timeout=7) as response:
            raw = response.read()
            return json.loads(raw.decode("utf-8")) if raw else None
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:400]
        raise RuntimeError(f"RabbitMQ API HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise RuntimeError(f"无法连接 RabbitMQ Management: {exc.reason}") from exc


def qinfo(name):
    return api("GET", f"/queues/{VHOST}/{quote(name, safe='')}")


def mysql(sql):
    cmd = ["sudo", "-n", "mysql", "-N", "-B", "-e", sql, "resq"]
    result = subprocess.run(cmd, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError("MySQL 查询失败，请先运行 sudo -v，并确认 resq 数据库和消费记录表存在。\n"
                           + result.stderr.strip()[:400])
    return result.stdout.strip()


def wait_for(check, limit=25, interval=0.5):
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        if check():
            return True
        time.sleep(interval)
    return False


def publish(text):
    result = api("POST", f"/exchanges/{VHOST}/{quote(PROCESS_EXCHANGE, safe='')}/publish", {
        "properties": {"delivery_mode": 2, "content_type": "text/plain", "content_encoding": "utf-8"},
        "routing_key": PROCESS_KEY,
        "payload": text,
        "payload_encoding": "string"
    })
    if not result or not result.get("routed"):
        raise RuntimeError("消息未路由到超时处理队列，已中止测试")


def main():
    print("[0/3] 检查 Broker、消费者、Policy 和失败队列")
    process = qinfo(PROCESS_QUEUE)
    failed = qinfo(FAILED_QUEUE)
    api("GET", f"/exchanges/{VHOST}/{quote(PROCESS_EXCHANGE, safe='')}")
    api("GET", f"/exchanges/{VHOST}/{quote(FAILED_EXCHANGE, safe='')}")
    bindings = api("GET", f"/bindings/{VHOST}/e/{quote(FAILED_EXCHANGE, safe='')}/q/{quote(FAILED_QUEUE, safe='')}")
    if not any(b.get("routing_key") == FAILED_KEY for b in (bindings or [])):
        raise RuntimeError("failedExchange → failedQueue Binding 不存在，拒绝发送失败测试消息")
    if process.get("policy") != POLICY:
        raise RuntimeError(f"处理队列尚未应用 {POLICY} Policy，拒绝发送失败测试消息")
    if process.get("consumers", 0) < 1:
        raise RuntimeError("处理队列目前没有消费者：请先重启并保持 ResQ 运行")
    if failed.get("consumers", 0) != 0:
        raise RuntimeError("failedQueue 有在线消费者，可能立即拿走测试死信；为避免干扰，先停止测试")
    print("  OK：消费者在线，Policy、失败交换机和 Binding 均存在")

    # Unique positive bigint reservation ID. Never write to reservation/resource_slot.
    reservation_id = 7_000_000_000_000_000_000 + secrets.randbelow(100_000_000)
    event_id = f"reservation-expire-{reservation_id}"
    if mysql(f"SELECT COUNT(*) FROM reservation WHERE id={reservation_id}") != "0":
        raise RuntimeError("生成的测试预约 ID 与已有数据冲突，已停止")
    if mysql(f"SELECT COUNT(*) FROM mq_consume_record WHERE event_id='{event_id}'") != "0":
        raise RuntimeError("生成的测试 eventId 已存在，已停止")

    print("[1/3] 连续发送同一个 eventId 两次，验证消费幂等")
    event = json.dumps({"eventId": event_id, "reservationId": reservation_id,
                        "eventType": "RESERVATION_EXPIRE"}, separators=(",", ":"))
    publish(event)
    if not wait_for(lambda: mysql(f"SELECT COUNT(*) FROM mq_consume_record WHERE event_id='{event_id}'") == "1", 20):
        raise RuntimeError("第一次消息未在 20 秒内生成消费记录；请查看 ResQ 控制台错误日志")
    publish(event)
    if not wait_for(lambda: qinfo(PROCESS_QUEUE).get("messages", 0) == 0, 20):
        raise RuntimeError("重复消息仍在超时处理队列中，请检查消费者日志")
    time.sleep(1)  # Let AUTO ACK settle before checking the committed record.
    count = mysql(f"SELECT COUNT(*) FROM mq_consume_record WHERE event_id='{event_id}'")
    if count != "1":
        raise RuntimeError(f"幂等检查失败：相同 eventId 的记录数为 {count}，应为 1")
    print(f"  PASS：两次发送，相同 eventId 的消费记录仅 1 行；测试 eventId={event_id}")
    print("  注：使用不存在的预约 ID，只验证消息去重路径，不触发真实预约释放")

    print("[2/3] 发送一条非法 JSON，观察有限重试后进入 failedQueue")
    before = qinfo(FAILED_QUEUE).get("messages_ready", 0)
    bad_body = "day21-invalid-json-" + secrets.token_hex(8)
    publish(bad_body)
    if not wait_for(lambda: qinfo(FAILED_QUEUE).get("messages_ready", 0) >= before + 1, 30):
        raise RuntimeError("30 秒内 failedQueue 未增加消息；请检查重试工厂、Policy 和 ResQ 错误日志")
    print(f"  PASS：failedQueue Ready 从 {before} 增至 {qinfo(FAILED_QUEUE).get('messages_ready', 0)}")
    print(f"  错误消息标记：{bad_body}")

    print("[3/3] 测试完成")
    print("  两项 PASS。请结合 ResQ 控制台日志核对每条非法消息共尝试 3 次。")
    print("  保留 1 条合成消费记录和 1 条失败消息供你复查；未改动预约或时隙数据。")
    print(f"  如需清理消费记录：sudo mysql resq -e \"DELETE FROM mq_consume_record WHERE event_id='{event_id}';\"")
    print("  失败消息建议先在 Management 确认，勿直接清空整个 failedQueue。")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("[FAIL] " + str(exc), file=sys.stderr)
        sys.exit(1)
