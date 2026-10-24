# ResQ 性能实验报告与证据

三份正式报告统一放在 `benchmarks/`，按读取性能、预约竞争、缓存对照阅读。

| 实验 | 正式报告 | 证据目录 |
|---|---|---|
| GET slots，50/100/200 VU读取压测 | [resource-slot-query-report.md](resource-slot-query-report.md) | [results/get-slots-user-records/](results/get-slots-user-records/) |
| POST reservation，50/100/200 VU预约竞争 | [reservation-concurrency-report.md](reservation-concurrency-report.md) | [results/post-reservation-20261022-182544/](results/post-reservation-20261022-182544/) |
| Redis热命中与首次miss回源对照 | [redis-cache-report.md](redis-cache-report.md) | [results/redis-cache-20261022-185550/](results/redis-cache-20261022-185550/) |

```text
benchmarks/
├── README.md
├── resource-slot-query-report.md
├── reservation-concurrency-report.md
├── redis-cache-report.md
├── get-slots.js
├── post-reservation-race.js
├── refresh-benchmark-tokens.py
├── archives/
│   ├── README.md
│   ├── manifest.json
│   └── day24-*-raw.zip
└── results/
    ├── get-slots-user-records/
    ├── post-reservation-20261022-182544/
    ├── redis-cache-20261022-185550/
    └── layout-migration.json
```

## 目录改名对应关系

| 原目录 | 新目录 | 保留的历史实验编号 |
|---|---|---|
| `d24_20261022_182544_3163fc` | `post-reservation-20261022-182544` | `d24_20261022_182544_3163fc` |
| `d24cache_20261022_185550_9afb96` | `redis-cache-20261022-185550` | `d24cache_20261022_185550_9afb96` |

仅调整目录名称和正式报告入口。历史实验编号、测试资源名、数据库/vhost名、私有凭据目录、原始日志/SQL/JSON/脚本快照保持不变；它们记录的是当时执行事实，其中可能仍出现旧绝对路径。需要查阅历史文件时，用上表替换 `benchmarks/results/` 后的目录名。原有 `artifact-sha256.json` 等校验文件继续针对各证据目录内的原始相对路径生效。

顶层POST报告与原证据目录内版本在整理前逐字节相同；顶层作为正式入口，证据目录内历史 Markdown 快照仅保留本地，不再纳入 Git。Redis完整报告提升到顶层作为正式入口，原技术报告和结论也仅作为本地历史 Markdown 快照保留。报告中的证据文件名除另注外，均相对于对应证据目录。

GET资料来自用户提供的终端汇总，包含三轮原文和提取表；它没有原生逐请求采样，不能补造。POST、Redis目录包含独立实验的日志、检查证据和脚本。历史写请求命令、fixtures插入和执行脚本不能直接当作新实验重放；未来复测应使用新的实验标识和目录，先核查原服务及业务状态。

本次仅整理文件、补写文档并从已提供记录提取数字，没有运行压测、启动服务或修改业务数据。迁移前后原证据文件哈希及非benchmarks文件完整性见 [layout-migration.json](results/layout-migration.json)。

## 原始证据压缩归档

Day24 的大体积原始指标、完整日志、Redis 命令轨迹与历史脚本快照已按实验压缩到
[archives/](archives/README.md)，各文件的原路径、大小和 SHA-256 见
[归档清单](archives/manifest.json)。诊断异常轮与其配对轮也完整保留。
正式报告、测试与分析脚本、汇总 CSV/JSON、必要配置、源码快照和 `build/pom.xml` 继续按原路径纳入 Git。

现有本地原始文件保留；新克隆仓库需按归档说明解压后读取报告中引用的原始日志和采样。
历史报告与脚本中的旧实验路径仍按上表换算。`layout-migration.json` 与 `artifact-sha256.json`
保留当时的记录，当前归档范围见 `archives/manifest.json`。
`target/`、`__pycache__/` 和 `*.pyc` 已停止跟踪，未删除本地文件。

## 交付与本地材料

`benchmarks.md` 是已被上述三份报告替代的旧总汇；它与 results/ 下三份 Day24 Markdown 快照已停止 Git 跟踪，并通过精确忽略规则保留本地。正式报告、ZIP、清单、CSV/JSON 和复核证据继续纳入交付。历史 manifest、layout-migration 和实验脚本保留当时的路径记录，不作为新环境启动或验收入口；历史 Markdown 的后续生成结果仍被忽略。
