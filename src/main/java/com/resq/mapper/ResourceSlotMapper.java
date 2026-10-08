package com.resq.mapper;

import com.resq.domain.ResourceSlot;

import java.util.List;

/** 时隙查询契约；没有时隙时返回空列表。 */
public interface ResourceSlotMapper {
    List<ResourceSlot> findByResourceId(Long resourceId);
}
