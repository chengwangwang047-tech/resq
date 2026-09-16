package domain;

public class ComputeResource {

    private String id;
    private String name;
    private String type;
    private String description;
    private boolean enabled;

    public ComputeResource(String id, String name, String type, String description, boolean enabled) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.description = description;
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}