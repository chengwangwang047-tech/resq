# ResQ 项目交付整理与现状验收报告

验收日期：2026-10-24（Asia/Shanghai）。项目：`/home/colaminos/resq`，分支 `main`，基准提交 `278059c`。结论针对本机当前工作目录，包含尚未提交的 Compose 改造与本轮文档整理，不等于远端仓库已具备同样状态。

## 2026-10-24 后续补充：统一演示入口已完成

已实现 scripts/demo/prepare.py、run.py（basic/concurrency/advanced/all/status/cleanup），复用现有种子、Compose 启动与故障 IT。当前最终代码用两个新的独立 session，分别分模块与 all 彩排全部通过；真实验证请求幂等、50 用户竞争、60 秒 MQ 超时、MySQL 消费去重、缓存重建与候补排名。入口离线保护 20 项通过，默认 102 项测试及旧四服务 9 阶段验收通过。原环境保持原样，所有演示资源已清理，业务代码/schema/部署默认配置未改；当前改动未提交。

使用方法见 [统一演示](demo.md)，实测、失败历史与限制见 [演示验收](demo-acceptance.md)。这补齐的是可复现演示与验证，不表示候补自动递补、真实通知或 MQ 持久化补发已实现。下文保留之前各阶段的历史记录。

## 2026-10-24 后续补充：最小种子数据入口已完成

原问题 D 的种子入口缺口已解决：新增 seed_demo_data.py、可选 compose.demo-seed.yaml、离线保护测试和临时 MySQL 验证入口。仅允许有归属标记、独立数据目录、app 未运行且五张表为空的演示库，生成 2 个资源和 8 个动态未来时隙，不创建用户/预约，不改现有 schema；重复导入拒绝，不清空或覆盖数据库。本次 8 项离线保护测试、5 个真实 MySQL 验证阶段及 Compose overlay 配置模型检查通过，包含中途错误回滚；原容器与卷不变。这里只证明种子与保护，完整 v1.0 演示套件仍未实现。

说明见 [最小演示数据](demo-seed.md)，待实施方案见 [统一演示评估](demo-system-plan.md)。本次基础业务、并发、MQ 幂等、候补/缓存场景没有因此全部补齐，也没有新增时隙 HTTP 接口。以下保留之前验收时的历史问题和统计。

## 2026-10-24 后续验收补充：8 项专用测试入口已完成

下文原始 29 通过/8 跳过统计保留为上一轮历史快照；当前测试结论以本段为准。本轮基于提交 `06044d8`，只调整隔离脚本与文档，原业务源码、测试断言、schema 与部署配置均经哈希确认不变。

- 完整入口 `python3 scripts/run_integration_tests.py`：37 项通过，0 失败、0 错误、0 跳过；先执行原有 29 项并清理三个临时容器，再创建两个专用临时容器执行 8 项。
- 独立入口 `python3 scripts/run_p0_integration_tests.py`：预约时间边界 7 项、MQ 失败路由 1 项，全部通过，无需手工设置专用连接变量或启动现有 Compose。
- 离线工具保护测试：新增 13 项通过，加上原入口 2 项、policy 7 项、Compose 7 项，共 29 项通过；测试不调用真实中间件。
- 保留专用启用标记与 MySQL 数据库 guard，只允许本次完整容器 ID 和随机归属匹配；新建临时 MySQL/RabbitMQ，使用随机凭据、动态回环端口、tmpfs，不挂载开发数据卷。policy 只应用于本次 broker。
- 专用套件报告缺失、漏跑、跳过、失败或清理归属异常均使入口失败。仅清理自己创建且通过归属校验的容器，不读取真实 `.env`。
- 完整运行和单独运行均确认原容器状态未变、原卷保留，临时容器均已清理。未提交或推送本轮改动。

完整运行报告：`/home/colaminos/resq/target/integration-results/20261024T064344Z-cd51cab5dd/acceptance.json`、`summary.json`，第二阶段详情在 `p0/`；单独运行报告：`/home/colaminos/resq/target/p0-integration-results/20261024T064554Z-7d8065e6d4/summary.json`、`cleanup.json`。这些 target/ 文件会被下一次 clean 删除。复现与结果解释见 [测试说明](testing.md) 和 [P0 修复说明](p0-fixes.md)。

## 1. 验收结论与范围

在 Java 后端实习项目定位下，当前版本已具备可构建、可隔离部署、可演示的预约核心链路。本轮默认构建、真实中间件集成测试及全新四服务部署验收通过。没有在本轮覆盖范围内发现新的基本运行阻断，但不能据此宣称全部业务路径、故障场景和生产级消息可靠性已完整实现。

本轮只修改忽略规则、README、相关文档链接及 Git 跟踪状态；业务源码、测试代码、SQL、Dockerfile、Profile、Maven 配置和部署脚本均以文件哈希验证保持本轮开始时的内容。上一轮 Compose 改动原样保留，没有提交、推送或重写历史。

未读取或展示真实 `.env` 内容、JWT 密钥、数据库密码或原容器日志。本轮测试使用合成凭据与临时环境，没有对现有业务库、Redis 或 MQ 执行写测试。

## 2. 本轮整理结果

### 空目录

删除前逐一确认目录为空，使用非递归 `rmdir`；没有删除非空目录、文件、符号链接目标或 Git/IDE 元数据。构建会重新生成的空目录在验收后再次清理，去重后共 **15** 个路径：

- `benchmarks/results/redis-cache-20261022-185550/build/target/generated-sources`
- `benchmarks/results/redis-cache-20261022-185550/build/target/generated-sources/annotations`
- `docs/sql`
- `scripts/day17`
- `scripts/day18`
- `src/domain`
- `src/exception`
- `src/repository`
- `src/result`
- `src/service`
- `src/test/java/com/resq/day17`
- `target/generated-sources`
- `target/generated-sources/annotations`
- `target/generated-test-sources`
- `target/generated-test-sources/test-annotations`

Git 本身不跟踪空目录，因此这些删除通常不会出现在 Git diff。后续 Maven 构建重新生成 `target/generated-*` 空目录属于正常现象。

### 停止 Git 跟踪的 Markdown

以下四份文件已从 Git 索引移除，并加入精确的 `.gitignore` 规则；本地副本逐字节保留：

- `benchmarks/benchmarks.md`
- `benchmarks/results/post-reservation-20261022-182544/day24-benchmark-report.md`
- `benchmarks/results/redis-cache-20261022-185550/day24-redis-cache-conclusion.md`
- `benchmarks/results/redis-cache-20261022-185550/day24-redis-cache-report.md`

选择依据：旧总汇已经被三份正式压测报告替代，并仍引用旧目录；另外三份为同一实验的阶段性报告/结论快照。保留现行正式报告、ZIP 原始证据、清单和 CSV/JSON；没有把整个 docs/ 或所有 Markdown 忽略。

同步更新 `benchmarks/README.md` 和两份正式报告的快照链接说明，避免新克隆依赖已停止跟踪的 Markdown。历史 manifest、layout-migration 和脚本保留原记录，不改写证据内容；这些历史脚本不是新环境验收入口，其再次生成的快照仍会被忽略。

主 `README.md` 新增项目总览、快速启动、停止方法、功能边界和文档导航，列出全部 15 份现行下级 Markdown 文档。学习归档索引保留在 Git，实际 `learning-archive/` 继续只保留本地。

## 3. 本次重新执行的验证

| 验证 | 实际结果 | 范围及限制 |
| --- | --- | --- |
| `mvn -o -B clean package` | 102 项测试通过，0 失败、0 错误、0 跳过，JAR 成功生成 | 编译全部测试源码；默认不运行 integration 分组。使用已有 Maven 缓存，不是无缓存下载验收 |
| `scripts/test_compose_safety.py` | 7 项通过 | 无需中间件的部署工具保护测试 |
| `scripts/test_timeout_failure_policy.py` | 7 项通过 | policy 配置工具的安全约束 |
| `scripts/test_integration_runner.py` | 2 项通过 | 集成测试入口保护 |
| `scripts/run_integration_tests.py` | 报告 37 项：29 通过、8 跳过，0 失败/错误 | 新建 MySQL/Redis/RabbitMQ 临时容器；原容器状态不变、原卷保留；三个临时容器已清理 |
| `scripts/verify_compose_deployment.py` | 9 个验收阶段全部通过 | 真正构建镜像并在独立 Compose project、网络、三个新卷中启动四服务；不是 9 个 JUnit 测试 |

默认构建编译 63 个主源码文件、26 个测试源码文件。构建未依赖跳过测试参数。集成测试中跳过的是 `ReservationTimeBoundaryIT` 的 7 项和 `TimeoutFailureRoutingIT` 的 1 项，原因是本次通用入口没有提供它们专用的启用条件及 guard；**跳过不计为通过**，本轮没有重新执行这两个专用套件，也没有借用开发服务绕过保护。

集成测试实际覆盖 Mapper、缓存 HTTP、Redis 故障、取消冲突/回滚、真实锁竞争及事务。测试范围不同，不把不同入口的数字直接相加作为独立业务场景数。

### 四服务部署验收的实际内容

1. 独立 project、网络和卷归属检查。
2. 缺少必填配置时拒绝启动。
3. 依赖就绪、失败路由 policy 初始化及四服务健康。
4. 重启策略、日志轮转、应用非 root 用户。
5. 从现行 `deploy/init/01-schema.sql` 初始化五张业务表。
6. JWT 注册登录、未认证写请求拒绝、资源与缓存路径。
7. 预约创建、相同请求重试幂等、取消、重复取消冲突、重新预约与确认。
8. 终止临时应用进程后，容器自动重启并恢复健康和已有数据访问。
9. 重建临时依赖与应用后，MySQL 预约、Redis 标记及 RabbitMQ policy 保留，已有登录令牌仍可访问。

临时项目 `resq-compose-it-8f720b1a22da` 已清理 4 个容器、1 个网络、3 个临时卷，并删除本轮唯一镜像。清理前已验证归属；原有资源前后比较通过。此前保留的部署镜像标签未被覆盖。

本次结果原始位置：

- 集成测试：`/home/colaminos/resq/target/integration-results/20261024T062148Z-535c17f7b0/summary.json` 和 `cleanup.json`。
- Compose 验收：`/home/colaminos/resq/target/compose-acceptance/20261024T062237Z-8f720b1a22da/summary.json`。
- 持久副本：本次交付附件 `ResQ-项目现状验收证据-2026-10-24.json`。target/ 报告会被下一次 clean 移除，不纳入源码 Git。

## 4. 当前功能与部署状态

| 模块 | 根据当前代码/执行结果确认的内容 | 尚未证明或尚未实现的部分 |
| --- | --- | --- |
| 注册登录与鉴权 | BCrypt 密码校验、JWT 登录、用户/预约/候补鉴权；资源读取公开，资源写入需要 JWT | 没有管理员角色分级；本轮未执行专门的恶意输入/暴力登录测试 |
| 资源与时隙 | 资源列表/详情/创建、按资源和日期查时隙，Cache Aside | 新库没有演示资源/时隙；没有正式创建时隙 HTTP 接口。验收脚本只向自己的临时库准备时隙 |
| 预约与并发 | 创建、确认、取消、超时条件流转，事务与条件更新，Redisson 锁，Redis Lua 限流，请求 ID 与数据库回查幂等 | 单时隙控制不等于任意业务模型的有效预约唯一约束；本轮未重复持续压测 |
| 缓存 | 提交后删除、回滚不删、失败重试和 TTL，真实缓存/故障测试通过 | 不是强一致读取；进程内重试队列不能跨进程恢复 |
| MQ 与超时 | 提交后发送事件、超时消费事务与 eventId 去重、消费失败有限重试/失败路由，定时扫描补偿过期预约 | 发送失败没有持久化补发；普通通知仅写日志；本轮未重新执行专用 MQ 失败路由 IT |
| 候补 | Redis Lua 原子入队、查询本人排名/队列规模、时效校验 | 没有取消/超时后的自动递补，也没有候补持久化恢复验收 |
| 工程部署 | Compose 已含四服务，prod 显式映射、健康等待、日志轮转、重启策略、非 root、卷持久化；全新隔离环境验证通过 | 使用本机现有基础镜像缓存；不是另一台全空机器或远程公开部署验收 |

核验时原项目中已有 `deploy-app-1`、`deploy-mysql-1`、`deploy-redis-1`、`deploy-rabbitmq-1`，均处于 Exited 状态，与用户刚执行停止相符。本轮未启动、重建或修改它们，不能据隔离环境 UP 声称原环境此刻在线健康。三个原卷 `deploy_mysql_data`、`deploy_redis_data`、`deploy_rabbitmq_data` 全部保留。

## 5. 完善与改进建议（未在本轮实施）

### P1：建议在交付前明确处理或记录边界

**A. MQ 发送成功尚无闭环证据【已确认实现缺口】**

- 位置：`src/main/java/com/resq/mq/ReservationEventPublisher.java:33–64`、`src/main/java/com/resq/mq/ReservationTimeoutPublisher.java`。事务提交后发送，异常只记日志；当前配置没有 Publisher Confirm/Return 的完整处理链路，也没有持久化补发记录。
- 影响：数据库成功与 MQ 消息发送之间存在故障窗口。预约超时有 `ReservationExpirationScheduler` 扫描兜底，但它不能补回所有通知消息，不能宣传“消息绝不丢失”。
- 建议：先补发送失败、不可路由和重复消费测试，并在交付说明中写清现有保证；若确需保证最终投递，再采用单库 outbox/发送任务表与有限重试，不新增中间件。
- 预计涉及：两个 Publisher、MQ 配置/测试；选择持久化方案时新增表与迁移。持久化方案涉及业务和旧库 DDL，需要另批确认。

**B. 候补不是完整递补链路【已确认功能边界】**

- 位置：`src/main/java/com/resq/service/WaitlistService.java:45–113`、`src/main/java/com/resq/controller/WaitlistController.java:19–35`、`src/main/resources/lua/waitlist_join.lua`。
- 影响：用户能入队、查排名，但取消/超时释放后不会自动获得预约。不能把“有序候补队列”描述为“自动调度完成”。现有测试没有候补专用套件。
- 建议：当前先按真实能力交付，补重复入队、过期/不存在时隙、并发排名和 Redis 失败测试；若增加自动递补，应明确是否自动预约、确认期限和公平规则，再设计事务与幂等。
- 预计涉及：候补测试/文档；自动递补另涉及 WaitlistService、预约释放路径、事件和数据一致性，需先确认业务规则。

**C. 专用 P0 测试入口【后续已解决；以下为历史问题记录】**

- 位置：`scripts/run_integration_tests.py`、`src/test/java/com/resq/service/ReservationTimeBoundaryIT.java:30`、`src/test/java/com/resq/mq/TimeoutFailureRoutingIT.java:27`。
- 影响：正常集成命令显示 8 项跳过，读者易误判为全部验收。
- 建议：增加显式子命令或独立可复现入口，为这两个套件创建专属容器/guard，不能简单去掉启用条件或指向开发库。验收标准是两套件真实执行、0 跳过，并验证失败路由与并发时间边界。
- 预计涉及：隔离脚本、脚本保护测试、docs/testing.md；不应为启用测试修改正确业务逻辑。

**D. 最小演示数据准备入口【后续已补齐；以下为历史问题记录】**

- 位置：`deploy/init/01-schema.sql:24–102` 只有表结构，`ResourceController.java:27–60` 无时隙创建端点；部署验收用自己数据库中的合成时隙准备业务路径。
- 影响：新用户能启动空库，但仅靠 HTTP 不能自行准备完整预约演示，不应宣称 clone 后天然具有可预约数据。
- 建议：提供明确只面向全新 demo 环境、可重复执行的演示数据脚本和 HTTP 演示步骤，或另批设计时隙管理接口；避免默认给旧卷写数据。
- 预计涉及：独立 demo 初始化脚本、文档和验证；任何现有数据库写入需另行确认。

**E. 完成变更提交后才算仓库交付【已确认】**

- 当前 Compose、模板、启动/验收脚本、README、本文等仍有未提交内容；本轮只有取消跟踪的四项删除进入暂存区，这是 `git rm --cached` 的效果，不代表磁盘文件被删除。
- 建议：审查本轮和上一轮 diff，按部署、文档整理分批提交并推送。明确加入 `.env.example` 和现行脚本，真实 `.env`、target/、learning-archive/ 不加入。提交后再以临时 clone 验证默认构建、文档导航和部署入口，不依赖当前工作目录未跟踪文件。
- 本轮没有自动提交或推送；历史材料仍可从过去提交找回。

### P2：按收益选择，不影响目前演示

**F. 缓存一致性边界【已确认设计限制，竞态为潜在风险】**：`ResourceSlotCacheInvalidator.java:41–43` 使用内存重试队列，定位 slot 查询失败时 `:63–70` 直接返回；失败或进程退出后可能依赖 TTL 恢复。Cache Aside 仍有并发旧读回填风险，本轮没有注入该竞态。先补并发回填/重启测试，并明确最多约 300 秒 TTL 的数据陈旧边界；如演示要求更严格，再讨论版本化或补偿，预约正确性继续由数据库判定。

**G. SQL 初始化与查询索引【已确认】**：`01-schema.sql` 用普通 CREATE TABLE，不能直接对已有 schema 重复导入；同时保留历史 AUTO_INCREMENT 起点，无正确性影响。`ReservationMapper.xml:53–55` 按 status/expire_at 扫描，但当前预约表没有对应联合索引。先用真实数据规模和 EXPLAIN 衡量，再考虑 `(status, expire_at, id)`；结构演进使用版本化迁移，不覆盖旧库或重新初始化卷。

**H. 剩余学习组件【已确认】**：`ResourceLabMapper`、`ResourceXmlLabMapper`、`DelayProbePublisher` 仍在主源码，职责与限制见 docs/mybatis-mapping.md。本轮未移动它们；后续如清理须同时核对 Bean 扫描、XML 打包与现有 Mapper 测试，不能按 Day 字样批量删除。历史 TODO 和日志阶段名可另批规范化。

**I. Git 历史大对象【已确认】**：当前索引没有 JAR，但历史对象 `2a064b32734ddd44b6c206b667c04f9e3fadb116` 中仍有 `benchmarks/results/redis-cache-20261022-185550/build/target/resq-0.0.1-SNAPSHOT.jar`，58,305,371 字节，约 55.6 MiB。忽略规则/取消跟踪不减少旧历史体积。现阶段保留历史即可；只有确需瘦身且已备份、协调协作者时，才另行讨论历史重写与强制推送，本轮不执行。

**J. 安全与交付范围【部分检查，非全面安全认证】**：当前索引没有名为 `.env`、`.key`、`.pem` 的敏感文件，真实 `deploy/.env` 仍被忽略；本轮未读取秘密值，不能据文件名检查声称历史完全没有泄露。登录限流、管理员资源权限、依赖漏洞/供应链和完整 HTTP 示例可按需要补齐，不能在未测前声称均已覆盖。

## 6. 建议后续顺序与验收标准

1. 独立进行：审查并提交当前部署/文档改动，临时 clone 验证；保留本机秘密和归档。标准：远端所需文件齐全、文档无未跟踪文件依赖、默认构建通过。
2. 两套专用 P0 测试隔离入口已完成（见后续补充）；仍可独立补候补/消息发送测试与最小演示步骤。标准：真实执行、报告不把跳过当通过、原容器与数据保持不变。
3. 需要确认业务范围：决定候补是否做自动递补、通知是否需要最终投递保证。若仅作为实习项目演示，可保留现有实现并准确标注；若实现则分批修改，每批验证事务、幂等与失败恢复。
4. 涉及数据风险需先确认：outbox/索引/迁移、给已有库补演示数据、清理旧实验数据、重写 Git 历史。先给方案、备份与回滚办法，不与目录整理混做。

本轮交付已经完成目录清理、非交付 Markdown 停止跟踪、README 导航及实际隔离验收。上述改进项是后续计划，不表示已实施。原项目维持停止状态，已有数据保持原样。
