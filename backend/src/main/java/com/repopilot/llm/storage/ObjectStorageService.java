package com.repopilot.llm.storage;

import com.repopilot.llm.dto.ObjectStorageMetadata;

public interface ObjectStorageService {
    void putObject(String objectKey, byte[] contentBytes, String contentType);

    byte[] getObject(String objectKey);

    ObjectStorageMetadata headObject(String objectKey);

    void deleteObject(String objectKey);

    boolean objectExists(String objectKey);

    String generateStorageKey(java.util.UUID orgId, java.util.UUID jobId, String artifactId);
}
