# ResQ 索引设计记录

本文件记录 Day 9 对 ResQ 真实业务表的索引设计与验证。

设计原则：

> 先确定实际业务查询和数据约束，再决定需要什么索引；不是看到字段就直接建立索引。

---

## 现状调查

### 数据库

- 数据库：`resq`
- MySQL 版本：本次未记录
- 当前正式业务表数据量较小，主要为种子数据
- 因此本次重点验证执行计划和索引选择，不把少量数据下的执行耗时作为性能结论

### resource_slot

实际字段：

```text
id           BIGINT       NOT NULL PRIMARY KEY AUTO_INCREMENT
resource_id  BIGINT       NOT NULL
slot_start   DATETIME     NOT NULL
slot_end     DATETIME     NOT NULL
status       VARCHAR(20)  NOT NULL
```

### reservation

实际字段：

```text
id          BIGINT        NOT NULL PRIMARY KEY AUTO_INCREMENT
user_id     BIGINT        NOT NULL
slot_id     BIGINT        NOT NULL
status      VARCHAR(20)   NOT NULL
request_id  VARCHAR(100)  NULL UNIQUE
created_at  DATETIME      NOT NULL
```

### 初始已有索引

`resource_slot`：

```text
PRIMARY(id)
```

`reservation`：

```text
PRIMARY(id)
UNIQUE(request_id)
```

外键约束本次没有单独核验。

---

# resource_slot：联合唯一索引

## 字段名称核对

原设计中写作：

```text
(resource_id, start_time)
```

真实表字段为：

```text
(resource_id, slot_start)
```

因此实际索引使用：

```text
(resource_id, slot_start)
```

---

## 对应业务场景

`resource_slot` 表表示某个计算资源的可预约时间段。

业务规则：

> 同一个资源在同一个开始时间只能存在一个 slot。

允许：

```text
resource_id = 1, slot_start = 10:00
resource_id = 2, slot_start = 10:00
```

但不应允许：

```text
resource_id = 1, slot_start = 10:00
resource_id = 1, slot_start = 10:00
```

因此唯一性应该作用于：

```text
resource_id + slot_start
```

而不是仅对 `slot_start` 唯一。

---

## 重复数据检查

创建唯一索引前执行：

```sql
SELECT
    resource_id,
    slot_start,
    COUNT(*) AS count
FROM resource_slot
GROUP BY resource_id, slot_start
HAVING COUNT(*) > 1;
```

实际结果：

```text
Empty set
```

说明当前不存在重复的 `(resource_id, slot_start)` 组合，可以建立联合唯一索引。

---

## 最终索引

```sql
CREATE UNIQUE INDEX uk_resource_slot_start
ON resource_slot(resource_id, slot_start);
```

`SHOW INDEX FROM resource_slot` 验证：

```text
PRIMARY:
    id

uk_resource_slot_start:
    Seq_in_index = 1 → resource_id
    Seq_in_index = 2 → slot_start
    Non_unique = 0
    Index_type = BTREE
```

`Non_unique = 0` 表示该索引要求组合唯一。

---

## 字段顺序与设计理由

联合索引：

```text
(resource_id, slot_start)
```

排序逻辑：

```text
先 resource_id
再 slot_start
```

它适合类似：

```sql
SELECT *
FROM resource_slot
WHERE resource_id = ?
ORDER BY slot_start;
```

或：

```sql
SELECT *
FROM resource_slot
WHERE resource_id = ?
AND slot_start = ?;
```

同时数据库层面可以保证：

```text
同一资源 + 同一开始时间
```

不会重复。

---

## 性能验证证据

正式 `resource_slot` 数据规模较小，本次没有用正式表进行大规模前后性能比较。

对应的性能实验已经在 `resource_slot_test` 的 10000 条数据上完成：

```text
只有 resource_id 索引：
key   = idx_resource_id
rows  = 100
Extra = Using filesort

(resource_id, start_time) 联合索引：
key   = idx_resource_id_AND_start_time
rows  = 100
Extra = NULL
```

说明联合索引可以在按资源筛选后继续利用时间字段的有序性，避免额外排序。

---

## 写入成本与适用范围

新增该索引后：

- INSERT 时需要额外维护索引
- 修改 `resource_id` 或 `slot_start` 时需要维护索引
- UNIQUE 约束还需要进行重复检查
- 会额外占用存储空间

但该索引同时承担：

1. 资源时隙查询
2. `(resource_id, slot_start)` 组合唯一约束

因此具有明确业务价值。

### 最终决定

保留：

```text
UNIQUE(resource_id, slot_start)
```

原因：

> 同时服务业务查询和数据库最终唯一性约束。

---

# reservation：联合索引

## 实际字段

```text
user_id
status
created_at
```

与候选设计一致。

---

## 对应业务场景

典型业务：

> 用户查看自己的某种状态的预约，并按照创建时间从新到旧展示。

实际测试 SQL：

```sql
SELECT *
FROM reservation
WHERE user_id = 1
AND status = 'CONFIRMED'
ORDER BY created_at DESC;
```

---

## 建立索引前

EXPLAIN：

```text
type          = ALL
possible_keys = NULL
key           = NULL
rows          = 3
filtered      = 33.33
Extra         = Using where; Using filesort
```

执行过程可以理解为：

```text
扫描 reservation
        ↓
根据 user_id 过滤
        ↓
根据 status 过滤
        ↓
再按照 created_at 排序
```

---

## 索引设计

创建：

```sql
CREATE INDEX idx_user_status_created
ON reservation(user_id, status, created_at);
```

字段顺序：

```text
user_id
   ↓
status
   ↓
created_at
```

设计原因：

查询为：

```sql
WHERE user_id = ?
AND status = ?
ORDER BY created_at DESC
```

前两个字段承担等值筛选：

```text
user_id = 常量
status = 常量
```

在相同：

```text
user_id + status
```

范围内，`created_at` 已经按照索引顺序排列，因此可以继续用于排序。

---

## 建立索引后

EXPLAIN：

```text
type          = ref
possible_keys = idx_user_status_created
key           = idx_user_status_created
ref           = const,const
rows          = 2
filtered      = 100.00
Extra         = Backward index scan
```

解释：

### type = ref

从全表扫描变为普通索引等值查找。

### key = idx_user_status_created

MySQL 实际选择了新建的联合索引。

### ref = const,const

表示联合索引前两个用于定位的字段：

```text
user_id
status
```

分别与查询中的固定常量：

```text
1
'CONFIRMED'
```

进行等值比较。

### Backward index scan

SQL 要求：

```sql
ORDER BY created_at DESC
```

联合索引中的 `created_at` 已经有序，因此 MySQL 可以从索引的一端反向扫描，而不需要额外执行 filesort。

---

## 前后对比

| 指标 | 建索引前 | 建索引后 | 说明 |
| --- | --- | --- | --- |
| type | ALL | ref | 从全表扫描变为索引等值访问 |
| key | NULL | idx_user_status_created | 联合索引被实际选择 |
| rows | 3 | 2 | 当前正式表数据太少，不作为主要性能证据 |
| Extra | Using where; Using filesort | Backward index scan | 消除了额外排序 |

当前 `reservation` 数据量非常小，因此：

> 不将 `rows: 3 → 2` 作为性能提升证据。

本次更有意义的变化是：

```text
ALL
→ ref

key=NULL
→ idx_user_status_created

Using filesort
→ Backward index scan
```

---

## 不筛选 status 时

例如：

```sql
SELECT *
FROM reservation
WHERE user_id = ?
ORDER BY created_at DESC;
```

本次尚未单独执行 EXPLAIN 验证。

根据当前索引结构：

```text
(user_id, status, created_at)
```

`user_id` 满足最左前缀，因此可以用于筛选用户。

但由于中间的 `status` 没有固定，不能直接假设 `created_at` 能像：

```sql
WHERE user_id = ?
AND status = ?
ORDER BY created_at
```

一样完整承担排序。

该场景后续应单独使用 EXPLAIN 验证，再决定是否需要：

```text
(user_id, created_at)
```

等额外索引。

当前不提前创建，避免无依据增加索引。

---

## 写入成本与适用范围

该联合索引适合：

```sql
WHERE user_id = ?
AND status = ?
ORDER BY created_at
```

增加索引后：

- INSERT reservation 时需要维护索引
- 修改 `user_id`、`status` 或 `created_at` 时需要维护索引
- 占用额外存储空间

因此只有在该查询确实属于主要业务访问路径时才值得保留。

当前 ResQ 中“用户按状态查看自己的预约记录”属于合理业务需求，因此暂时保留。

---

# 冗余索引评估

| 已有索引 | 支持的查询或约束 | 与其他索引的重叠 | 验证证据 | 结论 |
| --- | --- | --- | --- | --- |
| `resource_slot.PRIMARY(id)` | 按 slot id 定位；主键/聚簇索引 | 与联合唯一索引职责不同 | SHOW INDEX | 保留 |
| `uk_resource_slot_start(resource_id, slot_start)` | 资源时隙查询 + 组合唯一性 | 不与 PRIMARY 重复 | 重复检查 + SHOW INDEX | 保留 |
| `reservation.PRIMARY(id)` | 按 reservation id 查询 | 与其他索引职责不同 | SHOW INDEX | 保留 |
| `reservation.UNIQUE(request_id)` | 请求唯一性、后续请求幂等 | 与业务查询索引职责不同 | SHOW INDEX | 保留 |
| `idx_user_status_created(user_id, status, created_at)` | 用户按状态查询预约，并按创建时间排序 | 不与 PRIMARY/request_id 重复 | EXPLAIN 前后对比 | 保留 |

### 当前结论

目前没有发现必须删除的真实业务冗余索引。

没有为了完成“删除冗余索引”这一任务而强行删除有效索引。

判断标准是：

> 每一个索引都必须能够说明它服务的查询或数据约束。

例如以后如果额外出现：

```sql
INDEX(user_id)
```

而已经存在：

```text
(user_id, status, created_at)
```

则 `(user_id)` 可能与联合索引最左前缀产生较大重叠，需要结合真实查询和 EXPLAIN 再判断是否保留。

---

# Day 9 最终结论

当前 ResQ 索引设计：

```text
resource_slot
├── PRIMARY(id)
└── UNIQUE(resource_id, slot_start)

reservation
├── PRIMARY(id)
├── UNIQUE(request_id)
└── INDEX(user_id, status, created_at)
```

每个索引目前都有明确职责：

```text
PRIMARY
→ 主键定位

UNIQUE(resource_id, slot_start)
→ 时隙组合唯一性 + 资源时隙查询

UNIQUE(request_id)
→ 请求唯一性 / 后续幂等保障

(user_id, status, created_at)
→ 用户预约列表过滤和排序
```

当前没有无使用理由的冗余索引需要删除。