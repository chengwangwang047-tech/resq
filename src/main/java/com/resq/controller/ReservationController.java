
package com.resq.controller;

import com.resq.domain.Reservation;
import com.resq.dto.ReservationCreateDTO;
import com.resq.service.ReservationService;

import jakarta.validation.Valid;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
            @RequestAttribute("currentUserId") Long userId) {

        return reservationService.create(userId, dto);
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
