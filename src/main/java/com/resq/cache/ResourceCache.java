
package com.resq.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resq.domain.ComputeResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ResourceCache {

    private static final Logger log =
            LoggerFactory.getLogger(ResourceCache.class);

    // Redis Key 前缀
    private static final String KEY_PREFIX = "resource:";

    // 缓存有效期：5 分钟
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // 构造器注入
    public ResourceCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {

        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // 1. 查询缓存
    public ComputeResource get(Long id) {

        String key = KEY_PREFIX + id;
        String json;

        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("[CACHE ERROR] GET resourceId={}", id);
            return null;
        }

        // 缓存未命中
        if (json == null) {
            log.debug("[CACHE MISS] resourceId={}", id);
            return null;
        }

        // JSON 转换为 Java 对象
        try {
            ComputeResource resource =
                    objectMapper.readValue(json, ComputeResource.class);

            log.debug("[CACHE HIT] resourceId={}", id);
            return resource;

        } catch (JsonProcessingException e) {
            log.warn("[CACHE JSON ERROR] resourceId={}", id);
            return null;
        }
    }

    // 2. 写入缓存
    public void put(Long id, ComputeResource resource) {

        String key = KEY_PREFIX + id;

        try {
            // Java 对象转换为 JSON 字符串
            String json = objectMapper.writeValueAsString(resource);

            // 写入 Redis，并设置 TTL
            redisTemplate.opsForValue().set(key, json, TTL);

            log.debug("[CACHE PUT] resourceId={}", id);

        } catch (JsonProcessingException e) {
            log.warn("[CACHE JSON ERROR] resourceId={}", id);

        } catch (DataAccessException e) {
            log.warn("[CACHE ERROR] PUT resourceId={}", id);
        }
    }

    // 3. 删除缓存
    public void evict(Long id) {

        String key = KEY_PREFIX + id;

        try {
            redisTemplate.delete(key);
            log.debug("[CACHE EVICT] resourceId={}", id);

        } catch (DataAccessException e) {
            log.error("[CACHE EVICT FAILED] resourceId={}", id);
            throw e;
        }
    }
}
