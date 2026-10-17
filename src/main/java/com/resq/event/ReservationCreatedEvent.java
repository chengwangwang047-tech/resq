package com.resq.event;

public record ReservationCreatedEvent(
        Long reservationId,
        Long userId,
        Long slotId,
        String requestId,
        String status
) {
}