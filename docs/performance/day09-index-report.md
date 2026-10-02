# Day 9 MySQL 索引实验记录

## 实验环境与数据

- 日期：2026-10-02
- MySQL 版本：未记录，后续可执行 `SELECT VERSION();` 补充
- 数据库：`resq`
- 实验表：`resource_slot_test`
- 数据生成方式：使用 Java `Day09DataGenerator` 生成 `docs/sql/day09-generated-data.sql`，再导入 MySQL
- 实际行数：10000
- 数据分布：
  - `resource_id`：1 ~ 100
  - 每个 `resource_id`：100 条
  - `start_time`：从 `2026-01-01 00:00:00` 开始，每条递增 1 小时
  - `end_time`：`start_time + 1 hour`
  - `status`：`AVAILABLE` / `RESERVED` 交替生成，总体各约 5000 条
- 本次单字段索引测试值：`resource_id = 50`
- `resource_id = 50` 对应数据量：100 条

---

## 实验一：resource_id 单字段索引

### 查询 SQL

```sql
SELECT *
FROM resource_slot_test
WHERE resource_id = 50;
```

### 无索引 EXPLAIN

当时除主键外，没有 `resource_id` 二级索引。

```text
id: 1
select_type: SIMPLE
table: resource_slot_test
partitions: NULL
type: ALL
possible_keys: NULL
key: NULL
key_len: NULL
ref: NULL
rows: 9862
filtered: 10.00
Extra: Using where
```

说明：

- `type = ALL`：发生全表扫描
- `key = NULL`：没有实际使用索引
- `rows = 9862`：优化器预计需要检查接近整张表的数据
- `Using where`：扫描数据后还需要根据 `resource_id = 50` 继续过滤

### 加索引后 EXPLAIN

索引定义：

```sql
CREATE INDEX idx_resource_id
ON resource_slot_test(resource_id);
```

EXPLAIN：

```text
id: 1
select_type: SIMPLE
table: resource_slot_test
partitions: NULL
type: ref
possible_keys: idx_resource_id
key: idx_resource_id
key_len: 8
ref: const
rows: 100
filtered: 100.00
Extra: NULL
```

### 对比

| 指标 | 无索引 | 加索引后 | 观察与解释 |
| --- | --- | --- | --- |
| type | ALL | ref | 从全表扫描变为普通索引等值查找 |
| key | NULL | idx_resource_id | MySQL 实际选择了 `resource_id` 索引 |
| rows | 9862 | 100 | 预计检查的数据从接近 10000 行下降到约 100 行 |
| Extra | Using where | NULL | 不再需要在大量扫描结果中继续过滤 |

结论：

对 `resource_id = 50` 这种等值查询建立 `resource_id` 二级索引后，MySQL 可以直接定位对应索引区域。实验中访问方式由 `ALL` 变为 `ref`，预计检查行数由 9862 降到 100，说明该索引能够有效服务按资源查询时隙的场景。

---

## 实验二：联合索引

### 查询目标

查询指定资源的全部时隙，并按开始时间排序：

```sql
SELECT *
FROM resource_slot_test
WHERE resource_id = 50
ORDER BY start_time;
```

### 创建联合索引前

此时已有：

```text
PRIMARY(id)
idx_resource_id(resource_id)
```

EXPLAIN：

```text
id: 1
select_type: SIMPLE
table: resource_slot_test
partitions: NULL
type: ref
possible_keys: idx_resource_id
key: idx_resource_id
key_len: 8
ref: const
rows: 100
filtered: 100.00
Extra: Using filesort
```

说明：

`idx_resource_id` 可以快速定位 `resource_id = 50` 的约 100 条记录，但是该索引没有按照 `start_time` 继续排序，因此 MySQL 还需要额外完成 `ORDER BY start_time`。

### 创建联合索引

本次实验实际创建：

```sql
CREATE INDEX idx_resource_id_AND_start_time
ON resource_slot_test(resource_id, start_time);
```

联合索引内部顺序可以理解为：

```text
先按照 resource_id 排序
同一个 resource_id 内再按照 start_time 排序
```

### 创建联合索引后 EXPLAIN

```text
id: 1
select_type: SIMPLE
table: resource_slot_test
partitions: NULL
type: ref
possible_keys: idx_resource_id,idx_resource_id_AND_start_time
key: idx_resource_id_AND_start_time
key_len: 8
ref: const
rows: 100
filtered: 100.00
Extra: NULL
```

### 对比

| 指标 | 创建前 | 创建后 | 观察与解释 |
| --- | --- | --- | --- |
| type | ref | ref | 两种索引都能通过 `resource_id` 快速定位 |
| key | idx_resource_id | idx_resource_id_AND_start_time | 优化器选择了联合索引 |
| rows | 100 | 100 | 两者筛选 `resource_id` 的能力相近，因此预计行数没有明显变化 |
| Extra | Using filesort | NULL | 联合索引可以利用 `start_time` 的有序性，避免额外排序 |

实际选择的索引：

```text
idx_resource_id_AND_start_time
```

本实验中联合索引的主要收益不是进一步减少 `rows`，而是消除了 `Using filesort`。

结论：

对于：

```sql
WHERE resource_id = ?
ORDER BY start_time
```

查询，`(resource_id, start_time)` 比单独的 `(resource_id)` 更符合过滤和排序需求。

---

## 实验三：最左前缀无法有效利用示例

现有联合索引：

```text
(resource_id, start_time)
```

测试 SQL：

```sql
SELECT *
FROM resource_slot_test
WHERE start_time = '2026-01-03 01:00:00';
```

EXPLAIN：

```text
id: 1
select_type: SIMPLE
table: resource_slot_test
partitions: NULL
type: ALL
possible_keys: NULL
key: NULL
key_len: NULL
ref: NULL
rows: 9862
filtered: 10.00
Extra: Using where
```

原因：

联合索引首先按照 `resource_id` 排序，只有在相同 `resource_id` 内部才继续按照 `start_time` 排序。

该查询跳过最左列 `resource_id`，直接查询 `start_time`，因此不能按照 `(resource_id, start_time)` 的索引顺序直接定位。

本次实际表现为：

```text
type = ALL
key = NULL
rows = 9862
```

这可以作为 Day 9 的一个索引无法有效利用的实例。

---

## 总结

本次实验支持以下结论：

1. 索引不是修改原表排序，而是数据库额外维护的有序数据结构。
2. 对高频等值查询建立合适索引，可以显著减少需要检查的数据范围。
3. 联合索引的字段顺序应根据实际查询模式设计。
4. `(resource_id, start_time)` 可以同时服务 `resource_id` 等值过滤和 `start_time` 排序。
5. 联合索引满足最左前缀时更容易被有效利用；跳过最左列后可能无法直接定位。
6. `EXPLAIN` 中本次主要关注：
   - `type`
   - `key`
   - `rows`
   - `Extra`
7. `rows` 是优化器估计值，不是实际精确扫描行数。
8. 本次没有使用执行耗时作为主要性能证据；若需要严格比较耗时，应增加更大的数据量并进行多轮实际测量。