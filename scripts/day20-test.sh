#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${RESQ_BASE_URL:-http://127.0.0.1:8080}"
TAG="day20_$(date +%s)_$$"
resource_id=""

db() {
    sudo mysql --batch --skip-column-names resq -e "$1"
}

json_field() {
    python3 -c '
import json, sys
data = json.load(sys.stdin)
print(data[sys.argv[1]])
' "$2" <<< "$1"
}

# 仅删除本次创建的专用测试数据
cleanup() {
    if [[ "$resource_id" =~ ^[1-9][0-9]*$ ]]; then
        echo "清理本次专用测试数据..."

        if ! db "
            DELETE r FROM reservation r
            JOIN resource_slot s ON r.slot_id = s.id
            JOIN compute_resource c ON s.resource_id = c.id
            WHERE c.id = $resource_id AND c.name = '$TAG';

            DELETE s FROM resource_slot s
            JOIN compute_resource c ON s.resource_id = c.id
            WHERE c.id = $resource_id AND c.name = '$TAG';

            DELETE FROM compute_resource
            WHERE id = $resource_id AND name = '$TAG';
        " >/dev/null; then
            echo "清理失败，请检查测试资源 ID=$resource_id" >&2
        fi
    fi
}
trap cleanup EXIT

echo "=== Day20 预约超时测试 ==="

# 1. 登录，自动获取 JWT
read -r -p "测试用户名: " username
read -rs -p "测试密码: " password
echo

login_body=$(
    printf '%s\0%s' "$username" "$password" |
    python3 -c '
import json, sys
u, p = sys.stdin.buffer.read().split(b"\0", 1)
print(json.dumps({
    "username": u.decode(),
    "password": p.decode()
}))
'
)
unset password

login_response=$(
    curl -fsS -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        --data-binary @- <<< "$login_body"
)
unset login_body

token=$(json_field "$login_response" token)
echo "登录成功，JWT 已自动获取。"

# 2. 创建独立测试资源
# 复制现有资源的 type/status，避免猜测业务枚举值
resource_id=$(
    db "
        INSERT INTO compute_resource
            (name, type, status, created_at)
        SELECT '$TAG', type, status, NOW()
        FROM compute_resource
        LIMIT 1;

        SELECT LAST_INSERT_ID();
    "
)

if ! [[ "$resource_id" =~ ^[1-9][0-9]*$ ]]; then
    echo "创建测试资源失败：请确认存在可作为模板的资源。" >&2
    exit 1
fi

# 3. 创建两个独立的 AVAILABLE 时隙
db "
    INSERT INTO resource_slot
        (resource_id, slot_start, slot_end, status)
    VALUES
        ($resource_id,
         NOW() + INTERVAL 2 DAY,
         NOW() + INTERVAL 2 DAY + INTERVAL 1 HOUR,
         'AVAILABLE'),

        ($resource_id,
         NOW() + INTERVAL 2 DAY + INTERVAL 2 HOUR,
         NOW() + INTERVAL 2 DAY + INTERVAL 3 HOUR,
         'AVAILABLE');
" >/dev/null

mapfile -t slot_ids < <(
    db "
        SELECT id FROM resource_slot
        WHERE resource_id = $resource_id
        ORDER BY id;
    "
)

if [[ ${#slot_ids[@]} -ne 2 ]]; then
    echo "没有正确创建两个测试时隙。" >&2
    exit 1
fi

confirm_slot="${slot_ids[0]}"
expire_slot="${slot_ids[1]}"

echo "测试资源 ID: $resource_id"
echo "确认测试时隙: $confirm_slot"
echo "过期测试时隙: $expire_slot"

# 4. 验证时隙查询接口
curl -fsS \
    -H "Authorization: Bearer $token" \
    "$BASE_URL/api/resources/$resource_id/slots" |
python3 -c '
import json, sys
slots = json.load(sys.stdin)
expected = set(map(int, sys.argv[1:3]))
available = {
    s["id"] for s in slots
    if s["status"] == "AVAILABLE"
}
if not expected.issubset(available):
    sys.exit("时隙查询接口没有返回预期的 AVAILABLE 时隙")
' "$confirm_slot" "$expire_slot"

echo "时隙接口检查通过。"

create_reservation() {
    local slot_id="$1"
    local request_id="$2"

    curl -fsS -X POST "$BASE_URL/api/reservations" \
        -H "Authorization: Bearer $token" \
        -H "X-Request-Id: $request_id" \
        -H "Content-Type: application/json" \
        --data "{\"slotId\":$slot_id}"
}

# 5. 场景 A：创建预约，立即确认
echo "=== 场景 A：及时确认 ==="

created_a=$(
    create_reservation "$confirm_slot" "${TAG}_confirm"
)
id_a=$(json_field "$created_a" id)

# 检查实际写入期限，防止误用 600 秒配置
read -r configured_seconds remaining_seconds <<< "$(
    db "
        SELECT
            TIMESTAMPDIFF(SECOND, created_at, expire_at),
            TIMESTAMPDIFF(SECOND, NOW(3), expire_at)
        FROM reservation
        WHERE id = $id_a;
    "
)"

if [[ "$configured_seconds" != "30" ]] ||
   (( remaining_seconds < 10 || remaining_seconds > 31 )); then
    echo "确认期限异常：配置=$configured_seconds 秒，剩余=$remaining_seconds 秒" >&2
    echo "请检查 30 秒启动参数及 Java/MySQL 时区。" >&2
    exit 1
fi

confirmed=$(
    curl -fsS -X POST \
        -H "Authorization: Bearer $token" \
        "$BASE_URL/api/reservations/$id_a/confirm"
)

if [[ "$(json_field "$confirmed" status)" != "CONFIRMED" ]]; then
    echo "确认预约失败。" >&2
    exit 1
fi

echo "预约 $id_a 确认成功。"

# 6. 场景 B：创建预约，不主动确认
echo "=== 场景 B：等待自动过期 ==="

created_b=$(
    create_reservation "$expire_slot" "${TAG}_expire"
)
id_b=$(json_field "$created_b" id)

echo "预约 $id_b 已创建，等待 RabbitMQ 自动过期..."

# 最多等待 90 秒，给消息投递和消费留出余量
deadline=$((SECONDS + 90))
status_b=""

while (( SECONDS < deadline )); do
    status_b=$(
        db "SELECT status FROM reservation WHERE id = $id_b;"
    )

    if [[ "$status_b" == "EXPIRED" ]]; then
        break
    fi

    sleep 2
done

if [[ "$status_b" != "EXPIRED" ]]; then
    echo "自动过期超时，最终状态：$status_b" >&2
    exit 1
fi

# 7. 检查预约状态与时隙状态
result_a=$(
    db "
        SELECT CONCAT(r.status, ':', s.status)
        FROM reservation r
        JOIN resource_slot s ON r.slot_id = s.id
        WHERE r.id = $id_a;
    "
)

result_b=$(
    db "
        SELECT CONCAT(r.status, ':', s.status)
        FROM reservation r
        JOIN resource_slot s ON r.slot_id = s.id
        WHERE r.id = $id_b;
    "
)

echo "确认预约：$result_a"
echo "超时预约：$result_b"

if [[ "$result_a" != "CONFIRMED:RESERVED" ]]; then
    echo "失败：已确认预约的状态不正确。" >&2
    exit 1
fi

if [[ "$result_b" != "EXPIRED:AVAILABLE" ]]; then
    echo "失败：过期预约或时隙释放状态不正确。" >&2
    exit 1
fi

echo "=== Day20 两个核心业务场景验证通过 ==="
