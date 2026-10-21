package com.resq.service;

import com.resq.exception.RateLimitExceededException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ReservationRateLimiter {

    private static final Logger log =
            LoggerFactory.getLogger(ReservationRateLimiter.class);

    private static final long KEY_TTL_SECONDS = 120;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    @Value("${resq.rate-limit.reservation-per-minute:10}")
    private int maxRequestsPerMinute;

    public ReservationRateLimiter(
            StringRedisTemplate redisTemplate) {

        this.redisTemplate = redisTemplate;

        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setLocation(
                new ClassPathResource(
                        "lua/reservation_rate_limit.lua"));
        this.rateLimitScript.setResultType(Long.class);
    }

    public void check(Long userId) {

        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 ID 不合法");
        }

        // 当前自然分钟编号
        long minute = Instant.now().getEpochSecond() / 60;

        // 每个用户、每个分钟使用独立 Redis Key
        String key = "rate:reservation:user:"
                + userId + ":" + minute;

        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    List.of(key),
                    String.valueOf(maxRequestsPerMinute),
                    String.valueOf(KEY_TTL_SECONDS)
            );

            if (result == null) {
                throw new IllegalStateException(
                        "Redis 限流脚本没有返回结果");
            }

            if (result == 0L) {
                throw new RateLimitExceededException(
                        "预约请求过于频繁，请稍后重试");
            }

            if (result != 1L) {
                throw new IllegalStateException(
                        "Redis 限流脚本返回未知结果");
            }

        } catch (RedisConnectionFailureException
                 | QueryTimeoutException e) {

            // 限流 Redis 不可用，保留已有业务降级路径
            log.warn(
                    "[RATE LIMIT] Redis 不可用，跳过本次限流，userId={}",
                    userId,
                    e
            );
        }
    }
}