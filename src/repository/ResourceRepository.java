package repository;

import domain.ComputeResource;
import java.util.*;

public class ResourceRepository {

    private Map<Long, ComputeResource> storage = new HashMap<>();

    public void save(ComputeResource resource) {
        storage.put(Long.valueOf(resource.getId()), resource);
    }

    public ComputeResource findById(Long id) {
        return storage.get(id);
    }

    public void delete(Long id) {
        storage.remove(id);
    }

    public List<ComputeResource> findAll() {
        return new ArrayList<>(storage.values());
    }
}