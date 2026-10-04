# Day11 Spring Boot + JDBC

## 目录与历史代码

正式入口：src/main/java/com/resq/ResqApplication.java。
Maven 默认只编译 src/main/java；旧 src/domain、src/repository、src/service、
旧异常、并发实验和 SQL/索引文档均保持原样。

正式 ComputeResource 对齐现有 compute_resource：
Long id、String name、String type、String status、LocalDateTime createdAt。
保留无参/全参构造器及 getter/setter；正式版移除 description/enabled。
旧 src/domain/ComputeResource.java 仍保留 String id、description、enabled。

## 请求链路与学习点

GET /api/resources 或 GET /api/resources/{id}
-> ResourceController -> ResourceService -> ResourceMapper
-> JdbcResourceMapper -> JdbcTemplate -> MySQL resq.compute_resource。

所有层的 id 使用 Long。Spring 将 URL 参数转换为 Long。
JdbcResourceMapper 是唯一 ResourceMapper 实现，使用 @Repository 和构造器注入。
ResourceMapper 是普通 Java 接口；今天不使用 MyBatis 注解或 XML。

SQL 显式列出 id, name, type, status, created_at。
findAll 按 id 排序，数据库没有记录时自然返回空列表，JSON 为 []。
findById 用 WHERE id = ? 参数绑定；没有记录时返回 null，
Service 抛出 ResourceNotFoundException，HTTP 为 404。
数据库连接/SQL 错误不转换为 null 或 404。

RowMapper 显式把 created_at 映射为 createdAt；
DATETIME 直接读取为 LocalDateTime，不转换成带时区的瞬时时间。
接口 JSON 的 createdAt 采用 ISO 本地日期时间，例如 2026-09-29T21:35:13。
不再使用 UnconfiguredResourceMapper 或 DatabaseNotConfiguredException。

## 依赖与连接配置

pom.xml 保留 Web/Test，仅新增：
- org.springframework.boot:spring-boot-starter-jdbc：JdbcTemplate、连接池和自动配置。
- com.mysql:mysql-connector-j（runtime）：MySQL JDBC 驱动。
版本由现有 Spring Boot 3.5.16 parent 管理。

application.properties：
- URL 从 RESQ_DB_URL 读取，缺省为
  jdbc:mysql://localhost:3306/resq?characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai
- 用户名和密码分别从 RESQ_DB_USERNAME、RESQ_DB_PASSWORD 读取，无密码默认值。
- spring.sql.init.mode=never，启动时不执行 schema.sql/data.sql。

参考：
https://docs.spring.io/spring-boot/3.5/reference/data/sql.html
https://dev.mysql.com/doc/connector-j/en/connector-j-reference-charsets.html
https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html

## 本地应用账号（已由用户手动创建）

此前 sudo -n 检查需要交互输入密码，账号由用户手动创建。
现已验证 resq_app@localhost 可通过普通 TCP 登录；实际权限仅为 compute_resource 的 SELECT。
Spring Boot 使用这个普通应用账号，不依赖 sudo/socket root 登录。
以下命令保留为配置说明，已有账号不要重复创建。

在 WSL 打开 MySQL，关闭该会话的客户端历史记录：
```bash
sudo env MYSQL_HISTFILE=/dev/null mysql resq
```

在 MySQL 中执行（强密码占位须由用户替换，不是可直接使用的密码）：
```sql
CREATE USER 'resq_app'@'localhost' IDENTIFIED BY '<你自己设置的强密码>';
GRANT SELECT ON resq.compute_resource TO 'resq_app'@'localhost';
SHOW GRANTS FOR 'resq_app'@'localhost';
EXIT;
```

若账号已存在，CREATE USER 会报错；不要覆盖已有账号或重置密码。
只授予 compute_resource 的 SELECT 权限，不授予写入、其他表或远程访问权限。
以上不创建、修改或清空业务表，无需 FLUSH PRIVILEGES。

在 WSL 检查 TCP 登录（-p 会安全提示密码）：
```bash
mysql --protocol=TCP -h localhost -P 3306 -u resq_app -p resq \
  -e "SELECT id, name, type, status, created_at FROM compute_resource ORDER BY id;"
```

## 编译与启动

```bash
cd /home/colaminos/resq
mvn package
export RESQ_DB_USERNAME=resq_app
read -rsp 'MySQL 应用账号密码: ' RESQ_DB_PASSWORD
printf '\n'
export RESQ_DB_PASSWORD
java -jar target/resq-0.0.1-SNAPSHOT.jar --server.port=18080
```

密码由隐藏输入提供给进程，不写进仓库或启动参数。此终端保持运行。
未设置连接变量的启动尝试不算真实数据库验收通过。

另开一个 WSL 终端：
```bash
curl -i http://localhost:18080/api/resources
curl -i http://localhost:18080/api/resources/1
curl -i http://localhost:18080/api/resources/999999
```

验收必须检查：
- 列表 HTTP 200，含实际的四条记录，id 为 JSON 数字。
- 每条只有 id/name/type/status/createdAt；内容与 MySQL SELECT 一致。
- id=1 为 GPU-A100-01、GPU、BUSY、2026-09-29T21:35:13。
- id=999999 为 HTTP 404，数据库异常不能冒充资源不存在。
当前运行记录见下方；不要把用户提供的示例数据写成已实测结果。

## 本次运行记录

- mvn package：BUILD SUCCESS。
- Jar 中只有 ResourceMapper/JdbcResourceMapper，无旧占位 Mapper 或 503 异常。
- 28 个旧源码/文档逐文件哈希检查保持一致。
- 2026-10-04 18:03（Asia/Shanghai）：使用普通 resq_app 账号完成真实 JDBC 验收。
- Spring Boot 在 18080 启动成功。
- GET /api/resources：HTTP 200，返回实际四条资源。
- GET /api/resources/1：HTTP 200，返回 id=1 的 GPU-A100-01 / GPU / BUSY。
- GET /api/resources/999999：HTTP 404。
- 列表和单条 JSON 的所有字段均与同一应用账号的实时 MySQL SELECT 逐字段一致。
- createdAt 实际为 2026-09-29T21:35:13；id 为数字；无 description/enabled。
- 验证进程已停止，未修改业务数据。详细结果保存在 target/day11-jdbc-verification.json。
- 用户创建账号时使用了密码占位文字，需自行更换为实际独立密码；密码没有写入仓库。
