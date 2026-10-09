package com.resq.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 只检查注册及 XML 可解析，不连接数据库，不表示 SQL/业务验收通过。 */
class Day13MapperWiringTest {

    @Test
    void annotationLabMapperCanBeRegistered() {
        Configuration configuration = new Configuration();
        configuration.addMapper(ResourceLabMapper.class);
        assertTrue(configuration.hasMapper(ResourceLabMapper.class));
    }

    @Test
    void xmlFilesArePackagedAndRegisterTheirNamespaces() throws Exception {
        Configuration configuration = new Configuration();
        for (String path : new String[] {
                "mapper/ResourceXmlLabMapper.xml", "mapper/ReservationMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input, "Missing classpath XML: " + path);
                new XMLMapperBuilder(input, configuration, path,
                        configuration.getSqlFragments()).parse();
            }
        }
        assertTrue(configuration.hasMapper(ResourceXmlLabMapper.class));
        assertTrue(configuration.hasMapper(ReservationMapper.class));
    }
}
