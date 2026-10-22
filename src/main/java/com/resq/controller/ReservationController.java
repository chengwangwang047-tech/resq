
package com.resq.controller;

import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.service.ReservationLockService;
import com.resq.service.ReservationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.resq.vo.ReservationPageVO;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationLockService reservationLockService;

    public ReservationController(
            ReservationService reservationService,
            ReservationLockService reservationLockService) {

        this.reservationService = reservationService;
        this.reservationLockService = reservationLockService;
    }

    // POST /api/reservations
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Reservation create(
            @Valid @RequestBody ReservationCreateDTO dto,
            @RequestAttribute("currentUserId") Long userId,
            @RequestHeader("X-Request-Id") @NotBlank(message = "requestId must not be blank") @Size(max = 100, message = "requestId must not exceed 100 characters") String requestId) {

        if (requestId == null ||
                !requestId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "X-Request-Id 格式不合法");
        }

        dto.setRequestId(requestId);
        return reservationLockService.create(userId, dto);

    }

    // GET /api/reservations/me
    @GetMapping("/me")
    public List<Reservation> findMine(
            @RequestAttribute("currentUserId") Long userId) {

        return reservationService.findByUserId(userId);
    }
    // Day23：当前用户预约历史游标分页
@GetMapping("/me/page")
public ReservationPageVO findMinePage(
        @RequestAttribute("currentUserId") Long userId,

        @RequestParam(name = "size", defaultValue = "20")
        int size,

        @RequestParam(name = "cursorCreatedAt", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime cursorCreatedAt,

        @RequestParam(name = "cursorId", required = false)
        Long cursorId) {

    // 1. 限制分页大小
    if (size < 1 || size > 100) {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "size 必须在 1~100 之间"
        );
    }

    // 2. 两个游标必须同时提供或同时不提供
    if ((cursorCreatedAt == null) != (cursorId == null)) {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "两个游标参数必须同时提供"
        );
    }

    // 3. 游标 ID 必须合法
    if (cursorId != null && cursorId <= 0) {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "cursorId 必须大于 0"
        );
    }

    // 4. 调用 Service
    return reservationService.findPageByUserId(
            userId,
            cursorCreatedAt,
            cursorId,
            size
    );
}
    
    // GET /api/reservations/{id}
    @GetMapping("/{id}")
    public Reservation findById(
            @PathVariable Long id,
            @RequestAttribute("currentUserId") Long userId) {

        return reservationService.findById(userId, id);
    }

    // DELETE /api/reservations/{id}
    @DeleteMapping("/{id}")
    public Reservation cancel(
            @PathVariable Long id,
            @RequestAttribute("currentUserId") Long userId) {

        return reservationService.cancel(userId, id);
    }

    /**
     * Day20：用户确认预约。
     */
    @PostMapping("/{id}/confirm")
    public Reservation confirm(
            @PathVariable Long id,
            @RequestAttribute("currentUserId") Long userId) {

        return reservationService.confirm(userId, id);
    }
}
