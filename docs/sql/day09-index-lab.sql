-- Day 9：MySQL 索引学习实验
-- 只在 resource_slot_test 实验表上操作；不要修改或清理正式业务表。
-- 按阶段补全并执行 SQL，每次保存 EXPLAIN 到实验报告。
-- TODO：记录 MySQL 版本、数据库名称，确认当前数据库适合实验。

-- 1. 创建实验表
-- TODO：自行编写 resource_slot_test 的建表 SQL。
-- 建议字段：id、resource_id、start_time、end_time、status。
-- TODO：选择字段类型、主键及必要约束；初始阶段不添加业务二级索引。
USE resq;
-- DROP TABLE IF EXISTS resource_slot_test;

-- CREATE TABLE resource_slot_test (
--     id BIGINT PRIMARY KEY AUTO_INCREMENT,
--     resource_id BIGINT NOT NULL,
--     start_time DATETIME NOT NULL,
--     end_time DATETIME NOT NULL,
--     status VARCHAR(20) NOT NULL
-- );
-- 2. 准备至少 10000 条数据
-- TODO：选择 Java 生成或 SQL 生成方式，并记录导入步骤。
-- TODO：让 resource_id 有重复值，让时间和状态有合理分布。
-- TODO：检查总行数、resource_id 分布，以及测试值确实存在。

-- 3. 无索引 EXPLAIN
-- TODO：编写按 resource_id 查询的 SQL，选一个确实存在的值。
-- TODO：执行 EXPLAIN，保存完整结果及 key、rows、Extra。
-- 3. 无索引 EXPLAIN

-- EXPLAIN
-- SELECT *
-- FROM resource_slot_test
-- WHERE resource_id = 50;
-- 4. 单字段索引 EXPLAIN
-- TODO：自行编写 resource_id 单字段索引的创建 SQL。
-- TODO：对第 3 步同一条查询执行 EXPLAIN，保存并对比结果。
-- CREATE INDEX idx_resource_id
-- ON resource_slot_test(resource_id);

-- EXPLAIN
-- SELECT *
-- FROM resource_slot_test
-- WHERE resource_id = 50;

-- 5. 联合索引实验
-- TODO：编写按 resource_id 筛选、按 start_time 范围过滤或排序的查询。
-- TODO：保存创建联合索引前的 EXPLAIN。
-- CREATE INDEX idx_r_i_s_t
-- ON resource_slot_test(resource_id,start_time);
EXPLAIN
SELECT *
FROM resource_slot_test
WHERE resource_id = 50
ORDER BY start_time;
-- TODO：自行编写 (resource_id, start_time) 联合索引的创建 SQL。
-- TODO：对同一查询再次 EXPLAIN，观察实际选择的索引和 Extra。
-- TODO：记录各阶段存在的索引，解释单字段与联合索引共存的影响。
-- TODO：可选：设计只使用 start_time 的查询，观察与完整前缀查询的差异。

-- 6. 整理实验
-- TODO：记录最终索引清单和实验结论。
-- TODO：如需清理，先确认仅针对 resource_slot_test，再自行编写清理 SQL。
