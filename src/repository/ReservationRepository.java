package repository;

import domain.Reservation;
import java.util.*;

public class ReservationRepository {

    private Map<Long, Reservation> storage = new HashMap<>();

    public void save(Reservation reservation) {
        Long id = Long.valueOf(reservation.getId());
        storage.put(id, reservation);
    }

    public Reservation findById(Long id) {
        return storage.get(id);
    }

    public void delete(Long id) {
        storage.remove(id);
    }

    public List<Reservation> findAll() {
        List<Reservation> allReservation = new ArrayList<>();

        for (Reservation r : storage.values()) {
            allReservation.add(r);
        }

        return allReservation;
    }
}