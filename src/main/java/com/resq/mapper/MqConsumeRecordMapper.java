package com.resq.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MqConsumeRecordMapper {

    @Insert("""
        INSERT INTO mq_consume_record(event_id)
        VALUES (#{eventId})
        """)
    int insert(@Param("eventId") String eventId);
}