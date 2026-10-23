package com.resq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ResourceCreateDTO {

    @NotBlank(message = "name must not be blank")
    @Size(max = 100, message = "name must not exceed 100 characters")
    private String name;

    @NotBlank(message = "type must not be blank")
    @Size(max = 20, message = "type must not exceed 20 characters")
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