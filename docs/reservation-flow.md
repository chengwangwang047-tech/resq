# 预约业务流程

本说明以当前 src/main 下的 Controller、Service、Mapper 和事务实现为准。早期内存模型与事务设计原文已归档，见 [学习归档索引](learning-archive.md)。

## 创建预约

1. JWT 拦截器校验身份，Controller 取得服务端用户 ID，校验 slotId 与 X-Request-Id。
2. ReservationLockService 先从 MySQL 查询合法重试；新请求执行用户限流、登记 Redis PROCESSING，再获取对应时隙的 Redisson 锁。
3. ReservationService.create 在事务中重新检查 requestId，以条件 SQL 把 AVAILABLE 且 slot_end > NOW(3) 的时隙更新为 RESERVED。受影响行数必须为 1。
4. 插入 PENDING 预约并设置 expireAt；插入失败会回滚时隙抢占。登记提交后缓存删除，发布应用事件。
5. 返回事务服务后更新 Redis SUCCESS。幂等请求仍以 MySQL 记录为最终依据；部分 Redis/Redisson 连接故障使用既有 MySQL 兜底分支。

Spring 提交后事件监听器负责发送通知和超时消息。当前发送失败存在既有可靠性边界，不能据此宣称端到端消息不丢失。缓存与数据库也不是原子更新。

## 状态变化

| 入口 | 条件与结果 |
| --- | --- |
| 创建 | 可预约时隙被抢占后，插入 PENDING |
| 确认 | 本人、PENDING 且 expire_at > NOW(3)，条件更新为 CONFIRMED |
| 取消 | 本人、PENDING，条件更新为 CANCELLED，再释放 RESERVED 时隙；整段使用一个事务 |
| 超时 | PENDING 且 expire_at <= NOW(3)，条件更新为 EXPIRED 并释放时隙 |

重复取消、确认后取消或状态竞争导致条件更新失败，返回 409，不能再次释放新预约占用的时隙。真正的释放失败继续返回安全的 500 并回滚。确认竞争的更详细边界仍以现有代码和审计记录为准。

RabbitMQ 延迟消息与到期扫描共同触发超时处理；扫描用于补偿，不能据此扩大为所有 MQ 事件的可靠投递保证。历史预约会保留，不能通过简单 UNIQUE(slot_id) 限制全部历史。

## 证据与测试

- ReservationHttpContractTest：请求参数与响应契约。
- ReservationServiceTest：期限、事件、影响行数与异常分支。
- ReservationLockTransactionTest：真实隔离 MySQL/Redis 下的事务、锁和幂等回归。
- ReservationCancellationHttpTest：真实隔离 HTTP/JWT 取消冲突、失败回滚与缓存断言。
- ReservationConcurrencyComparisonTest：100/500 客户端的并发对照；其中不安全模拟只使用临时实验表。

默认构建与隔离执行方法见 [测试说明](testing.md)。Mock 测试不能替代真实事务或 MQ 验收；未启用的 P0 专用 IT 不计为通过。
