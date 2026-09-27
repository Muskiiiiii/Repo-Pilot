package com.repopilot.review.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.change.service.DiffGeneratorService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.FindingConfidence;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingSide;
import com.repopilot.review.entity.FindingType;
import com.repopilot.security.service.SecretScannerService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class ReviewFindingValidatorImpl implements ReviewFindingValidator {

    public static final int MAX_FINDINGS_LIMIT = 50;
    public static final int MAX_TITLE_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 10_000;
    public static final int MAX_EVIDENCE_LENGTH = 10_000;
    public static final int MAX_REMEDIATION_LENGTH = 10_000;
    public static final int MAX_LINE_RANGE_LIMIT = 500;

    private static final Pattern CWE_PATTERN = Pattern.compile("^CWE-\\d{1,5}$");
    private static final Pattern UNSAFE_REMEDIATION_PATTERN = Pattern.compile(
            "(?i)\\b(sudo|chmod|chown|rm\\s+-[a-z]*f|curl\\s+|wget\\s+|powershell|bash\\s+|sh\\s+|docker\\s+|kubectl\\s+|git\\s+push|git\\s+commit|git\\s+checkout)\\b|--- a/|\\+\\+\\+ b/|diff --git"
    );

    private final ReviewContextService reviewContextService;
    private final CodeChangeRepository codeChangeRepository;
    private final SecretScannerService secretScannerService;
    private final DiffGeneratorService diffGeneratorService;

    public ReviewFindingValidatorImpl(ReviewContextService reviewContextService,
                                       CodeChangeRepository codeChangeRepository,
                                       SecretScannerService secretScannerService,
                                       DiffGeneratorService diffGeneratorService) {
        this.reviewContextService = reviewContextService;
        this.codeChangeRepository = codeChangeRepository;
        this.secretScannerService = secretScannerService;
        this.diffGeneratorService = diffGeneratorService;
    }

    @Override
    public ValidationResult validateFindings(ReviewContext context, ReviewResponseDto reviewResponse) {
        // 1. Context & Response Null Checks
        if (context == null || reviewResponse == null) {
            return ValidationResult.failure(ValidationErrorReason.STRUCTURAL_VALIDATION_FAILURE, "ReviewContext or ReviewResponseDto cannot be null");
        }

        // 2. Staleness & Version Checks
        if (context.isStale() || reviewContextService.isContextStale(context)) {
            return ValidationResult.failure(ValidationErrorReason.STALE_CONTEXT, "ReviewContext is stale");
        }
        if (!"v1.0".equals(context.contextVersion())) {
            return ValidationResult.failure(ValidationErrorReason.STALE_CONTEXT, "Invalid contextVersion: " + context.contextVersion());
        }

        // 3. Database CodeChange Consistency Check
        if (context.codeChangeId() != null) {
            Optional<CodeChange> changeOpt = codeChangeRepository.findById(context.codeChangeId());
            if (changeOpt.isEmpty()) {
                return ValidationResult.failure(ValidationErrorReason.STALE_CONTEXT, "Referenced CodeChange no longer exists");
            }
            CodeChange dbChange = changeOpt.get();
            if (context.originalHash() != null && dbChange.getOriginalHash() != null
                    && !context.originalHash().equals(dbChange.getOriginalHash())) {
                return ValidationResult.failure(ValidationErrorReason.STALE_CONTEXT, "Original hash mismatch with database CodeChange");
            }
            if (context.proposedHash() != null && dbChange.getProposedHash() != null
                    && !context.proposedHash().equals(dbChange.getProposedHash())) {
                return ValidationResult.failure(ValidationErrorReason.STALE_CONTEXT, "Proposed hash mismatch with database CodeChange");
            }
        }

        // 4. Finding Count Limit Check
        List<ReviewFindingResponseDto> findings = reviewResponse.getFindings();
        if (findings != null && findings.size() > MAX_FINDINGS_LIMIT) {
            return ValidationResult.failure(ValidationErrorReason.RESOURCE_LIMIT_EXCEEDED, "Findings count exceeds maximum limit of " + MAX_FINDINGS_LIMIT);
        }

        if (findings == null || findings.isEmpty()) {
            return ValidationResult.success(List.of());
        }

        List<ValidatedFindingCandidate> validatedCandidates = new ArrayList<>();
        List<String> rejectedFindingErrors = new ArrayList<>();
        Set<String> seenFingerprints = new HashSet<>();

        // 5. Validate Each Finding
        for (int index = 0; index < findings.size(); index++) {
            ReviewFindingResponseDto finding = findings.get(index);
            if (finding == null) {
                rejectedFindingErrors.add("Finding #" + index + ": finding is null");
                continue;
            }

            // A. Structural Validation & Field Size Limits
            if (finding.getTitle() == null || finding.getTitle().isBlank()) {
                rejectedFindingErrors.add("Finding #" + index + ": title is null or blank");
                continue;
            }
            if (finding.getTitle().length() > MAX_TITLE_LENGTH) {
                rejectedFindingErrors.add("Finding #" + index + ": title exceeds max length of " + MAX_TITLE_LENGTH);
                continue;
            }
            if (finding.getDescription() == null || finding.getDescription().isBlank()) {
                rejectedFindingErrors.add("Finding #" + index + ": description is null or blank");
                continue;
            }
            if (finding.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
                rejectedFindingErrors.add("Finding #" + index + ": description exceeds max length of " + MAX_DESCRIPTION_LENGTH);
                continue;
            }
            if (finding.getEvidence() != null && finding.getEvidence().length() > MAX_EVIDENCE_LENGTH) {
                rejectedFindingErrors.add("Finding #" + index + ": evidence exceeds max length of " + MAX_EVIDENCE_LENGTH);
                continue;
            }
            if (finding.getRemediation() != null && finding.getRemediation().length() > MAX_REMEDIATION_LENGTH) {
                rejectedFindingErrors.add("Finding #" + index + ": remediation exceeds max length of " + MAX_REMEDIATION_LENGTH);
                continue;
            }

            // Enums Check
            if (finding.getFindingType() == null) {
                rejectedFindingErrors.add("Finding #" + index + ": findingType is null");
                continue;
            }
            if (finding.getSeverity() == null) {
                rejectedFindingErrors.add("Finding #" + index + ": severity is null");
                continue;
            }
            if (finding.getConfidence() == null) {
                rejectedFindingErrors.add("Finding #" + index + ": confidence is null");
                continue;
            }
            if (finding.getSide() == null) {
                rejectedFindingErrors.add("Finding #" + index + ": side is null");
                continue;
            }

            // B. Path Validation
            String filePath = finding.getFilePath();
            if (filePath == null || filePath.isBlank()) {
                rejectedFindingErrors.add("Finding #" + index + ": filePath is null or blank");
                continue;
            }
            try {
                reviewContextService.validatePath(filePath);
            } catch (ApiException e) {
                rejectedFindingErrors.add("Finding #" + index + ": path security validation failed for " + filePath + ": " + e.getMessage());
                continue;
            }

            // C. Scope Validation
            if (!isPathInReviewScope(filePath, context)) {
                rejectedFindingErrors.add("Finding #" + index + ": filePath " + filePath + " is outside authorized review context scope");
                continue;
            }

            // D. Line Range & File Existence Validation
            Integer startLine = finding.getStartLine();
            Integer endLine = finding.getEndLine();
            if (startLine == null || endLine == null || startLine < 1 || endLine < startLine) {
                rejectedFindingErrors.add("Finding #" + index + ": invalid line range [" + startLine + ", " + endLine + "]");
                continue;
            }
            if ((endLine - startLine + 1) > MAX_LINE_RANGE_LIMIT) {
                rejectedFindingErrors.add("Finding #" + index + ": line range length exceeds limit of " + MAX_LINE_RANGE_LIMIT);
                continue;
            }

            String sourceContent = getSourceContentForSide(context, finding.getSide());
            if (sourceContent == null || sourceContent.isBlank()) {
                rejectedFindingErrors.add("Finding #" + index + ": source content for side " + finding.getSide() + " is empty or unavailable");
                continue;
            }

            String[] lines = sourceContent.split("\n", -1);
            if (endLine > lines.length) {
                rejectedFindingErrors.add("Finding #" + index + ": line range endLine " + endLine + " exceeds file EOF (" + lines.length + ")");
                continue;
            }

            // Extract lines [startLine, endLine] (1-indexed)
            StringBuilder actualSnippetBuilder = new StringBuilder();
            for (int lineNum = startLine; lineNum <= endLine; lineNum++) {
                actualSnippetBuilder.append(lines[lineNum - 1]);
                if (lineNum < endLine) {
                    actualSnippetBuilder.append("\n");
                }
            }
            String actualSnippet = actualSnippetBuilder.toString();

            // E. Evidence Verification
            String claimedEvidence = finding.getEvidence();
            if (claimedEvidence != null && !claimedEvidence.isBlank()) {
                String cleanClaimed = claimedEvidence.replace("\r\n", "\n").trim();
                String cleanActual = actualSnippet.replace("\r\n", "\n").trim();
                if (!cleanActual.contains(cleanClaimed) && !cleanClaimed.contains(cleanActual)) {
                    rejectedFindingErrors.add("Finding #" + index + ": claimed evidence does not match actual code lines");
                    continue;
                }
            }

            String contentHash = diffGeneratorService.computeSha256Hash(actualSnippet);

            // F. Secret Scanning
            if (containsSecrets(finding)) {
                rejectedFindingErrors.add("Finding #" + index + ": potential secret detected in finding content");
                continue;
            }

            // G. Remediation Safety Check
            if (finding.getRemediation() != null && UNSAFE_REMEDIATION_PATTERN.matcher(finding.getRemediation()).find()) {
                rejectedFindingErrors.add("Finding #" + index + ": remediation contains unsafe shell execution commands or patch markers");
                continue;
            }

            // H. CWE / CVE Claims
            String cweId = finding.getCweId();
            boolean isUnverifiedClaim = false;
            if (cweId != null && !cweId.isBlank()) {
                if (!CWE_PATTERN.matcher(cweId.trim()).matches()) {
                    isUnverifiedClaim = true;
                    cweId = null; // Neutralize malformed CWE
                }
            }

            // I. Normalization & Fingerprint Calculation
            String normCategory = finding.getCategory() != null ? finding.getCategory().trim().toUpperCase() : "GENERAL";
            String safeFilePath = filePath.replace("\\", "\\\\").replace("|", "\\|");
            String safeCategory = normCategory.replace("\\", "\\\\").replace("|", "\\|");
            String canonicalString = String.join("|",
                    context.projectId().toString(),
                    context.repositoryId().toString(),
                    safeFilePath,
                    safeCategory,
                    String.valueOf(startLine),
                    String.valueOf(endLine),
                    contentHash
            );
            String fingerprint = diffGeneratorService.computeSha256Hash(canonicalString);

            // J. Deduplication within response
            if (seenFingerprints.contains(fingerprint)) {
                // Deduplicate cleanly without failing validation
                continue;
            }
            seenFingerprints.add(fingerprint);

            validatedCandidates.add(new ValidatedFindingCandidate(
                    finding.getFindingType(),
                    normCategory,
                    cweId,
                    finding.getSeverity(),
                    finding.getConfidence(),
                    finding.getTitle().trim(),
                    finding.getDescription().trim(),
                    filePath,
                    startLine,
                    endLine,
                    finding.getSide(),
                    finding.getEvidence() != null ? finding.getEvidence().trim() : actualSnippet,
                    finding.getRemediation() != null ? finding.getRemediation().trim() : null,
                    contentHash,
                    fingerprint,
                    isUnverifiedClaim
            ));
        }

        if (validatedCandidates.isEmpty() && !rejectedFindingErrors.isEmpty()) {
            return ValidationResult.failure(ValidationErrorReason.STRUCTURAL_VALIDATION_FAILURE,
                    "All findings failed validation: " + String.join("; ", rejectedFindingErrors));
        }

        return ValidationResult.partial(validatedCandidates, rejectedFindingErrors);
    }

    private boolean isPathInReviewScope(String filePath, ReviewContext context) {
        if (filePath == null || context == null) return false;
        if (filePath.equals(context.filePath())) {
            return true;
        }
        if (context.evidenceReferences() != null) {
            for (EvidenceRef ref : context.evidenceReferences()) {
                if (filePath.equals(ref.filePath())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String getSourceContentForSide(ReviewContext context, FindingSide side) {
        if (context == null || side == null) return "";
        String raw = (side == FindingSide.PROPOSED) ? context.proposedContent() : context.originalContent();
        if (raw == null) return "";
        // Strip XML wrapper tags if present
        return raw.replaceAll("(?s)<UNTRUSTED_SOURCE_CODE.*?>", "")
                  .replaceAll("</UNTRUSTED_SOURCE_CODE>", "")
                  .replace("\r\n", "\n")
                  .stripLeading();
    }

    private boolean containsSecrets(ReviewFindingResponseDto finding) {
        if (secretScannerService.scanContent(finding.getTitle()).hasSecret()) return true;
        if (secretScannerService.scanContent(finding.getDescription()).hasSecret()) return true;
        if (finding.getEvidence() != null && secretScannerService.scanContent(finding.getEvidence()).hasSecret()) return true;
        if (finding.getRemediation() != null && secretScannerService.scanContent(finding.getRemediation()).hasSecret()) return true;
        if (finding.getCategory() != null && secretScannerService.scanContent(finding.getCategory()).hasSecret()) return true;
        return false;
    }
}
