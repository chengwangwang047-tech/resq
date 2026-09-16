package domain;

public class ResourceSlot {

    private String id;
    private ComputeResource resource;
    private String startTime;
    private String endTime;

    public ResourceSlot(
            String id,
            ComputeResource resource,
            String startTime,
            String endTime) {

        this.id = id;
        this.resource = resource;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public String getId() {
        return id;
    }

    public ComputeResource getResource() {
        return resource;
    }

    public String getStartTime() {
        return startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setResource(ComputeResource resource) {
        this.resource = resource;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }
}