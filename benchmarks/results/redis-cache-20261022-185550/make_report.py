import pathlib,json,datetime,statistics
root=pathlib.Path('/home/colaminos/resq/benchmarks/results/d24cache_20261022_185550_9afb96')
a=json.loads((root/'analysis.json').read_text()); final=json.loads((root/'final-verification.json').read_text()); life=json.loads((root/'application-lifecycle.json').read_text()); mf=json.loads((root/'fixtures-manifest.json').read_text())
assert len([x for x in a['blocks'] if x['accepted_main']])==12
pooled={(x['vus'],x['arm']):x for x in a['pooled']}; main=sorted([x for x in a['blocks'] if x['accepted_main']],key=lambda x:x['start_utc']); sens=[x for x in a['blocks'] if not x['monitor_enabled']]
f=lambda x:f'{x:.2f}'
def local(s): return datetime.datetime.fromisoformat(s).astimezone(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S')
table='| VU | 组别 | 两轮请求数 | 实际 req/s 范围 | avg ms | median ms | P95 ms | P99 ms | HTTP / 业务失败 |\n|---:|---|---:|---|---:|---:|---:|---:|---|\n'
for n in [50,100,200]:
 for arm in ['hot','cold']:
  x=pooled[n,arm]; table+=f"| {n} | {'热命中' if arm=='hot' else '首次 miss＋回源＋回填'} | {x['requests']} | {f(x['rps_range'][0])}–{f(x['rps_range'][1])} | {f(x['avg_ms'])} | {f(x['median_ms'])} | {f(x['p95_ms'])} | {f(x['p99_ms'])} | 0% / 0% |\n"
individual='| 轮次 | 请求数 | req/s | avg | median | P95 | P99 | 时隙 digest Δ | 连接 SELECT Δ − 扫描 Δ | 返回行 Δ |\n|---|---:|---:|---:|---:|---:|---:|---:|---|---:|\n'
for x in main:
 individual+=f"| {x['label']} | {x['requests']} | {f(x['actual_rps_k6'])} | {f(x['avg_ms'])} | {f(x['median_ms'])} | {f(x['p95_ms'])} | {f(x['p99_ms'])} | {x['db_shared_digest_slot_selects']} | {x['db_connection_selects']} − {x['db_background_scans']} | {x['db_rows_sent']} |\n"
sensitivity='| 200 VU，无 MONITOR | 请求数 | req/s | avg ms | median ms | P95 ms | P99 ms | 时隙查询核验 |\n|---|---:|---:|---:|---:|---:|---:|---|\n'
for x in sens:
 sensitivity+=f"| {x['arm']} | {x['requests']} | {f(x['actual_rps_k6'])} | {f(x['avg_ms'])} | {f(x['median_ms'])} | {f(x['p95_ms'])} | {f(x['p99_ms'])} | {x['db_connection_selects']} − {x['db_background_scans']} = {x['db_slot_selects']}；返回 {x['db_rows_sent']} 行 |\n"
ratios='；'.join(f"{n} VU 的热组平均延迟比冷组低 {(1-pooled[n,'hot']['avg_ms']/pooled[n,'cold']['avg_ms'])*100:.1f}%" for n in [50,100,200])
senshot=next(x for x in sens if x['arm']=='hot'); senscold=next(x for x in sens if x['arm']=='cold')
senssentence=f"关闭 MONITOR 后，200 VU 热组 avg/P95/P99 为 {f(senshot['avg_ms'])}/{f(senshot['p95_ms'])}/{f(senshot['p99_ms'])} ms，冷组为 {f(senscold['avg_ms'])}/{f(senscold['p95_ms'])}/{f(senscold['p99_ms'])} ms。"
text=f'''# ResQ Day24 Redis 缓存性能对照实验

实验编号：`{root.name}`。时间：{local(life['start'])}–{local(life['stopped_observed'])}，Asia/Shanghai（UTC+08:00）。

在本次隔离数据集和受控请求节奏下，Redis 热命中的平均延迟低于每请求首次 miss、数据库回源并回填的路径。{ratios}。两组都保留正常缓存逻辑；**本实验没有测“关闭 Redis 缓存”的系统，也没有测最大吞吐量。**

## 主要结果

主对照每档每组两轮，每个 VU 固定执行 30 次、每次请求后 `sleep(1)`，共 42,000 次请求。下面分位数从两轮逐请求 `http_req_duration` 样本合并后重算，未平均各轮分位数。实际 req/s 是 k6 输出的速率，包含执行与收尾时间；sleep 会主动限制速率。

{table}
所有主对照完成预定迭代，0 中断，k6 退出码均为 0。HTTP 失败是 k6 对网络错误/非预期 HTTP 的统计；业务失败独立校验响应为精确的两个专用 slotId、resourceId、日期、时间和 AVAILABLE 状态。两项失败率均为 0%，每请求两项 check 均通过。冷热同轮每个 index 的日期和响应字节数一一一致，原始记录见 `*.requests.csv`。

## 对比口径与数据

只测 `GET /api/resources/24000001/slots?date=...` 的时隙缓存层。ResourceCache 资源详情层在两组测量开始前均通过正常 GET 预热；必要的精确删除仅限本实验拥有的 `resource:24000001`，在计时之外完成。主组观测到每次请求一次资源 GET、无资源 SET，排除了资源详情层回源混入。

专用资源名为 `{mf['resource_name']}`。隔离库保留原库真实表结构及索引，仅插入一个新资源、6,120 个日期、12,240 条明确标记的未来 AVAILABLE 时隙；其中主池 6,000 日期、12,000 行，辅助池 120 日期、240 行。每日期恰好 2 行，和原 `resourceId=111/date=2026-10-25` 的两条时隙在行数上相同。主池为 {mf['dates'][0]['date']}–{mf['dates'][-1]['date']}，不同日期用来生成独立业务缓存键。未复制/创建用户、预约、候补或业务消息。

每档热、冷组使用相同的前 N×30 个日期、相同数据、相同顺序。索引为 `iteration×VU数 + VU序号−1`，一轮内每个键只请求一次。冷组测前仅按 manifest 精确删除本实验 DB15 中目标时隙键；每个请求均为首次访问，正常查 Redis、查 DB、写回 Redis。热组先经正常 HTTP 预热相同全部键，测前断言全部存在且最短剩余 TTL >60 秒。数据在冷组结束后也回填，但没有在该轮再次读取该键，因而不会将后续命中误称回源。

MySQL 数据页/操作系统缓存未清空，测试数据由插入、预热、前序请求自然加热。这里“冷”仅指 Redis 时隙键缺失，不表示磁盘冷读。数据库层只有一项独立合成数据规模，不能外推到大型业务库。

## 源码只读核查

- `ResourceCache`：键 `resource:<id>`；正常值 TTL 为 300 秒加 0–60 秒随机量；不存在资源缓存 `__NOT_FOUND__`，TTL 30 秒。正常命中直接返回；空值命中直接 404；miss、Redis访问异常或解析异常进入 DB 路径，正常资源/不存在资源分别回填。
- `ResourceSlotCache`：键 `resource:<resourceId>:slots:<YYYY-MM-DD>`；TTL 为 300–360 秒。包括 `[]` 的非 null 列表均视为命中；缺键、损坏内容或 Redis异常视为 miss。
- `ResourceService`：带 date 时先执行资源存在性逻辑，再查时隙缓存；miss 调用 `findByResourceIdAndDate` 并回填，包括空列表。不带 date 时绕过日期时隙缓存、直接查全部时隙，因此没有用它冒充相同接口的“无缓存基线”。
- JDBC 查询条件为 `resource_id=? AND slot_start>=? AND slot_start<? ORDER BY slot_start,id`；真实表有 `(resource_id,slot_start)` 唯一索引。DDL、EXPLAIN、fixture查询与源码快照均已留存。

## 环境、安全隔离与控制变量

确认先前 POST 目录 `benchmarks/results/d24_20261022_182544_3163fc` 存在，先前临时实例已停止；本次开始时 8080 未监听。已有 Java 进程是 VS Code Java language server，没有停止它。原库当时有 3 条到期 PENDING，原 Rabbit timeout dead 队列有 3 条消息，failed 队列有 2 条；直接普通启动可能消费或补偿它们。

因此本次用原始未修改源码构建独立实例，临时环境变量指向数据库 `{root.name}`、同名 RabbitMQ vhost、Redis DB15（启用前只读确认空）。专用 DB 账户仅能写实验库并只读 performance_schema，专用 MQ 账户仅有实验 vhost 权限。正常 scheduler、消费者、认证、限流、缓存均保留，没有绕过业务约束。实际 MySQL连接、Rabbit连接和 Redis CLIENT/键记录证实隔离生效，原 DB0 不存在实验资源键。

所有构建输出位于本结果目录 `build/`，独立构建描述仅设置原始源码/资源的读取路径及 benchmarks 内输出路径；原 `pom.xml`、Java、SQL、配置不变。运行 Java 21.0.12.1、k6 2.3.0、MySQL 8.0.46、Redis 7.0.15，固定一个 JVM、`-Xms512m -Xmx512m`。先在辅助日期完成 2,100 次请求预热，再执行正式对照；中途没有重启 JVM。

原配置的 cache 与 ResourceService 为 DEBUG。本次仅通过 `SPRING_APPLICATION_JSON` 环境变量将 root、`com.resq.cache`、`com.resq.service.ResourceService` 设为 INFO，未编辑配置文件。实际应用日志 DEBUG 行数为 0。这控制住了此前 ConsoleAppender 竞争风险，但不能声称已通过线程剖析排除了全部锁等待。旧 GET 50/100/200 VU 是 30 秒定时测试，本次为每 VU 固定30次，两者请求节奏接近而非完全相同；不能直接用旧数据算缓存收益。

主对照按 50：cold1→hot1→hot4→cold4，100：hot1→cold1→cold2→hot2，200：cold1→hot1→hot2→cold2 交替。热组预热并行度16，不计入计时；两组正式 k6 请求、明细输出、原生 JSON指标输出、连接复用与监控一致。固定每 VU 30 次确保两组请求数量及日期集合完全相同，实际执行约30秒，精确时段在各 `.evidence.json` 中。

## 缓存与数据库证据

低强度顺序验证：10个缺失键首次请求新增10次时隙摘要查询、回填10个键；随后重复读取相同10键新增查询0，响应内容一致。2 VU×5次 k6检查全部通过。正式主组在本次Redis DB15的实验键上观测到 N 个唯一时隙 GET：热组起始全存在、SET=0；冷组起始全缺失、SET=N；结束均全存在。所有主组前置资源 GET=N、SET=0。离线还核对轨迹键集合与 manifest 完全一致。

Redis MONITOR 不返回 GET 值，命中/回源由键状态、专用命令轨迹、业务响应及 DB计数交叉判断。MONITOR流中仅持久化本实验DB/键的时间戳、GET/SET和键名；值及其他命令全部丢弃。Redis INFO统计是实例全局，仅保存为背景，未当成本实验专属命中率。

最初 `50vu-r2-cold` 的 HTTP/业务、1,500次唯一 GET和1,500次SET均通过，但共享 SQL digest COUNT增量仅1,498，原因未确认。原始文件完整保留，**该轮及对应 hot2 不纳入主结果**；没有将1,498改写为1,500。随后增加稳定10条应用连接的线程级 SELECT计数、单线程到期扫描摘要与返回行数交叉核验。50 r4的连接计数减扫描为冷1,500、热0；100/200每个冷轮还观察返回2N行，热轮0行。共享digest若与连接口径不同，在表中原样并列，不强称它是精确审计计数。线程/连接身份及所有相关业务 SELECT摘要快照可复核。

以下均为单轮原始耗时统计，单位ms；“not collected”表示当时没有采集，未补造。

{individual}
## MONITOR关闭后的敏感性复测

MONITOR是Redis实例级观测，即使只保存实验键，Redis仍会发送观测流；冷组额外SET及其value会增加流量，因此存在非对称观测开销。主表应理解为“相同MONITOR与k6明细输出条件下”的结果。

主套件之后，同一JVM补做200 VU cold3→hot3，各6,000次、sleep(1)，关闭MONITOR，仍保存k6原生指标、逐请求记录、精确键状态与数据库连接计数。两组HTTP和业务失败率均为0。

{sensitivity}
{senssentence}该复测仅一对，仍受顺序及共享主机波动影响；它用来判断观测敏感性，不取代主组或代表生产环境性能。

## 事实、推断与限制

**观察事实**：上述原始请求、分位数、键状态、命令数量、SQL不同计数口径和返回行数均来自本次实际执行。主表每档每组只有两轮短测；诊断轮、pilot和敏感性复测独立列示，没有择优替换原始数值。

**合理推断**：在数据、请求集合和前置资源层命中保持一致时，热命中省去了时隙DB查询及Redis回填，与本次更低的平均延迟相符。冷路径还包含Redis GET miss、SQL查询、序列化和SET，因此差值不能全部称为“MySQL耗时”。

**限制**：没有关闭缓存的持续基线；没有磁盘冷读；没有达到率阶梯、饱和点搜索或长时间稳态测试。带sleep的实际QPS不能称为最大吞吐，两轮及单对敏感性试验不能证明稳态容量或统计显著性。单机同时运行客户端、Redis、MySQL、RabbitMQ、JVM，JIT/GC、操作系统调度和其他工作负载仍可能影响长尾。隔离库仅一个资源、每日期两条时隙，不能推广到其他响应规模、写入失效风暴、空值缓存、404或真实预约负载。P95/P99若在某轮接近或反向，也必须按原值解释，不能概括成所有分位数必然改善。

## 收尾与证据索引

只向本次创建的Java进程组发送SIGTERM。结束时8080未监听，应用PID已退出或被回收，专用Rabbit连接已断开。原业务6张表逐行排序哈希前后相同，原队列的ready/unacked/consumer数相同，POST资源112及其117/118/119时隙、用户和预约未改动。

共核对 {final['non_benchmarks_files_hashed']} 个非benchmarks文件，变化路径为 `{final['non_benchmarks_changed_paths']}`；Git tracked diff为空，工作区仍仅有原本的未跟踪 `benchmarks/`。未执行FLUSHALL/FLUSHDB、批量业务键删除、消息purge、业务数据删除或破坏性清理。专用实验数据库、账户、vhost及fixtures保留，Redis键按正常TTL自然过期；私有凭据保存在 `/tmp/{root.name}_private` 的0700目录及0600文件，不进入结果包。

- `summary-per-block.csv`、`analysis.json`：各轮与合并统计；`duration-histogram.csv`：统一分箱耗时分布；`*.requests.csv`：逐请求明细。
- `*.k6.log`、`*.metrics.ndjson`、`*.summary.json`：原始日志、原生采样及k6汇总；`*.command.json`：实际命令和无敏感信息参数。
- `*.evidence.json`、`*.manifest.json`、`*.exact-key-deletion.json`、`*.resource-key-prep.json`：精确数据/键集合、TTL与状态。
- `*.redis-owned-commands*.json/ndjson`、`*.db-before/after.json`、`*.connections-before/after.json`：缓存与不同数据库计数口径。
- `source-audit/`、`schema-copy.sql`、`fixture-inserts.sql`、`db-fixtures.tsv`、`db-explain.tsv`：只读实现审计与隔离数据创建记录。
- `application-lifecycle.json`、`isolation-evidence.json`、`logging-verification.json`、`final-verification.json`、前后哈希及MQ快照：隔离、日志及收尾证据。
- `cache_compare.js`、`cache_lab.py`、初版脚本快照、`run_suite.py`、`resume_suite.py`、setup/analysis/verify辅助脚本：本次可核查执行逻辑。历史label不可直接覆盖复跑；脚本现会拒绝覆盖已存在的evidence。

复现时先重新确认原服务状态，并创建新的隔离库/vhost/凭据和唯一结果目录；用该次私有settings指向辅助脚本，保留本报告的同池、唯一日期、前置资源热缓存、INFO日志和固定迭代参数。先pilot并核对SQL/键证据，正式按相反顺序各做一对，任何计数异常保留原始结果后停止。不得把原业务库、原vhost、原Redis业务键作为清理目标。
'''
(root/'day24-redis-cache-report.md').write_text(text)
summary=f'''# ResQ Day24 Redis 缓存对照结论

实验 `{root.name}` 已实际完成。先只读确认源码、原数据库和旧 POST 结果；低强度验证通过后，完成50/100/200 VU每组两轮主对照、200 VU一对关闭MONITOR的复测。主对照共42,000请求，全部HTTP和业务内容检查通过，失败率均0%。

## 核心结果

单位ms，来自每档每组两轮全部请求样本；P95/P99重新合并计算。

{table}
{ratios}。200 VU 热组 P99为23.40ms，略高于冷组22.12ms，因此没有证据表明所有长尾指标都改善。每VU固定30次、每次sleep(1)，实际速率受主动节奏限制，**这些QPS不是最大吞吐，也不是稳态容量结论**。

## 怎样保证冷组确实回源

ResourceCache键为 `resource:<id>`，正常TTL300–360秒，不存在资源缓存30秒；ResourceSlotCache键为 `resource:<id>:slots:<date>`，TTL300–360秒，空列表也缓存。带date的时隙查询先检查资源，再读时隙缓存，miss后正常查DB并回填。

两组使用同一个专用资源、同一份日期和时隙数据，每日期恰好2行。每轮每日期只访问一次；热组先用正常HTTP全部预热，冷组只精确删除manifest中的实验时隙键，再逐个首次访问。前置资源详情层在两组均保持热状态。主组命令轨迹核验为每键GET一次；热组SET=0，冷组SET=N，查询返回内容完全一致。

所以比较的是 **热命中 vs 首次miss＋数据库查询＋Redis回填**，不是关闭缓存。MySQL数据页未清空、已由准备和前序访问加热，因此也不是磁盘冷读。42,000个主对照中，冷请求合计21,000、热请求合计21,000。

## 计数异常与交叉证据

一次50VU冷组的1,500个HTTP/业务检查和1,500次回填通过，但共享SQL digest只记录增量1,498，原因未确认。该轮及其配对热轮不进主表，原始证据完整保留。之后增加稳定10条应用连接的SELECT计数、定时任务扫描计数和返回行数核验；新轮次按“连接SELECT减扫描”核验冷组N、热组0，100/200VU冷组返回2N行、热组0行。共享digest的少计值仍与连接口径并列，没有补改历史结果。

精确命令观测本身可能增加Redis开销，主表是在相同MONITOR与k6明细输出条件下取得。额外关闭MONITOR的200VU复测：

{sensitivity}
{senssentence}该复测仅一对，未与主表混合，仍受运行顺序和共享主机波动影响。

## 对原项目的影响

原8080服务未启动，但原库已有3条到期PENDING、原timeout dead队列有3条消息，普通启动可能处理旧POST结果。本次以临时环境变量将同一未修改应用连接到专用DB、RabbitMQ vhost和原先为空的Redis DB15，保留scheduler、消费者及业务约束。专用库仅复制真实表结构，插入明确标记的一个资源、6,120日期及12,240时隙；没有用户、预约或业务消息。

同一JVM、固定512MiB堆运行全部轮次。仅通过临时环境变量将cache及ResourceService日志设INFO，实际DEBUG行数0；未修改配置文件。旧111资源2026-10-25的GET测试只有请求节奏和每日期行数可参考，不能把旧环境数值当作严格受控对照。

实验结束已正常停止本次Java实例，端口关闭、Rabbit连接断开。原库6张表哈希、原消息队列计数前后一致，502个非benchmarks文件无变化，Git tracked diff为空。只按准确实验键删除缓存；未清库、清队列、删业务键或清理旧数据。专用实验环境和fixtures保留，缓存按正常TTL过期，凭据未进入报告或证据包。

本实验只支持同机隔离小数据集上这条GET读路径的结论。每档每组两轮短测不能代表生产容量、统计显著性、写入失效、空值/404或其他数据规模。详细逐轮统计、原始k6日志、耗时分布、SQL/Redis证据、脚本及完整性核验见同目录技术报告和证据包。
'''
(root/'day24-redis-cache-conclusion.md').write_text(summary)
print('report written',len(text),'characters')
