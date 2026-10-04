package com.resq.service;

import com.resq.domain.ComputeResource;
import com.resq.exception.ResourceNotFoundException;
import com.resq.mapper.ResourceMapper;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ResourceService {
    private final ResourceMapper resourceMapper;

    public ResourceService(ResourceMapper resourceMapper) {
        this.resourceMapper = resourceMapper;
    }

    public List<ComputeResource> findAll() {
        return resourceMapper.findAll();
    }

    public ComputeResource findById(Long id) {
        ComputeResource resource = resourceMapper.findById(id);
        if (resource == null) {
            throw new ResourceNotFoundException("Resource not found: " + id);
        }
        return resource;
    }
}
