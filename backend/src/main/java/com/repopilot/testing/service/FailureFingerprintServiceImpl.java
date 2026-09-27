package com.repopilot.testing.service;

import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.policy.FailureFingerprintResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FailureFingerprintServiceImpl implements FailureFingerprintService {

    @Override
    public String generateFingerprint(Ecosystem ecosystem, FailureClassification classification, ParsedTestEvidence evidence, String stdout, String stderr) {
        String canonicalRepresentation = buildCanonicalRepresentation(ecosystem, classification, evidence, stdout, stderr);
        return sha256Hex(canonicalRepresentation);
    }

    @Override
    public FailureFingerprintResult analyzeFingerprintSequence(Ecosystem ecosystem, FailureClassification classification, ParsedTestEvidence evidence, String stdout, String stderr, List<String> previousFingerprints) {
        String fingerprint = generateFingerprint(ecosystem, classification, evidence, stdout, stderr);
        FailureType category = classification != null ? classification.getFailureCategory() : FailureType.UNKNOWN;
        String parserStatus = evidence != null ? evidence.getParserStatus() : "UNPARSED";

        List<String> history = previousFingerprints != null ? List.copyOf(previousFingerprints) : Collections.emptyList();
        boolean isRepeated = false;
        boolean isOscillating = false;

        if (!history.isEmpty()) {
            String lastFingerprint = history.get(history.size() - 1);
            if (fingerprint.equals(lastFingerprint) || history.contains(fingerprint)) {
                isRepeated = true;
            }
            if (history.contains(fingerprint) && !fingerprint.equals(lastFingerprint)) {
                isOscillating = true;
            }
        }

        String normalizedIdentity = extractNormalizedIdentity(ecosystem, classification, evidence, stdout, stderr);
        return new FailureFingerprintResult(fingerprint, category, parserStatus, normalizedIdentity, isRepeated, isOscillating, history);
    }

    @Override
    public String sanitizeEvidence(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }

        String s = rawContent.replace("\r\n", "\n");

        // 1. Secrets
        s = s.replaceAll("AKIA[0-9A-Z]{16}", "[REDACTED_AWS_KEY]");
        s = s.replaceAll("gh[pousr]_[a-zA-Z0-9]{30,40}", "[REDACTED_GITHUB_TOKEN]");
        s = s.replaceAll("github_pat_[a-zA-Z0-9_]{30,90}", "[REDACTED_GITHUB_TOKEN]");
        s = s.replaceAll("sk-[a-zA-Z0-9_-]{20,}", "[REDACTED_LLM_KEY]");
        s = s.replaceAll("vy-[a-zA-Z0-9_-]{20,}", "[REDACTED_VOYAGE_KEY]");
        s = s.replaceAll("(postgres|postgresql|mysql|mongodb|redis)://[^\\s\"']+", "$1://[REDACTED_DB_CREDENTIALS]");
        s = s.replaceAll("(?s)-----BEGIN (?:RSA |EC |PGP |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |PGP |OPENSSH )?PRIVATE KEY-----", "[REDACTED_PRIVATE_KEY]");
        s = s.replaceAll("-----BEGIN (?:RSA |EC |PGP |OPENSSH )?PRIVATE KEY-----", "[REDACTED_PRIVATE_KEY]");

        // 2. Volatile Host / Temp Workspace Paths
        s = s.replaceAll("/var/folders/[^\\s\"':]+/workspaces/[^\\s\"':]+", "/workspace/repo");
        s = s.replaceAll("/tmp/junit[0-9]+[^\\s\"':]+", "/workspace/repo");
        s = s.replaceAll("/tmp/repopilot_workspaces/[^\\s\"':]+", "/workspace/repo");
        s = s.replaceAll("[A-Z]:\\\\(?i)[^\\s\"':]+\\\\workspaces\\\\[^\\s\"':]+", "/workspace/repo");

        // 3. Volatile Identifiers (Container IDs, Timestamps, Memory addresses)
        s = s.replaceAll("repopilot_exec_[a-f0-9-]+", "repopilot_exec_[CONTAINER_ID]");
        s = s.replaceAll("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b", "[UUID]");
        s = s.replaceAll("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z?\\b", "[TIMESTAMP]");
        s = s.replaceAll("\\b0x[0-9a-fA-F]+\\b", "[MEM_ADDR]");

        return s.trim();
    }

    private String buildCanonicalRepresentation(Ecosystem ecosystem, FailureClassification classification, ParsedTestEvidence evidence, String stdout, String stderr) {
        FailureType category = classification != null ? classification.getFailureCategory() : FailureType.UNKNOWN;
        String parserStatus = evidence != null ? evidence.getParserStatus() : "UNPARSED";

        String sanitizedStdout = sanitizeEvidence(stdout);
        String sanitizedStderr = sanitizeEvidence(stderr);
        String combinedLog = (sanitizedStdout + "\n" + sanitizedStderr).toLowerCase();

        // 1. Unparsed / Ambiguous / Unknown
        if (!"PARSED".equalsIgnoreCase(parserStatus) && (category == FailureType.UNKNOWN || classification == null)) {
            return "AMBIGUOUS_FAILURE_UNPARSED_EVIDENCE";
        }

        // 2. PASS
        if (category == FailureType.PASS || (evidence != null && "PARSED".equalsIgnoreCase(parserStatus) && evidence.getFailuresCount() == 0 && evidence.getErrorsCount() == 0 && category == FailureType.PASS)) {
            return "PASS:CLEAN_EXIT_0";
        }

        // 3. Non-Repairable Infrastructure / Container / Network / Resource limit
        if (category == FailureType.TIMEOUT || category == FailureType.OOM || category == FailureType.RESOURCE_LIMIT ||
            category == FailureType.EXECUTION_INFRA_FAILURE || category == FailureType.DEPENDENCY_UNAVAILABLE || category == FailureType.NETWORK_REQUIRED) {
            return "INFRASTRUCTURE_FAILURE:" + category.name();
        }

        // 4. Parsed Test Failures
        if ("PARSED".equalsIgnoreCase(parserStatus) && evidence != null && (evidence.getFailuresCount() > 0 || evidence.getErrorsCount() > 0)) {
            List<String> testIdentities = extractTestFailureIdentities(evidence, sanitizedStdout, sanitizedStderr);
            Collections.sort(testIdentities);
            String ecoStr = ecosystem != null ? ecosystem.name() : "PYTHON";
            return "TEST_FAILURE:" + ecoStr + ":" + String.join(";", testIdentities);
        }

        // 5. Compilation Failure
        if (category == FailureType.COMPILATION_FAILURE) {
            String ecoStr = ecosystem != null ? ecosystem.name() : "JAVA";
            String fileLoc = extractCompilationFileLocation(combinedLog);
            return "COMPILATION_FAILURE:" + ecoStr + ":" + fileLoc;
        }

        // 6. Code Runtime Exception
        if (category == FailureType.CODE_FAILURE) {
            String ecoStr = ecosystem != null ? ecosystem.name() : "PYTHON";
            String excIdentity = extractCodeFailureIdentity(combinedLog);
            return "CODE_FAILURE:" + ecoStr + ":" + excIdentity;
        }

        // 7. Generic Fallback for unparsed error text
        if (combinedLog.contains("error") || combinedLog.contains("failed") || combinedLog.contains("exception")) {
            return "AMBIGUOUS_FAILURE_UNPARSED_EVIDENCE";
        }

        return "AMBIGUOUS_FAILURE_UNPARSED_EVIDENCE";
    }

    private String extractNormalizedIdentity(Ecosystem ecosystem, FailureClassification classification, ParsedTestEvidence evidence, String stdout, String stderr) {
        return buildCanonicalRepresentation(ecosystem, classification, evidence, stdout, stderr);
    }

    private List<String> extractTestFailureIdentities(ParsedTestEvidence evidence, String stdout, String stderr) {
        List<String> list = new ArrayList<>();
        if (evidence.getMetadata() != null && evidence.getMetadata().containsKey("failingTests")) {
            Object obj = evidence.getMetadata().get("failingTests");
            if (obj instanceof List<?> l) {
                for (Object item : l) {
                    if (item != null) list.add(item.toString().trim());
                }
            }
        }

        if (list.isEmpty()) {
            // Pattern match in stdout/stderr for test failures (e.g. test_calc.py:test_divide_by_zero)
            String combined = (stdout + "\n" + stderr);
            Pattern pattern = Pattern.compile("(test_[a-zA-Z0-9_]+\\.py::[a-zA-Z0-9_]+|AssertionError:[^\\n]+|[a-zA-Z0-9_]+Test > [a-zA-Z0-9_]+\\(\\))");
            Matcher matcher = pattern.matcher(combined);
            while (matcher.find()) {
                list.add(matcher.group().trim());
            }
        }

        if (list.isEmpty()) {
            list.add("generic_test_failure");
        }
        return list;
    }

    private String extractCompilationFileLocation(String combinedLog) {
        Pattern pattern = Pattern.compile("([a-zA-Z0-9_\\-/]+\\.(java|py|go|rs|ts|js))");
        Matcher matcher = pattern.matcher(combinedLog);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown_file";
    }

    private String extractCodeFailureIdentity(String combinedLog) {
        if (combinedLog.contains("zerodivisionerror")) return "ZeroDivisionError";
        if (combinedLog.contains("nullpointerexception")) return "NullPointerException";
        if (combinedLog.contains("indexoutofboundsexception")) return "IndexOutOfBoundsException";
        if (combinedLog.contains("keyerror")) return "KeyError";
        if (combinedLog.contains("typeerror")) return "TypeError";
        if (combinedLog.contains("valueerror")) return "ValueError";

        Pattern pattern = Pattern.compile("([a-zA-Z0-9_]+exception|[a-zA-Z0-9_]+error)");
        Matcher matcher = pattern.matcher(combinedLog);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "UncaughtException";
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
