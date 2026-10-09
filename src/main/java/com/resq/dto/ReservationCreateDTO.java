package com.resq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 请求体不包含 userId/status；身份和初始状态由服务端确定。 */
public class ReservationCreateDTO {

    @NotNull(message = "slotId must not be null")
    @Positive(message = "slotId must be positive")
    private Long slotId;

    @NotBlank(message = "requestId must not be blank")
    @Size(max = 100, message = "requestId must be at most 100 characters")
    private String requestId;

    public ReservationCreateDTO() {
    }

    public ReservationCreateDTO(Long slotId, String requestId) {
        this.slotId = slotId;
        this.requestId = requestId;
    }

    public Long getSlotId() { return slotId; }
    public void setSlotId(Long slotId) { this.slotId = slotId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
