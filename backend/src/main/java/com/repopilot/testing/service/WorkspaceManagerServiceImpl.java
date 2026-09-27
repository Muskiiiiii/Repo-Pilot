package com.repopilot.testing.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.common.exception.ApiException;
import com.repopilot.repository.entity.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Service
public class WorkspaceManagerServiceImpl implements WorkspaceManagerService {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceManagerServiceImpl.class);

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    @Value("${repopilot.testing.workspace-path:/tmp/repopilot/workspaces}")
    private String workspaceBasePath;

    @Override
    public Path materializeWorkspace(UUID executionId, Repository repository) {
        Path masterRepoDir = Paths.get(baseStoragePath, repository.getId().toString());
        if (!Files.exists(masterRepoDir) || !Files.isDirectory(masterRepoDir)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Master repository content not found on disk");
        }

        Path workspaceRootDir = Paths.get(workspaceBasePath, executionId.toString());
        Path workspaceRepoDir = workspaceRootDir.resolve("repo");

        try {
            // Clean up any stale workspace directory if present
            cleanupWorkspace(executionId);

            Files.createDirectories(workspaceRepoDir);

            // Physical recursive file copy (NO HARDLINKS)
            Files.walkFileTree(masterRepoDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path targetDir = workspaceRepoDir.resolve(masterRepoDir.relativize(dir));
                    if (!Files.exists(targetDir)) {
                        Files.createDirectories(targetDir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path targetFile = workspaceRepoDir.resolve(masterRepoDir.relativize(file));

                    // Reject external symlinks
                    if (attrs.isSymbolicLink()) {
                        Path linkTarget = Files.readSymbolicLink(file);
                        Path resolvedTarget = file.getParent().resolve(linkTarget).normalize();
                        if (!resolvedTarget.startsWith(masterRepoDir)) {
                            logger.warn("Skipping external symlink {} -> {} during workspace materialization", file, linkTarget);
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    // Deep physical copy (NO HARDLINKS)
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });

            // Write execution lock marker file
            Path lockFile = workspaceRootDir.resolve(".execution.lock");
            Files.writeString(lockFile, "executionId=" + executionId + "\ncreated=" + Instant.now(), StandardCharsets.UTF_8);

            logger.info("Materialized physical workspace for execution {} at {}", executionId, workspaceRepoDir);
            return workspaceRepoDir;

        } catch (IOException e) {
            logger.error("Failed to materialize workspace for execution {}: {}", executionId, e.getMessage(), e);
            cleanupWorkspace(executionId);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_CREATION_FAILED", "Failed to materialize isolated execution workspace: " + e.getMessage());
        }
    }

    @Override
    public void applyCodeChangeSnapshot(UUID executionId, Path workspaceRepoDir, List<CodeChange> codeChanges) {
        if (codeChanges == null || codeChanges.isEmpty()) {
            logger.info("No code changes in snapshot to apply for execution {}", executionId);
            return;
        }

        Path canonicalWorkspaceRoot;
        try {
            canonicalWorkspaceRoot = workspaceRepoDir.toRealPath();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_PATH_ERROR", "Invalid workspace path: " + e.getMessage());
        }

        for (CodeChange change : codeChanges) {
            String relPathStr = change.getFilePath();
            validatePathSecurity(relPathStr);

            Path targetPath = canonicalWorkspaceRoot.resolve(relPathStr).normalize();

            // Strict path traversal boundary check
            if (!targetPath.startsWith(canonicalWorkspaceRoot)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "CodeChange target path '" + relPathStr + "' escapes workspace boundary");
            }

            // Strict symlink rejection policy for CodeChange targets
            if (Files.isSymbolicLink(targetPath)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SYMLINK_TARGET_PROHIBITED", "CodeChange target path '" + relPathStr + "' is a symbolic link, which is strictly prohibited");
            }

            String operation = change.getOperation();
            if ("DELETE".equalsIgnoreCase(operation)) {
                if (Files.exists(targetPath)) {
                    try {
                        String currentContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                        String currentHash = computeSha256(currentContent);
                        if (change.getOriginalHash() != null && !change.getOriginalHash().equalsIgnoreCase(currentHash)) {
                            throw new ApiException(HttpStatus.BAD_REQUEST, "HASH_MISMATCH", "Original hash mismatch for file '" + relPathStr + "' prior to deletion");
                        }
                        Files.delete(targetPath);
                        logger.info("Deleted file '{}' in workspace {}", relPathStr, executionId);
                    } catch (IOException e) {
                        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_DELETE_FAILED", "Failed to delete file '" + relPathStr + "': " + e.getMessage());
                    }
                }
            } else { // CREATE or MODIFY
                try {
                    if ("MODIFY".equalsIgnoreCase(operation) || Files.exists(targetPath)) {
                        String currentContent = Files.exists(targetPath) ? Files.readString(targetPath, StandardCharsets.UTF_8) : "";
                        String currentHash = computeSha256(currentContent);
                        if (change.getOriginalHash() != null && !change.getOriginalHash().isEmpty() && !change.getOriginalHash().equalsIgnoreCase(currentHash)) {
                            throw new ApiException(HttpStatus.BAD_REQUEST, "HASH_MISMATCH", "Original SHA-256 mismatch for file '" + relPathStr + "'. Expected: " + change.getOriginalHash() + ", Actual: " + currentHash);
                        }
                    }

                    // Create parent directories if needed
                    Path parentDir = targetPath.getParent();
                    if (parentDir != null && !Files.exists(parentDir)) {
                        Files.createDirectories(parentDir);
                    }

                    String newContent = change.getNewContent() != null ? change.getNewContent() : "";
                    Files.writeString(targetPath, newContent, StandardCharsets.UTF_8);

                    // Post-write SHA-256 verification
                    String writtenHash = computeSha256(newContent);
                    if (change.getProposedHash() != null && !change.getProposedHash().isEmpty() && !change.getProposedHash().equalsIgnoreCase(writtenHash)) {
                        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "HASH_VERIFICATION_FAILED", "Post-write SHA-256 mismatch for file '" + relPathStr + "'. Expected: " + change.getProposedHash() + ", Written: " + writtenHash);
                    }

                    logger.info("Applied {} on file '{}' in workspace {}", operation, relPathStr, executionId);

                } catch (IOException e) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_WRITE_FAILED", "Failed to write file '" + relPathStr + "': " + e.getMessage());
                }
            }
        }
    }

    @Override
    public String calculateTreeDigest(Path directory) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return "";
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> allFiles = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(directory)) {
                stream.filter(Files::isRegularFile).sorted().forEach(allFiles::add);
            }

            for (Path file : allFiles) {
                Path rel = directory.relativize(file);
                digest.update(rel.toString().getBytes(StandardCharsets.UTF_8));
                try (InputStream is = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }

            return bytesToHex(digest.digest());
        } catch (Exception e) {
            logger.error("Failed to compute tree digest for {}: {}", directory, e.getMessage());
            return "";
        }
    }

    @Override
    public void cleanupWorkspace(UUID executionId) {
        Path workspaceRootDir = Paths.get(workspaceBasePath, executionId.toString());
        if (!Files.exists(workspaceRootDir)) {
            return;
        }

        try {
            Files.walkFileTree(workspaceRootDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            logger.info("Successfully cleaned up workspace directory for execution {}", executionId);
        } catch (IOException e) {
            logger.warn("Failed to delete workspace directory for execution {}: {}", executionId, e.getMessage());
        }
    }

    @Override
    public Path getWorkspaceRepoDir(UUID executionId) {
        return Paths.get(workspaceBasePath, executionId.toString(), "repo");
    }

    private void validatePathSecurity(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "File path cannot be blank");
        }
        if (pathStr.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Path contains illegal '..' directory traversal segment");
        }
        if (pathStr.startsWith("/") || pathStr.startsWith("\\") || pathStr.contains("\0")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Path must be a relative, non-NUL path");
        }
        if (pathStr.matches("^[a-zA-Z]:.*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Absolute Windows drive paths are prohibited");
        }
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((input != null ? input : "").getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
