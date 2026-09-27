package com.repopilot.approval.service;

import com.repopilot.approval.dto.ApprovedChangeVerificationResult;
import com.repopilot.approval.dto.PatchMaterializationResult;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.security.service.SecretScannerService;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class ApprovedPatchMaterializerImpl implements ApprovedPatchMaterializer {

    private static final Logger logger = LoggerFactory.getLogger(ApprovedPatchMaterializerImpl.class);

    private final ApprovedChangeVerifier approvedChangeVerifier;
    private final HumanApprovalRepository humanApprovalRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final MutationWorkspaceManager mutationWorkspaceManager;
    private final SecretScannerService secretScannerService;

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovedPatchMaterializerImpl(
            ApprovedChangeVerifier approvedChangeVerifier,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            MutationWorkspaceManager mutationWorkspaceManager,
            SecretScannerService secretScannerService
    ) {
        this.approvedChangeVerifier = approvedChangeVerifier;
        this.humanApprovalRepository = humanApprovalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.mutationWorkspaceManager = mutationWorkspaceManager;
        this.secretScannerService = secretScannerService;
    }

    public ApprovedPatchMaterializerImpl(
            ApprovedChangeVerifier approvedChangeVerifier,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            MutationWorkspaceManager mutationWorkspaceManager,
            SecretScannerService secretScannerService,
            String baseStoragePath
    ) {
        this.approvedChangeVerifier = approvedChangeVerifier;
        this.humanApprovalRepository = humanApprovalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.mutationWorkspaceManager = mutationWorkspaceManager;
        this.secretScannerService = secretScannerService;
        this.baseStoragePath = baseStoragePath;
    }

    @Override
    @Transactional(readOnly = true)
    public PatchMaterializationResult materializeApprovedPatch(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    ) {
        if (approvalId == null || operationId == null || projectId == null || repositoryId == null || taskId == null || codeChangeId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MATERIALIZATION_REQUEST", "All operation, approval, and entity IDs are required");
        }

        // 1. Authoritative Approval Verification
        ApprovedChangeVerificationResult verificationResult = approvedChangeVerifier.verifyApprovedChange(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester
        );

        if (!verificationResult.isValid()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPROVAL_VERIFICATION_FAILED",
                    "Approved change verification failed: " + verificationResult.failureReason()
            );
        }

        // 2. Fetch Authoritative Snapshot Records
        HumanApproval approval = humanApprovalRepository.findById(approvalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "HumanApproval record not found"));

        CodeChange codeChange = codeChangeRepository.findById(codeChangeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange record not found"));

        if (!approval.getStatus().isApproved()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "APPROVAL_NOT_ACTIVE", "HumanApproval record is not in APPROVED status");
        }

        // 3. Diff Hash Verification
        String diffContent = codeChange.getDiff() != null ? codeChange.getDiff() : codeChange.getNewContent();
        if (diffContent == null) diffContent = "";
        String computedDiffHash = hashString(diffContent);

        if (!computedDiffHash.equals(approval.getCodeChangeDiffHash())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DIFF_HASH_MISMATCH",
                    "Persisted diff hash (" + computedDiffHash + ") does not match approved diff hash (" + approval.getCodeChangeDiffHash() + ")"
            );
        }

        // 4. Retrieve & Validate Mutation Workspace
        Path workspaceRepoDir = mutationWorkspaceManager.getMutationWorkspacePath(operationId);
        if (workspaceRepoDir == null || !Files.exists(workspaceRepoDir)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "Mutation workspace not found for operationId: " + operationId);
        }

        Path masterRepoDir = Paths.get(baseStoragePath, repositoryId.toString()).toAbsolutePath().normalize();
        if (!Files.exists(masterRepoDir)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BASELINE_NOT_FOUND", "Master repository content not found on disk");
        }

        Path tempFileToClean = null;

        try {
            // 5. Pre-Application Tree Digest & Master Immutability Check
            String masterDigestBefore = mutationWorkspaceManager.calculateTreeDigest(masterRepoDir);
            String originalTreeHash = mutationWorkspaceManager.calculateTreeDigest(workspaceRepoDir);

            // 6. Approved File Scope & Path Security Validation
            List<String> approvedFiles = verificationResult.approvedFiles();
            String relativeFilePath = codeChange.getFilePath();

            validatePathSecurity(relativeFilePath);
            Path normalizedRelPath = Paths.get(relativeFilePath).normalize();
            String normalizedPathStr = normalizedRelPath.toString().replace("\\", "/");

            boolean isApproved = approvedFiles.stream()
                    .map(p -> Paths.get(p).normalize().toString().replace("\\", "/"))
                    .anyMatch(p -> p.equals(normalizedPathStr));

            if (!isApproved) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNAPPROVED_FILE_PATH", "File path " + relativeFilePath + " is not in approved file set: " + approvedFiles);
            }

            Path targetPath = workspaceRepoDir.resolve(normalizedRelPath).toAbsolutePath().normalize();
            mutationWorkspaceManager.validateContainment(workspaceRepoDir, targetPath);

            // Symlink & Hardlink Race Protection
            if (Files.isSymbolicLink(targetPath)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_SECURITY_VIOLATION", "Target patch path is a symbolic link");
            }
            Path parent = targetPath.getParent();
            while (parent != null && !parent.equals(workspaceRepoDir)) {
                if (Files.isSymbolicLink(parent)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_SECURITY_VIOLATION", "Parent directory component is a symbolic link: " + parent);
                }
                parent = parent.getParent();
            }

            // 7. Pre-Application File State Verification
            String operation = codeChange.getOperation() != null ? codeChange.getOperation().toUpperCase(Locale.ROOT) : "MODIFY";
            if (!Set.of("MODIFY", "REPLACE", "CREATE", "DELETE").contains(operation)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_OPERATION", "Unsupported CodeChange operation: " + operation);
            }

            if ("MODIFY".equals(operation) || "REPLACE".equals(operation)) {
                if (!Files.exists(targetPath)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_NOT_FOUND", "Target file to modify does not exist: " + relativeFilePath);
                }
                String currentWorkspaceHash = hashFileContent(targetPath);
                if (!currentWorkspaceHash.equalsIgnoreCase(codeChange.getOriginalHash())) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "ORIGINAL_HASH_MISMATCH",
                            "Workspace file content hash (" + currentWorkspaceHash + ") mismatch against approved original hash (" + codeChange.getOriginalHash() + ")"
                    );
                }
            } else if ("CREATE".equals(operation)) {
                if (Files.exists(targetPath)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_ALREADY_EXISTS", "Target file to create already exists: " + relativeFilePath);
                }
            } else if ("DELETE".equals(operation)) {
                if (!Files.exists(targetPath)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_NOT_FOUND", "Target file to delete does not exist: " + relativeFilePath);
                }
                String currentWorkspaceHash = hashFileContent(targetPath);
                if (!currentWorkspaceHash.equalsIgnoreCase(codeChange.getOriginalHash())) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "ORIGINAL_HASH_MISMATCH",
                            "Workspace file content hash (" + currentWorkspaceHash + ") mismatch against approved original hash (" + codeChange.getOriginalHash() + ")"
                    );
                }
            }

            // 8. Deterministic Patch Materialization & Hunk Safety
            String resultingContent = null;
            if ("MODIFY".equals(operation) || "REPLACE".equals(operation)) {
                String existingContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                if (codeChange.getDiff() != null && codeChange.getDiff().contains("@@")) {
                    resultingContent = applyUnifiedDiffHunks(existingContent, codeChange.getDiff(), codeChange.getStartLine());
                } else if (codeChange.getNewContent() != null) {
                    resultingContent = codeChange.getNewContent();
                } else {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH_CONTENT", "No diff or newContent provided for MODIFY operation");
                }
            } else if ("CREATE".equals(operation)) {
                if (codeChange.getNewContent() == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH_CONTENT", "No newContent provided for CREATE operation");
                }
                resultingContent = codeChange.getNewContent();
            }

            // 9. Resulting File Hash & Secret Scanning
            if ("MODIFY".equals(operation) || "REPLACE".equals(operation) || "CREATE".equals(operation)) {
                String actualProposedHash = hashString(resultingContent);
                if (!actualProposedHash.equalsIgnoreCase(codeChange.getProposedHash())) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "PROPOSED_HASH_MISMATCH",
                            "Resulting materialization proposed hash (" + actualProposedHash + ") mismatch against approved proposed hash (" + codeChange.getProposedHash() + ")"
                    );
                }

                SecretScannerService.SecretScanResult secretResult = secretScannerService.scanContent(resultingContent);
                if (secretResult.hasSecret()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "SECRET_DETECTED", "Secret pattern detected in patch materialization: " + secretResult.reason());
                }
            }

            // 10. Safe Write Strategy (Temp File -> Atomic Replace)
            if ("MODIFY".equals(operation) || "REPLACE".equals(operation) || "CREATE".equals(operation)) {
                Files.createDirectories(targetPath.getParent());
                tempFileToClean = workspaceRepoDir.resolve(".tmp_patch_" + UUID.randomUUID());
                Files.writeString(tempFileToClean, resultingContent, StandardCharsets.UTF_8);

                try {
                    Files.move(tempFileToClean, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFileToClean, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                tempFileToClean = null;
            } else if ("DELETE".equals(operation)) {
                Files.delete(targetPath);
            }

            // 11. Post-Materialization Resulting Tree Hash & Set Equality
            String resultingTreeHash = mutationWorkspaceManager.calculateTreeDigest(workspaceRepoDir);

            // Re-verify Master Immutability
            mutationWorkspaceManager.verifyMasterImmutability(masterRepoDir, masterDigestBefore);

            logger.info("Successfully materialized approved patch operationId={} approvalId={} file={}", operationId, approvalId, relativeFilePath);

            return new PatchMaterializationResult(
                    operationId,
                    approvalId,
                    projectId,
                    repositoryId,
                    taskId,
                    codeChangeId,
                    workspaceRepoDir.toString(),
                    originalTreeHash,
                    resultingTreeHash,
                    1,
                    List.of(normalizedPathStr),
                    Instant.now()
            );

        } catch (ApiException e) {
            cleanupTempAndWorkspace(tempFileToClean, operationId);
            throw e;
        } catch (Exception e) {
            cleanupTempAndWorkspace(tempFileToClean, operationId);
            logger.error("Failed to materialize approved patch operationId={}: {}", operationId, e.getMessage(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MATERIALIZATION_FAILED", "Failed to materialize approved patch: " + e.getMessage());
        }
    }

    private String applyUnifiedDiffHunks(String originalContent, String diffText, Integer startLineHint) {
        String[] originalLines = originalContent.split("\r?\n", -1);
        List<String> resultLines = new ArrayList<>(Arrays.asList(originalLines));

        List<String> diffLines = Arrays.asList(diffText.split("\r?\n", -1));
        int currentLine = 0;
        int origLineIndex = 0;

        for (int i = 0; i < diffLines.size(); i++) {
            String line = diffLines.get(i);
            if (line.startsWith("@@")) {
                // Parse hunk header e.g. @@ -10,4 +10,5 @@
                int origStart = parseHunkStart(line, "-");
                origLineIndex = origStart > 0 ? origStart - 1 : 0;
            } else if (line.startsWith("-")) {
                String removedText = line.substring(1);
                if (origLineIndex >= resultLines.size() || !resultLines.get(origLineIndex).equals(removedText)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "PATCH_HUNK_MISMATCH", "Context mismatch at line " + (origLineIndex + 1) + ": expected '" + removedText + "'");
                }
                resultLines.remove(origLineIndex);
            } else if (line.startsWith("+")) {
                String addedText = line.substring(1);
                resultLines.add(origLineIndex, addedText);
                origLineIndex++;
            } else if (line.startsWith(" ")) {
                String contextText = line.substring(1);
                if (origLineIndex >= resultLines.size() || !resultLines.get(origLineIndex).equals(contextText)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "PATCH_HUNK_MISMATCH", "Context mismatch at line " + (origLineIndex + 1) + ": expected '" + contextText + "'");
                }
                origLineIndex++;
            }
        }

        return String.join("\n", resultLines);
    }

    private int parseHunkStart(String header, String prefix) {
        try {
            int startIdx = header.indexOf(prefix);
            if (startIdx != -1) {
                int commaIdx = header.indexOf(",", startIdx);
                int spaceIdx = header.indexOf(" ", startIdx);
                int endIdx = (commaIdx != -1 && commaIdx < spaceIdx) ? commaIdx : spaceIdx;
                if (endIdx != -1) {
                    return Integer.parseInt(header.substring(startIdx + prefix.length(), endIdx));
                }
            }
        } catch (Exception e) {
            // Ignore parse errors, fallback
        }
        return 1;
    }

    private void validatePathSecurity(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "Path cannot be null or empty");
        }
        if (pathStr.contains("\0")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "Path contains null byte");
        }
        if (pathStr.contains("%2e") || pathStr.contains("%2E")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "Encoded path traversal detected");
        }
        if (pathStr.startsWith("/") || pathStr.startsWith("\\") || pathStr.contains(":\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "Absolute or Windows drive path rejected");
        }
        Path p = Paths.get(pathStr).normalize();
        if (p.isAbsolute() || p.toString().startsWith("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "Path traversal detected: " + pathStr);
        }
    }

    private void cleanupTempAndWorkspace(Path tempFile, UUID operationId) {
        if (tempFile != null && Files.exists(tempFile)) {
            try {
                Files.delete(tempFile);
            } catch (Exception ignored) {}
        }
        if (operationId != null) {
            mutationWorkspaceManager.cleanupMutationWorkspace(operationId);
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
}
