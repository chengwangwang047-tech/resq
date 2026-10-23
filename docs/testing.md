# 测试入口与 P1-01、P1-02、P1-05

## 默认构建

```bash
mvn clean package
```

默认执行所有不带 `integration` 标签的单元与 MVC 测试；所有测试源码都会编译，不使用 `-DskipTests` 或 `-Dmaven.test.skip=true`。注册真实 JWT/MVC 的测试使用临时随机密钥和模拟服务，不需要 MySQL、Redis 或 RabbitMQ。

本次补齐 ReservationService 的 ApplicationEventPublisher、ReservationLockService 的 ReservationRateLimiter 测试依赖。手工构造 Service 的夹具显式设置 600 秒确认期限；测试会验证期限及创建事件字段，未给生产代码新增测试兼容构造器。

## 完整的隔离集成入口

在 WSL Ubuntu 仓库根目录执行：

```bash
python3 scripts/run_integration_tests.py
```

前提：Java 21、Maven、Python 3、Docker 可用，且本机已有 `mysql:8.0.46`、`redis:7-alpine`、`rabbitmq:3.13-management` 镜像和 Maven 依赖缓存。脚本不会拉取或更新现有镜像。

脚本会：

1. 拒绝已占用的专用回环端口 13317、16317、56719、15679，不停止任何原有进程。
2. 创建具有唯一归属标记的三个临时容器，数据目录使用 tmpfs，生成仅供本次使用的合成凭据。
3. 从 `deploy/init/01-schema.sql` 初始化独立 `resq_day17` 库，写入数据库、Redis 和 RabbitMQ 的隔离 guard。
4. 清除继承的应用连接变量与 JVM/Maven 覆盖参数，设置本次隔离连接，执行 `mvn -Pintegration test` 及 Day17 显式启用参数。
5. 输出独立测试报告，最后核对创建 ID 和归属标记，只清理本次临时容器；现有数据卷不挂载、不清理。

七组 Spring 集成测试使用显式 test Profile。连接前检查隔离标记以及数据库/Redis/MQ guard；不提供 guard 时直接拒绝，不回退到 dev 或 prod。测试中的后台预约过期/缓存重试任务不执行，MQ 监听器不自动启动，避免干扰事务和缓存断言。

集成测试会创建/更新/删除自己的合成用户、资源、时隙、预约，写 Redis Key，并向临时 MQ 发送事件；这些操作仅存在于本次临时环境。所有 Mapper 夹具自行建立关系，不再依赖 user=1、slot=1。测试 schema 补齐 expire_at 等当前字段；未来时隙用于真实预约，缓存 JSON 断言与夹具日期一致。

`scripts/run_native_reservation_tests.py` 提供原生 MySQL/Redis 进程验证入口，已适配测试分组和最新 schema，说明见 [原生预约测试](native-reservation-testing.md)。不得在共享或开发服务上手动伪造 guard 来绕过隔离检查。

P0 的两个 `*IT` 仍保留独立的显式启用标记；本脚本不设置其连接变量，因此 profile 列入它们时会显示跳过。它们的独立隔离验收方法见 `docs/p0-fixes.md`，不应把跳过计为通过。

报告位于 `target/integration-results/<时间-随机后缀>/`，含 `maven.log`、独立 Surefire 报告、`summary.json` 和 `cleanup.json`；这些是构建产物，执行 clean 前如需保留应另行归档。不要把含合成运行参数的完整日志直接当成公开简历附件。

入口安全测试不调用 Docker：

```bash
python3 scripts/test_integration_runner.py
```

## 异常与校验契约

- 重复取消、确认后取消等正常状态竞争使用 ReservationConflictException，返回 409，不释放其他预约的时隙。
- 真正的释放失败等内部一致性错误保持 500 与数据库事务回滚，响应采用 `success/message`，不暴露原始内部信息。
- Spring MVC 的缺少请求头、非法参数/JSON、媒体类型等错误保留原 4xx 状态和必要响应头，统一错误体。
- 资源 name 最多 100、type 最多 20，与当前列长度一致，超限在进入 Service 前返回 400。
- 解锁或所有权检查失败不能覆盖原业务异常，清理异常作为 suppressed 保留；已提交但解锁失败时仍沿用 MySQL 回查恢复路径。

`ReservationCancellationHttpTest` 使用真实隔离 MySQL、Redis、JWT 与 HTTP，证明重复取消不释放后续新预约、确认后取消为 409，以及释放失败为 500 时预约状态/时隙和缓存保持回滚前结果。

预中断锁等待的真实集成测试接受两条已有路径：Redisson 包装的 RedisException，或 ReservationBusyException 包装 InterruptedException。两条路径都必须保留中断原因，业务中断分支还必须恢复中断标记；锁仍属于原持有线程、争抢线程不能解锁、数据库不发生预约写入的断言保留。异常外层类型取决于库内部异步等待时序，不应把单次实验观察写成唯一契约。
