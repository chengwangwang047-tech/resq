# ResQ v1.0 命令行演示套件

本套件通过真实 HTTP、独立 MySQL/Redis/RabbitMQ 和可执行断言演示项目，不需要手动替换 JWT、用户 ID、资源 ID 或请求 ID。它不新增前端、时隙管理接口或预约业务逻辑。

## 快速使用

在 WSL Ubuntu 的仓库根目录执行。先打开 Docker，准备 Java 21、Maven、Python 3 和四个本地镜像：mysql:8.0.46、redis:7-alpine、rabbitmq:3.13-management、eclipse-temurin:21-jre-jammy。首次联网执行 mvn clean package 准备 Maven 缓存；演示准备阶段使用离线 Maven，不自动拉取/更新镜像。镜像缺失时应先自行准备，避免现场下载。

```bash
cd ~/resq

# 提前准备：构建、默认测试、隔离故障测试、四服务、种子与 50 个并发用户。
python3 scripts/demo/prepare.py

# 开场检查：显示真实四服务状态、健康、session 与报告路径。
python3 scripts/demo/run.py status

# 一次自动执行全部场景。
python3 scripts/demo/run.py all

# 演示结束，仅清理本次项目、网络、数据卷和唯一镜像，保留脱敏报告。
python3 scripts/demo/run.py cleanup
```

也可以**用以下三条替代 all**，每段之间停下来讲解：

```bash
python3 scripts/demo/run.py basic
python3 scripts/demo/run.py concurrency
python3 scripts/demo/run.py advanced
```

不要先运行 basic/concurrency/advanced 再对同一 session 执行 all。写场景只允许首次执行，成功或失败后再执行都会拒绝，防止把已确认/已过期时隙当成新场景，也防止网络错误后偷偷重放写请求。再次演示运行 prepare.py 创建全新 session；它不覆盖旧 session。若同时保留多个环境，可在 run.py 后加 `--session <完整32位session>` 指定目标，清理时也一样。

准备失败时会打印 session，现场保留；用相应 session 的 cleanup 清理。不要为恢复演示而删除原 deploy 卷、执行全局 prune 或清空 Redis。已清理 session 的 status 和报告仍可查看。

## 实际演示与断言

| 模块 | 实时操作与证据 |
| --- | --- |
| basic | 两用户 HTTP 注册登录、匿名鉴权；HTTP 创建资源，查询种子资源/时隙；两条不同预约分别确认、取消；重复取消 409；SQL 核对预约与时隙 |
| concurrency | 50 个不同用户同步竞争一时隙；要求恰好 1 次创建、0 次 429、SQL 有效预约 1、时隙 RESERVED；409 必须是库存冲突，503 单列，技术错误失败；随即确认获胜预约 |
| 请求幂等 | 专用用户/时隙，3 次同请求 ID 返回同一预约；改时隙或用户复用 ID 返回 409；SQL request_id 只有一行 |
| advanced：超时 | app 演示期限默认 60 秒；核对 MySQL expire_at 差值与 MQ 等待队列 TTL=60000；真实等待 MQ 令 PENDING→EXPIRED、时隙 AVAILABLE，且对应 mq_consume_record=1 |
| 消费者幂等 | 对上述事件重投 2 次，要求观察到两次重复处理且队列排空，数据库消费记录仍 1；旧预约保持 EXPIRED、后续新预约 CONFIRMED、时隙 RESERVED，主 broker 无额外失败消息 |
| 故障路由 | 展示 prepare 本次预跑的专用隔离 IT：异常与非法 JSON 各重试 3 次后保留在 failed 队列，成功消息正常确认。明确是另一个临时 broker、模拟业务失败，不能当作真实数据库去重证明 |
| 缓存 | 独立资源/日期 Key 冷启动，查询构建 AVAILABLE；预约提交失效后重新查为 RESERVED；取消失效后重建 AVAILABLE；每次核对 HTTP、Redis 内容、SQL，非仅 EXISTS |
| 候补 | 已占用时隙，两个非持有用户依次入队并排名 1/2；重复入队不改排名、总数=2，Redis ZCARD=2 |

每个场景会输出 PASS；断言、传输或运行条件失败均以非零退出，模块标为 FAILED，保留现场。输出中的动态 ID 和统计来自该次运行，不是固定示例。

HTTP 只连接 session 的本机回环端口，显式绕过宿主代理；不对失败写请求自动重试。传输失败只展示安全的方法、路径和错误类别，不打印请求头、密码、JWT 或响应全文。

## 隔离与业务边界

- 默认每次生成 `resq-demo-<随机后缀>`，新网络、新的 MySQL/Redis/RabbitMQ 三卷和独立回环端口，额外检查同名资源是否已存在（含无标签资源）。不读取 deploy/.env，不接触原 deploy 项目的卷。
- 私有配置在 `~/.local/share/resq/demo/<session>/`，目录 0700，文件 0600，位于 Git 和 target/ 之外。合成密码与并发用户凭据仅由工具本地读取，JWT 仅在当前进程内使用。Maven clean 不会移除运行中的环境配置。
- 复用现有 Compose、Dockerfile 和 prod Profile，通过本次私有 override 配置 60 秒期限、有限内存和归属标签，不改变正常部署默认 600 秒。种子在 app 启动前导入，并复用原空库/卷归属检查。
- 只在演示 app 把超时扫描首轮推迟 24 小时；advanced 要求 session 创建不满 1 小时，并结合真实消费记录验证 MQ 路径。此配置不等于删除扫描补偿功能，也不代表单独验收了扫描路径。prepare 的 `--timeout-seconds` 可选 30～600 秒，默认 60 秒；它同时影响预约确认期限和 MQ TTL。
- 启动前后检查精确容器 ID、项目、guard 和配置哈希。配置、端口、归属不符则拒绝操作。cleanup 在任何删除前验证本项目资源归属，仅删除匹配项目的资源及本次镜像；清理成功后删除本次合成配置/密码，保留报告及本地诊断日志。
- 50 用户各请求一次，保留每用户每分钟 10 次限流，不靠关闭限流制造成功数字。客户端 50 请求不是最大吞吐量或生产容量测试。数据库控制仍是锁、事务和条件 UPDATE，不宣传存在未实现的有效预约 slot_id 唯一索引。
- 候补仅入队、去重、排名，没有自动递补；通知仅日志模拟，没有邮件/短信；事务提交后的 MQ 发送仍没有持久化补发，本套件不承诺消息绝不丢失。

## 报告与测试入口

每个 session 都有 report.json、report.md 和 state.json。可公开报告采用字段白名单，记录动态 ID、断言结果、HTTP 状态分布、耗时与故障证据来源，不含 credentials.json 或 synthetic.env。build.log、fault-evidence/maven.log 属于本地私有诊断日志，**不要把整个 session 文件夹当公开交付附件**。

```bash
# 入口保护与共享 HTTP 传输离线测试，不操作 Docker。
python3 scripts/demo/test_demo.py

# 既有完整集成测试仍独立保留，不必塞进 13 分钟演示现场。
python3 scripts/run_integration_tests.py
```

准备、业务演示和故障 IT 分别记录耗时。演示验证能在少量命令内执行，讲解可按 1 分钟健康、3 分钟基础、3 分钟并发、3 分钟异步、3 分钟缓存/候补组织；镜像下载、编译和 prepare 不计入现场 10～15 分钟。

## 主持人提纲

1. status：指出项目名、独立端口、四服务健康，说明原环境不参与。
2. basic：观察两个不同预约的确认/取消与 SQL，说明 JWT 来自真实登录。
3. concurrency：说明 50 个不同用户避免限流干扰，展示 HTTP 分类及唯一有效预约；随后展示相同请求 ID 和冲突参数。
4. advanced：等待 60 秒时解释等待队列 TTL、死信消费和扫描补偿的区别；看真实记录去重，再解释独立故障 IT 的证据范围。
5. 展示缓存内容三方一致与候补排名，不把范围扩大为强一致缓存、自动调度或真实通知。
6. 查看报告，cleanup，仅移除该 session；新一轮 prepare 使用新数据，原环境保持原样。

原始设计评估保留在 [demo-system-plan.md](demo-system-plan.md)，最小种子接口见 [demo-seed.md](demo-seed.md)。当前运行实测、回归结果及限制见 [demo-acceptance.md](demo-acceptance.md)。
