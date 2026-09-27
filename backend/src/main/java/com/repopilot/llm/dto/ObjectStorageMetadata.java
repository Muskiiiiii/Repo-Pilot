package com.repopilot.llm.dto;

import java.time.Instant;

public class ObjectStorageMetadata {
    private String objectKey;
    private long sizeBytes;
    private String contentType;
    private Instant lastModified;

    public ObjectStorageMetadata() {
    }

    public ObjectStorageMetadata(String objectKey, long sizeBytes, String contentType, Instant lastModified) {
        this.objectKey = objectKey;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.lastModified = lastModified;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public void setLastModified(Instant lastModified) {
        this.lastModified = lastModified;
    }
}
