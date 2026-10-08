package com.resq.vo;

import com.resq.domain.ComputeResource;

import java.time.LocalDateTime;

public class ResourceVO {

    private Long id;
    private String name;
    private String type;
    private String status;
    private LocalDateTime createdAt;

    public ResourceVO(Long id,
                      String name,
                      String type,
                      String status,
                      LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static ResourceVO from(ComputeResource resource) {
        return new ResourceVO(
                resource.getId(),
                resource.getName(),
                resource.getType(),
                resource.getStatus(),
                resource.getCreatedAt()
        );
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}