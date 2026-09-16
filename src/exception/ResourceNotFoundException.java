package exception;

/**
 * Thrown when a requested resource or reservation does not exist.
 */
public class ResourceNotFoundException extends ReservationException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
