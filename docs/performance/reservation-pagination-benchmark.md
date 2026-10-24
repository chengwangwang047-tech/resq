# Day23：ResQ SQL 深分页性能基准测试与游标分页优化报告

**项目：** ResQ 共享计算资源预约与任务调度平台  
**测试日期：** 2026-10-21 ～ 2026-10-22  
**技术栈：** Java 21、Spring Boot、MyBatis、MySQL（InnoDB）、JWT  
**测试内容：** SQL 深分页性能对比、联合索引优化、预约历史游标分页接口

---

## 一、实验背景与目标

ResQ 为用户提供计算资源预约功能，需要支持用户查询自己的历史预约记录。

最初的预约历史接口为：

```http
GET /api/reservations/me
```

对应的 SQL：

```sql
SELECT *
FROM reservation
WHERE user_id = ?
ORDER BY created_at DESC, id DESC;
```

该查询会返回用户的所有预约记录，没有分页限制。

当预约记录数量不断增加时，一次性查询全部数据可能增加数据库、网络传输和服务端的开销。

传统分页可以使用：

```sql
LIMIT offset, size;
```

但 OFFSET 较大时，数据库往往需要先读取并跳过大量记录，再返回当前页的数据。

因此，Day23 主要完成以下任务：

1. 构造 10 万条模拟预约数据。
2. 对比 OFFSET 深分页和游标分页的执行效率。
3. 使用 `EXPLAIN ANALYZE` 分析实际扫描行数和执行时间。
4. 为 ResQ 实现基于 `(created_at, id)` 的预约历史游标分页。
5. 分析现有联合索引，并增加适用于新查询的索引。
6. 通过真实 HTTP 请求验证分页正确性。

---

## 二、独立数据库性能实验

### 2.1 实验环境

为了避免在正式 ResQ 业务表中插入大量模拟数据，本次实验使用独立数据库。

| 项目 | 实际配置 |
|---|---|
| 数据库 | MySQL（InnoDB） |
| 实验数据库 | `resq_day23_lab` |
| 实验表 | `reservation_bench` |
| 总记录数 | 100,000 |
| 用户 100 的记录数 | 60,000 |
| 主键 | `PRIMARY(id)` |
| 二级索引 | `idx_user_id(user_id)` |
| 分页大小 | 20 |
| OFFSET | 50,000 |
| 每种方案测量次数 | 2 |

测试表主要字段：

```sql
id BIGINT PRIMARY KEY,
user_id BIGINT NOT NULL,
status VARCHAR(20) NOT NULL,
created_at DATETIME NOT NULL
```

本次实验只查询 `id`，目的是在两种方案都能够使用覆盖索引的情况下，重点比较深分页的记录扫描开销。

### 2.2 数据量验证

执行：

```sql
SELECT
    COUNT(*) AS total_rows,
    SUM(user_id = 100) AS target_user_rows
FROM reservation_bench;
```

实际结果：

```text
total_rows       = 100000
target_user_rows = 60000
```

说明测试数据已经满足本次性能对比要求。

---

## 三、OFFSET 深分页实验

### 3.1 SQL

```sql
SELECT id
FROM reservation_bench
WHERE user_id = 100
ORDER BY id DESC
LIMIT 50000, 20;
```

查询含义：

- 筛选 `user_id = 100` 的预约记录。
- 按 `id` 降序排列。
- 跳过前 50,000 条。
- 返回后续 20 条。

使用执行计划分析：

```sql
EXPLAIN ANALYZE
SELECT id
FROM reservation_bench
WHERE user_id = 100
ORDER BY id DESC
LIMIT 50000, 20;
```

### 3.2 实际执行结果

关键执行计划：

```text
Limit/Offset: 20/50000
(actual time=11.6..11.6 rows=20)

Covering index lookup using idx_user_id (reverse)
(actual time=0.824..10 rows=50020)
```

两次测量的结束时间分别为：

```text
第一次：11.6 ms
第二次：10.1 ms
```

平均耗时：

```text
(11.6 + 10.1) / 2 = 10.85 ms
```

### 3.3 原理分析

虽然 SQL 使用了二级索引 `idx_user_id`，而且属于覆盖索引查询，但它仍然需要读取大量记录。

执行过程可以概括为：

```text
定位 user_id = 100 的索引范围
              ↓
按 id DESC 反向扫描
              ↓
读取前 50000 条并跳过
              ↓
继续读取 20 条
              ↓
返回 20 条记录
```

实际扫描行数：

```text
50000 + 20 = 50020
```

**结论：使用索引不代表 SQL 一定高效。**

对于这次查询，性能开销主要来自大量记录被扫描后又被跳过。

---

## 四、游标分页实验

### 4.1 SQL

```sql
SELECT id
FROM reservation_bench
WHERE user_id = 100
  AND id < 10001
ORDER BY id DESC
LIMIT 20;
```

使用：

```sql
EXPLAIN ANALYZE
SELECT id
FROM reservation_bench
WHERE user_id = 100
  AND id < 10001
ORDER BY id DESC
LIMIT 20;
```

这里的 `10001` 是已知的分页边界。

与 OFFSET 分页不同，游标分页不再要求数据库跳过前 50,000 条结果，而是根据索引键值定位后续范围。

### 4.2 实际执行结果

关键执行计划：

```text
Limit: 20
(actual time=0.238..0.245 rows=20)

Covering index range scan using idx_user_id
over (user_id = 100 AND id < 10001) (reverse)
(actual time=0.234..0.237 rows=20)
```

两次测量的结束时间分别为：

```text
第一次：0.245 ms
第二次：0.198 ms
```

平均耗时：

```text
(0.245 + 0.198) / 2 = 0.2215 ms
```

约为：

```text
0.222 ms
```

### 4.3 为什么 idx_user_id 能支持 ID 范围查询？

实验中显式创建的二级索引是：

```sql
idx_user_id(user_id)
```

但 InnoDB 二级索引叶子记录中包含主键值。

因此，在当前非唯一二级索引中，可以从逻辑上理解为：

```text
(user_id, id)
```

其中 `id` 是隐含的主键部分。

所以 MySQL 能够利用：

```sql
WHERE user_id = 100
  AND id < 10001
```

进行索引范围扫描。

本次 `EXPLAIN ANALYZE` 已验证这一访问方式。

### 4.4 结果正确性

OFFSET 分页返回：

```text
10000, 9999, 9998, ..., 9981
```

游标分页返回：

```text
10000, 9999, 9998, ..., 9981
```

两种方案均返回 20 条记录，结果一致。

---

## 五、性能对比与结论

### 5.1 测试数据

| 指标 | OFFSET 分页 | 游标分页 |
|---|---:|---:|
| 总数据量 | 100,000 | 100,000 |
| 用户数据量 | 60,000 | 60,000 |
| 返回记录数 | 20 | 20 |
| 第一次耗时 | 11.6 ms | 0.245 ms |
| 第二次耗时 | 10.1 ms | 0.198 ms |
| 平均耗时 | 10.85 ms | 0.2215 ms |
| 实际扫描行数 | 50,020 | 20 |
| 使用索引 | 是 | 是 |
| 覆盖索引 | 是 | 是 |

### 5.2 性能计算

按本次两轮实验平均值计算：

```text
10.85 / 0.2215 ≈ 49.0
```

游标分页的执行时间约为 OFFSET 分页的 1/49。

耗时下降比例：

```text
(10.85 - 0.2215) / 10.85 × 100%
≈ 97.96%
```

### 5.3 测试结论

本次独立实验中：

- OFFSET 分页扫描了 50,020 条记录。
- 游标分页扫描了 20 条记录。
- 两种方案返回的数据完全一致。
- 游标分页显著减少了无效扫描。

**核心结论：SQL 优化不仅需要关注是否使用索引，还需要关注实际访问了多少记录。**

### 5.4 实验局限性

本次结果需要结合以下条件理解：

1. 每种方案只执行了两轮测量。
2. 两条 SQL 都只查询 `id`，属于覆盖索引场景。
3. 测试在独立实验数据库中完成，不是生产环境压力测试。
4. 游标值 `10001` 已经预先确定，未计入获得游标的成本。
5. 不代表所有业务查询或所有数据规模下都能达到 49 倍性能差距。

上述耗时来自 `EXPLAIN ANALYZE` 的实际执行时间，不是完整 HTTP 接口响应时间。

---

## 六、ResQ 预约历史游标分页实现

### 6.1 原有查询问题

原有接口：

```http
GET /api/reservations/me
```

通过以下调用链查询：

```text
ReservationController
        ↓
ReservationService
        ↓
ReservationMapper
        ↓
MySQL reservation
```

原有 SQL：

```sql
SELECT *
FROM reservation
WHERE user_id = ?
ORDER BY created_at DESC, id DESC;
```

该接口没有分页限制。

为兼容已有功能，Day23 保留原接口，并新增独立分页接口。

### 6.2 新增 HTTP 接口

```http
GET /api/reservations/me/page
```

第一页请求：

```http
GET /api/reservations/me/page?size=20
```

下一页请求：

```http
GET /api/reservations/me/page?size=20&cursorCreatedAt=2026-10-22T12:00:39&cursorId=56
```

其中：

| 参数 | 含义 |
|---|---|
| `size` | 每页返回条数，默认 20 |
| `cursorCreatedAt` | 上一页最后一条记录的创建时间 |
| `cursorId` | 上一页最后一条记录的主键 ID |

接口限制：

- `size` 必须在 1～100 之间。
- 两个游标必须同时提供或同时不提供。
- `cursorId` 必须大于 0。
- `userId` 从经过 JWT 验证的服务端请求属性中取得，不能由客户端自行指定。

### 6.3 为什么需要联合游标？

ResQ 的预约历史按以下顺序排列：

```sql
ORDER BY created_at DESC, id DESC
```

但是 `created_at` 并不保证唯一。

多条预约可能在同一秒创建。

如果只使用：

```sql
WHERE created_at < :cursorCreatedAt
```

就可能遗漏与上一页最后一条记录创建时间相同的其他预约。

因此采用：

```sql
WHERE user_id = :userId
  AND (
      created_at < :cursorCreatedAt
      OR (
          created_at = :cursorCreatedAt
          AND id < :cursorId
      )
  )
ORDER BY created_at DESC, id DESC
LIMIT :limit;
```

判断规则是：

1. 创建时间更早的记录应出现在后面。
2. 创建时间相同，则 ID 更小的记录出现在后面。

由于 `id` 唯一，`(created_at, id)` 能够形成稳定的排序边界。

### 6.4 MyBatis 实现

新增 Mapper 方法：

```java
List<Reservation> findPageByUserId(
        @Param("userId") Long userId,
        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
        @Param("cursorId") Long cursorId,
        @Param("limit") int limit);
```

对应 XML：

```xml
<select id="findPageByUserId" resultMap="reservation">
    SELECT
        id,
        user_id,
        slot_id,
        status,
        request_id,
        created_at,
        expire_at
    FROM reservation
    WHERE user_id = #{userId}

    <if test="cursorCreatedAt != null and cursorId != null">
        AND (
            created_at &lt; #{cursorCreatedAt}
            OR (
                created_at = #{cursorCreatedAt}
                AND id &lt; #{cursorId}
            )
        )
    </if>

    ORDER BY created_at DESC, id DESC
    LIMIT #{limit}
</select>
```

其中：

- MyBatis 的 `<if>` 用于动态生成游标条件。
- 第一页不提供游标，因此不会追加范围条件。
- XML 中的 `<` 需要写为 `&lt;`。
- 所有查询都保留 `user_id` 条件，保证用户数据隔离。

### 6.5 Service：size + 1 策略

为了知道是否还有下一页，Service 并不是只查询 `size` 条，而是查询：

```java
limit = size + 1;
```

例如客户端请求：

```text
size = 20
```

Service 查询最多 21 条。

- 如果查询到 21 条：`hasMore = true`，只返回前 20 条。
- 如果查询到不超过 20 条：`hasMore = false`。
- 如果 `hasMore = true`，使用本页最后一条记录的 `(createdAt, id)` 作为下一页游标。
- 如果 `hasMore = false`，返回两个 `null` 游标。

响应对象：

```java
public record ReservationPageVO(
        List<Reservation> records,
        boolean hasMore,
        LocalDateTime nextCursorCreatedAt,
        Long nextCursorId
) {}
```

### 6.6 实际修改文件

本次 Day23 分页实现涉及：

```text
src/main/java/com/resq/controller/ReservationController.java
src/main/java/com/resq/service/ReservationService.java
src/main/java/com/resq/mapper/ReservationMapper.java
src/main/java/com/resq/vo/ReservationPageVO.java
src/main/resources/mapper/ReservationMapper.xml
```

使用以下命令验证编译：

```bash
mvn -q -DskipTests compile
```

实际结果：编译成功。

本次未声称 Maven 全量测试套件通过。

---

## 七、HTTP 功能验收

### 7.1 测试环境

通过 ResQ 正式注册和登录接口创建 Day23 测试账号。

登录后在当前 WSL 终端保存 JWT：

```bash
export TOKEN=...
```

历史测试曾在本机加载自动注册与登录脚本。公开版用环境变量指定仓库外实际文件路径，不要求其他机器存在同名临时文件；此示例不是当前自动演示入口：

```bash
: "${RESQ_AUTH_ENV_FILE:?请先指定本机认证脚本路径}"
source "$RESQ_AUTH_ENV_FILE"
```

JWT 不写入项目文档，也不记录实际密钥或 Token 内容。原脚本路径保存在已忽略的本地文档快照中；本文未改动实验数据或结果。当前可复现的统一入口见 [演示说明](../demo.md)。

本次 HTTP 验收使用的测试数据：

| 数据 | ID |
|---|---:|
| 测试用户 | 27 |
| 测试资源 | 111 |
| 测试时隙 | 115、116 |
| 测试预约 | 55、56 |

这些是本次测试时的实际记录 ID，不要求其他环境复现时使用相同 ID。

### 7.2 无 JWT 请求

请求：

```bash
curl -i \
  'http://localhost:8080/api/reservations/me/page?size=1'
```

实际结果：

```text
HTTP/1.1 401
```

**结论：** 新分页接口受到现有 JWT 拦截器保护。

### 7.3 新用户查询空列表

注册并登录后执行：

```bash
curl -sS \
  -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/reservations/me/page?size=1'
```

实际结果：

```json
{
  "records": [],
  "hasMore": false,
  "nextCursorCreatedAt": null,
  "nextCursorId": null
}
```

**结论：** 空数据场景正常，返回格式符合设计。

### 7.4 第一页测试

使用测试账号分别预约时隙 115、116。

随后执行：

```http
GET /api/reservations/me/page?size=1
```

实际响应的关键字段：

```json
{
  "records": [
    {
      "id": 56,
      "userId": 27,
      "slotId": 116,
      "status": "PENDING",
      "createdAt": "2026-10-22T12:00:39",
      "expireAt": "2026-10-22T12:10:39"
    }
  ],
  "hasMore": true,
  "nextCursorCreatedAt": "2026-10-22T12:00:39",
  "nextCursorId": 56
}
```

说明：

- 返回 1 条记录，符合 `size=1`。
- `hasMore=true`，说明存在下一页。
- 成功生成时间和 ID 联合游标。

以上 JSON 省略了响应中的 `requestId`，其他字段来自实际测试。

### 7.5 第二页测试

使用第一页返回的真实游标：

```bash
curl -sS -G \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'size=1' \
  --data-urlencode 'cursorCreatedAt=2026-10-22T12:00:39' \
  --data-urlencode 'cursorId=56' \
  'http://localhost:8080/api/reservations/me/page'
```

实际响应的关键字段：

```json
{
  "records": [
    {
      "id": 55,
      "userId": 27,
      "slotId": 115,
      "status": "PENDING",
      "createdAt": "2026-10-22T12:00:39",
      "expireAt": "2026-10-22T12:10:39"
    }
  ],
  "hasMore": false,
  "nextCursorCreatedAt": null,
  "nextCursorId": null
}
```

### 7.6 相同时间戳边界验证

本次两条测试预约恰好具有完全相同的创建时间：

```text
id = 56
created_at = 2026-10-22 12:00:39

id = 55
created_at = 2026-10-22 12:00:39
```

第一页返回 ID 56。

下一页使用：

```sql
created_at < '2026-10-22 12:00:39'
OR (
    created_at = '2026-10-22 12:00:39'
    AND id < 56
)
```

因此第二页成功返回 ID 55。

**结论：** 在本次相同时间戳的测试场景中，联合游标正确区分了记录，没有重复或遗漏。

### 7.7 参数校验

实际测试：

```bash
curl -sS -o /dev/null \
  -w 'size=0: HTTP %{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/reservations/me/page?size=0'
```

结果：

```text
size=0: HTTP 400
```

只传递一个游标：

```bash
curl -sS -o /dev/null \
  -w 'missing cursor: HTTP %{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/reservations/me/page?cursorId=56'
```

结果：

```text
missing cursor: HTTP 400
```

**结论：** 两个实际测试的非法参数场景均正确返回 HTTP 400。

---

## 八、正式 ResQ 数据库索引分析

### 8.1 原有联合索引

正式 `reservation` 表已经存在：

```sql
KEY idx_user_status_created
(user_id, status, created_at)
```

该索引适合：

```sql
WHERE user_id = ?
  AND status = ?
ORDER BY created_at DESC;
```

但 Day23 的预约历史分页不限制 `status`：

```sql
WHERE user_id = ?
ORDER BY created_at DESC, id DESC;
```

原索引的逻辑排序顺序可以理解为：

```text
user_id
   ↓
status
   ↓
created_at
   ↓
隐含主键 id
```

由于 `status` 没有固定，同一用户的所有预约并不按 `created_at` 全局有序。

因此，旧联合索引未必能够直接满足新分页接口的排序要求。

### 8.2 创建新的联合索引

为预约历史分页新增：

```sql
CREATE INDEX idx_user_created_id
ON reservation(user_id, created_at DESC, id DESC);
```

该索引对应：

```text
user_id ASC
    ↓
created_at DESC
    ↓
id DESC
```

设计目标：

- 根据 `user_id` 查找当前用户。
- 根据 `(created_at, id)` 进行游标范围查询。
- 利用索引顺序满足 `ORDER BY`。
- 尽量避免额外排序。

注意，本次查询还需要 `slot_id`、`request_id`、`expire_at` 等字段，因此使用新二级索引时仍可能需要回表。

索引优化的目标不是消灭所有回表，而是降低整体执行成本。

### 8.3 优化器自动选择索引

新增索引后，执行：

```sql
EXPLAIN
SELECT
    id,
    user_id,
    slot_id,
    status,
    request_id,
    created_at,
    expire_at
FROM reservation
WHERE user_id = 27
  AND (
      created_at < '2026-10-22 12:00:39'
      OR (
          created_at = '2026-10-22 12:00:39'
          AND id < 56
      )
  )
ORDER BY created_at DESC, id DESC
LIMIT 2;
```

实际结果：

```text
type          = ref
possible_keys = PRIMARY,idx_user_status_created,idx_user_created_id
key           = idx_user_status_created
key_len       = 8
ref           = const
rows          = 2
Extra         = Using index condition; Using filesort
```

分析：

1. 新索引已经出现在 `possible_keys` 中，说明它是候选索引。
2. MySQL 仍然选择原有的 `idx_user_status_created`。
3. `key_len=8`，对应 `BIGINT NOT NULL` 类型的 `user_id`。
4. `Using index condition` 表示使用了索引条件下推。
5. `Using filesort` 表示仍然需要额外排序。

当前测试用户只有很少的预约记录。优化器可能认为使用原索引再排序的成本更低。

因此，不能只凭新增索引后仍然出现 `Using filesort`，就认为新索引没有作用。

### 8.4 强制使用新索引进行验证

执行：

```sql
EXPLAIN
SELECT
    id,
    user_id,
    slot_id,
    status,
    request_id,
    created_at,
    expire_at
FROM reservation FORCE INDEX (idx_user_created_id)
WHERE user_id = 27
  AND (
      created_at < '2026-10-22 12:00:39'
      OR (
          created_at = '2026-10-22 12:00:39'
          AND id < 56
      )
  )
ORDER BY created_at DESC, id DESC
LIMIT 2;
```

实际结果：

```text
type          = range
possible_keys = idx_user_created_id
key           = idx_user_created_id
key_len       = 21
ref           = NULL
rows          = 2
filtered      = 100.00
Extra         = Using index condition
```

与自动选择旧索引时相比：

| 指标 | 自动选择旧索引 | FORCE INDEX 新索引 |
|---|---|---|
| type | ref | range |
| key | idx_user_status_created | idx_user_created_id |
| key_len | 8 | 21 |
| rows（估计） | 2 | 2 |
| Using index condition | 有 | 有 |
| Using filesort | 有 | 无 |

**验证结果：新索引能够满足本次排序要求，避免额外 filesort。**

### 8.5 key_len = 21 的含义

当前新索引的字段类型为：

```text
user_id    BIGINT NOT NULL
created_at DATETIME NOT NULL
id         BIGINT NOT NULL
```

对应索引键长度：

```text
user_id    = 8 字节
created_at = 5 字节
id         = 8 字节

合计 = 21 字节
```

`key_len=21` 反映该执行计划使用的索引键长度。

但需要注意：

- `key_len` 不是扫描行数。
- `key_len` 不是性能评分。
- `key_len` 变大不意味着查询一定更快。
- 应结合 `type`、`key`、`rows`、`Extra` 以及实际执行时间判断。

### 8.6 最终索引选择

本次保留两个不同用途的联合索引：

```sql
idx_user_status_created
(user_id, status, created_at)
```

主要服务于按用户和状态筛选预约的场景。

```sql
idx_user_created_id
(user_id, created_at DESC, id DESC)
```

主要服务于不筛选状态的预约历史游标分页。

两个索引存在一定字段重叠，但对应的查询模式不同。

由于正式表当前数据量较小，新索引的实际大数据量性能收益尚未单独进行基准测试。

**本次不将 `FORCE INDEX` 固定写入 MyBatis 业务 SQL。**

是否进一步合并或删除索引，应根据后续真实查询频率、数据规模、写入开销和执行计划判断。

---

## 九、慢 SQL 排查与索引设计知识总结

### 9.1 慢 SQL 的基本排查顺序

遇到慢 SQL，可以按照以下顺序分析：

1. 确认实际 SQL、查询参数和数据量。
2. 使用 `EXPLAIN` 查看执行计划。
3. 重点关注 `type`、`key`、`rows`、`Extra`。
4. 使用 `EXPLAIN ANALYZE` 检查实际执行时间和实际处理行数。
5. 分析是否存在不合理的扫描、排序、回表或深分页。
6. 根据 `WHERE`、`ORDER BY` 等条件设计或调整索引。
7. 重新验证执行计划和性能，避免只凭经验判断。

### 9.2 EXPLAIN 重点字段

| 字段 | 含义 |
|---|---|
| `type` | 表的访问方式 |
| `possible_keys` | 候选索引 |
| `key` | 实际选择的索引 |
| `key_len` | 使用的索引键长度，单位为字节 |
| `ref` | 等值查找使用的比较对象 |
| `rows` | 优化器估计读取的行数 |
| `filtered` | 估计通过条件筛选的比例 |
| `Extra` | 额外执行信息 |

常见的 `Extra`：

- `Using index`：覆盖索引访问。
- `Using index condition`：索引条件下推。
- `Using filesort`：需要额外排序。

需要注意，`Using index condition` 不等于覆盖索引查询。

### 9.3 OFFSET 与游标分页的选择

OFFSET 分页优势：

- 实现简单。
- 支持按页码查询。
- 适用于数据量较小或浅分页场景。

OFFSET 分页局限：

- OFFSET 较大时可能扫描大量记录。
- 数据插入或删除后，不同页之间可能出现重复或遗漏。

游标分页优势：

- 能够利用合适的索引进行范围定位。
- 避免为到达深页而跳过大量记录。
- 适合连续滚动加载、时间线和历史记录查询。

游标分页局限：

- 需要保存上一页游标。
- 不方便直接跳转到任意页码。
- 必须保证排序规则与游标条件一致。
- 如果排序字段不唯一，通常需要增加唯一字段形成联合游标。
- 并发更新排序字段时仍需考虑分页一致性。

### 9.4 为什么索引不是越多越好？

新增索引可以加速部分查询，但也会带来：

- 更多磁盘空间占用。
- INSERT 时的额外索引维护。
- UPDATE 索引字段时的额外维护。
- DELETE 时的额外维护。
- 更多候选执行计划和索引管理成本。

因此，应根据实际业务 SQL 设计索引，而不是给每个字段都建立索引。

---

## 十、最终成果与结论

### 10.1 已完成内容

- [x] 创建独立 SQL 性能实验数据库。
- [x] 生成 10 万条模拟预约数据。
- [x] 验证目标用户拥有 6 万条记录。
- [x] 执行 OFFSET 深分页测试。
- [x] 执行游标分页测试。
- [x] 验证两种方案返回相同结果。
- [x] 使用 `EXPLAIN ANALYZE` 对比执行计划。
- [x] 完成两轮性能测量。
- [x] 在 ResQ 新增预约历史游标分页查询。
- [x] 实现分页参数校验和 `size + 1` 逻辑。
- [x] Maven 编译成功。
- [x] 验证 JWT 认证。
- [x] 验证空列表返回。
- [x] 验证第一页和第二页衔接。
- [x] 验证相同创建时间的联合游标处理。
- [x] 验证非法分页参数返回 HTTP 400。
- [x] 新增预约历史联合索引。
- [x] 通过 `FORCE INDEX` 的 EXPLAIN 验证新索引能避免额外排序。
- [x] 整理 Day23 性能基准测试报告。

### 10.2 核心结论

**实验层面：**

在本次 10 万条数据、已知游标、仅查询 ID 的对比中：

```text
OFFSET：
扫描 50020 条
平均约 10.85 ms

游标分页：
扫描 20 条
平均约 0.222 ms
```

游标分页通过索引范围定位，显著减少了无效扫描。

**项目层面：**

ResQ 新增预约历史游标分页接口：

```http
GET /api/reservations/me/page
```

采用：

```text
(user_id, created_at DESC, id DESC)
```

作为对应查询的索引设计。

通过 JWT 保证用户只能查询自己的预约历史，并使用 `(created_at, id)` 联合游标避免时间重复造成的分页遗漏。

**优化方法层面：**

SQL 性能优化应该形成完整闭环：

```text
发现查询问题
    ↓
分析 SQL 和索引
    ↓
使用 EXPLAIN / EXPLAIN ANALYZE
    ↓
设计优化方案
    ↓
实际运行验证
    ↓
记录结果与局限性
```

本次 Day23 完成了从独立 SQL 性能实验，到 Spring Boot/MyBatis 业务接口落地，再到执行计划验证的完整过程。

### 10.3 尚未完成或不在本次范围内的工作

- 正式 ResQ 表尚未进行大数据量 HTTP 性能基准测试。
- 尚未证明新索引在实际业务负载下能提升多少毫秒。
- 本次只验证了两条相同时间戳预约的分页衔接，未覆盖所有并发场景。
- 未运行项目完整自动化测试套件。
- 本次创建的测试用户、资源、时隙和预约记录尚待按需清理。

这些不影响 Day23 已完成的核心实验与功能验收，但在后续性能结论中应与已验证事实区分。

---

## 十一、相关代码与文档

项目涉及的主要源码：

```text
src/main/java/com/resq/controller/ReservationController.java
src/main/java/com/resq/service/ReservationService.java
src/main/java/com/resq/mapper/ReservationMapper.java
src/main/java/com/resq/domain/Reservation.java
src/main/java/com/resq/vo/ReservationPageVO.java
src/main/resources/mapper/ReservationMapper.xml
```

相关性能文档：

```text
docs/performance/resq-index-design.md
docs/performance/reservation-pagination-benchmark.md
```

**最终总结：**

> 本次优化的核心不是简单地把 OFFSET 改成游标，而是根据真实查询的过滤条件和排序规则设计索引，并用执行计划和实际结果验证优化是否有效。