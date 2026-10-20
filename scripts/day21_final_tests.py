#!/usr/bin/env python3
"""ResQ Day21 focused integration tests; no Day20 scripts, JWT, or Postman.

Test A: Force releaseReservedSlot() to fail on an isolated synthetic reservation,
        proving the event record and EXPIRED state roll back together.
Test B: Complete a real expiration of an isolated synthetic reservation, then
        publish the IDENTICAL event again as an ACK-loss/redelivery *simulation*.
        This does not actually cut the AMQP channel or lose an ACK.

Requires a LOCAL DEVELOPMENT resq database, running ResQ/Spring listeners,
RabbitMQ Management API, and a MySQL CLI accessible with `sudo -n mysql`.
All created reservation/resource_slot/consume_record rows are cleaned up on PASS.
One failed message is intentionally left in failedQueue for inspection.

Do NOT run against production or a shared database: this inserts test records,
including one deliberately inconsistent synthetic reservation.
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
MQ_USER = os.getenv("MQ_USER", "guest")
MQ_PASSWORD = os.getenv("MQ_PASSWORD", "guest")
VHOST = os.getenv("MQ_VHOST", "/")
DATABASE = os.getenv("RESQ_TEST_DB", "resq")

PROCESS_EXCHANGE = "resq.reservation.timeout.dead.exchange"
PROCESS_KEY = "reservation.timeout.expired"
PROCESS_QUEUE = "resq.reservation.timeout.dead.queue"
FAILED_EXCHANGE = "resq.reservation.timeout.failed.exchange"
FAILED_KEY = "reservation.timeout.failed"
FAILED_QUEUE = "resq.reservation.timeout.failed.queue"
POLICY = "resq-timeout-failure"

# Only alphanumeric/underscore database names; no user-supplied SQL.
if not DATABASE.replace("_", "").isalnum():
    raise SystemExit("[FAIL] 数据库名称包含不支持的字符")


class TestFailure(Exception):
    pass


def api(method, path, data=None):
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = Request(BASE + "/api" + path, data=body, method=method)
    credentials = base64.b64encode(f"{MQ_USER}:{MQ_PASSWORD}".encode()).decode()
    req.add_header("Authorization", "Basic " + credentials)
    req.add_header("Content-Type", "application/json")
    try:
        with urlopen(req, timeout=7) as response:
            raw = response.read()
            return json.loads(raw.decode("utf-8")) if raw else None
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:300]
        raise TestFailure(f"RabbitMQ HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise TestFailure(f"无法访问 RabbitMQ Management: {exc.reason}") from exc


def uri(s):
    return quote(s, safe="")


def qinfo(name):
    return api("GET", f"/queues/{uri(VHOST)}/{uri(name)}")


def sql(statement):
    # Never use a shell here; `sudo -n` prevents the script from hanging at a password prompt.
    cmd = ["sudo", "-n", "mysql", "--batch", "--raw", "--skip-column-names",
           "--database", DATABASE, "--execute", statement]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    except FileNotFoundError as exc:
        raise TestFailure("缺少 sudo/mysql 命令，请先检查 WSL 环境") from exc
    except subprocess.TimeoutExpired as exc:
        raise TestFailure("MySQL 查询超过 15 秒，请先排查数据库连接") from exc
    if p.returncode:
        raise TestFailure("MySQL 失败：" + p.stderr.strip()[:500]
                          + "\n如提示 sudo 需要密码，请在终端先运行一次 sudo -v。")
    return p.stdout.strip()


def scalar(statement):
    out = sql(statement)
    return out.splitlines()[-1].strip() if out else ""


def one_int(statement):
    return int(scalar(statement))


def sql_str(text):
    # All input originates in generated IDs; reject anything unexpected.
    if not text or not all(ch.isascii() and (ch.isalnum() or ch in "_-") for ch in text):
        raise TestFailure("测试 ID 包含非法字符")
    return "'" + text + "'"


def event_json(reservation_id):
    return json.dumps({
        "eventId": f"reservation-expire-{reservation_id}",
        "reservationId": reservation_id,
        "eventType": "RESERVATION_EXPIRE"
    }, ensure_ascii=False, separators=(",", ":"))


def publish_event(reservation_id):
    result = api("POST", f"/exchanges/{uri(VHOST)}/{uri(PROCESS_EXCHANGE)}/publish", {
        "properties": {"delivery_mode": 2, "content_type": "application/json"},
        "routing_key": PROCESS_KEY,
        "payload": event_json(reservation_id),
        "payload_encoding": "string"
    })
    if not result or not result.get("routed"):
        raise TestFailure("消息没有路由到正常超时消费队列，测试中止")


def wait_until(predicate, seconds=30, interval=0.5):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def wait_queue_idle(seconds=25):
    # The test should run on a quiet local environment, not a busy live queue.
    return wait_until(lambda: qinfo(PROCESS_QUEUE).get("messages_ready", 0) == 0
                      and qinfo(PROCESS_QUEUE).get("messages_unacknowledged", 0) == 0,
                      seconds=seconds)


def verify_preflight():
    print("[0/3] 只读预检查：RabbitMQ、消费端、MySQL 表结构")
    proc = qinfo(PROCESS_QUEUE)
    failed = qinfo(FAILED_QUEUE)
    api("GET", f"/exchanges/{uri(VHOST)}/{uri(PROCESS_EXCHANGE)}")
    api("GET", f"/exchanges/{uri(VHOST)}/{uri(FAILED_EXCHANGE)}")
    bindings = api("GET", f"/bindings/{uri(VHOST)}/e/{uri(FAILED_EXCHANGE)}/q/{uri(FAILED_QUEUE)}")
    if not any(row.get("routing_key") == FAILED_KEY for row in bindings or []):
        raise TestFailure("failedExchange 到 failedQueue 的 Binding 未生效")
    if proc.get("policy") != POLICY:
        raise TestFailure(f"{PROCESS_QUEUE} 未应用 {POLICY} Policy")
    if proc.get("consumers", 0) < 1:
        raise TestFailure("超时消费者未在线，请先启动修改后的 ResQ")
    if failed.get("consumers", 0):
        raise TestFailure("failedQueue 有消费者，测试死信可能被取走；请先停止该消费者")
    if proc.get("messages_ready", 0) or proc.get("messages_unacknowledged", 0):
        raise TestFailure("超时处理队列不是空闲状态，请等它处理完再测试")
    # Explicitly check actual DB schema. Never silently improvise a different layout.
    expected = {
        "reservation": {"id", "user_id", "slot_id", "status", "request_id", "created_at", "expire_at"},
        "resource_slot": {"id", "resource_id", "status"},
        "mq_consume_record": {"event_id"}
    }
    for table, need in expected.items():
        fields = set(sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                         f"WHERE TABLE_SCHEMA={sql_str(DATABASE)} AND TABLE_NAME={sql_str(table)}").splitlines())
        if not need <= fields:
            raise TestFailure(f"表 {table} 缺少字段：{sorted(need-fields)}，脚本不会修改未知表结构")
    columns = set(sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                      f"WHERE TABLE_SCHEMA={sql_str(DATABASE)} AND TABLE_NAME='resource_slot'").splitlines())
    if {"start_time", "end_time"} <= columns:
        start_col, end_col = "start_time", "end_time"
    elif {"slot_start", "slot_end"} <= columns:
        start_col, end_col = "slot_start", "slot_end"
    else:
        raise TestFailure("resource_slot 时间字段未知（预期 start_time/end_time 或 slot_start/slot_end），请先检查表结构")
    # All foreign key references come from existing user/resource rows; no actual business row is reused.
    user_id = scalar("SELECT id FROM `user` ORDER BY id LIMIT 1")
    resource_id = scalar("SELECT id FROM compute_resource ORDER BY id LIMIT 1")
    if not user_id.isdigit() or not resource_id.isdigit():
        raise TestFailure("缺少用于建立独立测试数据的 user 或 compute_resource 行；没有改动数据库")
    if not sql("SELECT 1") == "1":
        raise TestFailure("MySQL 客户端未正常响应")
    print("  OK：所有检查通过；使用本地测试数据，不调用 HTTP 登录接口")
    return int(user_id), int(resource_id), start_col, end_col, int(failed.get("messages_ready", 0))


class Fixture:
    def __init__(self, user_id, resource_id, start_col, end_col, kind):
        self.kind = kind
        self.reservation_id = None
        self.slot_id = None
        self.request_id = "day21_" + kind + "_" + secrets.token_hex(8)
        self.event_id = None
        self.cleaned = False
        # New slot, with a unique far-future time (not modifying existing live slots).
        # The date is not used to determine reservation expiry; expire_at is separate.
        offset_seconds = 90000000 + secrets.randbelow(10000000)
        status = "AVAILABLE" if kind == "rollback" else "RESERVED"
        self.slot_id = one_int(
            f"INSERT INTO resource_slot (resource_id, `{start_col}`, `{end_col}`, status) "
            f"VALUES ({resource_id}, DATE_ADD(NOW(), INTERVAL {offset_seconds} SECOND), "
            f"DATE_ADD(NOW(), INTERVAL {offset_seconds + 3600} SECOND), '{status}'); "
            "SELECT LAST_INSERT_ID();")
        if self.slot_id <= 0:
            raise TestFailure("无法创建独立测试时隙")
        try:
            self.reservation_id = one_int(
                "INSERT INTO reservation (user_id, slot_id, status, request_id, created_at, expire_at) "
                f"VALUES ({user_id}, {self.slot_id}, 'PENDING', {sql_str(self.request_id)}, "
                "DATE_SUB(NOW(), INTERVAL 10 MINUTE), DATE_SUB(NOW(), INTERVAL 60 SECOND)); "
                "SELECT LAST_INSERT_ID();")
            if self.reservation_id <= 0:
                raise TestFailure("无法创建独立测试预约")
            self.event_id = f"reservation-expire-{self.reservation_id}"
        except Exception:
            # Creation can partially succeed: do not leave an orphan slot.
            try:
                sql(f"DELETE FROM resource_slot WHERE id={self.slot_id}")
            except Exception:
                print(f"[WARN] 独立测试时隙 id={self.slot_id} 创建后清理失败，请人工核对", file=sys.stderr)
            raise

    def record_count(self):
        return one_int(f"SELECT COUNT(*) FROM mq_consume_record WHERE event_id={sql_str(self.event_id)}")

    def reservation_status(self):
        return scalar(f"SELECT status FROM reservation WHERE id={self.reservation_id} "
                      f"AND request_id={sql_str(self.request_id)}")

    def slot_status(self):
        return scalar(f"SELECT status FROM resource_slot WHERE id={self.slot_id}")

    def cleanup(self):
        # Only delete rows generated by this invocation, in FK-safe order.
        # Never purge RabbitMQ queues, never touch any pre-existing reservation/slot.
        if self.reservation_id is not None:
            sql(f"DELETE FROM mq_consume_record WHERE event_id={sql_str(self.event_id)}")
            sql("DELETE FROM reservation "
                f"WHERE id={self.reservation_id} AND request_id={sql_str(self.request_id)}")
        if self.slot_id is not None:
            sql(f"DELETE FROM resource_slot WHERE id={self.slot_id}")
        self.cleaned = True

    def show_manual_cleanup(self):
        if self.cleaned:
            return
        print(f"  待检查的测试记录：reservation_id={self.reservation_id}, "
              f"slot_id={self.slot_id}, request_id={self.request_id}")
        print("  因测试异常，未自动删除这些记录，以免和未完成的消费冲突。")


def test_rollback(f, failed_ready_before):
    print("[1/3] 测试事务回滚：故意让测试预约的时隙保持 AVAILABLE")
    # reservation is PENDING + expired, but synthetic slot is AVAILABLE.
    # UPDATE reservation succeeds, UPDATE resource_slot affects 0 rows -> exception.
    # REQUIRE both consumption INSERT and reservation UPDATE to roll back.
    if f.reservation_status() != "PENDING" or f.slot_status() != "AVAILABLE":
        raise TestFailure("事务回滚测试初始条件错误")
    publish_event(f.reservation_id)
    ok = wait_until(lambda: qinfo(FAILED_QUEUE).get("messages_ready", 0) >= failed_ready_before + 1, 35)
    if not ok:
        raise TestFailure("35 秒内未观察到新消息进入 failedQueue，请查看 ResQ 控制台")
    if not wait_queue_idle():
        raise TestFailure("消费处理队列仍有 Ready / Unacked 消息")
    count, status, slot = f.record_count(), f.reservation_status(), f.slot_status()
    if (count, status, slot) != (0, "PENDING", "AVAILABLE"):
        raise TestFailure(f"事务回滚失败：consume_count={count}, reservation={status}, slot={slot}")
    print("  PASS：消费记录=0、预约仍为 PENDING、时隙仍为 AVAILABLE")
    print("  PASS：失败消息进入 failedQueue（和源业务数据事务分离）")
    print("  说明：这是在独立测试数据上触发 releaseReservedSlot() 失败，不会更改现有预约。")


def test_redelivery(f):
    print("[2/3] 测试 ACK 丢失后的重投效果：真实过期成功后重发同一 eventId")
    if f.reservation_status() != "PENDING" or f.slot_status() != "RESERVED":
        raise TestFailure("重投测试初始条件错误")
    publish_event(f.reservation_id)
    first = wait_until(lambda: f.record_count() == 1 and f.reservation_status() == "EXPIRED"
                       and f.slot_status() == "AVAILABLE", 25)
    if not first:
        raise TestFailure("首次消费未完成状态过期、时隙释放和消费记录提交")
    if not wait_queue_idle():
        raise TestFailure("首次消息尚未结束消费")
    print("  首次消费成功：PENDING → EXPIRED，RESERVED → AVAILABLE，记录=1")
    # Intentionally send the same event body, simulating a redelivery following lost ACK.
    # Does NOT actually drop the connection or ACK.
    publish_event(f.reservation_id)
    if not wait_queue_idle():
        raise TestFailure("第二次消息尚未结束消费")
    time.sleep(0.6)
    actual = (f.record_count(), f.reservation_status(), f.slot_status())
    if actual != (1, "EXPIRED", "AVAILABLE"):
        raise TestFailure(f"重复投递后业务状态不符合预期：{actual}")
    print("  PASS：相同 eventId 再次投递，记录仍为 1，预约仍 EXPIRED，时隙仍 AVAILABLE")
    print("  说明：模拟的是 ACK 丢失造成的重投结果，不是实际断网或截断 ACK。")


def main():
    if len(sys.argv) > 1 and sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0
    if len(sys.argv) > 1:
        raise TestFailure("无需参数；使用 --help 查看说明")
    print("ResQ Day21 专项集成测试（非交互、无 Postman、无 Day20 脚本）")
    user_id, resource_id, start_col, end_col, failed_before = verify_preflight()
    fixtures = []
    completed = False
    try:
        a = Fixture(user_id, resource_id, start_col, end_col, "rollback")
        fixtures.append(a)
        test_rollback(a, failed_before)
        b = Fixture(user_id, resource_id, start_col, end_col, "redelivery")
        fixtures.append(b)
        test_redelivery(b)
        completed = True
    finally:
        # Clean after messages finish, including when an assertion failed.
        # Never delete rows while a message might still be in flight.
        try:
            safe_to_clean = wait_queue_idle(seconds=15)
        except Exception:
            safe_to_clean = False
        cleanup_errors = []
        if safe_to_clean:
            for fixture in reversed(fixtures):
                try:
                    fixture.cleanup()
                except Exception as exc:
                    cleanup_errors.append(str(exc))
                    fixture.show_manual_cleanup()
        else:
            for fixture in fixtures:
                fixture.show_manual_cleanup()
        if cleanup_errors:
            raise TestFailure("测试数据清理未完全成功：" + "; ".join(cleanup_errors))
    print("[3/3] 全部 PASS；创建的测试预约、时隙及消费记录已清理")
    print("注意：为了便于查看死信，failedQueue 保留了 1 条新的测试失败消息；不要清空整个队列。")
    print("注意：本测试不验证真正的 ACK 丢包、Publisher Confirm 或生产者消息丢失。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (TestFailure, ValueError) as exc:
        print("[FAIL] " + str(exc), file=sys.stderr)
        sys.exit(1)
