package repository;

import domain.User;
import java.util.*;

public class UserRepository {

    private Map<Long, User> storage = new HashMap<>();

    public void save(User user) {
        storage.put(Long.valueOf(user.getId()),user);
    }

    public User findById(Long id) {
        return storage.get(id);
    }

    public void delete(Long id) {
        storage.remove(id);
    }

    public List<User> findAll() {
        return new ArrayList<>(storage.values());
    }
}