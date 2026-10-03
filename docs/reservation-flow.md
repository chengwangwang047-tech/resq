# Reservation Flow

登录
→ 查看资源
→ 查看时隙
→ 创建预约
→ PENDING
→ CONFIRMED / CANCELLED

## Day10：预约的数据库并发兜底

当前 Java 层仍是接口与内存练习；下面是接入 MySQL InnoDB 时的设计约定，不代表已实现持久化。

### 为什么先查再写会出错

A、B 都可能先 SELECT 到同一时隙为 AVAILABLE，再分别通过 Java 判断并写入预约。查询与写入之间存在竞态窗口。单纯把这两步放进事务也不能消除该窗口；Day6 的 synchronized 只能协调同一 JVM 中遵守同一把锁的线程，不能保护多个服务实例。

### 用条件 UPDATE 决定谁抢到时隙

以下是服务层控制的 SQL 流程示意，问号表示绑定参数，分支由应用判断：

```sql
START TRANSACTION;
UPDATE resource_slot
SET status = 'RESERVED'
WHERE id = ? AND status = 'AVAILABLE';
-- 立即读取这条 UPDATE 的 affected rows（例如 JDBC executeUpdate 返回值）。
-- 等于 1：才继续下面的 INSERT。
-- 等于 0：ROLLBACK，返回时隙不可预约；可能已被占用，也可能 id 不存在。
INSERT INTO reservation (user_id, slot_id, status, request_id, created_at)
VALUES (?, ?, 'PENDING', ?, NOW());
COMMIT;
```

主键 id 定位一个时隙，条件检查与状态修改在同一条 UPDATE 中完成。两个请求竞争同一行时，后来的请求可能等待；前者提交后，后者重新判断 AVAILABLE 条件，通常得到 0。若前者回滚，后者可能成功。因此应以 UPDATE 返回值判断抢占结果，不能依赖先前 SELECT 的结果。

### 事务边界与失败处理

UPDATE 时隙与 INSERT 预约必须使用同一数据库连接、同一事务，中间不能自动提交。INSERT 失败、唯一约束冲突或其他异常都应回滚，恢复此次 UPDATE 前的时隙状态；只有 COMMIT 成功才向用户报告预约成功。事务内不调用耗时的外部服务。死锁或锁等待超时应先回滚整笔事务，再按策略有限重试，重试使用同一个 request_id。

取消预约时也要在同一事务中校验并更新对应预约状态、释放它实际占用的时隙，不能仅凭 slot_id 无条件释放，以免重复取消释放了后续的新预约。

### 唯一约束负责最后一道防线

- Day08 的 reservation.request_id 已有 UNIQUE，但允许 NULL。真正接入预约服务时应要求非空（并考虑 NOT NULL），保证一次逻辑请求只有一个标识。重复请求应读取已有记录，核对 user_id、slot_id 等是否一致，再返回已有结果；不能换一个 request_id 重复创建。request_id 唯一解决重复请求，不解决不同请求争抢同一时隙。
- 业务约束是一个时隙最多一条有效预约（PENDING / CONFIRMED）。取消或过期后还要保留历史，因此不能直接给 reservation.slot_id 加 UNIQUE，否则时隙无法再次预约。也不能用 UNIQUE(slot_id, status)：它既允许 PENDING 与 CONFIRMED 并存，又会限制多条取消历史。
- 接入数据库时可用可空生成列 active_slot_id（有效状态时取 slot_id，其余为 NULL）加 UNIQUE，利用 MySQL 唯一索引允许多个 NULL 来保留历史；或使用以 slot_id 为主键的独立占用表。具体迁移应在确认有效状态规则并清理旧数据后执行，本次仅记录方案，不改表。

条件 UPDATE 负责抢占，事务负责时隙与预约共同成功或失败，唯一约束负责阻止绕过正常服务路径的重复数据。所有创建入口都应遵守同一协议；确认、取消、过期也要保持占用状态一致。

### 接入时的验收场景

1. 两个连接竞争同一 AVAILABLE 时隙：只提交一条有效预约，另一个收到冲突。
2. UPDATE 成功后故意让 INSERT 失败：回滚后时隙仍为 AVAILABLE。
3. 相同 request_id 重试：返回同一预约；携带不同参数时拒绝复用。
4. 取消后重新预约：保留取消历史，仍只能有一条有效预约。
