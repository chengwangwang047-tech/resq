# 构建与隔离测试入口

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
4. 清除继承的应用连接变量与 JVM/Maven 覆盖参数，禁用 Maven 用户 rc，使用 `mvn -o -B -Pintegration test -Dtest=**/*Test` 及 Day17 显式启用参数执行原有 29 项集成测试；两项专用 `*IT` 不在此阶段重复运行。
5. 核对创建 ID 和归属标记，清理第一阶段三个临时容器；现有数据卷不挂载、不清理。
6. 再调用下述专用入口，新建另外两个临时容器执行 8 项 P0 测试。两阶段依次执行，当前基线合计 37 项，要求 0 失败、0 错误、0 跳过。

七组 Spring 集成测试使用显式 test Profile。连接前检查隔离标记以及数据库/Redis/MQ guard；不提供 guard 时直接拒绝，不回退到 dev 或 prod。测试中的后台预约过期/缓存重试任务不执行，MQ 监听器不自动启动，避免干扰事务和缓存断言。

集成测试会创建/更新/删除自己的合成用户、资源、时隙、预约，写 Redis Key，并向临时 MQ 发送事件；这些操作仅存在于本次临时环境。所有 Mapper 夹具自行建立关系，不再依赖 user=1、slot=1。测试 schema 补齐 expire_at 等当前字段；未来时隙用于真实预约，缓存 JSON 断言与夹具日期一致。

`scripts/run_native_reservation_tests.py` 提供原生 MySQL/Redis 进程验证入口，已适配测试分组和最新 schema，说明见 [原生预约测试](native-reservation-testing.md)。不得在共享或开发服务上手动伪造 guard 来绕过隔离检查。

## 只运行 8 项专用 P0 测试

```bash
python3 scripts/run_p0_integration_tests.py
```

该命令可独立运行，也由完整入口自动调用，不要求先启动 ResQ 或 Compose，不读取 `deploy/.env`。它保留两个 `*IT` 的专用启用条件，不要求用户手动填写任何 `RESQ_P0_*` 变量。

- 新建 `mysql:8.0.46`、`rabbitmq:3.13-management` 两个容器，随机名称、完整 ID 和独立归属标记；MySQL/RabbitMQ 数据目录使用 tmpfs，不挂载已有数据或数据卷。
- Docker 自动分配 `127.0.0.1` 端口；拒绝公开绑定、多个绑定及开发端口 3306/5672。凭据每轮随机生成，清除继承的应用连接/JVM 参数，不接触本机原账号与密钥。
- MySQL 只在新容器中初始化当前 schema、`resq_p0` / `p0_test` 以及 `p0_safety_guard`；测试连接前仍检查数据库 guard。
- 检查容器归属后，只给本次 broker 应用既有超时失败路由 policy，再以精确测试名选择 `ReservationTimeBoundaryIT`（7 项）和 `TimeoutFailureRoutingIT`（1 项），通过 integration Profile 执行。
- 两套新报告必须同时存在、数量为 7+1、失败/错误/跳过均为零；Maven 返回成功但实际跳过、漏跑也会使入口失败。超时只终止本次 Maven 进程组。
- 正常和异常退出均尝试清理本次容器；清理前先校验全部完整 ID 与归属标记。发现不匹配则停止清理并保留失败记录，绝不按模糊名称清理原有资源；不执行 volume prune 或对现有环境 down。

详细业务断言与启用条件见 [P0 修复与验证](p0-fixes.md)。单纯手动给 Maven 设置标记不能证明环境隔离，不应绕过上述入口。直接 `mvn -Pintegration test` 也不等价于完整验收：它不会创建依赖、初始化 guard 或配置临时 broker policy。

独立运行报告：`target/p0-integration-results/<时间-随机后缀>/`，含 `maven.log`、`surefire-reports/`、`summary.json`（passed 与两套测试统计）、`cleanup.json`。目录 0700，命令行入口以 0077 umask 创建结果文件。测试日志只涉及合成参数，仍应本地保存，不直接公开。

完整运行报告位于 `target/integration-results/<时间-随机后缀>/`：根目录 `maven.log`、`surefire-reports/` 与 `cleanup.json` 对应第一阶段；`p0/` 含第二阶段相同文件及专用 `summary.json`。根目录 `summary.json` 汇总两阶段所有测试，`acceptance.json` 记录完整入口是否成功及最终统计。第二阶段失败会使完整入口返回非零，不能只看第一阶段报告判定成功；这些是构建产物，执行 clean 前如需保留应另行归档。不要把含合成运行参数的完整日志直接当成公开简历附件。

入口安全测试不调用 Docker：

```bash
python3 scripts/test_integration_runner.py
python3 scripts/test_p0_integration_runner.py
```

## 异常与校验契约

- 重复取消、确认后取消等正常状态竞争使用 ReservationConflictException，返回 409，不释放其他预约的时隙。
- 真正的释放失败等内部一致性错误保持 500 与数据库事务回滚，响应采用 `success/message`，不暴露原始内部信息。
- Spring MVC 的缺少请求头、非法参数/JSON、媒体类型等错误保留原 4xx 状态和必要响应头，统一错误体。
- 资源 name 最多 100、type 最多 20，与当前列长度一致，超限在进入 Service 前返回 400。
- 解锁或所有权检查失败不能覆盖原业务异常，清理异常作为 suppressed 保留；已提交但解锁失败时仍沿用 MySQL 回查恢复路径。

`ReservationCancellationHttpTest` 使用真实隔离 MySQL、Redis、JWT 与 HTTP，证明重复取消不释放后续新预约、确认后取消为 409，以及释放失败为 500 时预约状态/时隙和缓存保持回滚前结果。

预中断锁等待的真实集成测试接受两条已有路径：Redisson 包装的 RedisException，或 ReservationBusyException 包装 InterruptedException。两条路径都必须保留中断原因，业务中断分支还必须恢复中断标记；锁仍属于原持有线程、争抢线程不能解锁、数据库不发生预约写入的断言保留。异常外层类型取决于库内部异步等待时序，不应把单次实验观察写成唯一契约。
