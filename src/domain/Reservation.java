package domain;

public class Reservation {

    private String id;
    private User user;
    private ResourceSlot slot;
    private ReservationStatus status;

    public Reservation(
            String id,
            User user,
            ResourceSlot slot,
            ReservationStatus status) {

        this.id = id;
        this.user = user;
        this.slot = slot;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public ResourceSlot getSlot() {
        return slot;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public void setSlot(ResourceSlot slot) {
        this.slot = slot;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }
}