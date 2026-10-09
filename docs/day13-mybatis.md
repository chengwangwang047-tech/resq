# Day13 · 你填关键代码的 MyBatis 骨架

这是可编译的练习起点，不是预约功能已完成。正式代码在 src/main/java/com/resq；
旧 src/domain、src/service 等目录不复制、不移动。ResourceMapper/JdbcResourceMapper
及已通过的资源测试保持原样。pom.xml 中你引入的 MyBatis Starter 3.0.5 保留。

## 按这个顺序填写

1. ResourceLabMapper：先写 findById 的 @Select 和 createdAt 映射，再写 findAll，
   最后写 @Insert / 主键回填。注解与 SQL 都由你填写。
2. ResourceXmlLabMapper.xml：写 resultMap、findById、findAll、insert；namespace
   已配好，statement id 对齐 Java 方法名。不要同时给 XML 方法添加注解 SQL。
3. ReservationMapper.xml：先 resultMap 和 findById，再 findByUserId。
   按 userId 查询只是底层数据能力，不认证身份，不代表 GET /me 完成。
4. ReservationService：先 findByUserId，再按 ID 查询的不存在/归属检查。
   当前所有方法都明确抛 UnsupportedOperationException，不能假返回成功。
5. 创建预约：补 insert / 主键回填、时隙条件抢占及影响行数检查，再补 Service。
   自己添加 @Transactional，使抢占和插入共同提交或回滚。
6. 取消预约：补条件状态转换、归属与状态校验、释放时隙和事务。
   延续 docs/reservation-flow.md 的 AVAILABLE / RESERVED 及
   PENDING / CONFIRMED / CANCELLED / EXPIRED 约定；明确哪些状态可取消。
7. 补完整测试断言，逐项移除 @Disabled。未写完的 fail("TODO") 必须保留禁用。

XML 当前只有注释，无 SQL / resultMap；未填 Mapper 方法被调用时会抛
MyBatis BindingException（statement 未绑定），不是返回 null/空集合。
不要用空 SQL、空 resultMap 或 catch 后返回空集合来伪装完成。
参数应使用绑定值；固定排序。数据库错误与业务“查不到”要区分。

## 身份边界

ReservationCreateDTO 只有 slotId / requestId，没有 userId / status。
Controller 已搭构造器及方法骨架，但没有任何方法级 HTTP 映射。
Day14 验证 JWT 后再开放按 ID、创建、取消和 /me；从验证过的身份上下文取 userId，
不能接受请求参数、请求体、URL 或自报请求头作为身份。
今天在测试里显式传入 userId，只是测试底层能力。

## 编译和测试（WSL）

```bash
cd /home/colaminos/resq
mvn -DskipTests test
mvn -Dtest=ResourceSlotsTest,Day13MapperWiringTest test
mvn test
```

第一条编译正式源码和测试源码，但不运行测试。
第二条只运行原有 3 个资源测试和 2 个框架检查。
第三条同时显示待完成练习被跳过；跳过不是业务验收通过。
Day13MapperWiringTest 只检查 Mapper 注册、XML 打包及解析，不执行 SQL。

三个 Mapper 集成测试类整体 @Disabled；需填写 SQL 后移除类级禁用，
可为仍未完成的方法临时添加方法级 @Disabled。它们使用 @SpringBootTest
和测试事务回滚；插入夹具只应在合适的本地测试库运行，不会清表，
但自增序号可能消耗。先核实夹具用户/时隙、边界 ID 及表权限；
历史文档中的账号权限可能仅为 SELECT，不代表今天已有写权限。
Service 测试不连接 DB，真实事务回滚需另外补集成测试，不可用 mock 证明。

```bash
mvn -Dtest=ResourceLabMapperTest test
mvn -Dtest=ResourceXmlLabMapperTest test
mvn -Dtest=ReservationMapperTest test
mvn -Dtest=ReservationServiceTest test
```

这些命令在移除对应 @Disabled 之前只会报告跳过。

## 本地运行环境

编译、资源单元测试和框架检查不需要密码，也不启动应用。
新 WSL shell 如要运行应用或数据库集成测试，请在本地设置
RESQ_DB_USERNAME / RESQ_DB_PASSWORD；可按需设置 RESQ_DB_URL 指向测试库。
密码只在你自己的终端设置，不需要发给助手。保留 spring.sql.init.mode=never。
不要执行 docs/sql/day08-practice.sql，它含 DROP TABLE。

构建会更新 target 中的生成产物；本项目已有部分 target 文件被 Git 跟踪，
不要把它们当成新写的业务实现，也不要用 git reset 覆盖你原有修改。
