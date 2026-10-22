package com.resq.vo;

import com.resq.domain.Reservation;
import java.time.LocalDateTime;
import java.util.List;

public record ReservationPageVO(
        List<Reservation> records,
        boolean hasMore,
        LocalDateTime nextCursorCreatedAt,
        Long nextCursorId
) {
}