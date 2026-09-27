package com.repopilot.review.service;

import com.repopilot.review.dto.CanonicalFindingInput;
import com.repopilot.review.dto.SecurityPolicyComplianceCertificateDto;
import com.repopilot.review.entity.ComplianceCertificate;
import com.repopilot.review.entity.ReviewFinding;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CanonicalCertificateHashService {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    // =========================================================================
    // FINDING EVIDENCE CANONICALIZATION & HASHING
    // =========================================================================

    public String canonicalizeFindings(List<CanonicalFindingInput> findings) {
        if (findings == null || findings.isEmpty()) {
            return "[]";
        }

        List<CanonicalFindingInput> sorted = new ArrayList<>(findings);
        sorted.sort((a, b) -> {
            if (a.id() == null && b.id() == null) return 0;
            if (a.id() == null) return 1;
            if (b.id() == null) return -1;
            return a.id().compareTo(b.id());
        });

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendCanonicalFinding(sb, sorted.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    public String hashFindingEvidence(List<CanonicalFindingInput> findings) {
        String canonicalJson = canonicalizeFindings(findings);
        return sha256Hex(canonicalJson.getBytes(StandardCharsets.UTF_8));
    }

    public String canonicalizeFindingsFromEntities(List<ReviewFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "[]";
        }
        List<CanonicalFindingInput> inputs = new ArrayList<>();
        for (ReviewFinding f : findings) {
            inputs.add(CanonicalFindingInput.fromEntity(f));
        }
        return canonicalizeFindings(inputs);
    }

    public String hashFindingEvidenceFromEntities(List<ReviewFinding> findings) {
        return sha256Hex(canonicalizeFindingsFromEntities(findings).getBytes(StandardCharsets.UTF_8));
    }

    public byte[] getCanonicalFindingBytes(List<CanonicalFindingInput> findings) {
        return canonicalizeFindings(findings).getBytes(StandardCharsets.UTF_8);
    }

    private void appendCanonicalFinding(StringBuilder sb, CanonicalFindingInput f) {
        sb.append('{');
        sb.append("\"category\":").append(formatString(f.category())).append(',');
        sb.append("\"contentHash\":").append(formatString(f.contentHash())).append(',');
        sb.append("\"cweId\":").append(formatString(f.cweId())).append(',');
        sb.append("\"description\":").append(formatString(f.description())).append(',');
        sb.append("\"endLine\":").append(formatInteger(f.endLine())).append(',');
        sb.append("\"filePath\":").append(formatString(f.filePath())).append(',');
        sb.append("\"findingType\":").append(formatString(f.findingType())).append(',');
        sb.append("\"fingerprint\":").append(formatString(f.fingerprint())).append(',');
        sb.append("\"id\":").append(formatUuid(f.id())).append(',');
        sb.append("\"severity\":").append(formatString(f.severity())).append(',');
        sb.append("\"side\":").append(formatString(f.side())).append(',');
        sb.append("\"startLine\":").append(formatInteger(f.startLine())).append(',');
        sb.append("\"status\":").append(formatString(f.status())).append(',');
        sb.append("\"title\":").append(formatString(f.title()));
        sb.append('}');
    }

    // =========================================================================
    // CERTIFICATE CANONICALIZATION & HASHING
    // =========================================================================

    public String canonicalizeCertificate(SecurityPolicyComplianceCertificateDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Certificate DTO cannot be null");
        }

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"adminOverrideByUserId\":").append(formatUuid(dto.adminOverrideByUserId())).append(',');
        sb.append("\"adminOverrideReason\":").append(formatString(dto.adminOverrideReason())).append(',');
        sb.append("\"certificateId\":").append(formatUuid(dto.certificateId())).append(',');
        sb.append("\"codeChangeId\":").append(formatUuid(dto.codeChangeId())).append(',');
        sb.append("\"complianceStatus\":").append(dto.complianceStatus() != null ? formatString(dto.complianceStatus().name()) : "null").append(',');
        sb.append("\"criticalUnmitigatedCount\":").append(formatInteger(dto.criticalUnmitigatedCount())).append(',');
        sb.append("\"findingEvidenceHash\":").append(formatString(dto.findingEvidenceHash())).append(',');
        sb.append("\"highUnmitigatedCount\":").append(formatInteger(dto.highUnmitigatedCount())).append(',');
        sb.append("\"idempotencyKey\":").append(formatString(dto.idempotencyKey())).append(',');
        sb.append("\"issuedAt\":").append(formatTimestamp(dto.issuedAt())).append(',');
        sb.append("\"issuedByUserId\":").append(formatUuid(dto.issuedByUserId())).append(',');
        sb.append("\"lowUnmitigatedCount\":").append(formatInteger(dto.lowUnmitigatedCount())).append(',');
        sb.append("\"mediumUnmitigatedCount\":").append(formatInteger(dto.mediumUnmitigatedCount())).append(',');
        sb.append("\"mitigatedFindingsCount\":").append(formatInteger(dto.mitigatedFindingsCount())).append(',');
        sb.append("\"policyVersion\":").append(formatString(dto.policyVersion())).append(',');
        sb.append("\"projectId\":").append(formatUuid(dto.projectId())).append(',');
        sb.append("\"repositoryId\":").append(formatUuid(dto.repositoryId())).append(',');
        sb.append("\"reviewContextHash\":").append(formatString(dto.reviewContextHash())).append(',');
        sb.append("\"reviewId\":").append(formatUuid(dto.reviewId())).append(',');
        sb.append("\"taskId\":").append(formatUuid(dto.taskId()));
        sb.append('}');
        return sb.toString();
    }

    public String hashCertificate(SecurityPolicyComplianceCertificateDto dto) {
        String canonicalJson = canonicalizeCertificate(dto);
        return sha256Hex(canonicalJson.getBytes(StandardCharsets.UTF_8));
    }

    public String canonicalizeCertificateEntity(ComplianceCertificate entity) {
        if (entity == null) {
            throw new IllegalArgumentException("ComplianceCertificate entity cannot be null");
        }
        return canonicalizeCertificate(entity.toDto());
    }

    public String hashCertificateEntity(ComplianceCertificate entity) {
        if (entity == null) {
            throw new IllegalArgumentException("ComplianceCertificate entity cannot be null");
        }
        return hashCertificate(entity.toDto());
    }

    public byte[] getCanonicalCertificateBytes(SecurityPolicyComplianceCertificateDto dto) {
        return canonicalizeCertificate(dto).getBytes(StandardCharsets.UTF_8);
    }

    // =========================================================================
    // FORMATTING HELPERS
    // =========================================================================

    private String formatUuid(UUID uuid) {
        if (uuid == null) return "null";
        return "\"" + uuid.toString().toLowerCase(Locale.ROOT) + "\"";
    }

    private String formatInteger(Integer value) {
        if (value == null) return "null";
        return value.toString();
    }

    private String formatTimestamp(Instant timestamp) {
        if (timestamp == null) return "null";
        Instant truncated = timestamp.truncatedTo(ChronoUnit.MILLIS);
        return "\"" + TIMESTAMP_FORMATTER.format(truncated) + "\"";
    }

    private String formatString(String input) {
        if (input == null) return "null";
        StringBuilder sb = new StringBuilder(input.length() + 16);
        sb.append('"');
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private String sha256Hex(byte[] inputBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(inputBytes);
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
