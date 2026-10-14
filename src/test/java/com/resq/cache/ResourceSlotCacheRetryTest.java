package com.resq.cache;

import com.resq.domain.ResourceSlot;
import com.resq.mapper.ResourceSlotMapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

class ResourceSlotCacheRetryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 13);

    @Test
    void failedEvictionShouldRetryAndSucceed() {

        // 1. 模拟 Mapper 和 Cache
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);

        ResourceSlotCache slotCache = mock(ResourceSlotCache.class);

        // 2. 准备测试时隙
        ResourceSlot slot = new ResourceSlot(
                10L,
                1L,
                LocalDateTime.of(2026, 10, 13, 9, 0),
                LocalDateTime.of(2026, 10, 13, 10, 0),
                "AVAILABLE");

        when(slotMapper.findById(10L))
                .thenReturn(slot);

        // 第一次失败，第二次成功
        when(slotCache.evict(1L, DATE))
                .thenReturn(false, true);

        // 3. 创建被测试对象
        ResourceSlotCacheInvalidator invalidator = new ResourceSlotCacheInvalidator(
                slotMapper,
                slotCache);

        // 4. 模拟业务触发缓存删除
        // 本测试没有事务，因此走 else 立即删除路径
        invalidator.evictAfterCommit(10L);

        // 验证：已经尝试删除一次
        verify(slotCache, times(1))
                .evict(1L, DATE);

        // 5. 手动触发一次后台重试逻辑
        invalidator.retryFailedEvictions();

        // 验证：累计删除两次
        verify(slotCache, times(2))
                .evict(1L, DATE);

        // 6. 再执行一轮，确认成功任务不再重试
        invalidator.retryFailedEvictions();

        verify(slotCache, times(2))
                .evict(1L, DATE);
    }

    @Test
    void failedEvictionShouldStopAfterThreeRetries() {

        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);

        ResourceSlotCache slotCache = mock(ResourceSlotCache.class);

        ResourceSlot slot = new ResourceSlot(
                10L,
                1L,
                LocalDateTime.of(2026, 10, 13, 9, 0),
                LocalDateTime.of(2026, 10, 13, 10, 0),
                "AVAILABLE");

        when(slotMapper.findById(10L)).thenReturn(slot);

        // 模拟 Redis 一直删除失败
        when(slotCache.evict(1L, DATE)).thenReturn(false);

        ResourceSlotCacheInvalidator invalidator = new ResourceSlotCacheInvalidator(
                slotMapper,
                slotCache);

        // 初始尝试：失败后入队
        invalidator.evictAfterCommit(10L);

        // 连续进行三轮后台重试
        invalidator.retryFailedEvictions();
        invalidator.retryFailedEvictions();
        invalidator.retryFailedEvictions();

        // 初始 1 次 + 后台重试 3 次
        verify(slotCache, times(4)).evict(1L, DATE);

        // 再执行一轮，不应该继续重试
        invalidator.retryFailedEvictions();

        verify(slotCache, times(4)).evict(1L, DATE);
    }
}