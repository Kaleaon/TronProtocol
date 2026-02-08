package com.tronprotocol.app.selfmod;

/**
 * Represents a code modification proposal
 */
public class CodeModification {
    private String id;
    private String componentName;
    private String description;
    private String originalCode;
    private String modifiedCode;
    private long timestamp;
    private long appliedTimestamp;
    private ModificationStatus status;
    private String backupId;
    
    public CodeModification(String id, String componentName, String description,
                           String originalCode, String modifiedCode, long timestamp,
                           ModificationStatus status) {
        this.id = id;
        this.componentName = componentName;
        this.description = description;
        this.originalCode = originalCode;
        this.modifiedCode = modifiedCode;
        this.timestamp = timestamp;
        this.status = status;
    }
    
    // Getters
    public String getId() { return id; }
    public String getComponentName() { return componentName; }
    public String getDescription() { return description; }
    public String getOriginalCode() { return originalCode; }
    public String getModifiedCode() { return modifiedCode; }
    public long getTimestamp() { return timestamp; }
    public long getAppliedTimestamp() { return appliedTimestamp; }
    public ModificationStatus getStatus() { return status; }
    public String getBackupId() { return backupId; }
    
    // Setters
    public void setStatus(ModificationStatus status) { this.status = status; }
    public void setAppliedTimestamp(long appliedTimestamp) { this.appliedTimestamp = appliedTimestamp; }
    public void setBackupId(String backupId) { this.backupId = backupId; }
    
    @Override
    public String toString() {
        return "CodeModification{" +
                "id='" + id + '\'' +
                ", component='" + componentName + '\'' +
                ", status=" + status +
                ", description='" + description + '\'' +
                '}';
    }
}
