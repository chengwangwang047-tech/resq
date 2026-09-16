package exception;

/**
 * Thrown when a reservation operation conflicts with the slot state.
 */
public class SlotConflictException extends ReservationException {

    private static final long serialVersionUID = 1L;

    public SlotConflictException(String message) {
        super(message);
    }
}
