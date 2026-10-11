package com.resq.cache;

import com.resq.domain.ResourceSlot;
import com.resq.mapper.ResourceSlotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;

@Component
public class ResourceSlotCacheInvalidator {
    private static final Logger log = LoggerFactory.getLogger(ResourceSlotCacheInvalidator.class);
    private final ResourceSlotMapper slotMapper;
    private final ResourceSlotCache slotCache;

    public ResourceSlotCacheInvalidator(ResourceSlotMapper slotMapper, ResourceSlotCache slotCache) {
        this.slotMapper = slotMapper;
        this.slotCache = slotCache;
    }

    /** 在当前业务事务中定位键，成功提交后删除；回滚时不删除。 */
    public void evictAfterCommit(Long slotId) {
        ResourceSlot slot;
        try {
            slot = slotMapper.findById(slotId);
        } catch (DataAccessException e) {
            log.warn("[SLOTS CACHE INVALIDATION ERROR] slotId={}", slotId);
            return;
        }
        if (slot == null || slot.getResourceId() == null || slot.getSlotStart() == null) {
            log.warn("[SLOTS CACHE INVALIDATION SKIPPED] slotId={}", slotId);
            return;
        }
        Long resourceId = slot.getResourceId();
        LocalDate date = slot.getSlotStart().toLocalDate();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    slotCache.evict(resourceId, date);
                }
            });
        } else {
            slotCache.evict(resourceId, date);
        }
    }
}
