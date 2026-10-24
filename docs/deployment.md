# Compose 统一部署

## 范围与前提

deploy/compose.yaml 管理 mysql、redis、rabbitmq、app 四个服务。Java 业务代码、生产 Profile、Dockerfile 与初始化 SQL 沿用现有实现。需要 Java 21、Maven、Python 3、Docker，以及支持 `up --wait` 和 `config --format json` 的 Compose；本次环境为 Compose 5.6.1。

默认项目名固定为 deploy，沿用 deploy_resq_net、deploy_mysql_data、deploy_redis_data、deploy_rabbitmq_data。不要为已有数据随意改项目名、把卷改成固定的其他名字，或通过删卷解决启动问题。隔离验收使用独立项目名与新卷。

## 全新环境启动

在项目根目录执行：

```bash
mvn clean package
cp -n deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

在本机编辑 deploy/.env，填写模板中的空字段，设置一个新的 RESQ_APP_IMAGE 标签，例如 resq:compose-本次版本。已有 .env 不应被模板覆盖。用户名和密码必须与实际服务对应，不能把模板用户名直接套用到已有数据库。

需要 MYSQL_ROOT_PASSWORD、RESQ_DB_USERNAME、RESQ_DB_PASSWORD、RESQ_RABBITMQ_USERNAME、RESQ_RABBITMQ_PASSWORD、RESQ_PROD_JWT_SECRET_BASE64 和 RESQ_APP_IMAGE。JWT 使用至少 32 字节随机密钥的 Base64 表示，仅在本机生成和保存，不发到聊天、日志或 Git。含 `$` 或 `#` 的 .env 值按 Compose 规则用单引号包裹。示例中的空值会在启动前报缺失变量名。

检查配置时使用安静模式，避免把展开后的凭据打印出来：

```bash
docker compose --env-file deploy/.env -p deploy -f deploy/compose.yaml config --quiet
python3 scripts/start_compose.py --env-file deploy/.env --build
```

启动入口先验证配置；指定 --build 时先构建应用镜像；接着等待三个依赖健康，调用已审核的 P0 policy 工具，最后启动 app。它不会读取 .env 内容或删除卷。首启不能跳过 policy 步骤直接启动 app；已有消息/队列不会被该工具清除，冲突 policy 会使启动入口停止。

MySQL 镜像只在空数据目录中执行挂载的 init/01-schema.sql。已有卷不会自动重跑 schema，也不会因修改 MYSQL_PASSWORD 或 RABBITMQ_DEFAULT_PASS 自动修改已持久化的账号。后续 schema 变化需要独立迁移，本轮不执行旧库 DDL。

## 连接、就绪与日志

app 明确激活 prod；Compose 把现有 RESQ_DB_*、RESQ_RABBITMQ_* 变量映射到 Profile 的 RESQ_PROD_*，不用重复保存两套密码。应用只接收明确定义的容器变量，不把整个本地 .env 注入应用。

| 项目 | 容器内地址/默认外部入口 |
| --- | --- |
| 应用 HTTP | 127.0.0.1:8081 -> app:8080 |
| MySQL | app 连接 mysql:3306；外部 127.0.0.1:3306 |
| Redis | app 连接 redis:6379；外部 127.0.0.1:6379 |
| RabbitMQ | app 连接 rabbitmq:5672；外部 127.0.0.1:5672 |
| RabbitMQ 管理界面 | 127.0.0.1:15672 |

外部端口可以通过模板中的 RESQ_*_PORT 调整，不改变容器间地址。当前配置只发布到本机回环地址；需要远程访问时应单独评估暴露范围。

MySQL healthcheck 使用应用账号执行 SELECT 1，Redis 检查 PONG，RabbitMQ 以 rabbitmq 用户检查应用已运行，app 检查 /actuator/health 返回 UP。depends_on: service_healthy 用于首次启动等待；健康状态不等于持续自动修复。unless-stopped 处理进程退出，手动停止的容器不会因此自动恢复。

四个服务都使用 json-file 日志，单文件 10m、最多 3 个文件。app 保留镜像的非 root 用户，并把两个原 DEBUG 缓存相关日志调整为 INFO。仅开放既有 health Actuator 端点，不打印完整环境变量或展开后的配置。

默认 RESQ_TIME_ZONE=UTC，与本次只读检查的 MySQL 容器系统时区一致。迁移已有 DATETIME 数据前仍需核对旧应用和数据库的时间约定；不要凭电脑显示的本地时区批量转换历史数据。

## 日常管理

首次 policy 初始化成功后，可以通过 Compose 统一管理四个服务。更新应用仍建议使用 startup 入口以复核 policy。

```bash
docker compose --env-file deploy/.env -p deploy -f deploy/compose.yaml up -d --wait
docker compose --env-file deploy/.env -p deploy -f deploy/compose.yaml ps
docker compose --env-file deploy/.env -p deploy -f deploy/compose.yaml logs --tail=100 app
docker compose --env-file deploy/.env -p deploy -f deploy/compose.yaml stop app
docker compose --env-file deploy/.env -p deploy -f deploy/compose.yaml start app
```

使用日志排错时在本机查看，分享前去除认证信息与业务数据。新建 broker 或丢失 policy 后，应重新通过 startup 入口初始化，而不是直接启动消费者。

## 隔离部署验收

```bash
mvn clean package
python3 scripts/test_compose_safety.py
python3 scripts/verify_compose_deployment.py
```

验收入口不读取 deploy/.env，清除继承的应用和 Compose 覆盖变量，创建随机项目名 resq-compose-it-*、专用回环端口、三个新数据卷和合成凭据。临时 override 只给本次资源加归属标记并限制测试资源占用，不修改正式配置。

它真实构建镜像、启动四个容器，验证必填变量拒绝、全新 schema、JWT 注册登录、资源与缓存、预约幂等/取消/确认、应用进程退出后的重启，以及重建全部容器后的 MySQL/Redis/RabbitMQ 状态保留。对现有容器状态与数据卷名单作前后比较。

清理前逐一检查所有临时容器、网络、卷的项目名和随机归属标记，只有核对通过才移除本次项目及本次卷。无法证明归属时停止清理并保留现场，不会对 deploy 项目清理。默认也只删除本次唯一镜像标签；需要保留受验镜像时加 --keep-image。

汇总在 target/compose-acceptance/<时间-随机后缀>/summary.json。临时凭据仅存在权限受限的本次 private 目录，成功清理后删除；失败时依据清理状态保留现场。请先保留需要的汇总，再运行 clean。该验收只证明部署和所列业务路径，不代替 MQ 可靠投递、并发竞态等其他审计项目。

## 现有环境切换与回滚

隔离验收通过不代表现有环境已经切换。增加 healthcheck、日志或环境配置可能导致 Compose 重建已有依赖容器，需要一次明确的维护窗口；数据卷名称和内容沿用。

切换前应核对项目名、当前三个卷、旧应用/8081 端口占用、本地变量是否齐全及旧时区约定；保留旧镜像、旧应用启动方法和原有本地配置。先完成正常构建和新镜像准备，再启动更新后的 Compose，不停止整套环境来做镜像构建。

本轮不自动执行现有环境切换；现有环境切换不使用 down -v 或 volume prune。隔离验收仅按前述归属规则清理本次资源。回滚以恢复旧应用镜像和已保存的旧启动方式为主，保留数据卷；不能假定旧镜像支持新的 prod 变量，也不能通过删除数据库卷“回滚”。当前数据库 schema 未改，不需要为本次 Compose 改造执行数据迁移。

## 官方依据

- [Compose 启动顺序与健康等待](https://docs.docker.com/compose/how-tos/startup-order/)
- [Compose 环境变量插值](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)
- [Compose up 对配置变更和容器重建的处理](https://docs.docker.com/reference/cli/docker/compose/up/)
- [已有 P0 policy 说明](p0-fixes.md)
