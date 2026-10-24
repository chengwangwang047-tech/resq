# 学习归档与职责命名索引

## 范围

学习快照在仓库根目录 learning-archive/，按 java-foundations、notes、sql、scripts、misc 分类。该目录完全被 Git 忽略，Docker 构建上下文原有白名单也不会包含它。此索引不受归档忽略规则影响，应与现行文件一起纳入后续提交。

本轮保留 src/main、deploy、Dockerfile、pom.xml 和压测原始证据，现行测试与工具不受归档忽略规则影响，应随本次改名一并提交。业务 Mapper/探针没有迁出主源码。独立阶段脚本原文保留为快照，不作为当前可执行验收入口。

## 本地归档的使用与恢复

`learning-archive/misc/local-environment/<记录目录>/` 另保存公共文档去本机化之前的原文快照与本机回退标识，含 manifest 校验清单。这些记录同样仅留本地，不包含真实环境变量文件、密码、JWT 密钥或私钥副本；新 clone 不会获得它们。读取方式和回退限制见 [练习说明](practice.md)。

归档文件仍在当前电脑上；新 clone 不会获得 learning-archive/。原文件仍可从迁移前 Git 提交恢复，本轮未重写历史。需要分享学习材料时应单独审查敏感信息和体积后导出，避免直接加入整个归档目录。

历史说明和脚本保留当时的路径、参数和阶段结论，不能据此判断现行业务状态，也不要用历史 SQL/脚本操作开发库。当前入口是 scripts/run_integration_tests.py 与 scripts/run_native_reservation_tests.py，详见 [测试说明](testing.md)。

Java 练习使用旧 domain/repository/service 包，独立于 com.resq 业务包。入口类已按用途改名，包内依赖保留；在项目根目录可以单独编译：

```bash
mkdir -p target/learning-classes
find learning-archive/java-foundations/src -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -d target/learning-classes
```

SlotIndexDataGenerator 的输出路径已同步到 learning-archive/sql/slot-index-sample-data.sql；执行它会覆盖该合成样本文件，本轮只验证编译，不执行生成器或 SQL。misc 中保留三个零字节占位文件与 SELECT 的非空原始输出，未删除其内容。

迁移前提交：`57abeb9d38db6c2c44a6a59511d3a6aa858a0c84`。

## 归档清单

| 原路径（相对仓库根目录） | 本地新路径 |
| --- | --- |
| `src/Day09DataGenerator.java` | `learning-archive/java-foundations/src/SlotIndexDataGenerator.java` |
| `src/Day3Test.java` | `learning-archive/java-foundations/src/ReservationStateDemo.java` |
| `src/Day4RepositoryPractice.java` | `learning-archive/java-foundations/src/InMemoryRepositoryDemo.java` |
| `src/Day5AvailableSlotSearch.java` | `learning-archive/java-foundations/src/AvailableSlotSearchDemo.java` |
| `src/Day6ReservationConcurrencyLab.java` | `learning-archive/java-foundations/src/ReservationRaceLab.java` |
| `src/Day7ReservationThreadPoolLab.java` | `learning-archive/java-foundations/src/ReservationThreadPoolLab.java` |
| `src/DomainTest.java` | `learning-archive/java-foundations/src/DomainModelDemo.java` |
| `src/domain/ComputeResource.java` | `learning-archive/java-foundations/src/domain/ComputeResource.java` |
| `src/domain/Reservation.java` | `learning-archive/java-foundations/src/domain/Reservation.java` |
| `src/domain/ReservationStatus.java` | `learning-archive/java-foundations/src/domain/ReservationStatus.java` |
| `src/domain/ResourceSlot.java` | `learning-archive/java-foundations/src/domain/ResourceSlot.java` |
| `src/domain/User.java` | `learning-archive/java-foundations/src/domain/User.java` |
| `src/exception/ReservationException.java` | `learning-archive/java-foundations/src/exception/ReservationException.java` |
| `src/exception/ResourceNotFoundException.java` | `learning-archive/java-foundations/src/exception/ResourceNotFoundException.java` |
| `src/exception/SlotConflictException.java` | `learning-archive/java-foundations/src/exception/SlotConflictException.java` |
| `src/repository/ReservationRepository.java` | `learning-archive/java-foundations/src/repository/ReservationRepository.java` |
| `src/repository/ResourceRepository.java` | `learning-archive/java-foundations/src/repository/ResourceRepository.java` |
| `src/repository/UserRepository.java` | `learning-archive/java-foundations/src/repository/UserRepository.java` |
| `src/result/Result.java` | `learning-archive/java-foundations/src/result/Result.java` |
| `src/service/ReservationService.java` | `learning-archive/java-foundations/src/service/ReservationService.java` |
| `docs/concurrency-lab.md` | `learning-archive/notes/in-memory-concurrency.md` |
| `docs/day11-spring-boot.md` | `learning-archive/notes/spring-boot-jdbc-migration.md` |
| `docs/day13-mybatis.md` | `learning-archive/notes/mybatis-exercise-instructions.md` |
| `docs/domain-draft.md` | `learning-archive/notes/domain-model-draft.md` |
| `docs/reservation-flow.md` | `learning-archive/notes/early-reservation-transaction-design.md` |
| `docs/performance/day09-index-report.md` | `learning-archive/notes/slot-index-experiment.md` |
| `docs/sql/day08-practice.sql` | `learning-archive/sql/schema-and-query-practice.sql` |
| `docs/sql/day09-generated-data.sql` | `learning-archive/sql/slot-index-sample-data.sql` |
| `docs/sql/day09-index-lab.sql` | `learning-archive/sql/slot-index-experiment.sql` |
| `docs/sql/day21-mq-consume-record.sql` | `learning-archive/sql/mq-consume-record-initial.sql` |
| `scripts/day18/run_tests.py` | `learning-archive/scripts/redis-fallback-regression.py` |
| `scripts/day20-cancel-test.sh` | `learning-archive/scripts/reservation-cancellation-smoke.sh` |
| `scripts/day20-test.sh` | `learning-archive/scripts/reservation-timeout-smoke.sh` |
| `scripts/day21_final_tests.py` | `learning-archive/scripts/mq-acceptance-snapshot.py` |
| `scripts/day21_mq_smoke.py` | `learning-archive/scripts/mq-smoke-snapshot.py` |
| `scripts/day21_regression.sh` | `learning-archive/scripts/mq-regression-snapshot.sh` |
| `FROM` | `learning-archive/misc/empty-from.txt` |
| `WHERE` | `learning-archive/misc/empty-where.txt` |
| `LIMIT` | `learning-archive/misc/empty-limit.txt` |
| `SELECT` | `learning-archive/misc/select-command-output.txt` |

## 现行文件改名

| 原路径（相对仓库根目录） | 当前路径 |
| --- | --- |
| `src/test/java/com/resq/controller/Day15CacheHttpTest.java` | `src/test/java/com/resq/controller/ResourceSlotCacheHttpTest.java` |
| `src/test/java/com/resq/controller/Day17ReservationHttpContractTest.java` | `src/test/java/com/resq/controller/ReservationHttpContractTest.java` |
| `src/test/java/com/resq/mapper/Day13MapperWiringTest.java` | `src/test/java/com/resq/mapper/MyBatisMapperWiringTest.java` |
| `src/test/java/com/resq/day17/Day17ConcurrencyComparisonTest.java` | `src/test/java/com/resq/reservation/ReservationConcurrencyComparisonTest.java` |
| `src/test/java/com/resq/day17/Day17LockTransactionTest.java` | `src/test/java/com/resq/reservation/ReservationLockTransactionTest.java` |
| `src/test/java/com/resq/day17/Day17TestDatabase.java` | `src/test/java/com/resq/reservation/ReservationTestDatabase.java` |
| `src/test/resources/day17-schema.sql` | `src/test/resources/reservation-test-schema.sql` |
| `scripts/day17/run_tests.py` | `scripts/run_native_reservation_tests.py` |
| `docs/day17-testing.md` | `docs/native-reservation-testing.md` |
| `docs/performance/day23-benchmark.md` | `docs/performance/reservation-pagination-benchmark.md` |
| `benchmarks/day24-get-slots-report.md` | `benchmarks/resource-slot-query-report.md` |
| `benchmarks/day24-post-reservation-report.md` | `benchmarks/reservation-concurrency-report.md` |
| `benchmarks/day24-redis-cache-report.md` | `benchmarks/redis-cache-report.md` |
| `benchmarks/refresh-day24-test-tokens.py` | `benchmarks/refresh-benchmark-tokens.py` |

原来的 com.resq.day17 测试包改为 com.resq.reservation，Java 类声明、引用、脚本白名单、表结构文件路径和文档链接一并更新。day17 测试连接属性和 guard 标识保持兼容，历史实测记录里的阶段名/日期也继续保留。

## P2-01 仍保留的事项

两个 Lab Mapper/XML、DelayProbePublisher 以及主源码中的历史 TODO 注释，按本轮业务源码不动的要求保留。旧探针不能证明当前带期限后缀的超时路由可用。这部分未宣称已清理。
