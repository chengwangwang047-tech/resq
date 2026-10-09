
package com.resq.controller;

import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.service.ReservationService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    // POST /api/reservations
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Reservation create(
            @Valid @RequestBody ReservationCreateDTO dto,
            Principal principal) {

        Long userId = currentUserId(principal);
        return reservationService.create(userId, dto);
    }

    // GET /api/reservations/{id}
    @GetMapping("/{id}")
    public Reservation findById(
            @PathVariable Long id,
            Principal principal) {

        Long userId = currentUserId(principal);
        return reservationService.findById(userId, id);
    }

    // DELETE /api/reservations/{id}
    @DeleteMapping("/{id}")
    public Reservation cancel(
            @PathVariable Long id,
            Principal principal) {

        Long userId = currentUserId(principal);
        return reservationService.cancel(userId, id);
    }

    // Day14: 接入真正的身份认证后，适配用户 ID 提取逻辑
    private Long currentUserId(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "请先登录");
        }

        try {
            Long userId = Long.valueOf(principal.getName());

            if (userId <= 0) {
                throw new NumberFormatException();
            }

            return userId;

        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "无效的用户身份");
        }
    }
}
