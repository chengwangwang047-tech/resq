package com.resq.mapper;

import com.resq.domain.ComputeResource;
import java.util.List;

/** 数据库访问契约；Day11 使用 JdbcTemplate 实现。 */
public interface ResourceMapper {
    List<ComputeResource> findAll();

    /** 查不到返回 null，由 Service 决定 HTTP 404。数据库故障则继续抛出异常。 */
    ComputeResource findById(Long id);
    void insert(ComputeResource resource);
}
