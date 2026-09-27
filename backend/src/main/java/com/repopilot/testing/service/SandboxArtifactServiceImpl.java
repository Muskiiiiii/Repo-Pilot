package com.repopilot.testing.service;

import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.testing.entity.SandboxExecutionArtifact;
import com.repopilot.testing.entity.SandboxJobExecution;
import com.repopilot.testing.repository.SandboxExecutionArtifactRepository;
import com.repopilot.testing.repository.SandboxJobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class SandboxArtifactServiceImpl implements SandboxArtifactService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxArtifactServiceImpl.class);

    public static final long MAX_SINGLE_ARTIFACT_SIZE_BYTES = 10_000_000L; // 10 MB
    public static final long MAX_TOTAL_ARTIFACT_SIZE_BYTES = 50_000_000L; // 50 MB
    public static final int MAX_ARTIFACT_COUNT_PER_JOB = 20;
    public static final int MAX_FILENAME_LENGTH = 255;

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("gh[pousr]_[a-zA-Z0-9_]{30,90}"),
            Pattern.compile("github_pat_[a-zA-Z0-9_]{30,90}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("ASIA[0-9A-Z]{16}"),
            Pattern.compile("sk-[a-zA-Z0-9]{32,}"),
            Pattern.compile("eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+"),
            Pattern.compile("(?i)(?:api_key|apikey|secret_key|jwt_secret|password|db_password|token|access_token|private_key)\\s*[:=]\\s*[\"'][a-zA-Z0-9_\\-+=/]{8,}[\"']")
    );

    private final SandboxJobExecutionRepository jobExecutionRepository;
    private final SandboxExecutionArtifactRepository artifactRepository;
    private final PreLlmSecretScanner secretScanner;

    @Autowired
    public SandboxArtifactServiceImpl(SandboxJobExecutionRepository jobExecutionRepository,
                                      SandboxExecutionArtifactRepository artifactRepository,
                                      @Autowired(required = false) PreLlmSecretScanner secretScanner) {
        this.jobExecutionRepository = jobExecutionRepository;
        this.artifactRepository = artifactRepository;
        this.secretScanner = secretScanner;
    }

    @Override
    @Transactional
    public SandboxExecutionArtifact saveExecutionArtifact(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion,
                                                           String artifactType, String relativePath, String rawContent) {
        // 1. Validate inputs
        if (jobId == null || workerNodeId == null || leaseToken == null || fencingVersion == null) {
            throw new IllegalArgumentException("Ownership identity parameters cannot be null");
        }
        if (artifactType == null || artifactType.isBlank()) {
            throw new IllegalArgumentException("Artifact type cannot be blank");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Relative path cannot be blank");
        }
        if (relativePath.length() > MAX_FILENAME_LENGTH) {
            throw new IllegalArgumentException("Filename length exceeds maximum allowed limit of " + MAX_FILENAME_LENGTH);
        }

        // 2. Validate Path Traversal Protection
        validatePathSecurity(relativePath);

        // 3. Fencing Ownership Validation against PostgreSQL
        SandboxJobExecution job = jobExecutionRepository.findById(jobId)
                .orElseThrow(() -> new SecurityException("Job execution not found: " + jobId));

        if (job.getAssignedWorkerNode() == null || !job.getAssignedWorkerNode().getId().equals(workerNodeId)) {
            throw new SecurityException("Fenced artifact publication rejected: Worker ID mismatch for job " + jobId);
        }
        if (!leaseToken.equals(job.getLeaseToken())) {
            throw new SecurityException("Fenced artifact publication rejected: Lease token mismatch for job " + jobId);
        }
        if (!fencingVersion.equals(job.getFencingVersion())) {
            throw new SecurityException("Fenced artifact publication rejected: Stale fencing version " + fencingVersion + " (current: " + job.getFencingVersion() + ")");
        }
        if (job.getLeaseExpiresAt() == null || job.getLeaseExpiresAt().isBefore(Instant.now())) {
            throw new SecurityException("Fenced artifact publication rejected: Lease expired for job " + jobId);
        }

        // 4. Resource Bounds Check
        byte[] rawBytes = (rawContent != null) ? rawContent.getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (rawBytes.length > MAX_SINGLE_ARTIFACT_SIZE_BYTES) {
            throw new IllegalArgumentException("Artifact size (" + rawBytes.length + " bytes) exceeds maximum limit of " + MAX_SINGLE_ARTIFACT_SIZE_BYTES + " bytes");
        }

        List<SandboxExecutionArtifact> existing = artifactRepository.findByJobExecutionId(jobId);
        if (existing.size() >= MAX_ARTIFACT_COUNT_PER_JOB) {
            throw new IllegalArgumentException("Maximum artifact count (" + MAX_ARTIFACT_COUNT_PER_JOB + ") reached for job " + jobId);
        }

        long existingTotalBytes = existing.stream()
                .mapToLong(a -> (a.getRawOutput() != null) ? a.getRawOutput().getBytes(StandardCharsets.UTF_8).length : 0L)
                .sum();

        if (existingTotalBytes + rawBytes.length > MAX_TOTAL_ARTIFACT_SIZE_BYTES) {
            throw new IllegalArgumentException("Combined artifact size (" + (existingTotalBytes + rawBytes.length) + " bytes) exceeds total limit of " + MAX_TOTAL_ARTIFACT_SIZE_BYTES + " bytes");
        }

        // 5. Calculate Cryptographic Digest (SHA-256)
        String contentHash = computeSha256(rawBytes);

        // 6. Idempotency Check: Avoid duplicate artifact creation
        for (SandboxExecutionArtifact art : existing) {
            if (art.getArtifactType().equalsIgnoreCase(artifactType) && art.getContentHash().equalsIgnoreCase(contentHash)) {
                logger.info("Idempotent artifact save: artifact of type {} with hash {} already exists for job {}", artifactType, contentHash, jobId);
                return art;
            }
        }

        // 7. Secret Sanitization
        ScrubResult scrubResult = sanitizeContent(rawContent);

        // 8. Persist Artifact
        SandboxExecutionArtifact artifact = new SandboxExecutionArtifact();
        artifact.setJobExecution(job);
        artifact.setArtifactType(artifactType.toUpperCase());
        artifact.setContentHash(contentHash);
        artifact.setRawOutput(rawContent);
        artifact.setSanitizedOutput(scrubResult.sanitized);
        artifact.setSecretScrubbedCount(scrubResult.scrubbedCount);
        artifact.setCreatedAt(Instant.now());

        return artifactRepository.save(artifact);
    }

    @Override
    @Transactional
    public List<SandboxExecutionArtifact> collectAndPersistWorkspaceArtifacts(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion,
                                                                                Path workspaceDir, int maxArtifacts, long maxTotalSizeBytes) {
        if (workspaceDir == null || !Files.exists(workspaceDir)) {
            return List.of();
        }

        List<SandboxExecutionArtifact> collected = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(workspaceDir)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .limit(maxArtifacts)
                    .toList();

            for (Path file : files) {
                Path relative = workspaceDir.relativize(file);
                String relPathStr = relative.toString().replace('\\', '/');

                // Read bounded content
                long size = Files.size(file);
                if (size > MAX_SINGLE_ARTIFACT_SIZE_BYTES) {
                    logger.warn("Skipping artifact file {} because size {} exceeds single limit", relPathStr, size);
                    continue;
                }

                String content = Files.readString(file, StandardCharsets.UTF_8);
                SandboxExecutionArtifact art = saveExecutionArtifact(
                        jobId, workerNodeId, leaseToken, fencingVersion,
                        "FILE_ARTIFACT", relPathStr, content
                );
                collected.add(art);
            }
        } catch (IOException e) {
            logger.error("Failed to collect workspace artifacts from {}: {}", workspaceDir, e.getMessage(), e);
        }

        return collected;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SandboxExecutionArtifact> getArtifactsForJob(UUID jobId) {
        if (jobId == null) return List.of();
        return artifactRepository.findByJobExecutionId(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyArtifactChecksum(UUID artifactId, String expectedSha256) {
        if (artifactId == null || expectedSha256 == null || expectedSha256.isBlank()) {
            return false;
        }

        SandboxExecutionArtifact artifact = artifactRepository.findById(artifactId).orElse(null);
        if (artifact == null) return false;

        String raw = artifact.getRawOutput();
        byte[] bytes = (raw != null) ? raw.getBytes(StandardCharsets.UTF_8) : new byte[0];
        String computed = computeSha256(bytes);

        return computed.equalsIgnoreCase(expectedSha256.trim());
    }

    private void validatePathSecurity(String relativePath) {
        if (relativePath.contains("..") || relativePath.contains("\\") || relativePath.startsWith("/")) {
            throw new SecurityException("Path traversal detected in artifact path: " + relativePath);
        }

        Path baseRoot = Path.of("/tmp", "repopilot-workspaces").toAbsolutePath().normalize();
        Path resolved = baseRoot.resolve(relativePath).toAbsolutePath().normalize();

        if (!resolved.startsWith(baseRoot)) {
            throw new SecurityException("Artifact path traversal escapes workspace root: " + relativePath);
        }
    }

    private String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    private record ScrubResult(String sanitized, int scrubbedCount) {}

    private ScrubResult sanitizeContent(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) {
            return new ScrubResult("", 0);
        }

        if (secretScanner != null) {
            String sanitized = secretScanner.redactSecrets(rawContent);
            int count = secretScanner.containsSecret(rawContent) ? 1 : 0;
            return new ScrubResult(sanitized, count);
        }

        String result = rawContent;
        int count = 0;
        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher m = pattern.matcher(result);
            while (m.find()) {
                count++;
            }
            result = m.replaceAll("[REDACTED_SECRET]");
        }

        return new ScrubResult(result, count);
    }
}
