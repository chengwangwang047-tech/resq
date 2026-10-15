package com.resq.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class ReservationRequestStateStore {

    private final StringRedisTemplate redisTemplate;

    // PROCESSING 最多保留 3 分钟
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(3);

    // SUCCESS 短期保留 24 小时
    private static final Duration SUCCESS_TTL = Duration.ofHours(24);

    public ReservationRequestStateStore(
            StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 构造 Redis Key
    private String key(String requestId) {
        return "request:" + requestId;
    }

    // 构造 PROCESSING 状态
    private String processingValue(
            Long userId, Long slotId, String ownerToken) {

        return "PROCESSING|" + ownerToken
                + "|" + userId
                + "|" + slotId;
    }

    // 1. 读取现有状态
    public String getState(String requestId) {
        return redisTemplate.opsForValue()
                .get(key(requestId));
    }

    // 2. 尝试获取处理资格
    public boolean tryStart(
            String requestId,
            Long userId,
            Long slotId,
            String ownerToken) {

        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(
                        key(requestId),
                        processingValue(userId, slotId, ownerToken),
                        PROCESSING_TTL);

        return Boolean.TRUE.equals(success);
    }

    // 3. 只有当前所有者才能改成 SUCCESS
    private static final DefaultRedisScript<Long> SUCCESS_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]); "
                    + "return 1 else return 0 end",
            Long.class);

    public boolean markSuccess(
            String requestId,
            Long userId,
            Long slotId,
            String ownerToken,
            Long reservationId) {

        String expected = processingValue(userId, slotId, ownerToken);

        String successValue = "SUCCESS|" + reservationId
                + "|" + userId
                + "|" + slotId;

        Long result = redisTemplate.execute(
                SUCCESS_SCRIPT,
                List.of(key(requestId)),
                expected,
                successValue,
                String.valueOf(SUCCESS_TTL.getSeconds()));

        return Long.valueOf(1L).equals(result);
    }

    // 4. 只有当前所有者才能删除 PROCESSING
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('DEL', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    public boolean releaseProcessing(
            String requestId,
            Long userId,
            Long slotId,
            String ownerToken) {

        Long result = redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(key(requestId)),
                processingValue(userId, slotId, ownerToken));

        return Long.valueOf(1L).equals(result);
    }
}