import domain.*;

public class DomainTest {

    public static void main(String[] args) {

        User user = new User(
                "u1",
                "Tom",
                "123456",
                "tom@example.com"
        );

        ComputeResource resource = new ComputeResource(
                "r1",
                "A100",
                "GPU",
                "80GB GPU",
                true
        );

        ResourceSlot slot = new ResourceSlot(
                "s1",
                resource,
                "10:00",
                "12:00"
        );

        Reservation reservation = new Reservation(
                "rv1",
                user,
                slot,
                ReservationStatus.PENDING
        );

        System.out.println(reservation.getUser().getUsername());

        System.out.println(
                reservation
                        .getSlot()
                        .getResource()
                        .getName()
        );

        System.out.println(reservation.getStatus());

        reservation.setStatus(
                ReservationStatus.CONFIRMED
        );

        System.out.println(reservation.getStatus());
    }
}