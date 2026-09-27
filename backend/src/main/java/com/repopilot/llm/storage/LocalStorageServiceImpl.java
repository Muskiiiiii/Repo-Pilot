package com.repopilot.llm.storage;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.ObjectStorageMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LocalStorageServiceImpl implements ObjectStorageService {

    private final Map<String, StorageEntry> memoryStore = new ConcurrentHashMap<>();
    private final Path baseStoragePath;

    public LocalStorageServiceImpl() {
        this.baseStoragePath = Paths.get(System.getProperty("java.io.tmpdir"), "repopilot-audit-exports");
        try {
            Files.createDirectories(baseStoragePath);
        } catch (IOException ignored) {}
    }

    @Override
    public void putObject(String objectKey, byte[] contentBytes, String contentType) {
        validateStorageKey(objectKey);
        if (contentBytes == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Object content cannot be null");
        }

        memoryStore.put(objectKey, new StorageEntry(contentBytes, contentType != null ? contentType : "application/octet-stream", Instant.now()));

        try {
            Path targetFile = baseStoragePath.resolve(objectKey).normalize();
            if (!targetFile.startsWith(baseStoragePath.normalize())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Path traversal forbidden");
            }
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, contentBytes);
        } catch (IOException ignored) {}
    }

    @Override
    public byte[] getObject(String objectKey) {
        validateStorageKey(objectKey);
        StorageEntry entry = memoryStore.get(objectKey);
        if (entry != null) {
            return entry.bytes;
        }

        try {
            Path targetFile = baseStoragePath.resolve(objectKey).normalize();
            if (Files.exists(targetFile) && targetFile.startsWith(baseStoragePath.normalize())) {
                return Files.readAllBytes(targetFile);
            }
        } catch (IOException ignored) {}

        throw new ApiException(HttpStatus.NOT_FOUND, "Export artifact object not found");
    }

    @Override
    public ObjectStorageMetadata headObject(String objectKey) {
        validateStorageKey(objectKey);
        StorageEntry entry = memoryStore.get(objectKey);
        if (entry != null) {
            return new ObjectStorageMetadata(objectKey, entry.bytes.length, entry.contentType, entry.createdAt);
        }

        try {
            Path targetFile = baseStoragePath.resolve(objectKey).normalize();
            if (Files.exists(targetFile) && targetFile.startsWith(baseStoragePath.normalize())) {
                long size = Files.size(targetFile);
                Instant lastModified = Files.getLastModifiedTime(targetFile).toInstant();
                return new ObjectStorageMetadata(objectKey, size, "application/octet-stream", lastModified);
            }
        } catch (IOException ignored) {}

        throw new ApiException(HttpStatus.NOT_FOUND, "Export artifact object not found");
    }

    @Override
    public void deleteObject(String objectKey) {
        validateStorageKey(objectKey);
        memoryStore.remove(objectKey);

        try {
            Path targetFile = baseStoragePath.resolve(objectKey).normalize();
            if (Files.exists(targetFile) && targetFile.startsWith(baseStoragePath.normalize())) {
                Files.delete(targetFile);
            }
        } catch (IOException ignored) {}
    }

    @Override
    public boolean objectExists(String objectKey) {
        validateStorageKey(objectKey);
        if (memoryStore.containsKey(objectKey)) {
            return true;
        }
        try {
            Path targetFile = baseStoragePath.resolve(objectKey).normalize();
            return Files.exists(targetFile) && targetFile.startsWith(baseStoragePath.normalize());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String generateStorageKey(UUID orgId, UUID jobId, String artifactId) {
        if (orgId == null || jobId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "orgId and jobId required for storage key generation");
        }
        String safeArtifactId = (artifactId != null && !artifactId.isBlank()) ? artifactId : UUID.randomUUID().toString();
        safeArtifactId = safeArtifactId.replaceAll("[^a-zA-Z0-9\\-_]", "");
        return "audit-exports/" + orgId + "/" + jobId + "/" + safeArtifactId + ".enc";
    }

    private void validateStorageKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Storage object key cannot be empty");
        }
        if (key.contains("..") || key.contains("\0") || key.startsWith("/") || key.startsWith("\\") || key.contains("://")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Path traversal or illegal key pattern forbidden");
        }
        for (char c : key.toCharArray()) {
            if (Character.isISOControl(c)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Control characters forbidden in storage key");
            }
        }
    }

    private static class StorageEntry {
        final byte[] bytes;
        final String contentType;
        final Instant createdAt;

        StorageEntry(byte[] bytes, String contentType, Instant createdAt) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.createdAt = createdAt;
        }
    }
}
