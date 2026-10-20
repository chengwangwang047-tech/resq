package com.resq.mq;

public record ReservationTimeoutEvent(
        String eventId,
        Long reservationId,
        String eventType
) {
    public static ReservationTimeoutEvent forReservation(
            Long reservationId) {

        return new ReservationTimeoutEvent(
                "reservation-expire-" + reservationId,
                reservationId,
                "RESERVATION_EXPIRE"
        );
    }
}