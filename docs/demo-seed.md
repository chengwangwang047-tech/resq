# 全新演示环境的最小种子数据

本入口只准备资源和时隙，不实现统一演示系统，不为现有开发库补数据。`deploy/init/01-schema.sql` 保持只建表；普通 Compose 启动不会自动执行种子。

## 数据内容

创建 GPU、CPU 资源各一个，以及八个 AVAILABLE 时隙。日期以数据库 UTC 当前日期为基准生成，最早明天，持续一小时；不使用历史日期，不假定 ID 从 1 开始。

| 场景标识 | 资源 | UTC 日期偏移/时间 | 用途 |
| --- | --- | --- | --- |
| basic-confirm | GPU | +1 天 09:00 | 创建并确认 |
| basic-cancel | CPU | +1 天 10:00 | 另一个预约的取消与释放 |
| concurrency | GPU | +2 天 09:00 | 多用户竞争一个时隙 |
| idempotency | GPU | +3 天 09:00 | 同一请求 ID 重放 |
| idempotency-conflict | GPU | +3 天 10:00 | 同一请求 ID 改变参数被拒绝 |
| timeout | GPU | +4 天 09:00 | 确认期限到期释放；不是等到时隙结束 |
| cache | GPU | +5 天 09:00 | 缓存构建、失效、重新加载 |
| waitlist | GPU | +6 天 09:00 | 预约占用后，其他用户候补入队/去重/排名 |

不创建用户、预约或 MQ 消费记录。后续演示必须通过真实 HTTP 注册登录、获取 JWT、预约；不能把直接插入数据库的结果当作业务接口已执行。不同场景隔离时隙，缓存场景还隔离资源/日期缓存 Key。

## 允许写入的目标

`scripts/seed_demo_data.py --container <完整容器ID> --guard <随机32位十六进制标记>` 同时要求：

1. MySQL 镜像为 mysql:8.0.46，容器运行中；Compose service 标签为 mysql。
2. Compose project 标签为 `resq-demo-<guard前12位>`，容器的 `resq.demo.guard` 必须等于给定 guard；不能传 deploy-mysql-1 等容器名绕过验证。
3. 数据目录是本次 tmpfs，或本项目的 `<project>_mysql_data` 卷；使用卷时，卷本身也必须有匹配的 Compose project 与 guard 标签。拒绝挂载 deploy_mysql_data、其他卷和宿主机数据目录。
4. 同项目没有运行中的 app。种子操作期间不要并发启动 app 或运行其他数据写入脚本。
5. 数据库固定为 resq，当前 schema 的五张表均为空且为 InnoDB，没有额外永久表。任意一张表有数据就拒绝，包括已经成功导入过种子的数据库。

脚本不会从宿主机读取数据库密码或 `.env`，只在已核实归属的容器内部引用其 MYSQL_USER/MYSQL_PASSWORD，固定 TCP 连接本容器 MySQL。原始命令错误输出不会直接打印。

写入使用同一个数据库会话中的命名锁、空库检查与事务。临时 CHECK 表只用于使检查失败时终止后续 SQL，不保留永久 schema 变更。中途 SQL 失败由客户端退出/连接关闭回滚业务行；AUTO_INCREMENT 可能留下间隙，这是正常现象，不通过重置自增值修复。临时表不会隐式提交，但其创建本身不可回滚，连接关闭后自动清理，依据 [MySQL 官方事务说明](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)。

## 如何用于独立的新 Compose 演示环境

当前尚未实现 `scripts/demo/prepare.py`，以下是种子阶段的手工接入方法，不是完整演示命令。首先在仓库外准备权限受限的**新演示配置文件**，可参考 deploy/.env.example 的变量名；填写新的合成账号/密码/JWT 密钥、独立镜像标签、UTC 和不与现有环境冲突的端口。不要复制原环境的真实 `.env`，不要沿用已有卷。

把该文件绝对路径保存到 `DEMO_ENV`。Compose 解析整个配置时仍要求包括 JWT 和镜像在内的必填变量齐全，即使本步骤只启动 MySQL。终端中若已 export 同名 RESQ 配置，可能覆盖文件；手工接入前应清除冲突覆盖，未来 prepare 入口会统一清理继承变量。

```bash
cd ~/resq
# DEMO_ENV 必须指向上述单独准备的新演示配置文件，不能是 deploy/.env。
export RESQ_DEMO_GUARD="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
DEMO_PROJECT="resq-demo-${RESQ_DEMO_GUARD:0:12}"

# 仅启动新演示项目的 MySQL；项目名与可选 overlay 必须同时指定。
docker compose --env-file "$DEMO_ENV" -p "$DEMO_PROJECT" \
  -f deploy/compose.yaml -f deploy/compose.demo-seed.yaml up -d --wait mysql

DEMO_MYSQL_ID="$(docker compose --env-file "$DEMO_ENV" -p "$DEMO_PROJECT" \
  -f deploy/compose.yaml -f deploy/compose.demo-seed.yaml ps -q mysql)"

python3 scripts/seed_demo_data.py --container "$DEMO_MYSQL_ID" --guard "$RESQ_DEMO_GUARD"
```

成功时输出 JSON 清单，含 resourceId、slotId、日期和场景标识，不含密码或 JWT。保存这份输出即可供后续演示按场景获取动态 ID。导入后不能再次往同一库补种；本入口没有 clear、force、reset 或覆盖功能。重复演示应使用未来 prepare 创建的新 session/新项目，不清空原库。

原有 `start_compose.py` CLI 没有 `--override` 参数，不要直接给它传一个不存在的选项；未来统一 prepare 可以复用其 compose_command/start 函数传入 overlay。当前本步骤只提供种子写入，尚未自动编排四服务或演示流程。

## 不准备任何本地凭据，先验证种子工具

```bash
python3 scripts/test_demo_seed.py
python3 scripts/verify_demo_seed.py
```

需要 Python 3、Docker 和本机已缓存 mysql:8.0.46。第二条命令只创建一个专属临时 MySQL，不发布数据库端口，不挂载已有卷；使用当前 schema 初始化，在 tmpfs 中验证后按归属清理。它不会保留可供后端使用的长期演示环境。

本次实测：8 项离线保护测试通过；5 个真实 MySQL 验证阶段通过，覆盖全新 schema、写入后故障回滚、两资源/八未来时隙及 ID 清单、重复导入拒绝、错误 guard 拒绝。原容器状态与数据卷保留，临时容器已清理。命名卷分支有离线归属测试，实际数据库写入验收采用 tmpfs，未在现有卷上试验。

验证结果在 target/demo-seed-results/<时间-随机后缀>/，含 summary.json 和脱敏 manifest.json；下一次 Maven clean 会删除。业务代码、测试断言、原 schema、Dockerfile 和原 Compose 均未改变。
