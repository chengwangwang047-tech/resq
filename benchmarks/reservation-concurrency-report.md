# ResQ Day24：POST /api/reservations 并发竞争实验

公开版路径说明：`<项目目录>`、`<私有凭据目录>` 是占位符，使用前必须替换为自己的实际目录；`RESQ_BENCH_ENV_FILE` 需指向本机为实验准备的认证配置文件，不能依赖另一台电脑的隐藏文件。实验编号、数据与结论保持原值，整理前文档保留在本地已忽略目录。下方历史手工实验涉及写数据，仅供核对方法；当前隔离演示入口见 [演示说明](../docs/demo.md)，不要直接重放旧记录。

证据目录：[results/post-reservation-20261022-182544/](results/post-reservation-20261022-182544/)。下文未带路径的证据文件名均相对此目录；目录名已整理，实验编号及数据库/vhost保持原值；原始证据中的私有路径保留，公开正文使用占位符。正文中的旧目录路径属于当时记录，对应关系见 [README](README.md)。历史 Markdown 快照仅在原工作目录保留，不再进入 Git；本文件为交付入口，原始证据恢复方法见归档说明。

本报告引用的完整日志、原始指标、历史命令和脚本快照已压缩保存，解压路径见[原始证据归档说明](archives/README.md)；汇总、配置与校验资料仍在证据目录。

2026-10-22，Asia/Shanghai（UTC+08:00）。实验编号 `d24_20261022_182544_3163fc`。

50、100、200 VU 各执行一轮新预约竞争：每轮恰好 1 个 201，其余为已核实的库存冲突 409；没有 429、其他 HTTP 状态、网络错误或 5xx。三个独立时隙在只读 SQL 核验时均仅有 1 条有效预约，未观察到超卖。

## 结果

延迟单位为 ms，取 k6 `http_req_duration`，包含本轮 201 与 409；技术失败定义为 status=0 或 >=500。每行分母分别为 50、100、200 次请求。

| VU | slotId | 201 | 库存 409 | 429 | 其他状态 | P95 | P99 | 技术失败率 | 有效预约：前 → 后 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 50 | 117 | 1 | 49 | 0 | 0 | 639.30 | 647.52 | 0% | 0 → 1 |
| 100 | 118 | 1 | 99 | 0 | 0 | 332.58 | 345.06 | 0% | 0 → 1 |
| 200 | 119 | 1 | 199 | 0 | 0 | 596.67 | 616.79 | 0% | 0 → 1 |

业务冲突率分别为 98%、99%、99.5%。三轮的 `http_req_failed` 和非预期结果率均为 0%，checks 全部通过，k6 退出码均为 0，完成 50/100/200 次迭代，均无中断。这里 `http_req_failed` 沿用原脚本的 `expectedStatuses(201,409)` 口径，不能理解为所有预约都创建成功。

## 并发与时间证据

使用原有 `per-vu-iterations` executor，参数化 `vus=N`、`iterations=1`、`maxDuration=30s`，预先完成所有登录；setup 设置共同的未来 5 秒屏障。每个 VU 使用不同用户、不同 requestId，只 POST 一次，无重试、无循环预约、无业务预热请求。原始 `.inspect.json`、`.plan.json`、`.k6.log` 和逐请求 `.requests.json` 均已保留。

下表全部为 2026-10-22 北京时间；进程时长包含 5 秒屏障及输出开销，请求窗口从第一条调用开始至最后一条调用结束。

| VU | k6 进程开始–结束 | 实际 HTTP 调用窗口 | 进程时长 | 请求窗口 | 发起跨度 | 保守客户端在途峰值 |
|---:|---|---|---:|---:|---:|---:|
| 50 | 18:28:53.952–18:29:00.059 | 18:28:58.994–18:28:59.649 | 6.108 s | 655 ms | 4 ms | 50 |
| 100 | 18:29:00.134–18:29:06.084 | 18:29:05.193–18:29:05.569 | 5.950 s | 376 ms | 26 ms | 100 |
| 200 | 18:29:06.159–18:29:12.303 | 18:29:11.245–18:29:11.915 | 6.144 s | 670 ms | 49 ms | 198 |

在途峰值以毫秒记录计算半开区间 `[开始,结束)`，同一毫秒先结束后开始。200 档存在同毫秒首尾重合，因此只能保守确认峰值 198；这轮确实是 200 个并发 VU、200 次同槽竞争，**无法凭现有精度证明 200 条 HTTP 全部同时在途**。这些是客户端时间，不能用来证明服务器内部同时获得锁。

每档只有一轮短脉冲；P95/P99 主要描述冲突路径，201 成功写入每档仅 1 个样本。50 档是本次首轮预约写请求，未专门预热，后两轮受到既有运行状态影响；不能把 50 档慢于 100 档归因为并发规模，也不能据此推断持续吞吐或稳定容量。

## 环境、身份与实验数据

- 正式代码位置：WSL `Ubuntu-24.04` 的 `<项目目录>`；地址 `http://127.0.0.1:8080`；k6 2.3.0，Java 21。以正常环境运行当前源代码对应的 `mvn -q spring-boot:run`，未使用旧 jar。
- 启动前 HTTP 未监听；只读核实已有到期 PENDING 为 0，通知/超时 dead 队列无积压。原有 failed 队列 2 条未处理。
- 没有使用已有业务时隙。通过正常资源 API 新建虚拟资源 `DAY24_BENCH_ONLY_d24_20261022_182544_3163fc`，id=112；仅 INSERT 其 3 个全新 AVAILABLE 时隙，slotId=117/118/119，时间分别为合成未来日期 2099-01-24 的 09–10、10–11、11–12 点。`fixture-inserts.sql` 是实际插入记录，不是清理脚本。
- 通过正常 `/api/auth/register`、`/api/auth/login` 新建并认证 200 个 `d24_` 测试用户（id 28–227，邮箱为 example.invalid）。每个 VU 使用独立的服务端签发 JWT；三档复用前 N 个账户，每人本实验最多 3 次新预约，未改变或绕过每自然分钟 10 次限流。
- 每个请求 `X-Request-Id` 为“唯一实验档次标识 + VU + iteration”，全实验 350 个均不重复，不把换 requestId 的重试当作库存竞争。所有 347 个 409 的实际 message 均为“时隙不可用，预约失败”，未混入幂等键冲突。
- 凭据仅保存在 WSL 私有目录 `<私有凭据目录>`（0700），`accounts.json` 与 `tokens.json` 为 0600；未纳入报告或结果包，未输出 JWT、密码或签名密钥。JWT 有效期 1 小时，私有 /tmp 文件也可能被系统回收。

## 数据库核验

每档前后均在 `SET SESSION TRANSACTION READ ONLY; START TRANSACTION; ... COMMIT;` 内查询，同步保存数据库本地时间及 UTC 时间。有效预约采用保守的 `status IN ('PENDING','CONFIRMED')`，同时核查该 slot 的全部预约行；本次每槽总行数也恰好为 1。

| VU | slotId | 前：时隙 / 有效数 | 后：时隙 / 预约行数 | reservationId | 胜出 userId |
|---:|---:|---|---|---:|---:|
| 50 | 117 | AVAILABLE / 0 | RESERVED / 1 PENDING | 57 | 71 |
| 100 | 118 | AVAILABLE / 0 | RESERVED / 1 PENDING | 58 | 38 |
| 200 | 119 | AVAILABLE / 0 | RESERVED / 1 PENDING | 59 | 67 |

实际执行的关键查询如下，每档 SQL 及完整结果分别在 `50/100/200.db-before.sql/.tsv`、`.db-after.sql/.tsv`：

```sql
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION;
SELECT NOW(3), UTC_TIMESTAMP(3), @@session.time_zone;
-- 将 117 分别替换为 118、119，实际文件已填入具体 id。
SELECT s.id,s.resource_id,c.name,s.slot_start,s.slot_end,s.status
FROM resource_slot s JOIN compute_resource c ON c.id=s.resource_id
WHERE s.id=117;
SELECT status,COUNT(*) FROM reservation WHERE slot_id=117 GROUP BY status;
SELECT COUNT(*) AS effective_count FROM reservation
WHERE slot_id=117 AND status IN ('PENDING','CONFIRMED');
SELECT id,user_id,slot_id,status,request_id,created_at,expire_at
FROM reservation WHERE slot_id=117 ORDER BY id;
COMMIT;
```

真实 `SHOW CREATE TABLE` 已保留在 `schema.sql/.tsv`：InnoDB 的 `reservation` 仅有 request_id 唯一键，**没有 slot_id 或“同槽有效预约”的唯一约束**；resource_slot 有 `(resource_id,slot_start)` 唯一键。现有代码使用 Redisson 时隙锁与事务内 `AVAILABLE → RESERVED` 条件 UPDATE。本次最多一条是实测结果，不能写成 slot_id 数据库唯一索引所保证。

## Redis、MQ 与留存影响

- 完成后限流计数快照完整覆盖各档全部用户：50 档 50 个键为 1；100 档 100 个键为 1；200 档 100 个键为 2、另 100 个为 1，均低于 10。50 档请求发生在 18:28 自然分钟，另两档在 18:29。没有删除、重置限流键。
- MGET 空值解析截掉了尾部空项：预检中 50/100 档各仅存下 1 个空键，200 档存下前 100 个非空键；50 档结束跨分钟快照还漏掉了另一个分钟的空项，故**这些文件不是所有所查分钟、全账户预检的完整证据**。保留原文件并添加 `redis-snapshot-completeness.json`，未重造历史观察值；完成后每个用户的非空计数、350 个真实返回、0 个 429 与独立新建账户仍可核验。本地辅助采集器已修复空值解析。
- 幂等核查每档只有 1 个 SUCCESS，映射至预约 57/58/59；其余键均 ABSENT，无遗留 PROCESSING，时隙锁均不存在。日志未发现 Redis 降级或状态写入失败。
- 三个创建事件均有正常通知消费日志。最终通知队列与超时 dead 队列为 0；600 秒延迟队列新增 3 条正常实验超时消息；原 failed 队列仍为 2 条。未执行消息消费探针、purge、删除或 Redis 清库。
- 数据内容哈希核验：五张相关表中所有原有行均保持不变；仅新增 200 个测试用户、1 个虚拟资源、3 个时隙、3 条预约。364 个非 benchmarks 文件 SHA-256 未变，没有 Java、SQL 源文件或配置改动；正式日志级别、限流、认证、幂等实现均未改动。
- 实验临时启动的应用已于 18:32:48 正常停止，恢复原先未监听状态。未清理测试数据；采样时三个预约均为 PENDING，确认截止分别为 18:38:59、18:39:05、18:39:11。后续正常启动/消费可能将其自然过期。报告只断言记录时刻的状态。

## 可复现的操作记录

以下记录区分“本次已执行事实”和“下一轮复现命令”。本节补充时只编辑文档，**没有重新注册、写库或运行压测**。命令按本次流程整理，并补上新一轮的检查；性能数值不保证重复。以下 Linux 命令在 WSL Ubuntu 执行。

### 本次实际操作顺序

| 顺序 | 已执行操作 | 可核对记录 |
|---:|---|---|
| 1 | 读取用户现有脚本，核查 Git 状态、接口实现、真实 DDL、限流及后台任务；确认 HTTP 未监听、无到期历史预约需要处理 | `original-post-reservation-race.js`、`schema.tsv`、`application-lifecycle.json` |
| 2 | 18:24:13 用原有本机环境执行 `mvn -q spring-boot:run`，不覆盖业务设置 | `application-lifecycle.json` |
| 3 | 18:25:44–18:26:10 创建虚拟资源 112、实验时隙 117/118/119；正常注册、登录 200 个独立测试用户 | `fixture-inserts.sql`、`fixtures-public.json`；凭据保存在私有目录 |
| 4 | 每档先核查 AVAILABLE、总预约 0、有效预约 0，再检查 executor，依次跑 50/100/200 VU | `N.db-before.sql/.tsv`、`N.inspect.json`、`N.command.sh` |
| 5 | 18:28:53–18:29:12 保存 k6 原始输出、时段、逐请求记录，并逐档只读核查 DB、Redis、MQ | `N.k6.log`、`N.metrics.ndjson`、`N.summary.json`、`N.db-after.tsv`、`N.redis-*.json`、`N.mq-*.json` |
| 6 | 核对原有数据和非 benchmarks 文件未变化，保留全部实验数据；18:32:48 正常停止本次启动的服务 | `business-data-integrity.json`、`project-file-integrity.json`、`application-lifecycle.json` |
| 7 | 18:36:30 再次只读核查三个时隙，各仍有且仅有一条有效预约 | `final-verification.sql/.tsv` |

其中 N 分别为 50、100、200。原始 `N.command.sh` 是历史命令，里面的 slotId 和 requestId 前缀已经使用，**不要直接重放**；下一轮按下列步骤生成新资源、时隙和实验标识。

### 1．进入环境，建立新的结果目录

从 Windows PowerShell 进入带有原有启动环境的 WSL 登录 shell：

```powershell
wsl.exe -d Ubuntu-24.04 -- bash -il
```

然后在这个新开的 WSL 实验 shell 中按顺序执行。关键准备命令失败时停止当前实验 shell，先查明原因再继续；k6 的非零退出另行捕获，仍执行数据库后验。不要启用命令跟踪或输出整个环境变量：

```bash
set +x
set -e -o pipefail
cd "<项目目录>"
: "${RESQ_BENCH_ENV_FILE:?请先指定本机实验认证配置文件}"
source "$RESQ_BENCH_ENV_FILE"
: "${RESQ_DB_USERNAME:?请先加载原有数据库用户名}"
: "${RESQ_DB_PASSWORD:?请先加载原有数据库密码}"
: "${RESQ_JWT_SECRET_BASE64:?请先加载原有应用 JWT 签名环境}"

export DAY24_RUN="d24_$(date -u +%Y%m%dT%H%M%S)_$(python3 -c 'import secrets; print(secrets.token_hex(3))')"
export DAY24_OUT="$PWD/benchmarks/results/$DAY24_RUN"
mkdir -p benchmarks/results || exit 1
mkdir "$DAY24_OUT" || exit 1          # 重名就停止，不覆盖已有记录
export DAY24_PRIVATE="$(mktemp -d "/tmp/${DAY24_RUN}_credentials_XXXXXX")"
chmod 700 "$DAY24_PRIVATE"
export RESQ_TOKENS_FILE="$DAY24_PRIVATE/tokens.json"
export RESQ_BASE_URL='http://127.0.0.1:8080'
unset RESQ_START_AT_MS                # 使用脚本默认的未来 5 秒屏障

{ date --iso-8601=seconds; k6 version; java -version; } >"$DAY24_OUT/environment.txt" 2>&1
python3 -c 'import requests'          # 本次 WSL 已安装；下方仅用于正常注册
```

下方连接参数对应本次核实的 `127.0.0.1:3306/resq`。若应用的 `RESQ_DB_URL` 已换库，必须先让核验连接与应用实际数据库一致；不能核验另一个库。

```bash
db() {
  MYSQL_PWD="$RESQ_DB_PASSWORD" mysql \
    --host=127.0.0.1 --port=3306 --user="$RESQ_DB_USERNAME" \
    --database=resq --batch --raw "$@"
}
db_ro() {
  {
    printf 'SET SESSION TRANSACTION READ ONLY;\nSTART TRANSACTION;\n'
    cat
    printf '\nCOMMIT;\n'
  } | db "$@"
}
set -o pipefail
db_ro >"$DAY24_OUT/preflight-db.tsv" <<'SQL'
SELECT NOW(3),UTC_TIMESTAMP(3),DATABASE();
SELECT COUNT(*) AS due_pending FROM reservation
WHERE status='PENDING' AND expire_at IS NOT NULL AND expire_at<=NOW(3);
SHOW CREATE TABLE reservation;
SHOW CREATE TABLE resource_slot;
SQL
cat "$DAY24_OUT/preflight-db.tsv"
redis-cli PING
```

如果 HTTP 尚未启动，先确认历史到期预约及通知/dead 队列不存在需要另行判断的业务积压，再在**另一个同样加载原有环境的 WSL 终端**执行 `cd "<项目目录>" && mvn -q spring-boot:run`。已有服务正常运行时直接使用，不重复启动，不改变日志、认证、限流或后台任务参数。回到本终端用 `curl -fsS http://127.0.0.1:8080/api/resources >"$DAY24_OUT/readiness.json"` 检查就绪。

### 2．准备 200 个测试身份，JWT 不进入结果目录

若本次私有账户文件仍在，可以复用它，并通过正常登录取得新 JWT：

```bash
export DAY24_ACCOUNTS='<私有凭据目录>/accounts.json'
```

若对应私有文件已经不存在，使用下面的分支注册一批全新的 `d24_` 用户；已有账户文件时分支不会注册。随机密码仅写入 0600 私有文件，不打印。分支中断后保留已创建账户，不删除或自动重复整批注册。

```bash
if [ ! -f "$DAY24_ACCOUNTS" ]; then
  export DAY24_ACCOUNTS="$DAY24_PRIVATE/accounts.json"
  python3 - <<'PY'
import json, os, secrets
from pathlib import Path
import requests

path = Path(os.environ['DAY24_ACCOUNTS'])
with path.open('x', encoding='utf-8') as handle:
    os.chmod(path, 0o600)
    json.dump([], handle)
session = requests.Session()
session.trust_env = False
accounts = []
for i in range(1, 201):
    username = f"{os.environ['DAY24_RUN']}_{i:03d}"
    password = secrets.token_urlsafe(24)
    response = session.post('http://127.0.0.1:8080/api/auth/register',
        json={'username': username, 'password': password,
              'email': username + '@example.invalid'},
        timeout=10, allow_redirects=False)
    if response.status_code != 201:
        raise SystemExit(f'注册在第 {i} 个账户停止，HTTP {response.status_code}；保留现有记录。')
    accounts.append({'userId': int(response.json()['userId']),
                     'username': username, 'password': password})
    path.write_text(json.dumps(accounts), encoding='utf-8')
print('已准备 200 个独立测试账户，密码未输出。')
PY
fi
```

确认上一命令成功，再执行正常登录。辅助脚本仅接受 `d24_` 测试账户、连接本机登录接口，并检查私有文件权限：

```bash
python3 benchmarks/refresh-benchmark-tokens.py \
  --accounts "$DAY24_ACCOUNTS" --tokens "$RESQ_TOKENS_FILE"
```

JWT 格式是私有 JSON 数组 `[{"userId":数字,"token":"服务端签发值"}, ...]`；不要把真实 token 填入报告或 `k6 -e` 命令。下方限流检查会在每档运行前检查本自然分钟额度；不要删除限流键，也不要换 requestId 重试失败请求。

### 3．为新一轮创建独立虚拟资源和 3 个新时隙

这一段只创建全新的、名称明确标记的实验资源及其时隙，不复用 117/118/119，不 UPDATE 已有业务数据。通过资源 API 新建资源后，记录返回 id：

```bash
export DAY24_RESOURCE_NAME="DAY24_BENCH_ONLY_${DAY24_RUN}"
curl -fsS -X POST "$RESQ_BASE_URL/api/resources" \
  -H 'Content-Type: application/json' \
  --data "{\"name\":\"$DAY24_RESOURCE_NAME\",\"type\":\"GPU\"}" \
  >"$DAY24_OUT/resource-created.json"
export DAY24_RESOURCE_ID="$(python3 -c 'import json,sys; print(int(json.load(open(sys.argv[1]))["id"]))' "$DAY24_OUT/resource-created.json")"
[[ "$DAY24_RESOURCE_ID" =~ ^[1-9][0-9]*$ ]] || { printf '资源创建未核实，停止。\n'; exit 1; }
```

只有资源创建已核实时才执行以下 INSERT。一次执行即可；失败或中断后核查已有记录，不盲目重复。

```bash
cat >"$DAY24_OUT/fixture-inserts.sql" <<SQL
START TRANSACTION;
INSERT INTO resource_slot(resource_id,slot_start,slot_end,status)
SELECT id,'2099-01-24 09:00:00','2099-01-24 10:00:00','AVAILABLE'
FROM compute_resource WHERE id=$DAY24_RESOURCE_ID AND name='$DAY24_RESOURCE_NAME';
INSERT INTO resource_slot(resource_id,slot_start,slot_end,status)
SELECT id,'2099-01-24 10:00:00','2099-01-24 11:00:00','AVAILABLE'
FROM compute_resource WHERE id=$DAY24_RESOURCE_ID AND name='$DAY24_RESOURCE_NAME';
INSERT INTO resource_slot(resource_id,slot_start,slot_end,status)
SELECT id,'2099-01-24 11:00:00','2099-01-24 12:00:00','AVAILABLE'
FROM compute_resource WHERE id=$DAY24_RESOURCE_ID AND name='$DAY24_RESOURCE_NAME';
COMMIT;
SQL
db <"$DAY24_OUT/fixture-inserts.sql" >"$DAY24_OUT/fixture-inserts.tsv"

mapfile -t DAY24_SLOTS < <(db_ro --skip-column-names <<SQL
SELECT id FROM resource_slot WHERE resource_id=$DAY24_RESOURCE_ID
ORDER BY slot_start;
SQL
)
test "${#DAY24_SLOTS[@]}" -eq 3 || { printf '时隙不是恰好 3 个，停止。\n'; exit 1; }
printf '50\t%s\n100\t%s\n200\t%s\n' "${DAY24_SLOTS[0]}" "${DAY24_SLOTS[1]}" "${DAY24_SLOTS[2]}" \
  >"$DAY24_OUT/slots.tsv"
```

### 4．定义只读证据查询与限流快照

先定义函数，随后三档都会调用。SQL 文件自身包含 READ ONLY 与事务边界；同时保存时隙总预约数、有效数和全部预约行，防止只看 HTTP 结果。

```bash
slot_evidence() {
  local slot="$1" phase="$2"
  cat >"$DAY24_OUT/$RESQ_VUS.db-$phase.sql" <<SQL
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION;
SELECT NOW(3) AS observed_db_time,UTC_TIMESTAMP(3) AS observed_utc_time;
SELECT s.id,s.resource_id,c.name,s.slot_start,s.slot_end,s.status
FROM resource_slot s JOIN compute_resource c ON c.id=s.resource_id WHERE s.id=$slot;
SELECT COUNT(*) AS total_count,
COALESCE(SUM(status IN ('PENDING','CONFIRMED')),0) AS effective_count
FROM reservation WHERE slot_id=$slot;
SELECT id,user_id,slot_id,status,request_id,created_at,expire_at
FROM reservation WHERE slot_id=$slot ORDER BY id;
COMMIT;
SQL
  db <"$DAY24_OUT/$RESQ_VUS.db-$phase.sql" >"$DAY24_OUT/$RESQ_VUS.db-$phase.tsv"
}

rate_snapshot() {
  python3 - "$1" <<'PY'
import json, os, subprocess, sys, time
from pathlib import Path

phase = sys.argv[1]
n = int(os.environ['RESQ_VUS'])
users = json.loads(Path(os.environ['RESQ_TOKENS_FILE']).read_text())[:n]
if len(users) != n or len({str(u['userId']) for u in users}) != n:
    raise SystemExit('测试用户数不足或 userId 重复。')
minute = int(time.time() // 60)
keys = [f"rate:reservation:user:{u['userId']}:{minute}" for u in users]
result = subprocess.run(['redis-cli','--raw','MGET',*keys],
                        capture_output=True, text=True, check=True)
values = result.stdout.splitlines()  # 保留 nil 空行，不能 rstrip 后再拆行
if len(values) != len(keys):
    raise SystemExit('Redis 快照记录数不符，停止；不猜测缺失项。')
counts = {k: int(v) if v else 0 for k,v in zip(keys,values)}
path = Path(os.environ['DAY24_OUT']) / f'{n}.redis-{phase}.json'
path.write_text(json.dumps({'observed_epoch_seconds':time.time(),
                           'minute':minute,'rate_key_counts':counts}, indent=2))
if phase == 'before' and any(v >= 10 for v in counts.values()):
    raise SystemExit('本自然分钟有用户已达 10 次；停止，等待下一分钟后重新检查。')
PY
}
```

快照保存的是观测时刻所属分钟；如果请求窗口跨分钟，应再只读采集实际发起分钟的键。前后都保留观测时间，不把另一分钟的 0 当作已经处理请求的计数。每轮每用户仅 1 次，三档合计最多 3 次；0 个 429 仍须由实际返回核实。

### 5．依次运行 50、100、200 VU，各档只跑一次

只有前述步骤全部成功才执行整块。它在每档发请求前确认资源归属、新时隙 AVAILABLE 且总预约数为 0；每个档次只调用一次 k6。不要额外添加 `--duration`、`--iterations` 或 `--vus` 覆盖脚本的场景。退出码非零时也保存 DB 后验，随后停止；不能在已用时隙上直接重跑。

```bash
for index in 0 1 2; do
  counts=(50 100 200)
  export RESQ_VUS="${counts[$index]}"
  export RESQ_POST_SLOT_ID="${DAY24_SLOTS[$index]}"
  export RESQ_RUN_ID="${DAY24_RUN}_n${RESQ_VUS}"
  [[ "$RESQ_POST_SLOT_ID" =~ ^[1-9][0-9]*$ ]] || break

  state="$(db_ro --skip-column-names <<SQL
SELECT CONCAT(s.status,':',(SELECT COUNT(*) FROM reservation r WHERE r.slot_id=s.id))
FROM resource_slot s JOIN compute_resource c ON c.id=s.resource_id
WHERE s.id=$RESQ_POST_SLOT_ID AND c.id=$DAY24_RESOURCE_ID AND c.name='$DAY24_RESOURCE_NAME';
SQL
)"
  if [ "$state" != 'AVAILABLE:0' ]; then
    printf '实验时隙状态不符，停止。\n'
    break
  fi
  slot_evidence "$RESQ_POST_SLOT_ID" before || break
  rate_snapshot before || break

  # k6 无需数据库密码或应用的 JWT 签名密钥。
  k6_local=(env -u RESQ_DB_PASSWORD -u RESQ_JWT_SECRET_BASE64 k6)
  "${k6_local[@]}" inspect --include-system-env-vars benchmarks/post-reservation-race.js \
    >"$DAY24_OUT/$RESQ_VUS.inspect.json" 2>"$DAY24_OUT/$RESQ_VUS.inspect.stderr" || break
  date --iso-8601=ns >"$DAY24_OUT/$RESQ_VUS.started-at.txt"
  if "${k6_local[@]}" run --summary-mode=full \
      --summary-trend-stats='avg,min,med,max,p(90),p(95),p(99)' \
      --summary-export="$DAY24_OUT/$RESQ_VUS.summary.json" \
      --out "json=$DAY24_OUT/$RESQ_VUS.metrics.ndjson" \
      benchmarks/post-reservation-race.js >"$DAY24_OUT/$RESQ_VUS.k6.log" 2>&1; then
    rc=0
  else
    rc=$?
  fi
  date --iso-8601=ns >"$DAY24_OUT/$RESQ_VUS.ended-at.txt"
  printf '%s\n' "$rc" >"$DAY24_OUT/$RESQ_VUS.exit-code.txt"
  slot_evidence "$RESQ_POST_SLOT_ID" after || break
  rate_snapshot after || break
  redis-cli EXISTS "resq:lock:slot:$RESQ_POST_SLOT_ID" >"$DAY24_OUT/$RESQ_VUS.lock-exists.txt"
  redis-cli --scan --pattern "request:${RESQ_RUN_ID}_*" >"$DAY24_OUT/$RESQ_VUS.request-keys.txt"
  printf '%s VU 完成，k6 exit=%s；请核对原始日志与 SQL。\n' "$RESQ_VUS" "$rc"
  [ "$rc" -eq 0 ] || break
done
```

复现时仍以原始 k6 日志中的 N complete、0 interrupted，以及 SQL 中最多一条有效预约为准。将所有 409 按响应 message 核查；脚本只有确切库存 message 才计入 `reservation_conflict`，其他 409 会触发非预期结果。429 单列，网络错误/5xx 才计为本报告的技术失败，三者不能混为库存竞争失败。

### 6．提取结果，保留异常，不做清理

下面仅读取本轮已落盘的汇总，输出状态计数和 P95/P99；缺失档次如实显示“未完成”，不补数。

```bash
python3 - <<'PY'
import json, os
from pathlib import Path
root = Path(os.environ['DAY24_OUT'])
for n in [50,100,200]:
    path = root / f'{n}.summary.json'
    if not path.exists():
        print(n, '未完成，核查本档原始记录')
        continue
    m = json.loads(path.read_text())['metrics']
    count = lambda key: m[key]['count']
    latency = m['http_req_duration']
    print(n, {'201':count('reservation_created'),
              '409_inventory':count('reservation_conflict'),
              '429':count('reservation_rate_limited'),
              'unexpected':count('reservation_unexpected'),
              'technical_failure_rate':m['reservation_technical_failure_rate']['value'],
              'p95_ms':latency['p(95)'],'p99_ms':latency['p(99)']})
PY
```

发起跨度、客户端在途峰值需从原始日志中的 `reservation_race_request` 逐请求时间复算，不能用 VU 数代替。日志默认 `msg="..."` 有一层 JSON 字符串转义，需先解码该字段，再解析内部 JSON；相同毫秒按结束先于开始的半开区间计算，保留精度限制。

正常观察通知及超时队列并保存快照；不要通过取走消息来探测。一次成功预约会产生正常通知和延迟超时事件，后续自然过期可能改变测试预约状态。如果服务是本轮临时启动的，在其专用终端用 Ctrl+C 正常停止；原本就运行的服务保持其原有运行状态。测试账户、资源、预约、Redis 状态和 MQ 消息均保留，不执行删除、清库、purge 或业务数据重置。只打包 `$DAY24_OUT`，**不要打包 `$DAY24_PRIVATE` 或账户文件**。

## 文件索引

- `benchmark-summary.csv`：便于复盘的数值表。
- `N.k6.log`、`N.metrics.ndjson`、`N.summary.json`：原始输出、逐指标采样、汇总；旧汇总格式里的 `thresholds: false` 表示未失败，应结合原始日志的 ✓ 与退出码 0 阅读。
- `N.requests.json`、`N.run.json`、`N.plan.json`：从原始日志解码的逐请求记录、时段、executor 与屏障；未改写原始日志。
- `N.db-*.sql/.tsv`、`final-verification.sql/.tsv`：数据库前后证据。
- `N.redis-*.json`、`N.mq-*.json`、`application-experiment-events.log`：Redis/MQ 留证。
- `project-file-integrity.json`、`business-data-integrity.json`、`application-lifecycle.json`：源文件、原有数据、应用启动停止核验。
- `original-post-reservation-race.js`：用户原脚本备份；`post-reservation-race.js`：实际执行版；`refresh-day24-test-tokens.py`：本地正常登录辅助脚本。

executor 与 HTTP 失败口径依据：[k6 per-vu-iterations 官方文档](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/per-vu-iterations/)、[k6 responseCallback 官方文档](https://grafana.com/docs/k6/latest/javascript-api/k6-http/set-response-callback/)。
