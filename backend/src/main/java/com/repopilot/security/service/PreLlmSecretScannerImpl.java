package com.repopilot.security.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defense-in-depth pre-LLM secret scanner.
 * IMPORTANT: Secret scanning is a defense-in-depth security measure and does NOT guarantee 100% secret detection.
 * High-confidence token detectors and heuristic credential detectors are kept conceptually distinct.
 */
@Service
public class PreLlmSecretScannerImpl implements PreLlmSecretScanner {

    private final List<SecretDetector> highConfidenceDetectors;
    private final List<SecretDetector> heuristicDetectors;
    private final List<SecretDetector> detectors;

    public PreLlmSecretScannerImpl() {
        List<SecretDetector> highConfidence = new ArrayList<>();
        // 1. High-Confidence Token & Key Detectors
        highConfidence.add(new RegexSecretDetector(Pattern.compile("gh[pousr]_[a-zA-Z0-9_]{30,90}"))); // GitHub PAT / OAuth
        highConfidence.add(new RegexSecretDetector(Pattern.compile("github_pat_[a-zA-Z0-9_]{30,90}"))); // GitHub Fine-grained PAT
        highConfidence.add(new RegexSecretDetector(Pattern.compile("AKIA[0-9A-Z]{16}"))); // AWS Access Key ID
        highConfidence.add(new RegexSecretDetector(Pattern.compile("ASIA[0-9A-Z]{16}"))); // AWS Temporary Access Key ID
        highConfidence.add(new RegexSecretDetector(Pattern.compile("-----BEGIN (?:RSA |EC |PGP |OPENSSH |DSA )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |PGP |OPENSSH |DSA )?PRIVATE KEY-----"))); // Multiline Private Key Block
        highConfidence.add(new RegexSecretDetector(Pattern.compile("-----BEGIN (?:RSA |EC |PGP |OPENSSH |DSA )?PRIVATE KEY-----"))); // Private Key Header Fallback
        highConfidence.add(new RegexSecretDetector(Pattern.compile("(?i)Bearer\\s+[a-zA-Z0-9_\\.~+\\-\\/=]+"))); // Bearer Token
        highConfidence.add(new RegexSecretDetector(Pattern.compile("sk-[a-zA-Z0-9]{32,}"))); // OpenAI API Key
        highConfidence.add(new RegexSecretDetector(Pattern.compile("pa-[a-zA-Z0-9]{32,}"))); // Voyage API Key
        highConfidence.add(new RegexSecretDetector(Pattern.compile("xox[baprs]-[a-zA-Z0-9_-]{10,90}"))); // Slack Token
        highConfidence.add(new RegexSecretDetector(Pattern.compile("eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+"))); // JWT Token

        List<SecretDetector> heuristic = new ArrayList<>();
        // 2. Heuristic Credential & Assignment Detectors
        heuristic.add(new RegexSecretDetector(Pattern.compile("(?i)(?:aws_secret_access_key|aws_secret_key)\\s*[:=]\\s*[\"']?[a-zA-Z0-9/+=]{40}[\"']?"))); // AWS Secret Key
        heuristic.add(new RegexSecretDetector(Pattern.compile("(?i)(?:api_key|apikey|secret_key|jwt_secret|password|token|access_token|private_key)\\s*[:=]\\s*[\"'][a-zA-Z0-9_\\-+=/]{8,}[\"']"))); // Hardcoded Secret Assignment

        this.highConfidenceDetectors = Collections.unmodifiableList(highConfidence);
        this.heuristicDetectors = Collections.unmodifiableList(heuristic);

        List<SecretDetector> combined = new ArrayList<>();
        combined.addAll(highConfidence);
        combined.addAll(heuristic);
        this.detectors = Collections.unmodifiableList(combined);
    }

    public PreLlmSecretScannerImpl(List<SecretDetector> customDetectors) {
        this.highConfidenceDetectors = Collections.unmodifiableList(new ArrayList<>(customDetectors));
        this.heuristicDetectors = Collections.emptyList();
        this.detectors = Collections.unmodifiableList(new ArrayList<>(customDetectors));
    }

    @Override
    public String redactSecrets(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String result = content;
        for (SecretDetector detector : detectors) {
            result = detector.redact(result, REDACTION_MARKER);
        }
        return result;
    }

    @Override
    public boolean containsSecret(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (SecretDetector detector : detectors) {
            if (detector.containsSecret(content)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<SecretDetector> getDetectors() {
        return detectors;
    }

    public List<SecretDetector> getHighConfidenceDetectors() {
        return highConfidenceDetectors;
    }

    public List<SecretDetector> getHeuristicDetectors() {
        return heuristicDetectors;
    }

    private static class RegexSecretDetector implements SecretDetector {
        private final Pattern pattern;

        public RegexSecretDetector(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean containsSecret(String content) {
            if (content == null) return false;
            return pattern.matcher(content).find();
        }

        @Override
        public String redact(String content, String replacementMarker) {
            if (content == null) return null;
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.replaceAll(replacementMarker);
            }
            return content;
        }
    }
}
