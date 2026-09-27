package domain;

import java.time.LocalDate;

public class ResourceSlot {

    private String id;
    private ComputeResource resource;
    private String startTime;
    private String endTime;
    private LocalDate date;
    private String status;

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

    public ResourceSlot(
            String id,
            ComputeResource resource,
            String startTime,
            String endTime,
            LocalDate date,
            String status) {

        this.id = id;
        this.resource = resource;
        this.startTime = startTime;
        this.endTime = endTime;
        this.date = date;
        this.status = status;
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

    public LocalDate getDate() {
        return date;
    }

    public String getStatus() {
        return status;
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

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
