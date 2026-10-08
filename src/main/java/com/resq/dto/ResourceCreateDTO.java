package com.resq.dto;

import jakarta.validation.constraints.NotBlank;

public class ResourceCreateDTO {

    @NotBlank(message = "name must not be blank")
    private String name;

    @NotBlank(message = "type must not be blank")
    private String type;

    public ResourceCreateDTO() {
    }

    public ResourceCreateDTO(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}