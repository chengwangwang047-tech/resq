-- =========================================================
-- Day 08 · MySQL 基础练习
-- ResQ
-- =========================================================
USE resq;

DROP TABLE IF EXISTS reservation;

DROP TABLE IF EXISTS resource_slot;

DROP TABLE IF EXISTS compute_resource;

DROP TABLE IF EXISTS user;

-- =========================================================
-- Part 1 · 建表 CREATE TABLE
-- 练习点：字段类型、PRIMARY KEY、AUTO_INCREMENT、
--        NOT NULL、UNIQUE
-- =========================================================
-- 1. 创建 user 表
-- 字段：
-- id          BIGINT，主键，自增
-- username    VARCHAR(50)，不能为空
-- password    VARCHAR(100)，不能为空
-- email       VARCHAR(100)，唯一
-- created_at  DATETIME，不能为空
-- TODO:
CREATE TABLE
    user (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        username VARCHAR(50) NOT NULL,
        password VARCHAR(100) NOT NULL,
        email VARCHAR(100) UNIQUE,
        created_at DATETIME NOT NULL
    );

-- 2. 创建 compute_resource 表
-- 字段：
-- id          BIGINT，主键，自增
-- name        VARCHAR(100)，不能为空
-- type        VARCHAR(20)，不能为空
-- status      VARCHAR(20)，不能为空
-- created_at  DATETIME，不能为空
-- TODO:
CREATE TABLE
    compute_resource (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        name VARCHAR(100) NOT NULL,
        type VARCHAR(20) NOT NULL,
        status VARCHAR(20) NOT NULL,
        created_at DATETIME NOT NULL
    );

-- 3. 创建 resource_slot 表
CREATE TABLE
    resource_slot (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        resource_id BIGINT NOT NULL,
        slot_start DATETIME NOT NULL,
        slot_end DATETIME NOT NULL,
        status VARCHAR(20) NOT NULL
    );

-- 4. 创建 reservation 表
CREATE TABLE
    reservation (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        user_id BIGINT NOT NULL,
        slot_id BIGINT NOT NULL,
        status VARCHAR(20) NOT NULL,
        request_id VARCHAR(100) UNIQUE,
        created_at DATETIME NOT NULL
    );

-- =========================================================
-- Part 2 · 插入数据 INSERT
-- =========================================================
-- 5. 插入 3 个用户
INSERT INTO
    user (username, password, email, created_at)
VALUES
    ('alice', '123456', 'alice@test.com', NOW()),
    ('bob', '123456', 'bob@test.com', NOW()),
    ('tom', '123456', 'tom@test.com', NOW());

-- 6. 插入 4 个计算资源
INSERT INTO
    compute_resource (name, type, status, created_at)
VALUES
    ('GPU-A100-01', 'GPU', 'AVAILABLE', NOW()),
    ('GPU-A100-02', 'GPU', 'AVAILABLE', NOW()),
    ('GPU-4090-01', 'GPU', 'BUSY', NOW()),
    ('CPU-01', 'CPU', 'AVAILABLE', NOW());

-- 7. 插入 resource_slot
INSERT INTO
    resource_slot (resource_id, slot_start, slot_end, status)
VALUES
    (
        1,
        '2026-09-30 09:00:00',
        '2026-09-30 10:00:00',
        'AVAILABLE'
    ),
    (
        2,
        '2026-09-30 10:00:00',
        '2026-09-30 11:00:00',
        'AVAILABLE'
    ),
    (
        3,
        '2026-09-30 11:00:00',
        '2026-09-30 12:00:00',
        'AVAILABLE'
    ),
    (
        4,
        '2026-09-30 13:00:00',
        '2026-09-30 14:00:00',
        'AVAILABLE'
    );

-- 8. 插入 reservation
-- alice 两条
-- bob 一条
-- tom 没有预约
INSERT INTO
    reservation (user_id, slot_id, status, request_id, created_at)
VALUES
    (1, 1, 'CONFIRMED', 'REQ-001', NOW()),
    (1, 2, 'PENDING', 'REQ-002', NOW()),
    (2, 3, 'CONFIRMED', 'REQ-003', NOW());

-- =========================================================
-- Part 3 · 基础查询 SELECT / WHERE
-- =========================================================
-- 9. 查询所有 compute_resource
-- TODO:
SELECT
    *
FROM
    compute_resource;

-- 10. 只查询资源的 id、name、type
-- TODO:
SELECT
    id,
    name,
    type
FROM
    compute_resource;

-- 11. 查询所有 GPU 类型资源
-- TODO:
SELECT
    *
FROM
    compute_resource
WHERE
    type = 'GPU';

-- 12. 查询 status 为 AVAILABLE 的 resource_slot
-- TODO:
SELECT
    *
FROM
    resource_slot
WHERE
    status = 'AVAILABLE';

-- =========================================================
-- Part 4 · 排序 ORDER BY / 分页 LIMIT
-- =========================================================
-- 13. 查询所有资源，按照 id 从大到小排列
-- TODO:
SELECT
    *
FROM
    compute_resource
ORDER BY
    id DESC;

-- 14. 查询 GPU 类型资源，按照 id 从大到小，只取前 2 条
-- TODO:
SELECT
    *
FROM
    compute_resource
WHERE
    type = 'GPU'
ORDER BY
    id DESC
LIMIT
    2;

-- 15. 查询 reservation，
-- 跳过前 2 条，再取 2 条
-- 提示：LIMIT offset, count
-- TODO:
SELECT
    *
FROM
    reservation
ORDER BY
    id DESC
LIMIT
    2, 2;

-- =========================================================
-- Part 5 · 修改 UPDATE / 删除 DELETE
-- =========================================================
-- 16. 把 id = 1 的 compute_resource 的 status 改为 BUSY
-- TODO:
UPDATE compute_resource
SET
    status = 'BUSY'
WHERE
    id = 1;

-- 17. 把某个 reservation 的 status 改为 CONFIRMED
-- TODO:
UPDATE reservation
SET
    status = 'CONFIRMED'
WHERE
    id = 2;

-- 18. 删除一条你专门为测试创建的数据
-- 注意 WHERE
INSERT INTO
    reservation (user_id, slot_id, status, request_id, created_at)
VALUES
    (2, 4, 'PENDING', 'REQ-DELETE-TEST', NOW());

DELETE FROM reservation
WHERE
    request_id = 'REQ-DELETE-TEST';

-- TODO:
-- =========================================================
-- Part 6 · 聚合 COUNT / GROUP BY / HAVING
-- =========================================================
-- 19. 统计 compute_resource 一共有多少条记录
-- TODO:
SELECT
    COUNT(*)
FROM
    compute_resource;

-- 20. 按 type 分组，统计每种资源的数量
-- 输出 type 和 resource_count
-- TODO:
SELECT
    type,
    COUNT(*) AS resource_count
FROM
    compute_resource
GROUP BY
    type;

-- 21. 按 reservation.status 分组，
-- 统计每种预约状态的数量
-- TODO:
SELECT
    status,
    COUNT(*) AS reservation_count
FROM
    reservation
GROUP BY
    status;

-- 22. 按 type 分组，
-- 只显示数量大于 1 的资源类型
-- TODO:
SELECT
    type,
    COUNT(*) AS resource_count
FROM
    compute_resource
GROUP BY
    type
HAVING
    resource_count > 1;

-- =========================================================
-- Part 7 · INNER JOIN
-- =========================================================
-- 23. 查询所有存在用户匹配的预约
-- 输出：
-- reservation.id
-- user.username
-- reservation.status
-- TODO:
SELECT
    reservation.id,
    user.username,
    reservation.status
FROM
    user
    INNER JOIN reservation ON reservation.user_id = user.id;

-- 24. 查询预约以及对应的 slot_id、资源名称
-- 需要：
-- reservation
-- resource_slot
-- compute_resource
--
-- 先尝试自己思考三张表怎么连接
-- TODO:
SELECT
    reservation.id,
    reservation.slot_id,
    compute_resource.name
FROM
    reservation
    INNER JOIN resource_slot ON reservation.slot_id = resource_slot.id
    INNER JOIN compute_resource ON resource_slot.resource_id = compute_resource.id;

-- =========================================================
-- Part 8 · LEFT JOIN
-- =========================================================
-- 25. 查询所有用户及其预约
-- 即使用户没有预约也必须显示
-- 输出：
-- username
-- reservation.id
-- reservation.status
-- TODO:
SELECT
    reservation.id,
    user.username,
    reservation.status
FROM
    user
    LEFT JOIN reservation ON reservation.user_id = user.id;

-- 26. 查询所有 compute_resource 以及它们对应的 slot
-- 即使某个资源还没有 slot，也必须显示
-- TODO:
SELECT
    *
FROM
    compute_resource
    LEFT JOIN resource_slot ON resource_slot.resource_id = compute_resource.id;

-- =========================================================
-- Part 9 · 综合练习
-- =========================================================
-- 27. 查询每个用户有多少条预约
-- 包括没有预约的用户
--
-- 思考：
-- INNER JOIN 还是 LEFT JOIN？
-- COUNT 应该数什么？
-- TODO:
SELECT
    user.username,
    COUNT(reservation.id) AS reservation_count
FROM
    user
    LEFT JOIN reservation ON user.id = reservation.user_id
GROUP BY
    user.id,
    user.username;

-- 28. 查询预约数量 >= 2 的用户
-- 输出 username 和 reservation_count
--
-- 思考：
-- JOIN
-- GROUP BY
-- HAVING
-- TODO:
SELECT
    user.username,
    COUNT(reservation.id) AS reservation_count
FROM
    user
    INNER JOIN reservation ON user.id = reservation.user_id
GROUP BY
    user.username
HAVING
    reservation_count >= 2;