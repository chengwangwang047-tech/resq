# ResQ Day24：GET slots 并发读取实验

本报告依据用户提供的 k6 终端记录整理，未重新运行压测。原文保存在 [user-pasted-k6-output.txt](results/get-slots-user-records/user-pasted-k6-output.txt)，三档日志和提取后的数值见[证据目录](results/get-slots-user-records/)。

50、100、200 VU 各运行一轮30秒。全部10,500次请求完成，HTTP失败率为0%，21,000项检查全部通过，未记录中断迭代。在本次带 `sleep(1)` 的固定请求节奏下，随VU增加，实际请求速率上升，平均延迟和高分位延迟也有所增加；现有记录未出现HTTP失败或空列表检查失败。

## 测试对象与方式

- 脚本：[get-slots.js](get-slots.js)。命令行分别指定 `--vus 50/100/200`、`--duration 30s`，覆盖脚本默认的1 VU、10秒。
- 按本次会话先前说明，目标为 `GET /api/resources/111/slots?date=2026-10-25`，地址为本机8080。粘贴命令没有展示当时的 `RESQ_BASE_URL`、`RESQ_RESOURCE_ID`、`RESQ_SLOT_DATE` 等环境变量，终端汇总自身不能独立证明最终URL或认证参数。
- 每个VU循环执行一次GET、两项检查，再 `sleep(1)`。两项检查分别为“HTTP状态为200”和“响应为非空数组”。非空检查不等于精确校验slotId、条数、日期、库存或预约正确性。
- 输出显示各档为固定VU循环场景：正式运行30秒，另有30秒gracefulStop上限；实际进程显示耗时为31.6/31.6/31.7秒。未提供绝对起止时间、进程退出码或当时的完整运行环境。

## 结果

延迟使用 k6 `http_req_duration`，单位ms。数值直接取自终端已经四舍五入的汇总，未从原生逐请求数据重算。

| VU | 完成请求数 | 实际请求速率 req/s | avg | median | P90 | P95 | P99 | min | max | HTTP失败率 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | 1,500 | 47.534349 | 5.38 | 5.19 | 8.50 | 9.44 | 10.77 | 1.57 | 11.42 | 0%（0/1,500） |
| 100 | 3,000 | 94.950234 | 5.97 | 5.31 | 8.81 | 10.46 | 23.34 | 1.11 | 28.21 | 0%（0/3,000） |
| 200 | 6,000 | 189.551159 | 6.55 | 5.63 | 9.81 | 14.68 | 24.25 | 1.23 | 31.63 | 0%（0/6,000） |

| VU | 检查总数 | 检查通过率 | 检查失败数 | 完成迭代 | 中断迭代 | 输出显示耗时 |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 3,000 | 100% | 0 | 1,500 | 0 | 31.6秒 |
| 100 | 6,000 | 100% | 0 | 3,000 | 0 | 31.6秒 |
| 200 | 12,000 | 100% | 0 | 6,000 | 0 | 31.7秒 |

每请求两项check，所以检查数是请求数的两倍。`checks_total` 的每秒速率不能当作HTTP QPS；本报告使用 `http_reqs` 的速率。

## 原始执行命令

以下为记录中50 VU命令；100/200 VU仅将 `--vus` 改为相应数值。原始命令和完整控制台输出均已留存。本次整理未执行这些命令，也未启动应用。

```bash
cd ~/resq
k6 run \
  --vus 50 \
  --duration 30s \
  --summary-mode=full \
  --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
  benchmarks/get-slots.js
```

脚本需要由运行环境提供 `RESQ_SLOT_DATE`；资源ID、地址及可选token也取自环境变量。历史命令中未显示的值没有补造成已采集证据。保留的脚本副本是本次整理时的当前版本，不能仅凭它证明历史执行时的文件字节完全一致。

## 结论与限制

**观察事实**：三档均完成预定循环，没有HTTP失败、空数组检查失败或中断；平均延迟为5.38–6.55ms，P95为9.44–14.68ms。50到200 VU的实际请求速率约增至4倍，P99由10.77ms升至24.25ms。

**合理解释**：每次请求后sleep1秒，低延迟时每个VU约每秒发起一次请求，因此速率与VU数接近线性增长符合该闭环负载模式。VU数表示并发虚拟用户数，不能证明相同数量的HTTP请求始终同时在途。

**证据限制**：每档仅一轮约30秒短测，没有重复轮次、到达率阶梯或饱和点搜索。因此不能把189.55 req/s称为最大吞吐，不能把单轮结果解释为稳态容量，也不能以0失败证明预约写入、库存或其他业务路径正确。

本记录没有缓存键状态、Redis命令轨迹、SQL查询计数、日志级别、JVM参数或启动顺序证据，无法判定各请求命中还是回源，也不能把整个GET轮次称为“无缓存”或“纯热缓存”基线。仅有汇总，无法还原逐请求分布、重算合并P95/P99或归因长尾。缓存收益请参阅独立的 [Redis缓存对照报告](day24-redis-cache-report.md)，不要直接把两次环境下的数值相减。

## 证据索引

- [完整粘贴原文](results/get-slots-user-records/user-pasted-k6-output.txt)：按附件字节原样保存，包含50/100/200 VU记录。
- [50 VU](results/get-slots-user-records/50.k6.log)、[100 VU](results/get-slots-user-records/100.k6.log)、[200 VU](results/get-slots-user-records/200.k6.log)：从原文按三段命令分离，内容未重造。
- [汇总CSV](results/get-slots-user-records/parsed-summary.csv)、[提取说明及JSON](results/get-slots-user-records/parsed-summary.json)：派生数据，不冒充原生k6 summary-export。
- [当前脚本快照](results/get-slots-user-records/get-slots.current-snapshot.js)：只读复制，原脚本未修改。
- [POST reservation报告](day24-post-reservation-report.md)：独立的预约并发竞争实验。
