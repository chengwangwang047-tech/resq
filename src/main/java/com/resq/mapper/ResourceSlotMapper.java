package com.resq.mapper;

import com.resq.domain.ResourceSlot;

import java.time.LocalDate;
import java.util.List;

/** 时隙查询契约；没有时隙时返回空列表。 */
public interface ResourceSlotMapper {
    List<ResourceSlot> findByResourceId(Long resourceId);

    /** 按 slot_start 所属日期查询，范围为 [当日 00:00, 次日 00:00)。 */
    List<ResourceSlot> findByResourceIdAndDate(Long resourceId, LocalDate date);

    /** 用于精确定位预约/取消所影响的资源日期缓存；不存在时返回 null。 */
    ResourceSlot findById(Long slotId);
}
