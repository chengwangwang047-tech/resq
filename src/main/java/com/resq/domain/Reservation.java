package com.resq.domain;

import java.time.LocalDateTime;

/** Day13 正式实体，对应 reservation；旧 src/domain/Reservation.java 保持原样。 */
public class Reservation {

    private Long id;
    private Long userId;
    private Long slotId;
    // TODO(D13-STATUS): 在 Service 校验 PENDING / CONFIRMED / CANCELLED / EXPIRED 转换。
    private String status;
    private String requestId;
    private LocalDateTime createdAt;

    public Reservation() {
    }

    public Reservation(Long id, Long userId, Long slotId, String status,
                       String requestId, LocalDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.slotId = slotId;
        this.status = status;
        this.requestId = requestId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSlotId() { return slotId; }
    public void setSlotId(Long slotId) { this.slotId = slotId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
