package com.resq.mapper;

import com.resq.domain.ComputeResource;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Day13 注解练习；独立于 ResourceMapper/JdbcResourceMapper，不接入资源请求链路。
 * 尚无 SQL：现在调用会抛 MyBatis BindingException，不会返回假数据。
 */
@Mapper
public interface ResourceLabMapper {

    // TODO(D13-ANN-1): 自己添加 @Select，显式列名、固定排序。
    // TODO: 用注解结果映射或列别名处理 created_at -> createdAt。
    @Select("""
    SELECT id, name, type, status, created_at AS createdAt
    FROM compute_resource
    ORDER BY id
    """)
    List<ComputeResource> findAll();

    // TODO(D13-ANN-2): 自己添加 @Select，通过绑定参数查询 id；查不到返回 null。
    @Select("SELECT id, name, type, status, created_at AS createdAt FROM compute_resource WHERE id=#{id}")
    ComputeResource findById(@Param("id") Long id);

    // TODO(D13-ANN-3): 自己添加 @Insert 和生成主键回填配置。
    // 参数没有 @Param 包装；回填到 resource.id。返回影响行数。
@Insert("""
    INSERT INTO compute_resource (name, type, status, created_at)
    VALUES (#{name}, #{type}, #{status}, #{createdAt})
    """)
@Options(useGeneratedKeys = true, keyProperty = "id")
int insert(ComputeResource resource);
}
