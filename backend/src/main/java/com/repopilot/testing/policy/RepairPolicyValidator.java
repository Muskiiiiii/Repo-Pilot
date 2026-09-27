package com.repopilot.testing.policy;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.testing.dto.AIRepairProposalDto;
import com.repopilot.testing.dto.ProposedFileModificationDto;
import com.repopilot.testing.dto.ValidationResult;
import com.repopilot.testing.service.FailureClassifierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class RepairPolicyValidator {

    public static final int MAX_FILES = 5;
    public static final int MAX_MODIFIED_LINES = 100;
    public static final int MAX_DIFF_BYTES = 10 * 1024; // 10 KB
    public static final int MAX_AI_RESPONSE_BYTES = 100 * 1024; // 100 KB

    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile(".*pom\\.xml$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*build\\.gradle.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*settings\\.gradle.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*package.*\\.json$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*yarn\\.lock$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*pnpm-lock\\.yaml$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*requirements\\.txt$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*poetry\\.lock$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*pyproject\\.toml$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Cargo\\..*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*go\\.(mod|sum)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Dockerfile.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*docker-compose.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.git(/.*)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.github(/.*)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.gitlab-ci\\.yml$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.(png|jpe?g|gif|ico|pdf|zip|tar|gz|7z|rar|jar|class|exe|dll|so|dylib|bin|dat|pyc|o|a)$", Pattern.CASE_INSENSITIVE),

            // Anti-Test-Tampering Patterns
            Pattern.compile(".*Test\\.java$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Tests\\.java$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*/test_.*\\.py$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*/.*_test\\.py$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*_test\\.go$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.test\\.(js|ts)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*\\.spec\\.(js|ts)$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*/tests?/.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*/__tests__/.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*/fixtures/.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*/testdata/.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*pytest\\.ini$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*jest\\.config.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*testng\\.xml$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*application-test\\.yml$", Pattern.CASE_INSENSITIVE)
    );

    private static final List<String> TEST_DISABLING_DIRECTIVES = List.of(
            "@Disabled", "@Ignore", "pytest.mark.skip", "it.skip", "t.Skip", "skipTests=true"
    );

    private final FailureClassifierService failureClassifierService;

    @Autowired
    public RepairPolicyValidator(FailureClassifierService failureClassifierService) {
        this.failureClassifierService = failureClassifierService;
    }

    /**
     * Mechanical 10-step Path Security Algorithm
     */
    public ValidationResult validatePathSecurity(Path workspaceRepoDir, Path masterRepoDir, String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            return ValidationResult.invalid("INVALID_PATH", "Input path is null or empty");
        }

        // 1. Control Character & Null Byte Check
        if (inputPath.contains("\0") || inputPath.contains("\r") || inputPath.contains("\n")) {
            return ValidationResult.invalid("INVALID_PATH", "Path contains null bytes or control characters");
        }

        // 2. Syntactic Path Traversal & Windows Drive Check
        if (inputPath.contains("..") || inputPath.startsWith("~") || inputPath.startsWith("/") || inputPath.startsWith("\\") || inputPath.matches("^[a-zA-Z]:.*")) {
            return ValidationResult.invalid("PATH_TRAVERSAL", "Path contains syntactic path traversal, leading slashes, or drive letters");
        }

        // Clean & Normalize
        String normalizedInput = inputPath.replace("\\", "/").replaceAll("^/+", "");
        Path targetPath;
        try {
            targetPath = workspaceRepoDir.resolve(normalizedInput).normalize();
        } catch (Exception e) {
            return ValidationResult.invalid("INVALID_PATH", "Failed to resolve path: " + e.getMessage());
        }

        // 3. Relativize Workspace Containment Check
        Path relative = workspaceRepoDir.relativize(targetPath);
        if (relative.startsWith("..") || relative.isAbsolute()) {
            return ValidationResult.invalid("PATH_CONTAINMENT_VIOLATION", "Target path escapes workspace root");
        }

        // 4. Sibling Prefix Attack Defense
        String workspaceString = workspaceRepoDir.toAbsolutePath().normalize().toString();
        String targetString = targetPath.toAbsolutePath().normalize().toString();
        if (!targetString.equals(workspaceString) && !targetString.startsWith(workspaceString + File.separator)) {
            return ValidationResult.invalid("SIBLING_PREFIX_ATTACK", "Target path breaks sibling directory isolation");
        }

        // 5. Forbidden Path Policy Check
        for (Pattern pattern : FORBIDDEN_PATTERNS) {
            if (pattern.matcher(normalizedInput).matches() || pattern.matcher(targetString).matches()) {
                return ValidationResult.invalid("FORBIDDEN_PATH", "Path matches forbidden test, build, or config policy: " + normalizedInput);
            }
        }

        // 6. Symlink Inspection Across All Path Components
        try {
            Path current = workspaceRepoDir.toAbsolutePath().normalize();
            for (Path component : relative) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    return ValidationResult.invalid("SYMLINK_DETECTED", "Path contains symbolic link: " + current);
                }
            }

            // Inspect Master Repo symlinks too
            current = masterRepoDir.toAbsolutePath().normalize();
            for (Path component : relative) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    return ValidationResult.invalid("SYMLINK_DETECTED", "Master baseline path contains symbolic link: " + current);
                }
            }
        } catch (Exception e) {
            return ValidationResult.invalid("SYMLINK_CHECK_FAILED", "Failed during symlink inspection: " + e.getMessage());
        }

        // 7. Master Baseline Existing Target Verification (Rejects new files)
        Path masterTarget = masterRepoDir.resolve(normalizedInput).normalize();
        if (Files.exists(masterRepoDir) && !Files.isRegularFile(masterTarget)) {
            return ValidationResult.invalid("NEW_FILE_REJECTED", "Target file does not exist in master repository baseline");
        }

        // 8. Parent Directory Containment Verification
        Path parentDir = targetPath.getParent();
        if (Files.exists(workspaceRepoDir) && (parentDir == null || !Files.isDirectory(parentDir))) {
            return ValidationResult.invalid("INVALID_PARENT", "Parent directory does not exist or is not a directory");
        }

        // 9. Canonical Real Path Containment Check (if file exists in workspace)
        try {
            if (Files.exists(targetPath)) {
                Path realWorkspace = workspaceRepoDir.toRealPath();
                Path realTarget = targetPath.toRealPath();
                if (!realTarget.startsWith(realWorkspace)) {
                    return ValidationResult.invalid("CANONICAL_PATH_ESCAPE", "Canonical real path escapes workspace root");
                }
            }
        } catch (IOException e) {
            return ValidationResult.invalid("CANONICAL_PATH_FAILED", "Failed to resolve real path: " + e.getMessage());
        }

        return ValidationResult.valid();
    }

    /**
     * Complete Repair Proposal Validation Pipeline
     */
    public ValidationResult validateRepairProposal(Path workspaceRepoDir,
                                                  Path masterRepoDir,
                                                  List<CodeChange> approvedPhase9CodeChanges,
                                                  AIRepairProposalDto proposal,
                                                  String activeWorkspaceTreeHash,
                                                  String parentSnapshotHash,
                                                  String rawAiResponseString) {

        // 0. AI Response Size Bounds Check
        if (rawAiResponseString != null && rawAiResponseString.getBytes().length > MAX_AI_RESPONSE_BYTES) {
            return ValidationResult.invalid("OVERSIZED_RESPONSE", "AI response size exceeds 100KB limit");
        }

        if (proposal == null || proposal.getFileModifications() == null || proposal.getFileModifications().isEmpty()) {
            return ValidationResult.invalid("MALFORMED_PROPOSAL", "Repair proposal contains no file modifications");
        }

        // 1. Stale / Replay Defense Check
        if (parentSnapshotHash != null && activeWorkspaceTreeHash != null && !parentSnapshotHash.equalsIgnoreCase(activeWorkspaceTreeHash)) {
            return ValidationResult.invalid("STALE_REPAIR_PROPOSAL", "Proposal parentSnapshotHash (" + parentSnapshotHash + ") does not match active workspace tree hash (" + activeWorkspaceTreeHash + ")");
        }

        // 2. Maximum File Count Bounds Check (Max 5 files)
        List<ProposedFileModificationDto> mods = proposal.getFileModifications();
        if (mods.size() > MAX_FILES) {
            return ValidationResult.invalid("EXCEEDS_FILE_LIMIT", "Proposal modifies " + mods.size() + " files (Max limit is " + MAX_FILES + ")");
        }

        // 3. Whitelist Extraction from Server-Side Approved Phase 9 CodeChanges
        Set<String> whitelist = new HashSet<>();
        if (approvedPhase9CodeChanges != null) {
            for (CodeChange cc : approvedPhase9CodeChanges) {
                if (cc.getFilePath() != null && !cc.getFilePath().isBlank()) {
                    whitelist.add(cc.getFilePath().replace("\\", "/").replaceAll("^/+", "").toLowerCase());
                }
            }
        }

        int totalModifiedLines = 0;
        int totalDiffBytes = 0;

        for (ProposedFileModificationDto mod : mods) {
            if (mod.getFilePath() == null || mod.getFilePath().isBlank()) {
                return ValidationResult.invalid("INVALID_PATH", "Proposed file modification path is null or empty");
            }

            String normalizedPath = mod.getFilePath().replace("\\", "/").replaceAll("^/+", "");

            // 4. Mechanical Path Security Validation
            ValidationResult pathResult = validatePathSecurity(workspaceRepoDir, masterRepoDir, normalizedPath);
            if (!pathResult.isValid()) {
                return pathResult;
            }

            // 5. Server-Side Whitelist Check
            if (!whitelist.contains(normalizedPath.toLowerCase())) {
                return ValidationResult.invalid("NOT_IN_WHITELIST", "Path is not present in approved Phase 9 CodeChange whitelist: " + normalizedPath);
            }

            // 6. Replacement Content & Secret Scanner Integration
            String replacement = mod.getReplacementContent();
            if (replacement != null) {
                // Anti-test-tampering directive check
                for (String directive : TEST_DISABLING_DIRECTIVES) {
                    if (replacement.contains(directive)) {
                        return ValidationResult.invalid("TEST_TAMPERING", "Replacement content contains test-disabling directive: " + directive);
                    }
                }

                // Secret scanner check
                String sanitized = failureClassifierService.sanitize(replacement);
                if (!sanitized.equals(replacement)) {
                    return ValidationResult.invalid("SECRET_DETECTED", "Sensitive secret material detected in proposed replacement content for " + normalizedPath);
                }

                totalDiffBytes += replacement.getBytes().length;
                int lines = replacement.split("\r\n|\r|\n", -1).length;
                totalModifiedLines += lines;
            }
        }

        // 7. Total Patch Size Bounds Checks
        if (totalModifiedLines > MAX_MODIFIED_LINES) {
            return ValidationResult.invalid("EXCEEDS_LINE_LIMIT", "Total modified lines (" + totalModifiedLines + ") exceeds limit of " + MAX_MODIFIED_LINES);
        }

        if (totalDiffBytes > MAX_DIFF_BYTES) {
            return ValidationResult.invalid("EXCEEDS_DIFF_LIMIT", "Total diff bytes (" + totalDiffBytes + ") exceeds limit of " + MAX_DIFF_BYTES + " bytes");
        }

        return ValidationResult.valid();
    }
}
