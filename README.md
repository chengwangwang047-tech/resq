## Redis 缓存设计与 Key 命名规范

ResQ 使用 Redis 缓存资源详情和指定日期的资源时隙列表，采用 **Cache Aside（旁路缓存）** 模式。MySQL 是业务数据的权威来源。

### 1. Key 命名规则

统一采用冒号 `:` 分隔业务模块、资源标识和查询条件。

| 缓存类型 | Key 模板 | 示例 | TTL |
|---|---|---|---|
| 资源详情 | `resource:{id}` | `resource:1` | 300 秒 |
| 日期时隙列表 | `resource:{id}:slots:{date}` | `resource:1:slots:2026-10-12` | 300 秒 |

其中：

- `{id}`：计算资源的唯一 ID。
- `{date}`：ISO 8601 日期格式 `yyyy-MM-dd`。
- `slots`：表示指定资源在对应日期开始的时隙集合。

### 2. 缓存读写策略

采用 Cache Aside 模式：

1. 优先查询 Redis。
2. 缓存命中（HIT），直接返回缓存结果。
3. 缓存未命中（MISS），查询 MySQL。
4. 将数据库结果序列化为 JSON，写入 Redis 并设置 TTL。
5. Redis 读取或写入失败时，尽可能降级使用 MySQL。

时隙查询支持缓存空列表 `[]`，以避免无时隙日期被重复查询。`null` 表示缓存未命中，`[]` 表示有效的空结果。

未提供 `date` 参数的时隙查询保留原有行为，不使用日期时隙缓存。

### 3. 缓存失效策略

- 所有上述缓存均设置 300 秒 TTL，到期后自动失效。
- 预约或取消成功后，在数据库事务提交之后，删除受影响资源及日期的时隙缓存。
- 事务回滚时不执行对应的缓存删除。
- 缓存可能存在短暂数据延迟；预约是否成功仍由 MySQL 事务及条件更新决定。
- 资源详情中的状态字段也可能变化，未来新增状态更新操作时，应同步考虑 `resource:{id}` 的缓存失效。

### 4. Redis CLI 验证示例

```bash
# 查询资源详情缓存
redis-cli GET resource:1

# 查询日期时隙缓存
redis-cli GET resource:1:slots:2026-10-12

# 查看剩余 TTL
redis-cli TTL resource:1:slots:2026-10-12

# 手动删除指定缓存
redis-cli DEL resource:1:slots:2026-10-12
```

缓存键仅包含业务标识与查询条件，不应包含密码、JWT 或其他敏感信息。
## X-Request-Id 生成与校验规则

## 1. 作用

`X-Request-Id` 是客户端为一次业务操作生成的唯一请求标识，用于实现预约接口的幂等性，避免网络重试或重复提交产生多条预约记录。

## 2. 生成规则

客户端可以通过 UUID 生成：

```java
String requestId = UUID.randomUUID().toString();
```

**核心原则：**

- 新的预约操作：生成新的 requestId。
- 同一次预约发生超时或网络重试：复用原来的 requestId。
- 不同预约操作：不得故意复用同一个 requestId。

目前 ResQ 尚无正式前端，由 Postman 模拟客户端生成和传递请求 ID。

## 3. 请求传递

通过 HTTP 请求头传递：

```http
POST /api/reservations
Authorization: Bearer <JWT>
X-Request-Id: day18-test-001
Content-Type: application/json
```

请求体：

```json
{
  "slotId": 106
}
```

`userId` 由服务端通过 JWT 获取，不需要客户端传递。

## 4. 服务端校验规则

当前使用正则：

```java
requestId.matches("[A-Za-z0-9_-]{1,100}")
```

规则：

- 长度为 1～100 个字符。
- 允许英文字母、数字、下划线 `_` 和连字符 `-`。
- 不允许空格、换行及其他特殊字符。
- 缺少请求头或格式不合法时返回 HTTP 400。

## 5. 幂等性与异常

| 情况 | 处理方式 |
|---|---|
| 首次合法请求 | 创建预约 |
| 相同 requestId、相同业务参数 | 返回历史预约 |
| 相同 requestId、不同用户或时隙 | 409 Conflict |
| 相同请求正在处理 | 503，提示稍后重试 |
| Redis 状态过期 | 通过 MySQL 历史记录继续保证幂等性 |

## 6. 责任划分

**客户端：** 生成 requestId、携带请求头、重试时复用 ID。

**服务端：** 校验格式、识别重复请求、检查业务参数、通过 Redis 和 MySQL 保障幂等性。

## 7. 请求幂等验证记录

- 顺序重复提交：两次均返回预约 ID 46。
- MySQL：对应 requestId 只有一条预约。
- Redis：`SUCCESS|46|23|106`。
- 不同业务参数复用 requestId：返回 409。
- 10 个相同请求并发：1 个成功、9 个返回 503，数据库最终只有一条预约。

**总结：requestId 标识的是一次业务操作，而不是一次 HTTP 发送。**
## SQL 性能优化

ResQ 支持基于 `(created_at, id)` 联合游标的预约历史分页，
并针对查询模式设计了联合索引。

在 10 万条模拟数据的独立实验中，游标分页将底层扫描记录数
从 50,020 条减少至 20 条，两轮平均执行时间从约 10.85 ms
降低至约 0.222 ms。

详细实验环境、SQL、执行计划及局限性见：
[预约分页性能基准测试报告](docs/performance/reservation-pagination-benchmark.md)

## P0 安全修复与验收

资源写权限、预约时间边界及 RabbitMQ 失败路由配置见 [P0 修复与验证](docs/p0-fixes.md)。新环境启动消费前应按该说明配置失败路由 policy。

## 构建与隔离测试

`mvn clean package` 默认运行无需中间件的测试；完整隔离集成测试使用 `python3 scripts/run_integration_tests.py`。环境、连接保护和异常契约见 [测试说明](docs/testing.md)。

## 学习资料与职责命名

现行业务保留原有 Maven 与部署结构；独立学习材料已分类保存在本地 `learning-archive/`，该目录不进入 Git 或 Docker 镜像。分类、迁移清单和恢复说明见 [学习归档索引](docs/learning-archive.md)。当前实现见 [预约流程](docs/reservation-flow.md) 和 [MyBatis 映射说明](docs/mybatis-mapping.md)。
