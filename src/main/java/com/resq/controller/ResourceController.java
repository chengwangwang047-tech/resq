package com.resq.controller;

import com.resq.domain.ComputeResource;
import com.resq.service.ResourceService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    public List<ComputeResource> findAll() {
        return resourceService.findAll();
    }

    @GetMapping("/{id}")
    public ComputeResource findById(@PathVariable Long id) {
        return resourceService.findById(id);
    }
}
