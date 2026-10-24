# ResQ v1.0 统一演示系统评估（待实施）

本文件基于当前实际代码评估方案，不代表 scripts/demo/prepare.py 或 run.py 已存在。本轮只实现最小种子脚本及验证，不实现下面的演示编排，不启动原环境，不新增业务接口。

## 1. 结论与适用定位

建议采用 A 基础业务、B 并发与请求幂等、C 异步与故障处理、D 缓存与候补的划分。将它作为 ResQ v1.0 的统一命令行演示套件即可，不需要额外前端、管理后台或第二套预约业务代码。

目前已有不少验证能力，但不能只给现有测试套一个菜单就宣称全部演示完成。还缺真实 MySQL/RabbitMQ 下的重复 eventId 消费验证、可保持现场的 demo 生命周期、完整缓存失效/重建断言以及候补专用场景。性能压测和演示分开，现场 50 请求竞争不表述为系统最大并发能力。

## 2. 当前可复用部分与缺口

| 来源 | 可以复用 | 不应直接照搬 |
| --- | --- | --- |
| scripts/start_compose.py | compose_command、启动等待、policy 后启动 app 的顺序 | CLI 默认目标 deploy，不用于默认演示；CLI 当前不接收 override 参数 |
| scripts/verify_compose_deployment.py | 临时项目归属检查、配置清理、HTTP 请求、容器/卷身份、健康检查 | HTTP/SQL 等函数局部定义在 main 内；固定 2099 日期；主流程会重启、重建并自动清理，不能直接用作保持现场的 demo prepare |
| scripts/run_integration_tests.py | 原有 29 项和专用 8 项的完整验收入口 | 测试重编译和多次容器创建不应全部塞入 13 分钟实时演示 |
| scripts/run_p0_integration_tests.py | 8 项的专属实例、guard、零跳过断言、失败路由演示 | MQ IT 模拟 consumeService，不连接真实消费事务数据库，不能证明消费者幂等 |
| scripts/configure_timeout_failure_policy.py | 新 broker 初始化失败路由，冲突保护 | 只能作用于已通过归属验证的 demo/test broker，不默认指向 deploy-rabbitmq-1 |
| scripts/seed_demo_data.py | 本轮已完成的 2 资源/8 动态未来时隙、场景 ID 清单、空库拒绝 | 不自动创建用户/预约，不导入现有库，不提供运行环境生命周期 |

复用建议：少量提取 HTTP、SQL/Redis 只读探针、合成身份、归属与安全输出等共有函数；原验收脚本和新演示入口调用它们。只提取真正重复的代码，不大规模重写既有测试，不让演示脚本直接调用 Service 方法绕开 HTTP。

## 3. 数据、环境与运行规则

- 每次 prepare 默认新建 session，项目名 resq-demo-<随机后缀>，使用独立网络、三卷、回环端口及合成凭据。原 deploy 项目不接触。种子前 app 不运行，先验证空库再插入，随后配置 MQ policy、启动 app。
- session 私有配置/凭据保存在仓库外（如 ~/.local/share/resq/demo/<session>/），权限 0700/0600；不能放进会被 Maven clean 删除的 target/，也不能提交到 Git。可公开结果另存脱敏报告。
- 生成 manifest，记录版本、场景语义、动态资源/时隙 ID 和实际 profile/超时配置。密码和 JWT 只在运行内存或私有状态中，终端与公开报告只输出必要 ID/状态。
- 八个种子时隙各自承担场景；每个模块按需注册专用用户，生成本轮 requestId，避免限流和状态相互干扰。缓存模块使用独立资源/日期，不因 basic 的查询提前暖缓存。
- 重复运行 all/basic 等默认使用新 session，或显式新建场景数据；不能假设已确认/已过期时隙能重新使用。resume 只恢复同 session 状态，不悄悄重置数据，跨日过期时明确要求新建。
- prepare、run、cleanup 读取同一 session 身份，校验精确容器 ID、项目和 guard。cleanup 独立命令且只清理本次标记资源；不执行全局 prune/FLUSHALL。失败默认保留脱敏证据与现场状态，不自动重跑写请求掩盖失败。

建议最终入口维持用户提出的 prepare/basic/concurrency/advanced/all，另外补充 status/cleanup 与可选 session 参数。没有必要把每个模块拆为一个服务。

## 4. 四模块的断言与证据

### A. 基础业务

1. HTTP 注册用户 A/B，并登录；额外验证匿名受保护接口被拒绝。通过 HTTP 创建一个演示资源验证资源写入接口，再查询已种子的 GPU/CPU 和未来时隙。
2. 在 basic-confirm 创建 PENDING 后立即确认，断言同一预约为 CONFIRMED，时隙仍 RESERVED。
3. 在 basic-cancel 创建另一条预约后取消，断言 CANCELLED、时隙 AVAILABLE；重复取消返回 409，不能释放其他新预约。
4. HTTP 与数据库按动态 ID 交叉检查。确认为终态的预约不拿来做取消演示；默认全局短期限可能导致讲解期间自动过期，所以这两条创建后应立即操作。

### B. 并发唯一性与请求幂等

- 并发演示使用 50 个已注册登录用户，每个用户一次预约，互不相同 requestId，共同屏障竞争同一时隙。代码按用户每自然分钟限额 10，见 ReservationRateLimiter.java；用一个用户发 50 次会主要测到限流。默认不关闭或提高业务限流。
- 严格验收恰好 1 次创建成功、数据库有效预约（PENDING/CONFIRMED）为 1、时隙 RESERVED；429 必须为 0，否则本轮竞争测试不合格。409 必须核对为库存竞争，503 单独统计为忙/等待，不混报为数据库冲突；其他技术失败判失败。不将客户端并发数量等同于同时执行 SQL 的数量。
- 请求幂等用另一个用户和独立时隙，先顺序重放少量相同 requestId，断言同一预约 ID、数据库 request_id 仅一条；再换 slotId 或用户重放，要求 409。相同请求并发重放可以有 PROCESSING 503，不能要求所有瞬时响应都是 201；随后按有限重试观察同一最终预约。每用户请求总数控制在限流额度内。
- 并发结束立即确认获胜预约，避免短确认期导致随后统计到 0 条有效预约；同时保留确认前的数据库快照。演示不声称 reservation.slot_id 有有效预约唯一索引；实际依赖锁、条件 UPDATE 与事务。

### C. 超时、消费者幂等与失败处理

**超时配置已有共用入口。** ReservationService.java:26 与 ReservationTimeoutMqConfig.java:31–68 都读取 resq.reservation.confirm-timeout-seconds；后者自动计算 TTL，并把秒数加入等待队列/路由键。建议只在 demo overlay 中设置 60 秒，启动后核对 expire_at 和等待队列 TTL=60000。无需改业务代码或全局 prod 默认 600 秒。此处 60 秒是建议，尚未现场验证。

**必须区分 MQ 和扫描补偿。** ReservationExpirationScheduler 默认 10 秒后首次扫描、每 60 秒扫描一次。仅看 EXPIRED/AVAILABLE 可能是扫描完成的，不能据此证明延迟消息已经消费。演示 MQ 路径时仅在 demo 环境延后扫描首轮（如 30 分钟），按期限轮询，并同时要求 mq_consume_record 中预期 eventId 存在；扫描补偿另用既有专用测试或独立场景说明。轮询应有上限，不假定恰好第 60 秒调度完成。

**消费者幂等要新增真实数据验证。** ReservationTimeoutConsumeService.java:35–65 在事务中校验 eventId、插入 mq_consume_record 再执行过期。合法 eventId 固定为 reservation-expire-<reservationId>；重复演示必须复用它，不能随意生成不同 ID。现有 TimeoutFailureRoutingIT.java 使用 mock 消费服务，只证明重试/失败路由，不能证明数据库去重。

建议在真实 demo MySQL/隔离 broker 中对已经到期的专用预约投递同一个合法事件两次，验证消费记录为 1、状态只发生一次有效转换、时隙 AVAILABLE、无额外失败消息；再安排一个后续新预约检查旧事件不会释放其时隙。只看去重日志或最终 AVAILABLE 不足以证明这些性质。若 HTTP confirm/调度竞争，应以数据库事务结果为准。

**失败队列使用专用测试，而非破坏主 demo 的数据库。** 复用现有 1 项 MQ IT，在它自己的 broker 中验证异常/非法 JSON 各重试 3 次进入 failed 队列，正常消息确认。终端显式显示“独立故障测试环境”，不要假装它是主 demo 的真实数据库故障。日志是辅助证据，断言应包含队列内容、消息头和重试次数。

### D. 缓存和候补

- 缓存专用日期初始 Key 不存在 → HTTP 查询 → 精确 Key 存在且内容为 AVAILABLE → 创建预约提交后、下一次查询前 Key 不存在 → HTTP 再查 → Redis 内容/HTTP/数据库均为 RESERVED。
- 取消上述 PENDING → 缓存再次失效 → 查询重建为 AVAILABLE。使用未超时的预约，步骤之间有界轮询，不以一次固定 sleep 替代断言。禁用读取旁路的并发演示客户端，避免刚删完被其他查询立即回填。
- 不用 FLUSHALL/FLUSHDB；正常应选新日期无缓存 Key，必要清理只允许 manifest 指向的本场景精确 Key。只看到 EXISTS=1 不能证明内容正确或减少了 SQL，不能顺带宣传已测得缓存加速百分比。
- 候补必须先让专用时隙 RESERVED，再让两个不同非持有用户依次入队，查询本人排名为 1/2，队列规模为 2；重复加入第一个用户，排名和规模不变。顺序入队才能确定谁是第一名，不把并发入队的用户顺序写死。
- 不演示取消后自动递补，当前代码没有这条路径；不把模拟通知日志描述为邮件/短信发送。

## 5. 10–15 分钟现场安排

镜像拉取、Maven 编译、完整 37 项集成验收以及多用户注册登录都在正式演示前准备好并记录结果；这些耗时不能算进“打开终端即 13 分钟”。现场从已经健康的独立 demo 环境开始。

| 阶段 | 预算 | 现场内容 |
| --- | --- | --- |
| 健康检查 | 1 分钟 | 四服务、health、版本/session 与隔离范围 |
| A | 3 分钟 | 注册登录、资源/时隙、两条预约分别确认与取消 |
| B | 3 分钟 | 50 用户竞争、SQL 最终断言、请求重放/参数冲突 |
| C | 3 分钟 | 60 秒期限实时演示、真实去重；故障路由可展示本次预跑证据或显式调用专用 IT |
| D | 3 分钟 | 缓存三方状态变化与候补去重/排名 |

准备步骤与演示步骤分别报告耗时。超时等待可穿插解释或只读展示，但不能在预约创建后停讲太久导致其他场景意外过期。待实现后完整彩排两轮，测量真实耗时，再承诺 10–15 分钟。

## 6. 实施批次与验收门槛

1. 本轮已完成：最小 seed、8 项离线保护测试和 5 个真实 MySQL 验证阶段，未改业务代码。
2. 待批准：prepare/session/status/cleanup，加薄的共有安全/HTTP 工具；接入 seed、policy 与四服务健康。标准：新项目可启动，错误归属/非空库拒绝，原容器与卷不变。
3. 待批准：basic 与 concurrency；输出动态 ID 和 SQL 证据，处理 HTTP 状态分类/限流。标准：独立运行和 all 都可按 session 复现，失败非零退出。
4. 待批准：advanced；新增真实 MySQL+MQ 消费幂等验证、短期限 MQ 归因、缓存重建和候补断言，复用专用故障测试。标准：全部实测且证据分清主 demo、独立 IT 和历史报告。
5. 待批准：彩排、脱敏 JSON/Markdown 报告、主持人讲解提纲与版本发布。不要在实现前写 v1.0 已完成，也不要将脚本输出示例当实测结果。

用户提出的八条验收要求均应保留。额外门槛是“证据能对应具体链路”：时隙释放有可能来自扫描，消费者去重不能只由 mock 证明，缓存 EXISTS 不能替代状态内容验证；这些是现有能力整合时真正需要补的部分。
