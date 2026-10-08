package com.resq.domain;

import java.time.LocalDateTime;

/** Day12 正式实体：对应 MySQL resource_slot 表。 */
public class ResourceSlot {

    private Long id;
    private Long resourceId;
    private LocalDateTime slotStart;
    private LocalDateTime slotEnd;
    private String status;

    public ResourceSlot() {
    }

    public ResourceSlot(Long id, Long resourceId, LocalDateTime slotStart,
                        LocalDateTime slotEnd, String status) {
        this.id = id;
        this.resourceId = resourceId;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public LocalDateTime getSlotStart() {
        return slotStart;
    }

    public void setSlotStart(LocalDateTime slotStart) {
        this.slotStart = slotStart;
    }

    public LocalDateTime getSlotEnd() {
        return slotEnd;
    }

    public void setSlotEnd(LocalDateTime slotEnd) {
        this.slotEnd = slotEnd;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
