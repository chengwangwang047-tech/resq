import domain.ComputeResource;
import domain.Reservation;
import domain.ReservationStatus;
import domain.ResourceSlot;
import domain.User;
import exception.ReservationException;
import exception.ResourceNotFoundException;
import exception.SlotConflictException;
import result.Result;
import service.ReservationService;

public class Day3Test {

    public static void main(String[] args) {
        testResultSuccessAndFailure();
        testReservationServiceAndExceptions();

        System.out.println("Day 3 tests passed.");
    }

    private static void testResultSuccessAndFailure() {
        Result<String> success = Result.success("created", "reservation-1");
        check(success.isSuccess(), "success result should be successful");
        check("created".equals(success.getMessage()), "success message should be preserved");
        check("reservation-1".equals(success.getData()), "generic data should be preserved");

        Result<String> failure = Result.failure("slot conflict");
        check(!failure.isSuccess(), "failure result should not be successful");
        check("slot conflict".equals(failure.getMessage()), "failure message should be preserved");
        check(failure.getData() == null, "failure result should not contain data");
    }

    private static void testReservationServiceAndExceptions() {
        User user = new User("u1", "Tom", "123456", "tom@example.com");
        ComputeResource resource = new ComputeResource("r1", "A100", "GPU", "80GB GPU", true);
        ResourceSlot slot = new ResourceSlot("s1", resource, "10:00", "12:00");
        ReservationService service = new TestReservationService();

        Result<Reservation> created = service.createReservation(user, slot);
        check(created.isSuccess(), "createReservation should return a success result");
        check(created.getData().getStatus() == ReservationStatus.PENDING,
                "new reservation should be pending");

        try {
            service.cancelReservation("missing");
            throw new AssertionError("missing reservation should throw an exception");
        } catch (ResourceNotFoundException exception) {
            check(exception instanceof ReservationException,
                    "resource-not-found should belong to the business exception hierarchy");
        }

        Result<Reservation> cancelled = service.cancelReservation("rv-day3");
        check(cancelled.getData().getStatus() == ReservationStatus.CANCELLED,
                "cancelReservation should update the status");

        try {
            service.confirmReservation("rv-day3");
            throw new AssertionError("cancelled reservation should not be confirmed");
        } catch (SlotConflictException exception) {
            check(exception instanceof ReservationException,
                    "slot-conflict should belong to the business exception hierarchy");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class TestReservationService implements ReservationService {

        private Reservation reservation;

        @Override
        public Result<Reservation> createReservation(User user, ResourceSlot slot) {
            reservation = new Reservation("rv-day3", user, slot, ReservationStatus.PENDING);
            return Result.success("reservation created", reservation);
        }

        @Override
        public Result<Reservation> cancelReservation(String reservationId) {
            Reservation existing = requireReservation(reservationId);
            if (existing.getStatus() != ReservationStatus.PENDING) {
                throw new SlotConflictException("Only pending reservations can be cancelled");
            }

            existing.setStatus(ReservationStatus.CANCELLED);
            return Result.success("reservation cancelled", existing);
        }

        @Override
        public Result<Reservation> confirmReservation(String reservationId) {
            Reservation existing = requireReservation(reservationId);
            if (existing.getStatus() != ReservationStatus.PENDING) {
                throw new SlotConflictException("Only pending reservations can be confirmed");
            }

            existing.setStatus(ReservationStatus.CONFIRMED);
            return Result.success("reservation confirmed", existing);
        }

        private Reservation requireReservation(String reservationId) {
            if (reservation == null || !reservation.getId().equals(reservationId)) {
                throw new ResourceNotFoundException("Reservation not found: " + reservationId);
            }
            return reservation;
        }
    }
}
