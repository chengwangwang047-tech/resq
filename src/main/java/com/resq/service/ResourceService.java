package com.resq.service;

import com.resq.domain.ComputeResource;
import com.resq.domain.ResourceSlot;
import com.resq.dto.ResourceCreateDTO;
import com.resq.exception.ResourceNotFoundException;
import com.resq.mapper.ResourceMapper;
import com.resq.mapper.ResourceSlotMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ResourceService {

    private final ResourceMapper resourceMapper;
    private final ResourceSlotMapper resourceSlotMapper;

    public ResourceService(ResourceMapper resourceMapper,
                           ResourceSlotMapper resourceSlotMapper) {
        this.resourceMapper = resourceMapper;
        this.resourceSlotMapper = resourceSlotMapper;
    }

    public List<ComputeResource> findAll() {
        return resourceMapper.findAll();
    }

    public ComputeResource findById(Long id) {
        ComputeResource resource = resourceMapper.findById(id);

        if (resource == null) {
            throw new ResourceNotFoundException(
                    "Resource not found: " + id
            );
        }

        return resource;
    }

    public List<ResourceSlot> findSlotsByResourceId(Long resourceId) {
        // 先确认资源存在，复用现有异常处理；没有时隙则自然返回空列表。
        findById(resourceId);
        return resourceSlotMapper.findByResourceId(resourceId);
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