# MyBatis 映射与 JDBC 分工

当前资源查询与写入通过 ResourceService、ResourceMapper/JdbcResourceMapper 使用 JDBC；预约和用户等路径使用当前 MyBatis Mapper。两种数据访问方式并存是现有实现，本轮没有替换它们。

ResourceLabMapper 演示注解映射，ResourceXmlLabMapper 与同名 XML 演示 XML 映射。它们已有 SQL，并非空骨架；目前仍作为 Mapper Bean 注册，但不接入资源请求链路。对应测试仍保留，避免因“学习代码”标签误删有效验证。

MyBatisMapperWiringTest 检查 Mapper 注册、XML 打包和解析，不连接数据库。ResourceLabMapperTest、ResourceXmlLabMapperTest、ReservationMapperTest 是显式隔离集成测试，不能直接对开发库运行。执行方法见 [测试说明](testing.md)。

早期“自己填写 SQL、Controller 尚未开放、测试均禁用”等内容只代表当时练习阶段，原文见本地学习归档。当前 ReservationMapper.xml 已包含状态条件更新、时隙抢占、expire_at 和分页查询。

按照本轮“业务源码不动”的约束，两个学习 Mapper、DelayProbePublisher 和主源码中的历史 TODO 注释均保留。DelayProbePublisher 使用旧延迟路由键，不能作为当前超时链路已验证的依据。后续若要迁出这些主源码组件，需要另批同步其 XML、注册与测试。
