# 本机常驻练习环境

更新日期：2026-10-24。当前四个 deploy-* 容器已切换到新练习数据卷，应用镜像为 resq:practice-latest，HTTP 入口仍为 http://localhost:8081。原容器通过 Compose 重建换挂载，原三个数据卷没有被删除、复制或覆盖。

## 日常启停

在 WSL 仓库根目录执行：

```bash
cd ~/resq
python3 scripts/practice.py status
python3 scripts/practice.py stop
python3 scripts/practice.py start
```

这套环境用于持续练习，停止后数据仍保留；没有自动清理或删除数据卷的命令。当前已启动并健康，不需要再运行 demo/prepare.py 才能访问。

修改 Java 代码后更新练习镜像：

```bash
mvn clean package
python3 scripts/practice.py start --build
```

后续以 practice-latest 为准；本次已将旧应用标签 day25 和 compose-it-ee4312f786a6 都指向当前构建镜像，但它们不会随着未来 practice-latest 的再次构建自动移动。

**不要混用原来的启动配置。** 本机原 deploy/.env 原样保留，用它直接执行旧 start_compose.py 会切回原账号/数据卷配置。当前练习请使用 practice.py，它会显式选择仓库外私有配置与三个新卷，并拒绝归属不匹配的环境。不要对原配置执行 down -v 或 volume prune。

## 当前数据与配置

- 2 个资源（GPU/CPU）、8 个未来时隙，当前 8 个均 AVAILABLE。
- 注册、登录、查询、预约请求重放和取消/释放已实际验证。检查留下 1 个合成验证账号和 1 条 CANCELLED 预约；没有复制旧用户或业务数据。
- 后续练习自行通过注册接口创建自己的账号；没有公开共享的默认账号/密码。旧库中的账号不属于新练习库，原登录 Token 也不能在新环境继续使用。
- 正常 prod Profile、UTC，预约确认期 600 秒、等待队列 TTL=600000；没有演示用的 60 秒期限或扫描延后 24 小时覆盖，扫描沿用业务默认配置。本次未现场等待 10 分钟超时完成。
- 依赖端口仍为本机回环地址 3306/6379/5672/15672，应用为 8081。
- 时隙按本次 UTC 日期相对生成，未来会自然到期；不是永久可预约数据。种子工具仍只允许全新空库，不能重跑它清空已有练习记录。

私有配置在 `~/.local/share/resq/practice/f5ab732eb1eb770a32c020d74eafadbc/`，目录 0700，synthetic.env/override.json 为 0600；current.json 记录本机入口。真实原 .env 未读取或改写，新账号密码与 JWT 签名密钥由本次单独生成，不提交到 Git。需要配置 IDE 数据源时在本机查看该私有文件，不把内容发到聊天或放入报告。

## 数据卷与镜像回退

当前实际挂载：

- `resq-demo-f5ab732eb1eb_mysql_data`
- `resq-demo-f5ab732eb1eb_redis_data`
- `resq-demo-f5ab732eb1eb_rabbitmq_data`

名称含 resq-demo 是种子导入时的安全隔离命名；现在由常驻练习配置作为 external 数据卷明确引用，不属于 demo/run.py 的某个临时 session，不会被演示清理入口删除。

原三个卷仍在：deploy_mysql_data、deploy_redis_data、deploy_rabbitmq_data。旧镜像内容也保留为：

- `resq:backup-day25-f5ab732eb1eb`
- `resq:backup-before-practice-f5ab732eb1eb`

需要恢复原环境时，应单独安排一次切换：先停止练习环境，再显式使用原 .env 与已保存的原应用镜像标签启动原 Compose 映射；不要删卷回滚。本次没有执行回退。由于原应用标签已更新，回退不能再假定 day25 或 compose-it-ee4312f786a6 仍指向旧镜像。

这份本机 current.json/私有配置不会随 Git clone 分发。其他机器的可复现演示仍使用 [统一演示入口](demo.md)，正常部署使用 [部署说明](deployment.md)；practice.py 是本机已初始化环境的便利入口，不是新机器初始化工具。

## 本次验证

四服务健康，health=UP；SQL 核对资源 2、时隙 8 且全部 AVAILABLE；登录、请求幂等和取消通过；确认期 600 秒且 MQ TTL 匹配。停止/再次启动后数据仍在，原卷及旧镜像回退标签保留。practice 入口 4 项离线安全测试通过。

```bash
python3 scripts/test_practice.py
```

本次没有改动业务 Java、原 schema、原 Compose、Profile 或 Dockerfile。代码/文档变更尚未提交 Git。
