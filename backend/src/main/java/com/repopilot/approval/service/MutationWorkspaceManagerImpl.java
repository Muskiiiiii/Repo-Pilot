package com.repopilot.approval.service;

import com.repopilot.approval.dto.MutationWorkspaceResult;
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
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Service
public class MutationWorkspaceManagerImpl implements MutationWorkspaceManager {

    private static final Logger logger = LoggerFactory.getLogger(MutationWorkspaceManagerImpl.class);

    public static final long MAX_FILES = 50_000L;
    public static final long MAX_TOTAL_BYTES = 1_000_000_000L; // 1 GB
    public static final long MAX_FILE_BYTES = 100_000_000L;    // 100 MB
    public static final int MAX_DIRECTORY_DEPTH = 50;

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    @Value("${repopilot.mutation.workspace-path:/tmp/repopilot/mutation-workspaces}")
    private String workspaceBasePath;

    public MutationWorkspaceManagerImpl() {
    }

    public MutationWorkspaceManagerImpl(String baseStoragePath, String workspaceBasePath) {
        this.baseStoragePath = baseStoragePath;
        this.workspaceBasePath = workspaceBasePath;
    }

    @Override
    public MutationWorkspaceResult createMutationWorkspace(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            Repository repository,
            UUID taskId
    ) {
        if (approvalId == null || operationId == null || repository == null || repository.getId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE_REQUEST", "Approval, operation, and repository IDs are required");
        }

        Path masterRepoDir = Paths.get(baseStoragePath, repository.getId().toString()).toAbsolutePath().normalize();
        if (!Files.exists(masterRepoDir) || !Files.isDirectory(masterRepoDir)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BASELINE_NOT_FOUND", "Master repository content not found on disk: " + masterRepoDir);
        }

        Path workspaceBaseDir = Paths.get(workspaceBasePath).toAbsolutePath().normalize();
        String workspaceFolderName = "mutation-" + approvalId + "-" + operationId;
        Path workspaceDir = workspaceBaseDir.resolve(workspaceFolderName).toAbsolutePath().normalize();
        Path workspaceRepoDir = workspaceDir.resolve("repo").toAbsolutePath().normalize();

        // 1. Path Containment Shield
        validateContainment(workspaceBaseDir, workspaceDir);
        validateContainment(workspaceDir, workspaceRepoDir);

        try {
            // 2. Compute Master Baseline Digest (Before)
            String baselineTreeHashBefore = calculateTreeDigest(masterRepoDir);

            // Clean up any stale workspace directory for this operation
            cleanupMutationWorkspace(operationId);

            Files.createDirectories(workspaceRepoDir);

            // 3. Physical Independent Copy & Resource Counting (NO HARDLINKS)
            long[] fileMetrics = copyDirectoryRecursively(masterRepoDir, workspaceRepoDir);
            long fileCount = fileMetrics[0];
            long totalByteSize = fileMetrics[1];

            // 4. Create Lock Marker File
            Path lockFile = workspaceDir.resolve(".execution.lock");
            String lockContent = String.format(
                    "operationId=%s\napprovalId=%s\nprojectId=%s\nrepositoryId=%s\ntaskId=%s\ncreated_at=%s\nbaselineTreeHash=%s\n",
                    operationId, approvalId, projectId, repository.getId(), taskId, Instant.now(), baselineTreeHashBefore
            );
            Files.writeString(lockFile, lockContent, StandardCharsets.UTF_8);

            // 5. Initial Workspace Digest Check
            String workspaceInitialTreeHash = calculateTreeDigest(workspaceRepoDir);
            if (!baselineTreeHashBefore.equals(workspaceInitialTreeHash)) {
                cleanupMutationWorkspace(operationId);
                throw new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "WORKSPACE_INTEGRITY_FAILURE",
                        "Initial workspace tree digest mismatch against baseline: baseline=" + baselineTreeHashBefore + ", workspace=" + workspaceInitialTreeHash
                );
            }

            // 6. Verify Master Baseline Immutability (After)
            verifyMasterImmutability(masterRepoDir, baselineTreeHashBefore);

            logger.info("Successfully created mutation workspace operationId={} at {}", operationId, workspaceDir);

            return new MutationWorkspaceResult(
                    operationId,
                    approvalId,
                    projectId,
                    repository.getId(),
                    taskId,
                    workspaceDir.toString(),
                    workspaceRepoDir.toString(),
                    baselineTreeHashBefore,
                    workspaceInitialTreeHash,
                    fileCount,
                    totalByteSize,
                    Instant.now()
            );

        } catch (ApiException e) {
            cleanupMutationWorkspace(operationId);
            throw e;
        } catch (Exception e) {
            logger.error("Failed to create mutation workspace operationId={}: {}", operationId, e.getMessage(), e);
            cleanupMutationWorkspace(operationId);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_CREATION_FAILED", "Failed to create mutation workspace: " + e.getMessage());
        }
    }

    @Override
    public String calculateTreeDigest(Path directoryPath) {
        if (directoryPath == null || !Files.exists(directoryPath)) {
            return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // Empty hash
        }

        Path normalizedRoot = directoryPath.toAbsolutePath().normalize();
        List<TreeEntry> entries = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(normalizedRoot, MAX_DIRECTORY_DEPTH)) {
            stream.forEach(path -> {
                if (path.equals(normalizedRoot)) return;
                try {
                    String relativePath = normalizedRoot.relativize(path).toString().replace("\\", "/");
                    relativePath = Normalizer.normalize(relativePath, Normalizer.Form.NFC);

                    // Skip internal lock files, dangerous git metadata, or external symlink escapes
                    if (relativePath.equalsIgnoreCase(".execution.lock")) return;
                    if (isDangerousGitMetadata(path)) return;

                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isSymbolicLink()) {
                        Path linkTarget = Files.readSymbolicLink(path);
                        Path resolvedTarget = path.getParent().resolve(linkTarget).normalize();
                        if (!resolvedTarget.startsWith(normalizedRoot)) {
                            throw new ApiException(
                                    HttpStatus.BAD_REQUEST,
                                    "UNSAFE_SYMLINK_DETECTED",
                                    "Unsafe external symbolic link detected pointing outside root boundary: " + relativePath + " -> " + linkTarget
                            );
                        }
                    }

                    String entryType = attrs.isDirectory() ? "DIR" : attrs.isSymbolicLink() ? "LINK" : "FILE";
                    long size = attrs.isRegularFile() ? attrs.size() : 0L;

                    String fileHash = "";
                    if (attrs.isRegularFile()) {
                        fileHash = hashFileContent(path);
                    } else if (attrs.isSymbolicLink()) {
                        fileHash = hashString(Files.readSymbolicLink(path).toString());
                    }

                    entries.add(new TreeEntry(relativePath, entryType, size, fileHash));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to digest file: " + path, e);
                }
            });
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TREE_HASH_FAILED", "Failed to walk directory for tree hashing: " + e.getMessage());
        }

        entries.sort(Comparator.comparing(TreeEntry::relativePath));

        StringBuilder canonicalTree = new StringBuilder();
        for (TreeEntry entry : entries) {
            canonicalTree.append(entry.relativePath())
                    .append(":")
                    .append(entry.type())
                    .append(":")
                    .append(entry.size())
                    .append(":")
                    .append(entry.contentHash())
                    .append("\n");
        }

        return hashString(canonicalTree.toString());
    }

    @Override
    public void verifyMasterImmutability(Path masterPath, String initialDigest) {
        if (masterPath == null || initialDigest == null) return;
        String currentDigest = calculateTreeDigest(masterPath);
        if (!initialDigest.equals(currentDigest)) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "MASTER_IMMUTABILITY_VIOLATION",
                    "Master baseline repository was mutated! Before=" + initialDigest + ", After=" + currentDigest
            );
        }
    }

    @Override
    public void cleanupMutationWorkspace(UUID operationId) {
        if (operationId == null) return;
        Path workspaceBaseDir = Paths.get(workspaceBasePath).toAbsolutePath().normalize();

        try {
            if (!Files.exists(workspaceBaseDir)) return;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceBaseDir, "mutation-*-" + operationId)) {
                for (Path target : stream) {
                    Path normalizedTarget = target.toAbsolutePath().normalize();
                    if (normalizedTarget.startsWith(workspaceBaseDir) && !normalizedTarget.equals(workspaceBaseDir)) {
                        deleteDirectoryRecursively(normalizedTarget);
                        logger.info("Cleaned up mutation workspace at {}", normalizedTarget);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error during workspace cleanup for operationId={}: {}", operationId, e.getMessage());
        }
    }

    @Override
    public void sweepOrphanWorkspaces(long maxAgeMinutes) {
        Path workspaceBaseDir = Paths.get(workspaceBasePath).toAbsolutePath().normalize();
        if (!Files.exists(workspaceBaseDir)) return;

        Instant threshold = Instant.now().minus(Duration.ofMinutes(maxAgeMinutes));

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceBaseDir, "mutation-*")) {
            for (Path target : stream) {
                Path normalizedTarget = target.toAbsolutePath().normalize();
                if (!normalizedTarget.startsWith(workspaceBaseDir) || normalizedTarget.equals(workspaceBaseDir)) {
                    continue;
                }

                Path lockFile = normalizedTarget.resolve(".execution.lock");
                boolean shouldSweep = false;

                if (Files.exists(lockFile)) {
                    try {
                        List<String> lines = Files.readAllLines(lockFile, StandardCharsets.UTF_8);
                        boolean foundCreatedAt = false;
                        for (String line : lines) {
                            if (line.startsWith("created_at=")) {
                                foundCreatedAt = true;
                                Instant createdAt = Instant.parse(line.substring("created_at=".length()).trim());
                                if (createdAt.isBefore(threshold)) {
                                    shouldSweep = true;
                                }
                                break;
                            }
                        }
                        if (!foundCreatedAt) {
                            shouldSweep = true; // Lock file missing created_at -> sweep
                        }
                    } catch (Exception e) {
                        shouldSweep = true; // Malformed lock file -> safe orphan sweep
                    }
                } else {
                    // No lock file and older directory -> orphan
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(normalizedTarget, BasicFileAttributes.class);
                        if (attrs.creationTime().toInstant().isBefore(threshold)) {
                            shouldSweep = true;
                        }
                    } catch (Exception e) {
                        shouldSweep = true;
                    }
                }

                if (shouldSweep) {
                    deleteDirectoryRecursively(normalizedTarget);
                    logger.info("Swept orphan mutation workspace at {}", normalizedTarget);
                }
            }
        } catch (Exception e) {
            logger.warn("Error sweeping orphan workspaces: {}", e.getMessage());
        }
    }

    @Override
    public Path getMutationWorkspacePath(UUID operationId) {
        if (operationId == null) return null;
        Path workspaceBaseDir = Paths.get(workspaceBasePath).toAbsolutePath().normalize();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceBaseDir, "mutation-*-" + operationId)) {
            for (Path target : stream) {
                return target.resolve("repo").toAbsolutePath().normalize();
            }
        } catch (Exception e) {
            logger.warn("Failed to locate workspace path for operationId={}", operationId);
        }
        return null;
    }

    private long[] copyDirectoryRecursively(Path sourceDir, Path targetDir) throws IOException {
        long[] fileCount = {0L};
        long[] totalBytes = {0L};

        Files.walkFileTree(sourceDir, EnumSet.noneOf(FileVisitOption.class), MAX_DIRECTORY_DEPTH, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceDir.relativize(dir);
                Path destination = targetDir.resolve(relative).toAbsolutePath().normalize();

                // Path Traversal Security Check
                if (!destination.startsWith(targetDir)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "Directory path resolves outside target workspace: " + relative);
                }

                // Filter out dangerous .git folders/hooks
                if (isDangerousGitMetadata(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (!Files.exists(destination)) {
                    Files.createDirectories(destination);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceDir.relativize(file);
                Path destination = targetDir.resolve(relative).toAbsolutePath().normalize();

                // 1. Path Traversal & Containment Security Check
                if (!destination.startsWith(targetDir)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "File path resolves outside target workspace: " + relative);
                }

                // 2. Filter out sensitive .git files (credentials, hooks)
                if (isDangerousGitMetadata(relative)) {
                    return FileVisitResult.CONTINUE;
                }

                BasicFileAttributes fileAttrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

                // 3. Symlink Escape Check
                if (fileAttrs.isSymbolicLink()) {
                    Path linkTarget = Files.readSymbolicLink(file);
                    Path resolvedTarget = file.getParent().resolve(linkTarget).normalize();
                    if (!resolvedTarget.startsWith(sourceDir)) {
                        throw new ApiException(
                                HttpStatus.BAD_REQUEST,
                                "UNSAFE_SYMLINK_DETECTED",
                                "Unsafe external symbolic link detected pointing outside baseline repository: " + relative + " -> " + linkTarget
                        );
                    }
                }

                // 4. Resource Limits Check
                fileCount[0]++;
                totalBytes[0] += fileAttrs.size();

                if (fileCount[0] > MAX_FILES) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "RESOURCE_LIMIT_EXCEEDED", "Workspace file count exceeded max limit of " + MAX_FILES);
                }
                if (fileAttrs.size() > MAX_FILE_BYTES) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "RESOURCE_LIMIT_EXCEEDED", "Individual file size " + fileAttrs.size() + " exceeds max limit of " + MAX_FILE_BYTES);
                }
                if (totalBytes[0] > MAX_TOTAL_BYTES) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "RESOURCE_LIMIT_EXCEEDED", "Total workspace size " + totalBytes[0] + " exceeds max limit of " + MAX_TOTAL_BYTES);
                }

                // 5. Deep Physical Copy (NO HARDLINKS, NOFOLLOW_LINKS)
                if (fileAttrs.isSymbolicLink()) {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                } else {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return new long[]{fileCount[0], totalBytes[0]};
    }

    private boolean isDangerousGitMetadata(Path relativePath) {
        String pathStr = relativePath.toString().replace("\\", "/");
        return pathStr.contains(".git/credentials") ||
               pathStr.contains(".git/hooks") ||
               pathStr.contains(".git/config") ||
               pathStr.contains(".git/objects/info/alternates") ||
               pathStr.contains(".git/worktrees") ||
               pathStr.startsWith(".git/hooks");
    }

    private void deleteDirectoryRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;

        // If dir itself is a symbolic link, delete the link without traversing into target!
        if (Files.isSymbolicLink(dir)) {
            Files.delete(dir);
            return;
        }

        Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), MAX_DIRECTORY_DEPTH, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void validateContainment(Path rootPath, Path childPath) {
        if (rootPath == null || childPath == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Path parameters cannot be null");
        }
        Path normRoot = rootPath.toAbsolutePath().normalize();
        Path normChild = childPath.toAbsolutePath().normalize();
        if (!normChild.startsWith(normRoot) || normChild.equals(normRoot)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "Child path escape detected outside root boundary");
        }
    }

    private String hashFileContent(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash file content: " + file, e);
        }
    }

    private String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    private record TreeEntry(String relativePath, String type, long size, String contentHash) {}
}
