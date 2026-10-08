package com.resq.controller;

import com.resq.dto.ResourceCreateDTO;
import com.resq.service.ResourceService;
import com.resq.vo.ResourceVO;
import com.resq.vo.SlotVO;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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
    public List<ResourceVO> findAll() {
        return resourceService.findAll()
                .stream()
                .map(ResourceVO::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResourceVO findById(@PathVariable Long id) {
        return ResourceVO.from(
                resourceService.findById(id)
        );
    }

    @GetMapping("/{id}/slots")
    public List<SlotVO> findSlotsByResourceId(@PathVariable Long id) {
        return resourceService.findSlotsByResourceId(id)
                .stream()
                .map(SlotVO::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceVO create(
            @Valid @RequestBody ResourceCreateDTO dto) {

        return ResourceVO.from(
                resourceService.create(dto)
        );
    }
}