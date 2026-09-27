package com.repopilot.report.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.report.dto.ReportSynthesisOutputDto;
import com.repopilot.report.entity.ImplementationReport;

import java.util.List;
import java.util.regex.Pattern;

public class DeterministicMarkdownRenderer {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern DANGEROUS_URL_PATTERN = Pattern.compile("(?i)(javascript|vbscript|data)[\\s\\x00-\\x1F]*:");
    private static final Pattern DANGEROUS_ATTR_PATTERN = Pattern.compile("(?i)\\bon[a-z]+\\s*=");

    public static String renderMarkdown(
            ImplementationReport report,
            ReportSynthesisOutputDto synthesisOutput,
            String aiSynthesisInputHash) {
        if (report == null || synthesisOutput == null) {
            return "# Implementation Report\n\n[Error: Missing report or synthesis data]";
        }

        StringBuilder sb = new StringBuilder();

        String execId = "N/A";
        if (report.getTaskExecution() != null) {
            try {
                execId = report.getTaskExecution().getId().toString();
            } catch (Exception e) {
                execId = "N/A";
            }
        }
        String taskTitle = "N/A";
        if (report.getTask() != null) {
            try {
                taskTitle = report.getTask().getTitle();
            } catch (Exception e) {
                taskTitle = "Task Execution";
            }
        }

        // Header & Metadata (Rendered 100% authoritatively from ImplementationReport)
        sb.append("# Implementation Synthesis Report\n\n");
        sb.append("**Execution ID**: `").append(execId).append("`  \n");
        sb.append("**Task Title**: ").append(sanitizeProse(taskTitle)).append("  \n");
        sb.append("**Outcome**: `").append(report.getExecutionOutcome() != null ? report.getExecutionOutcome() : "UNKNOWN").append("`  \n");
        sb.append("**Confidence Score**: ").append(report.getConfidenceScore() != null ? report.getConfidenceScore() : "N/A").append("  \n");
        sb.append("**Source Context Hash**: `").append(report.getSourceContextHash() != null ? report.getSourceContextHash() : "N/A").append("`  \n");
        sb.append("**Synthesis Input Hash**: `").append(aiSynthesisInputHash != null ? aiSynthesisInputHash : "N/A").append("`  \n");
        sb.append("**Generated At**: ").append(report.getCreatedAt() != null ? report.getCreatedAt().toString() : "N/A").append("  \n\n");

        sb.append("---\n\n");

        // Section 1: Executive Summary
        sb.append("## 1. Executive Summary\n\n");
        sb.append(sanitizeProse(synthesisOutput.getExecutiveSummary())).append("\n\n");

        sb.append("---\n\n");

        // Section 2: Changed Files & Implementation Details
        sb.append("## 2. Changed Files & Implementation Details\n\n");
        sb.append(sanitizeProse(synthesisOutput.getImplementationExplanation())).append("\n\n");
        appendChangedFilesTable(sb, report.getChangedFilesSummary());

        sb.append("---\n\n");

        // Section 3: Test Execution Analysis
        sb.append("## 3. Test Execution Analysis\n\n");
        sb.append(sanitizeProse(synthesisOutput.getTestingExplanation())).append("\n\n");
        appendTestSummaryMetrics(sb, report.getTestSummary());

        sb.append("---\n\n");

        // Section 4: Security & Compliance Review
        sb.append("## 4. Security & Compliance Review\n\n");
        sb.append(sanitizeProse(synthesisOutput.getSecurityExplanation())).append("\n\n");
        appendSecurityReviewMetrics(sb, report.getSecurityReviewSummary());

        sb.append("---\n\n");

        // Section 5: Known Limitations & Edge Cases
        sb.append("## 5. Known Limitations & Edge Cases\n\n");
        List<String> limitations = synthesisOutput.getLimitations();
        if (limitations == null || limitations.isEmpty()) {
            sb.append("- None reported.\n");
        } else {
            for (String lim : limitations) {
                sb.append("- ").append(sanitizeProse(lim)).append("\n");
            }
        }
        sb.append("\n---\n\n");

        // Section 6: Developer & Maintenance Notes
        sb.append("## 6. Developer & Maintenance Notes\n\n");
        List<String> notes = synthesisOutput.getDeveloperNotes();
        if (notes == null || notes.isEmpty()) {
            sb.append("- None reported.\n");
        } else {
            for (String note : notes) {
                sb.append("- ").append(sanitizeProse(note)).append("\n");
            }
        }

        return sb.toString();
    }

    private static void appendChangedFilesTable(StringBuilder sb, String changedFilesJson) {
        try {
            if (changedFilesJson != null && !changedFilesJson.isBlank()) {
                JsonNode root = objectMapper.readTree(changedFilesJson);
                if (root != null && root.isTextual()) {
                    root = objectMapper.readTree(root.asText());
                }
                JsonNode bounded = (root != null) ? root.get("boundedFiles") : null;
                if (bounded != null && bounded.isArray() && bounded.size() > 0) {
                    sb.append("| File Path | Operation | Proposed Hash |\n");
                    sb.append("| :--- | :--- | :--- |\n");
                    for (JsonNode item : bounded) {
                        String filePath = sanitizeProse(item.has("filePath") ? item.get("filePath").asText() : "");
                        String op = sanitizeProse(item.has("operation") ? item.get("operation").asText() : "");
                        String hash = sanitizeProse(item.has("proposedHash") ? item.get("proposedHash").asText() : "");
                        sb.append("| `").append(filePath).append("` | ").append(op).append(" | `").append(hash).append("` |\n");
                    }
                    sb.append("\n");
                    return;
                }
            }
        } catch (Exception ignored) {
        }
        sb.append("*No files modified in this execution.*\n\n");
    }

    private static void appendTestSummaryMetrics(StringBuilder sb, String testSummaryJson) {
        try {
            if (testSummaryJson != null && !testSummaryJson.isBlank()) {
                JsonNode root = objectMapper.readTree(testSummaryJson);
                if (root != null && root.isTextual()) {
                    root = objectMapper.readTree(root.asText());
                }
                int total = (root != null && root.has("totalTests")) ? root.get("totalTests").asInt() : 0;
                int passed = (root != null && root.has("passed")) ? root.get("passed").asInt() : 0;
                int failed = (root != null && root.has("failed")) ? root.get("failed").asInt() : 0;
                int skipped = (root != null && root.has("skipped")) ? root.get("skipped").asInt() : 0;

                sb.append("- **Total Tests**: ").append(total)
                        .append(" | **Passed**: ").append(passed)
                        .append(" | **Failed**: ").append(failed)
                        .append(" | **Skipped**: ").append(skipped).append("\n\n");
                return;
            }
        } catch (Exception ignored) {
        }
        sb.append("- **Total Tests**: 0 | **Passed**: 0 | **Failed**: 0 | **Skipped**: 0\n\n");
    }

    private static void appendSecurityReviewMetrics(StringBuilder sb, String securitySummaryJson) {
        try {
            if (securitySummaryJson != null && !securitySummaryJson.isBlank()) {
                JsonNode root = objectMapper.readTree(securitySummaryJson);
                if (root != null && root.isTextual()) {
                    root = objectMapper.readTree(root.asText());
                }
                JsonNode counts = (root != null) ? root.get("severityCounts") : null;
                int crit = (counts != null && counts.has("CRITICAL")) ? counts.get("CRITICAL").asInt() : 0;
                int high = (counts != null && counts.has("HIGH")) ? counts.get("HIGH").asInt() : 0;
                int med = (counts != null && counts.has("MEDIUM")) ? counts.get("MEDIUM").asInt() : 0;
                int low = (counts != null && counts.has("LOW")) ? counts.get("LOW").asInt() : 0;
                String certStatus = sanitizeProse((root != null && root.has("complianceCertificateStatus")) ? root.get("complianceCertificateStatus").asText() : "NONE");

                sb.append("- **Critical Findings**: ").append(crit)
                        .append(" | **High**: ").append(high)
                        .append(" | **Medium**: ").append(med)
                        .append(" | **Low**: ").append(low).append("\n");
                sb.append("- **Compliance Certificate Status**: `").append(certStatus).append("`\n\n");
                return;
            }
        } catch (Exception ignored) {
        }
        sb.append("- **Critical Findings**: 0 | **High**: 0 | **Medium**: 0 | **Low**: 0\n");
        sb.append("- **Compliance Certificate Status**: `NONE`\n\n");
    }

    public static String sanitizeProse(String text) {
        if (text == null) return "";
        // 1. Escaping HTML tags to prevent script injection
        String sanitized = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // 2. Reject/Neutralize event handler attributes (onerror=, onclick=, etc.)
        if (DANGEROUS_ATTR_PATTERN.matcher(sanitized).find()) {
            sanitized = DANGEROUS_ATTR_PATTERN.matcher(sanitized).replaceAll("blocked_attr=");
        }

        // 3. Reject/Neutralize dangerous link schemes (javascript:, data:, vbscript:)
        if (DANGEROUS_URL_PATTERN.matcher(sanitized).find()) {
            sanitized = DANGEROUS_URL_PATTERN.matcher(sanitized).replaceAll("blocked_scheme:");
        }
        return sanitized;
    }
}
