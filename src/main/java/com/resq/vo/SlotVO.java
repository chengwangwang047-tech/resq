package com.resq.vo;

import com.resq.domain.ResourceSlot;

import java.time.LocalDateTime;

public class SlotVO {

    private Long id;
    private Long resourceId;
    private LocalDateTime slotStart;
    private LocalDateTime slotEnd;
    private String status;

    public SlotVO(Long id, Long resourceId, LocalDateTime slotStart,
                  LocalDateTime slotEnd, String status) {
        this.id = id;
        this.resourceId = resourceId;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.status = status;
    }

    public static SlotVO from(ResourceSlot slot) {
        return new SlotVO(
                slot.getId(),
                slot.getResourceId(),
                slot.getSlotStart(),
                slot.getSlotEnd(),
                slot.getStatus()
        );
    }

    public Long getId() {
        return id;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public LocalDateTime getSlotStart() {
        return slotStart;
    }

    public LocalDateTime getSlotEnd() {
        return slotEnd;
    }

    public String getStatus() {
        return status;
    }
}
