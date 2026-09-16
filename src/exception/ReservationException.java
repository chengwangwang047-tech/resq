package exception;

/**
 * Base runtime exception for reservation business errors.
 */
public class ReservationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReservationException(String message) {
        super(message);
    }

    public ReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
