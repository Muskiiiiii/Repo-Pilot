package com.repopilot.approval.service;

import com.repopilot.approval.dto.ApprovedChangeVerificationResult;
import com.repopilot.approval.dto.MutationIntegrityResult;
import com.repopilot.approval.dto.MutationIntegrityStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.security.service.SecretScannerService;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MutationIntegrityVerifierImpl implements MutationIntegrityVerifier {

    private static final Logger logger = LoggerFactory.getLogger(MutationIntegrityVerifierImpl.class);

    private final ApprovedChangeVerifier approvedChangeVerifier;
    private final HumanApprovalRepository humanApprovalRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final MutationWorkspaceManager mutationWorkspaceManager;
    private final SecretScannerService secretScannerService;

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    @Value("${repopilot.mutation.workspace-path:/tmp/repopilot/mutation-workspaces}")
    private String workspaceBasePath;

    @Autowired
    public MutationIntegrityVerifierImpl(
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

    public MutationIntegrityVerifierImpl(
            ApprovedChangeVerifier approvedChangeVerifier,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            MutationWorkspaceManager mutationWorkspaceManager,
            SecretScannerService secretScannerService,
            String baseStoragePath,
            String workspaceBasePath
    ) {
        this.approvedChangeVerifier = approvedChangeVerifier;
        this.humanApprovalRepository = humanApprovalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.mutationWorkspaceManager = mutationWorkspaceManager;
        this.secretScannerService = secretScannerService;
        this.baseStoragePath = baseStoragePath;
        this.workspaceBasePath = workspaceBasePath;
    }

    @Override
    @Transactional(readOnly = true)
    public MutationIntegrityResult verifyMutationIntegrity(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    ) {
        if (approvalId == null || operationId == null || projectId == null || repositoryId == null || taskId == null || codeChangeId == null) {
            return MutationIntegrityResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    MutationIntegrityStatus.PATH_VIOLATION, "All operation and entity IDs are required"
            );
        }

        try {
            // 1. Authoritative Approval Re-Verification
            ApprovedChangeVerificationResult verificationResult = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repositoryId, taskId, codeChangeId, approvalId, requester
            );

            if (!verificationResult.isValid()) {
                return MutationIntegrityResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        MutationIntegrityStatus.APPROVAL_STALE, "Approval re-verification failed: " + verificationResult.failureReason()
                );
            }

            // 2. Fetch Database Records & Diff Hash Verification
            HumanApproval approval = humanApprovalRepository.findById(approvalId).orElse(null);
            CodeChange codeChange = codeChangeRepository.findById(codeChangeId).orElse(null);

            if (approval == null || codeChange == null) {
                return MutationIntegrityResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        MutationIntegrityStatus.SNAPSHOT_MISMATCH, "HumanApproval or CodeChange record not found in database"
                );
            }

            if (!approval.getStatus().isApproved()) {
                return MutationIntegrityResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        MutationIntegrityStatus.APPROVAL_STALE, "HumanApproval is not in APPROVED status"
                );
            }

            String diffContent = codeChange.getDiff() != null ? codeChange.getDiff() : codeChange.getNewContent();
            if (diffContent == null) diffContent = "";
            String computedDiffHash = hashString(diffContent);

            if (!computedDiffHash.equalsIgnoreCase(approval.getCodeChangeDiffHash())) {
                return MutationIntegrityResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        MutationIntegrityStatus.SNAPSHOT_MISMATCH, "Persisted diff hash mismatch against approved diff hash"
                );
            }

            // 3. Workspace Retrieval & Boundary Containment
            Path workspaceRepoDir = mutationWorkspaceManager.getMutationWorkspacePath(operationId);
            if (workspaceRepoDir == null || !Files.exists(workspaceRepoDir)) {
                return MutationIntegrityResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        MutationIntegrityStatus.PATH_VIOLATION, "Mutation workspace repository path not found on disk"
                );
            }

            Path workspaceBaseDir = Paths.get(workspaceBasePath).toAbsolutePath().normalize();
            mutationWorkspaceManager.validateContainment(workspaceBaseDir, workspaceRepoDir.getParent());

            Path masterRepoDir = Paths.get(baseStoragePath, repositoryId.toString()).toAbsolutePath().normalize();
            if (!Files.exists(masterRepoDir)) {
                return MutationIntegrityResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        MutationIntegrityStatus.BASELINE_MUTATED, "Master repository baseline content not found on disk"
                );
            }

            // 4. Master Baseline Immutability Check against recorded creation baseline
            String masterDigestNow = mutationWorkspaceManager.calculateTreeDigest(masterRepoDir);
            Path lockFile = workspaceRepoDir.getParent().resolve(".execution.lock");
            if (Files.exists(lockFile)) {
                try {
                    List<String> lines = Files.readAllLines(lockFile, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        if (line.startsWith("baselineTreeHash=")) {
                            String recordedBaselineHash = line.substring("baselineTreeHash=".length()).trim();
                            if (!masterDigestNow.equalsIgnoreCase(recordedBaselineHash)) {
                                return MutationIntegrityResult.failure(
                                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                        MutationIntegrityStatus.BASELINE_MUTATED, "Master repository baseline content was mutated since workspace creation"
                                );
                            }
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 5. Scan Workspace for Unexpected Temp Files, Symlink Escapes, Special Files, & Unapproved Symlinks
            List<String> approvedList = verificationResult.approvedFiles();
            Set<String> approvedChangedPaths = new HashSet<>();
            for (String p : approvedList) {
                String normP = Normalizer.normalize(Paths.get(p).normalize().toString().replace("\\", "/"), Normalizer.Form.NFC);
                if (!approvedChangedPaths.add(normP)) {
                    return MutationIntegrityResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            MutationIntegrityStatus.SNAPSHOT_MISMATCH, "Duplicate approved file path detected in approval snapshot: " + p
                    );
                }
            }

            MutationIntegrityResult artifactCheck = scanUnexpectedArtifactsAndSymlinks(
                    workspaceRepoDir, masterRepoDir, approvedChangedPaths, operationId, approvalId, projectId, repositoryId, taskId, codeChangeId
            );
            if (artifactCheck != null) return artifactCheck;

            // 6. Compute Master vs Workspace Entry Hashes & Compute ACTUAL_CHANGED_PATHS
            Map<String, String> masterFiles = collectFileHashes(masterRepoDir);
            Map<String, String> workspaceFiles = collectFileHashes(workspaceRepoDir);

            // Hardlink Inspection: Ensure no workspace file is hardlinked to master repo
            for (String wsRelPath : workspaceFiles.keySet()) {
                Path wsFile = workspaceRepoDir.resolve(wsRelPath).toAbsolutePath().normalize();
                Path masterFile = masterRepoDir.resolve(wsRelPath).toAbsolutePath().normalize();
                if (Files.exists(masterFile) && Files.exists(wsFile)) {
                    if (Files.isSameFile(wsFile, masterFile)) {
                        return MutationIntegrityResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                MutationIntegrityStatus.PATH_VIOLATION, "Hardlink to master repository detected in workspace: " + wsRelPath
                        );
                    }
                    try {
                        Object wsKey = Files.getAttribute(wsFile, "unix:ino", LinkOption.NOFOLLOW_LINKS);
                        Object masterKey = Files.getAttribute(masterFile, "unix:ino", LinkOption.NOFOLLOW_LINKS);
                        if (wsKey != null && wsKey.equals(masterKey)) {
                            return MutationIntegrityResult.failure(
                                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                    MutationIntegrityStatus.PATH_VIOLATION, "Hardlink to master repository inode detected in workspace: " + wsRelPath
                            );
                        }
                    } catch (Exception ignored) {}
                }
            }

            Set<String> actualChangedPaths = new HashSet<>();
            for (Map.Entry<String, String> entry : workspaceFiles.entrySet()) {
                String path = entry.getKey();
                String wsHash = entry.getValue();
                if (!masterFiles.containsKey(path)) {
                    actualChangedPaths.add(path); // Created file
                } else if (!masterFiles.get(path).equalsIgnoreCase(wsHash)) {
                    actualChangedPaths.add(path); // Modified file
                }
            }
            for (String masterPath : masterFiles.keySet()) {
                if (!workspaceFiles.containsKey(masterPath)) {
                    actualChangedPaths.add(masterPath); // Deleted file
                }
            }

            // 7. Approved Changed Paths Set Equality Check
            if (!actualChangedPaths.equals(approvedChangedPaths)) {
                Set<String> unapprovedExtras = new HashSet<>(actualChangedPaths);
                unapprovedExtras.removeAll(approvedChangedPaths);
                if (!unapprovedExtras.isEmpty()) {
                    return MutationIntegrityResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            MutationIntegrityStatus.UNEXPECTED_FILE, "Unapproved changed files detected in workspace: " + unapprovedExtras
                    );
                }
                Set<String> missingApproved = new HashSet<>(approvedChangedPaths);
                missingApproved.removeAll(actualChangedPaths);
                if (!missingApproved.isEmpty()) {
                    return MutationIntegrityResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            MutationIntegrityStatus.SNAPSHOT_MISMATCH, "Approved changed files were not materialized in workspace: " + missingApproved
                    );
                }
            }

            // 8. Resulting Changed File Content Hash Verification
            String operation = codeChange.getOperation() != null ? codeChange.getOperation().toUpperCase(Locale.ROOT) : "MODIFY";
            String targetRelPath = Paths.get(codeChange.getFilePath()).normalize().toString().replace("\\", "/");
            Path targetPath = workspaceRepoDir.resolve(targetRelPath).toAbsolutePath().normalize();

            if ("MODIFY".equals(operation) || "REPLACE".equals(operation) || "CREATE".equals(operation)) {
                if (!Files.exists(targetPath)) {
                    return MutationIntegrityResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            MutationIntegrityStatus.SNAPSHOT_MISMATCH, "Target file does not exist in workspace: " + targetRelPath
                    );
                }
                String actualProposedHash = hashFileContent(targetPath);
                if (!actualProposedHash.equalsIgnoreCase(codeChange.getProposedHash())) {
                    return MutationIntegrityResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            MutationIntegrityStatus.SNAPSHOT_MISMATCH, "Workspace resulting file hash mismatch for " + targetRelPath
                    );
                }
            } else if ("DELETE".equals(operation)) {
                if (Files.exists(targetPath)) {
                    return MutationIntegrityResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            MutationIntegrityStatus.SNAPSHOT_MISMATCH, "Target file still exists after approved deletion: " + targetRelPath
                    );
                }
            }

            // 9. Unchanged File Verification (Files Outside Approved Changed Set)
            for (Map.Entry<String, String> entry : masterFiles.entrySet()) {
                String masterRelPath = entry.getKey();
                String masterHash = entry.getValue();

                if (!approvedChangedPaths.contains(masterRelPath)) {
                    if (!workspaceFiles.containsKey(masterRelPath)) {
                        return MutationIntegrityResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                MutationIntegrityStatus.UNEXPECTED_FILE, "Unapproved deletion of file outside approved set: " + masterRelPath
                        );
                    }
                    String wsHash = workspaceFiles.get(masterRelPath);
                    if (!wsHash.equalsIgnoreCase(masterHash)) {
                        return MutationIntegrityResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                MutationIntegrityStatus.UNEXPECTED_FILE, "Unapproved modification to file outside approved set: " + masterRelPath
                        );
                    }
                }
            }

            // 10. Secret Scanning across all changed files in workspace
            for (String changedRelPath : actualChangedPaths) {
                Path fileToScan = workspaceRepoDir.resolve(changedRelPath);
                if (Files.exists(fileToScan) && Files.isRegularFile(fileToScan)) {
                    String content = Files.readString(fileToScan, StandardCharsets.UTF_8);
                    SecretScannerService.SecretScanResult secretResult = secretScannerService.scanContent(content);
                    if (secretResult.hasSecret()) {
                        return MutationIntegrityResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                MutationIntegrityStatus.SECRET_DETECTED, "Secret detected in workspace file " + changedRelPath + ": " + secretResult.reason()
                        );
                    }
                }
            }

            // 11. Re-verify Master Baseline Immutability
            mutationWorkspaceManager.verifyMasterImmutability(masterRepoDir, masterDigestNow);

            // 12. Resulting Tree Hash Calculation & Double Snapshot Stability Check
            String resultingTreeHashT1 = mutationWorkspaceManager.calculateTreeDigest(workspaceRepoDir);
            String resultingTreeHashT2 = mutationWorkspaceManager.calculateTreeDigest(workspaceRepoDir);

            if (!resultingTreeHashT1.equals(resultingTreeHashT2)) {
                return MutationIntegrityResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        MutationIntegrityStatus.WORKSPACE_UNSTABLE, "Workspace tree digest changed during verification (concurrent mutation race detected)"
                );
            }

            logger.info("Successfully verified mutation integrity for operationId={} approvalId={}", operationId, approvalId);

            return MutationIntegrityResult.verified(
                    operationId,
                    approvalId,
                    projectId,
                    repositoryId,
                    taskId,
                    codeChangeId,
                    masterDigestNow,
                    resultingTreeHashT1,
                    new ArrayList<>(approvedChangedPaths)
            );

        } catch (ApiException e) {
            return MutationIntegrityResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    MutationIntegrityStatus.PATH_VIOLATION, e.getMessage()
            );
        } catch (Exception e) {
            logger.error("Failed to verify mutation integrity operationId={}: {}", operationId, e.getMessage(), e);
            return MutationIntegrityResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    MutationIntegrityStatus.INTEGRITY_FAILED, "Mutation integrity verification failed: " + e.getMessage()
            );
        }
    }

    private MutationIntegrityResult scanUnexpectedArtifactsAndSymlinks(
            Path workspaceRepoDir, Path masterRepoDir, Set<String> approvedChangedPaths,
            UUID operationId, UUID approvalId, UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId
    ) throws IOException {
        try (Stream<Path> stream = Files.walk(workspaceRepoDir, 50)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (path.equals(workspaceRepoDir)) continue;

                String fileName = path.getFileName().toString();
                if (fileName.startsWith(".tmp_patch_") || fileName.startsWith(".repopilot_") || fileName.endsWith(".tmp") || fileName.endsWith(".bak") || fileName.endsWith(".swp")) {
                    return MutationIntegrityResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            MutationIntegrityStatus.UNEXPECTED_FILE, "Temporary patch artifact or backup file found in workspace: " + fileName
                    );
                }

                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

                // Reject special files (FIFOs, sockets, character/block devices)
                if (!attrs.isRegularFile() && !attrs.isDirectory() && !attrs.isSymbolicLink()) {
                    return MutationIntegrityResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            MutationIntegrityStatus.PATH_VIOLATION, "Special file or unsupported inode type detected in workspace: " + fileName
                    );
                }

                if (attrs.isSymbolicLink()) {
                    String relPath = Normalizer.normalize(workspaceRepoDir.relativize(path).toString().replace("\\", "/"), Normalizer.Form.NFC);

                    // Check if symlink is approved
                    if (!approvedChangedPaths.contains(relPath)) {
                        // Check if master had this exact symlink
                        Path masterEquivalent = masterRepoDir.resolve(relPath);
                        if (!Files.exists(masterEquivalent, LinkOption.NOFOLLOW_LINKS) || !Files.isSymbolicLink(masterEquivalent)) {
                            return MutationIntegrityResult.failure(
                                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                    MutationIntegrityStatus.UNEXPECTED_FILE, "Unapproved symbolic link created or modified in workspace: " + relPath
                            );
                        }
                    }

                    Path linkTarget = Files.readSymbolicLink(path);
                    Path resolvedTarget = path.getParent().resolve(linkTarget).normalize();

                    // Check for external symlink escape or link pointing to master
                    if (!resolvedTarget.startsWith(workspaceRepoDir) || resolvedTarget.startsWith(masterRepoDir)) {
                        return MutationIntegrityResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                MutationIntegrityStatus.PATH_VIOLATION, "Unsafe symbolic link escape detected: " + fileName + " -> " + linkTarget
                        );
                    }
                }
            }
        }
        return null;
    }

    private Map<String, String> collectFileHashes(Path rootDir) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        Path normalizedRoot = rootDir.toAbsolutePath().normalize();

        try (Stream<Path> stream = Files.walk(normalizedRoot, 50)) {
            stream.forEach(path -> {
                if (path.equals(normalizedRoot)) return;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isRegularFile()) {
                        String relPath = normalizedRoot.relativize(path).toString().replace("\\", "/");
                        relPath = Normalizer.normalize(relPath, Normalizer.Form.NFC);
                        if (!relPath.equalsIgnoreCase(".execution.lock")) {
                            hashes.put(relPath, hashFileContent(path));
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to hash file: " + path, e);
                }
            });
        }
        return hashes;
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
