# P0 修复与验证

## 资源写权限

`GET /api/resources`、资源详情、时隙查询保持公开，HEAD/OPTIONS 也不要求登录。资源路径的写操作必须通过现有 JwtAuthInterceptor；当前没有管理员角色，任何有效登录用户均可创建资源。

`ResourceAuthorizationTest` 使用实际 MVC 拦截器注册和临时随机签名密钥，验证匿名、无效/过期 JWT 被拒绝，有效 JWT 可创建，公开查询及原有受保护路径的行为保持正确。测试不启动 Boot 或访问中间件。

## 超时消费失败路由

继续使用现有 3 次重试和 RejectAndDontRequeueRecoverer，通过 policy 将重试耗尽的消息导向现有失败交换机。没有给已存在的队列添加不兼容的声明参数。

从仓库根目录执行，先预览并核对目标容器，再显式应用：

```bash
python3 scripts/configure_timeout_failure_policy.py --container deploy-rabbitmq-1
python3 scripts/configure_timeout_failure_policy.py --container deploy-rabbitmq-1 --apply
```

新环境应替换为本次环境的 RabbitMQ 容器名；应用消费前先应用 policy。每个新 broker 都需要这一步，已有持久化 broker 的 policy 可保留，脚本重复应用同一配置安全。脚本不读取密码，不删除队列，不清消息；若同名 policy 内容不同或其他 policy 已匹配该队列，将停止并要求审查。

范围：vhost `/`，policy `resq-timeout-failure`，优先级 10，只匹配 `^resq\.reservation\.timeout\.dead\.queue$`，转发至 `resq.reservation.timeout.failed.exchange` / `reservation.timeout.failed`。应确认应用已声明 failed exchange、queue 和 binding，消费者正常在线；policy 本身不会创建目标拓扑。没有失败路由时已丢弃的历史消息不能追回。

`python3 scripts/test_timeout_failure_policy.py` 可离线验证配置冲突保护和默认预览模式。`TimeoutFailureRoutingIT` 只在提供独立临时 broker 标记和连接参数时启用，验证业务异常和非法 JSON 各重试 3 次后进入失败队列、成功消息正常确认。

这项修复不等于完成生产者可靠投递，也不承诺 classic queue 在 broker 故障下的至少一次死信转发。相关能力仍属于审计报告 P1-03。policy 的更新方式参见 [RabbitMQ 3.13 官方说明](https://www.rabbitmq.com/docs/3.13/dlx)。

## 预约时间边界

原子抢占 SQL 同时检查 `status='AVAILABLE'` 和 `slot_end > NOW(3)`。结束时间等于数据库当前时间也拒绝，使用数据库时间避免应用层先查后写窗口。当前只禁止已结束时隙；已开始但未结束时隙的行为保持原样，未扩展已确认取消、资源停用或确认期限规则。

历史成功请求仍先查 MySQL 幂等记录，因此时隙后来结束不会令原成功请求重试变成新建或错误冲突。

`ReservationTimeBoundaryIT` 使用当前部署 schema、真实 MySQL、事务代理和独立合成夹具，覆盖已结束、恰好结束、未来时隙、进行中时隙、占用冲突、历史幂等重试、并发预约已结束时隙。只在隔离实例标记、非开发端口、专用库及库内 guard 全部匹配时连接。不得把环境参数指向现有开发库。

## 集成测试安全入口

两个 `*IT` 不进入 Maven 默认 `*Test` 选择，且额外要求随机 32 位十六进制隔离标记。它们不加载 application*.properties：

- MySQL：RESQ_P0_ISOLATED_MYSQL、RESQ_P0_MYSQL_PORT、RESQ_P0_MYSQL_PASSWORD。固定专用库 resq_p0、账号 p0_test，且要求 p0_safety_guard.guard 与标记匹配；测试写入该专用库。
- RabbitMQ：RESQ_P0_ISOLATED_BROKER、RESQ_P0_RABBIT_PORT、RESQ_P0_RABBIT_USER、RESQ_P0_RABBIT_PASSWORD。仅限临时 broker；测试创建业务同名拓扑并发送、消费合成消息。标记只是显式启用条件，不能代替容器归属检查。

当前旧测试编译问题已经修复。两个专用套件可通过仓库内安全入口复现，不依赖此前工作目录中的临时脚本，也不需要改写业务 SQL 或复制/筛选测试源码：

```bash
# 只执行这 8 项：新建自己的 MySQL 和 RabbitMQ，完成后按归属清理。
python3 scripts/run_p0_integration_tests.py

# 原有 29 项 + 以上 8 项，两阶段隔离执行，汇总要求 0 跳过。
python3 scripts/run_integration_tests.py

# 入口自身的离线保护测试，不调用 Docker/Maven。
python3 scripts/test_p0_integration_runner.py
```

前提是 Java 21、Maven、Python 3、Docker 可用，并已有 Maven 依赖缓存和所需镜像。专用入口只使用 `mysql:8.0.46`、`rabbitmq:3.13-management`；完整入口另外需要 `redis:7-alpine`。镜像缺失则明确失败，不会偷偷拉取或更新。首次可先 `mvn clean package` 准备依赖并自行拉取指定镜像。

入口自动分配本机端口、生成测试凭据/guard、导入现行 schema，并在新 broker 上设置失败路由 policy；不读取真实 `.env`，不使用现有容器或卷。报告与失败行为详见 [测试入口](testing.md)。测试源码和业务断言未改变；8 项必须真实执行，跳过不算通过。
