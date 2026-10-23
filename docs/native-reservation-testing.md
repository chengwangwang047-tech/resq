# 原生 MySQL/Redis 预约隔离测试

在 WSL Ubuntu 仓库根目录执行：

```bash
python3 scripts/run_native_reservation_tests.py
```

需要已安装的 mysqld、mysql、redis-server、redis-cli、Java 21 和 Maven。脚本只运行明确列出的单元/MVC 与预约并发、事务测试；不启动 Spring Boot 上下文或 RabbitMQ，不替代完整隔离入口。

专用回环端口为 MySQL 13317、Redis 16317；占用时立即拒绝，不停止已有服务。脚本在 /tmp 下建立自己的数据目录与测试账号，使用 resq_day17 和随机数据库/Redis guard，结束时只停止自己创建的进程并删除自己的临时数据。不会连接现有开发数据库。

使用临时 POM 编译正式源码和测试，表结构来自 src/test/resources/reservation-test-schema.sql；结果保存到 target/native-reservation-results/<时间-随机后缀>/。原有 day17 系统属性、测试账号/库名、guard Key 与临时目录前缀保留，以维持隔离协议兼容；它们不是生产配置。

测试白名单包括 ReservationLockTransactionTest、ReservationConcurrencyComparisonTest、ReservationHttpContractTest 及相关 Service/缓存测试。并发对照中的无锁示例只操作临时 day17_unsafe_* 表；真实服务路径仍使用现有事务与 MySQL 条件更新。

包含 Spring Boot 与 RabbitMQ 的完整隔离方法见 [测试说明](testing.md)。历史阶段脚本已归档，不应把其中的旧环境配置或执行方法当成当前入口。
