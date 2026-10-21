package com.resq.service;

import com.resq.domain.ResourceSlot;
import com.resq.exception.ReservationConflictException;
import com.resq.mapper.ResourceSlotMapper;
import com.resq.vo.WaitlistVO;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class WaitlistService {

    private final ResourceSlotMapper slotMapper;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> joinScript;

    public WaitlistService(
            ResourceSlotMapper slotMapper,
            StringRedisTemplate redisTemplate) {

        this.slotMapper = slotMapper;
        this.redisTemplate = redisTemplate;

        this.joinScript = new DefaultRedisScript<>();
        this.joinScript.setLocation(
                new ClassPathResource("lua/waitlist_join.lua"));
        this.joinScript.setResultType(Long.class);
    }

    // 加入候补队列
    public WaitlistVO join(Long slotId, Long userId) {

        validateIds(slotId, userId);

        // 1. 查询 MySQL 中的时隙
        ResourceSlot slot = requireSlot(slotId);

        // 2. 只允许已被预约的时隙进入候补
        if (!"RESERVED".equals(slot.getStatus())) {
            throw new ReservationConflictException(
                    "当前时隙未被占用，无需候补");
        }

        // 3. 时隙不能已经结束
        LocalDateTime now = LocalDateTime.now();

        if (slot.getSlotEnd() == null
                || !slot.getSlotEnd().isAfter(now)) {
            throw new ReservationConflictException(
                    "时隙已结束，无法候补");
        }

        // 4. 计算距离时隙结束的剩余时间
        long remainingMillis = Duration.between(
                now, slot.getSlotEnd()).toMillis();

        long ttlSeconds = Math.max(
                1L, remainingMillis / 1000 + 1);

        // 5. 构造 Redis Keys
        String waitlistKey = waitlistKey(slotId);
        String sequenceKey = sequenceKey(slotId);

        // 6. 原子入队
        Long result = redisTemplate.execute(
                joinScript,
                List.of(waitlistKey, sequenceKey),
                String.valueOf(userId),
                String.valueOf(ttlSeconds)
        );

        if (result == null || (result != 0L && result != 1L)) {
            throw new IllegalStateException(
                    "候补队列写入结果异常");
        }

        // 7. 返回当前用户候补信息
        return get(slotId, userId);
    }

    // 查询当前用户候补状态
    public WaitlistVO get(Long slotId, Long userId) {

        validateIds(slotId, userId);
        requireSlot(slotId);

        String key = waitlistKey(slotId);
        String member = String.valueOf(userId);

        // ZRANK 从 0 开始
        Long rank = redisTemplate.opsForZSet()
                .rank(key, member);

        Long total = redisTemplate.opsForZSet()
                .size(key);

        return new WaitlistVO(
                slotId,
                rank != null,
                rank == null ? null : rank + 1,
                total == null ? 0L : total
        );
    }

    private ResourceSlot requireSlot(Long slotId) {
        ResourceSlot slot = slotMapper.findById(slotId);

        if (slot == null) {
            throw new NoSuchElementException("时隙不存在");
        }

        return slot;
    }

    private void validateIds(Long slotId, Long userId) {
        if (slotId == null || slotId <= 0
                || userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "参数不合法");
        }
    }

    private String waitlistKey(Long slotId) {
        return "resq:waitlist:slot:" + slotId;
    }

    private String sequenceKey(Long slotId) {
        return "resq:waitlist:seq:" + slotId;
    }
}