import domain.Reservation;
import domain.ReservationStatus;
import repository.ReservationRepository;

public class Day4RepositoryPractice {

    public static void main(String[] args) {

        ReservationRepository repository =
                new ReservationRepository();

        // 这里只练习存取；user 和 slot 暂时不用。
        Reservation reservation =
                new Reservation(
                        "1001",
                        null,
                        null,
                        ReservationStatus.PENDING
                );

        // 1. 保存
        repository.save(reservation);


        // 2. 根据 id 查询
        Reservation found =
                repository.findById(1001L);

        System.out.println("findById:");
        System.out.println(found);


        // 3. 查询全部
        System.out.println("findAll:");

        for (Reservation r : repository.findAll()) {
            System.out.println(r);
        }


        // 4. 删除
        repository.delete(1001L);


        // 5. 删除后再次查询
        Reservation afterDelete =
                repository.findById(1001L);

        System.out.println("after delete:");
        System.out.println(afterDelete);
    }
}