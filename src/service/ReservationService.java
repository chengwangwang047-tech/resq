package service;

import domain.Reservation;
import domain.ResourceSlot;
import domain.User;
import exception.ResourceNotFoundException;
import exception.SlotConflictException;
import result.Result;

/**
 * Defines the reservation operations without choosing a storage mechanism yet.
 */
public interface ReservationService {

    /**
     * Creates a pending reservation for a user and resource slot.
     *
     * @throws ResourceNotFoundException when the user, resource, or slot is unavailable
     * @throws SlotConflictException when the slot cannot accept another reservation
     */
    Result<Reservation> createReservation(User user, ResourceSlot slot);

    /**
     * Cancels an existing reservation.
     *
     * @throws ResourceNotFoundException when the reservation does not exist
     * @throws SlotConflictException when its current state cannot be cancelled
     */
    Result<Reservation> cancelReservation(String reservationId);

    /**
     * Confirms an existing reservation.
     *
     * @throws ResourceNotFoundException when the reservation does not exist
     * @throws SlotConflictException when its current state cannot be confirmed
     */
    Result<Reservation> confirmReservation(String reservationId);
}

