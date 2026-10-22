
package com.resq.service;

import com.resq.cache.ResourceCache;
import com.resq.cache.ResourceSlotCache;
import com.resq.domain.ComputeResource;
import com.resq.domain.ResourceSlot;
import com.resq.dto.ResourceCreateDTO;
import com.resq.exception.ResourceNotFoundException;
import com.resq.mapper.ResourceMapper;
import com.resq.mapper.ResourceSlotMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ResourceService {

    // 新增：日志记录器
    private static final Logger log =
            LoggerFactory.getLogger(ResourceService.class);

    private final ResourceMapper resourceMapper;
    private final ResourceSlotMapper resourceSlotMapper;

    // 新增：Redis 缓存组件
    private final ResourceCache resourceCache;
    private final ResourceSlotCache resourceSlotCache;

    public ResourceService(
            ResourceMapper resourceMapper,
            ResourceSlotMapper resourceSlotMapper,
            ResourceCache resourceCache,
            ResourceSlotCache resourceSlotCache) {

        this.resourceMapper = resourceMapper;
        this.resourceSlotMapper = resourceSlotMapper;
        this.resourceCache = resourceCache;
        this.resourceSlotCache = resourceSlotCache;
    }

    public List<ComputeResource> findAll() {
        return resourceMapper.findAll();
    }

    // Day15：Cache Aside 资源详情缓存
// Day16：Cache Aside + 空值缓存，防止缓存穿透
public ComputeResource findById(Long id) {

    // 1. 优先查询 Redis
    ResourceCache.CacheResult cached = resourceCache.get(id);

    // 2. 命中正常资源缓存，直接返回
    if (cached.status() == ResourceCache.CacheStatus.FOUND) {
        return cached.resource();
    }

    // 3. 命中空值缓存，直接返回 404，不访问 MySQL
    if (cached.status() == ResourceCache.CacheStatus.NOT_FOUND) {
        throw new ResourceNotFoundException(
                "Resource not found: " + id
        );
    }

    // 4. MISS：查询 MySQL
    log.debug("[DB QUERY] resourceId={}", id);

    ComputeResource resource = resourceMapper.findById(id);

    // 5. MySQL 中也不存在，写入短 TTL 空值缓存
    if (resource == null) {
        resourceCache.putNotFound(id);

        throw new ResourceNotFoundException(
                "Resource not found: " + id
        );
    }

    // 6. MySQL 中存在，写入正常资源缓存
    resourceCache.put(id, resource);

    // 7. 返回资源对象
    return resource;
}

    public List<ResourceSlot> findSlotsByResourceId(Long resourceId) {
        // 先确认资源存在
        findById(resourceId);
        return resourceSlotMapper.findByResourceId(resourceId);
    }

    /** date 为空时保留原来的全部时隙查询；按时隙开始日期筛选。 */
    public List<ResourceSlot> findSlotsByResourceId(Long resourceId, LocalDate date) {
        if (date == null) {
            return findSlotsByResourceId(resourceId);
        }

        // 即使日期缓存命中，也先沿用资源不存在时的 404 逻辑。
        findById(resourceId);
        List<ResourceSlot> cached = resourceSlotCache.get(resourceId, date);
        // 空列表也是命中，只有 null 才表示 miss。
        if (cached != null) {
            return cached;
        }

        log.debug("[SLOTS DB QUERY] resourceId={} date={}", resourceId, date);
        List<ResourceSlot> slots = resourceSlotMapper.findByResourceIdAndDate(resourceId, date);
        resourceSlotCache.put(resourceId, date, slots);
        return slots;
    }

    public ComputeResource create(ResourceCreateDTO dto) {

        ComputeResource resource = new ComputeResource(
                null,
                dto.getName(),
                dto.getType(),
                "AVAILABLE",
                LocalDateTime.now()
        );

        resourceMapper.insert(resource);

        return resource;
    }
}
