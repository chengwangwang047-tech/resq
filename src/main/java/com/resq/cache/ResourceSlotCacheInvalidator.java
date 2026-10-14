package com.resq.cache;

import com.resq.domain.ResourceSlot;
import com.resq.mapper.ResourceSlotMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class ResourceSlotCacheInvalidator {

    private static final Logger log =
            LoggerFactory.getLogger(ResourceSlotCacheInvalidator.class);

    // 单个任务最多进行 3 次后台重试
    private static final int MAX_RETRIES = 3;

    // 每轮定时任务最多处理 100 个任务
    private static final int MAX_BATCH_SIZE = 100;

    private final ResourceSlotMapper slotMapper;
    private final ResourceSlotCache slotCache;

    // 记录待重试缓存删除任务
    private record RetryTask(
            Long resourceId,
            LocalDate date,
            int retryCount
    ) {}

    // 内存中的线程安全队列
    private final Queue<RetryTask> retryQueue =
            new ConcurrentLinkedQueue<>();

    public ResourceSlotCacheInvalidator(
            ResourceSlotMapper slotMapper,
            ResourceSlotCache slotCache) {

        this.slotMapper = slotMapper;
        this.slotCache = slotCache;
    }

    /**
     * 根据 slotId 定位缓存。
     * 有事务时注册提交后的删除回调；
     * 没有可用的事务同步机制时，沿用立即删除策略。
     */
    public void evictAfterCommit(Long slotId) {

        ResourceSlot slot;

        // 1. 查询时隙，找到对应资源和日期
        try {
            slot = slotMapper.findById(slotId);
        } catch (DataAccessException e) {
            log.warn(
                    "[SLOTS CACHE INVALIDATION ERROR] slotId={}",
                    slotId, e
            );
            return;
        }

        // 2. 检查必要信息是否存在
        if (slot == null
                || slot.getResourceId() == null
                || slot.getSlotStart() == null) {

            log.warn(
                    "[SLOTS CACHE INVALIDATION SKIPPED] slotId={}",
                    slotId
            );
            return;
        }

        Long resourceId = slot.getResourceId();
        LocalDate date = slot.getSlotStart().toLocalDate();

        // 3. 判断当前是否有事务且支持注册回调
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {

            // 有事务：注册 afterCommit，暂时不删除
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {

                        @Override
                        public void afterCommit() {
                            evictOrQueue(resourceId, date);
                        }
                    }
            );

        } else {

            // 没有可用的事务同步上下文：立即尝试删除
            evictOrQueue(resourceId, date);
        }
    }

    /**
     * 尝试删除 Redis 缓存。
     * 删除失败则加入内存重试队列。
     */
    private void evictOrQueue(Long resourceId, LocalDate date) {

        boolean success = slotCache.evict(resourceId, date);

        if (!success) {

            retryQueue.offer(
                    new RetryTask(resourceId, date, 0)
            );

            log.warn(
                    "[SLOTS CACHE RETRY QUEUED] resourceId={} date={}",
                    resourceId, date
            );
        }
    }

    /**
     * 每次执行结束后等待 5 秒，再处理下一轮。
     */
    @Scheduled(fixedDelay = 5000)
    public void retryFailedEvictions() {
        // 本轮最多处理 100 个待重试任务
        int batchSize = Math.min(
                retryQueue.size(),
                MAX_BATCH_SIZE
        );

        for (int i = 0; i < batchSize; i++) {

            // 1. 从队列取出任务
            RetryTask task = retryQueue.poll();

            if (task == null) {
                break;
            }

            // 2. 再次尝试删除 Redis
            boolean success = slotCache.evict(
                    task.resourceId(),
                    task.date()
            );

            // 3. 删除成功，任务结束
            if (success) {

                log.info(
                        "[SLOTS CACHE RETRY SUCCESS] resourceId={} date={}",
                        task.resourceId(),
                        task.date()
                );

                continue;
            }

            // 4. 更新后台重试次数
            int nextCount = task.retryCount() + 1;

            // 5. 还有重试机会，重新入队
            if (nextCount < MAX_RETRIES) {

                retryQueue.offer(
                        new RetryTask(
                                task.resourceId(),
                                task.date(),
                                nextCount
                        )
                );

                log.warn(
                        "[SLOTS CACHE RETRY FAILED] resourceId={} date={} attempt={}",
                        task.resourceId(),
                        task.date(),
                        nextCount
                );

            } else {

                // 6. 超过最大次数，不再重试
                log.error(
                        "[SLOTS CACHE RETRY EXHAUSTED] resourceId={} date={}",
                        task.resourceId(),
                        task.date()
                );
            }
        }
    }
}