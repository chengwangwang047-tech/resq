#!/usr/bin/env bash
set -euo pipefail

tag="day20_cancel_$(date +%s)_$$"
resource_id=0

db() {
    sudo mysql --batch --skip-column-names resq -e "$1"
}

cleanup() {
    if [[ "$resource_id" =~ ^[1-9][0-9]*$ ]]; then
        echo "清理专用测试数据..."
        db "
            DELETE r FROM reservation r
            JOIN resource_slot s ON r.slot_id = s.id
            WHERE s.resource_id = $resource_id
              AND r.request_id = '$tag';

            DELETE FROM resource_slot
            WHERE resource_id = $resource_id;

            DELETE FROM compute_resource
            WHERE id = $resource_id AND name = '$tag';
        " >/dev/null || echo "清理失败，测试资源 ID=$resource_id"
    fi
}
trap cleanup EXIT

# 1. 登录
read -r -p "测试用户名: " username
read -rs -p "测试密码: " password
echo

login_json=$(
    printf '%s\0%s' "$username" "$password" |
    python3 -c '
import json, sys
u, p = sys.stdin.buffer.read().split(b"\0", 1)
print(json.dumps({"username": u.decode(), "password": p.decode()}))
'
)
unset password

login_response=$(
    curl -fsS -X POST http://127.0.0.1:8080/api/auth/login \
        -H "Content-Type: application/json" \
        --data-binary @- <<< "$login_json"
)
unset login_json

token=$(
    python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' \
    <<< "$login_response"
)

# 2. 创建专用资源和可用时隙
resource_id=$(
    db "
        INSERT INTO compute_resource
            (name, type, status, created_at)
        SELECT '$tag', type, status, NOW()
        FROM compute_resource
        ORDER BY id LIMIT 1;

        SELECT LAST_INSERT_ID();
    "
)

[[ "$resource_id" =~ ^[1-9][0-9]*$ ]] || exit 1

slot_id=$(
    db "
        INSERT INTO resource_slot
            (resource_id, slot_start, slot_end, status)
        VALUES (
            $resource_id,
            NOW() + INTERVAL 3 DAY,
            NOW() + INTERVAL 3 DAY + INTERVAL 1 HOUR,
            'AVAILABLE'
        );
        SELECT LAST_INSERT_ID();
    "
)

# 3. 创建预约
created=$(
    curl -fsS -X POST http://127.0.0.1:8080/api/reservations \
        -H "Authorization: Bearer $token" \
        -H "X-Request-Id: $tag" \
        -H "Content-Type: application/json" \
        -d "{\"slotId\":$slot_id}"
)

id=$(
    python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' \
    <<< "$created"
)

[[ "$id" =~ ^[1-9][0-9]*$ ]] || exit 1

echo "创建预约 ID=$id"

# 4. 调用真正的取消接口
cancelled=$(
    curl -fsS -X DELETE \
        -H "Authorization: Bearer $token" \
        "http://127.0.0.1:8080/api/reservations/$id"
)

cancel_status=$(
    python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' \
    <<< "$cancelled"
)

echo "取消结果：$cancel_status"
[[ "$cancel_status" == "CANCELLED" ]] || exit 1

# 5. 直接模拟原预约的超时消息
timeout 10s rabbitmqadmin \
    --host=127.0.0.1 \
    --port=15672 \
    publish \
    exchange=resq.reservation.timeout.dead.exchange \
    routing_key=reservation.timeout.expired \
    payload="$id"

sleep 3

# 6. 检查最终状态
result=$(
    db "
        SELECT CONCAT(r.status, ':', s.status)
        FROM reservation r
        JOIN resource_slot s ON r.slot_id = s.id
        WHERE r.id = $id;
    "
)

echo "最终状态：$result"

if [[ "$result" == "CANCELLED:AVAILABLE" ]]; then
    echo "Day20 CANCELLED 场景验证通过！"
else
    echo "验证失败，请检查 Spring Boot 日志。" >&2
    exit 1
fi
