# Day24 原始证据压缩归档

正式报告、测试与分析脚本、汇总 CSV/JSON、必要配置、源码快照及历史校验清单继续按原路径纳入 Git。
本目录的 ZIP 保存逐指标原始采样、完整日志、Redis 命令轨迹，以及记录当时执行事实的历史脚本快照。
诊断异常轮及其配对轮完整保留；没有删除、筛选或改写原始记录。

| 压缩包 | 文件数 | 原始大小 | 压缩大小 |
|---|---:|---:|---:|
| [day24-get-slots-raw.zip](day24-get-slots-raw.zip) | 5 | 0.02 MiB | 0.01 MiB |
| [day24-post-reservation-raw.zip](day24-post-reservation-raw.zip) | 13 | 2.19 MiB | 0.10 MiB |
| [day24-redis-cache-raw.zip](day24-redis-cache-raw.zip) | 51 | 198.57 MiB | 8.99 MiB |

归档基于提交 `b106c94`。每个原始文件的仓库路径、ZIP 内路径、字节数和 SHA-256，
以及 ZIP 自身的 SHA-256，均记录在 [manifest.json](manifest.json)。
创建归档后，已将每个 ZIP 成员解压读取并与原文件逐一校验 SHA-256。

## 查阅与恢复

ZIP 内路径相对于 `benchmarks/results/`。从仓库根目录执行以下命令可恢复全部原始文件：

```bash
python3 -m zipfile -e benchmarks/archives/day24-get-slots-raw.zip benchmarks/results/
python3 -m zipfile -e benchmarks/archives/day24-post-reservation-raw.zip benchmarks/results/
python3 -m zipfile -e benchmarks/archives/day24-redis-cache-raw.zip benchmarks/results/
```

Windows 也可打开 ZIP，将内部实验目录解压到 `benchmarks/results/`。
例如 `50vu-r2-cold.metrics.ndjson` 恢复到
`benchmarks/results/redis-cache-20261022-185550/50vu-r2-cold.metrics.ndjson`。
正式报告和历史快照中的原始文件名仍指向这些解压后的路径。

本次保留原始文件的本地副本，并通过 `.gitignore` 避免再次提交重复的未压缩文件。
解压操作仅恢复资料；历史请求命令和实验脚本包含已使用的实验编号及旧路径，不能直接作为新一轮实验执行。

## 校验清单与构建产物

`results/layout-migration.json` 和 Redis 结果中的 `artifact-sha256.json` 均逐字节保留，
它们记录当时的整理状态。原始证据经解压后仍可按其中的 SHA-256 核验。
历史清单可能包含 `target/` 或 Python 字节码缓存；这些可重新生成的项目已停止纳入 Git，
不作为本次证据归档内容。当前受版本管理的资料范围及哈希以本目录 `manifest.json` 为准。

根目录 `target/`、实验 `build/target/`、`__pycache__/` 和 `*.pyc` 只停止 Git 跟踪，
本地文件保留。`build/pom.xml`、源码与配置快照仍在原路径纳入 Git。
本次没有重新压测、启动服务、修改业务代码、改变实验数值或重写 Git 历史。
