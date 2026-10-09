package com.resq.mapper;

import com.resq.domain.ComputeResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Day13 XML 对照练习，SQL 和 resultMap 由你填写在同名 XML 中。 */
@Mapper
public interface ResourceXmlLabMapper {

    List<ComputeResource> findAll();

    ComputeResource findById(@Param("id") Long id);

    int insert(ComputeResource resource);
}
