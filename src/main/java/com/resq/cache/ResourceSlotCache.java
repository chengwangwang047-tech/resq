package com.resq.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resq.domain.ResourceSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@Component
public class ResourceSlotCache {
    private static final Logger log = LoggerFactory.getLogger(ResourceSlotCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final TypeReference<List<ResourceSlot>> SLOT_LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ResourceSlotCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        // 使用 Spring 配置的 ObjectMapper，保留 JavaTimeModule 对 LocalDateTime 的支持。
        this.objectMapper = objectMapper;
    }

    private String key(Long resourceId, LocalDate date) {
        return "resource:" + resourceId + ":slots:" + date;
    }

    /** null 表示 miss；包括 [] 在内的非 null 列表表示 hit。 */
    public List<ResourceSlot> get(Long resourceId, LocalDate date) {
        try {
            String json = redisTemplate.opsForValue().get(key(resourceId, date));
            if (json == null) {
                log.debug("[SLOTS CACHE MISS] resourceId={} date={}", resourceId, date);
                return null;
            }
            List<ResourceSlot> slots = objectMapper.readValue(json, SLOT_LIST_TYPE);
            if (slots == null || slots.stream().anyMatch(slot -> slot == null)) {
                log.warn("[SLOTS CACHE JSON ERROR] resourceId={} date={}", resourceId, date);
                return null;
            }
            log.debug("[SLOTS CACHE HIT] resourceId={} date={}", resourceId, date);
            return slots;
        } catch (JsonProcessingException e) {
            log.warn("[SLOTS CACHE JSON ERROR] resourceId={} date={}", resourceId, date);
        } catch (DataAccessException e) {
            log.warn("[SLOTS CACHE ERROR] GET resourceId={} date={}", resourceId, date);
        }
        return null;
    }

    public void put(Long resourceId, LocalDate date, List<ResourceSlot> slots) {
        try {
            String json = objectMapper.writeValueAsString(slots);
            redisTemplate.opsForValue().set(key(resourceId, date), json, TTL);
            log.debug("[SLOTS CACHE PUT] resourceId={} date={}", resourceId, date);
        } catch (JsonProcessingException e) {
            log.warn("[SLOTS CACHE JSON ERROR] resourceId={} date={}", resourceId, date);
        } catch (DataAccessException e) {
            log.warn("[SLOTS CACHE ERROR] PUT resourceId={} date={}", resourceId, date);
        }
    }

    /** 精确删除单日缓存；失败交给 TTL 兜底，不改变已经提交的预约结果。 */
    public void evict(Long resourceId, LocalDate date) {
        try {
            redisTemplate.delete(key(resourceId, date));
            log.debug("[SLOTS CACHE EVICT] resourceId={} date={}", resourceId, date);
        } catch (DataAccessException e) {
            log.warn("[SLOTS CACHE ERROR] EVICT resourceId={} date={}", resourceId, date);
        }
    }
}
