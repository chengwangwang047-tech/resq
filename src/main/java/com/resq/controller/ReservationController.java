
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
}
