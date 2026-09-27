package com.repopilot.repository.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class SecretDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(SecretDetectionService.class);

    private final List<Pattern> secretPatterns = new ArrayList<>();
    private final List<Pattern> secretFilePatterns = new ArrayList<>();

    public SecretDetectionService() {
        // AWS Access Key ID
        secretPatterns.add(Pattern.compile("AKIA[0-9A-Z]{16}"));
        // GitHub Personal Access Token / OAuth Token
        secretPatterns.add(Pattern.compile("ghp_[a-zA-Z0-9]{36}"));
        secretPatterns.add(Pattern.compile("gho_[a-zA-Z0-9]{36}"));
        secretPatterns.add(Pattern.compile("github_pat_[a-zA-Z0-9_]{50,}"));
        // Private Keys
        secretPatterns.add(Pattern.compile("-----BEGIN (?:RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"));
        // Generic Password/Secret Assignments with explicit values
        secretPatterns.add(Pattern.compile("(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|password|passwd|private[_-]?key)\\s*[:=]\\s*[\"'][a-zA-Z0-9_\\-~!@#$%^&*()+=]{8,}[\"']"));
        // Slack Webhook URL
        secretPatterns.add(Pattern.compile("https://hooks\\.slack\\.com/services/T[a-zA-Z0-9_]+/B[a-zA-Z0-9_]+/[a-zA-Z0-9_]+"));

        // Secret Filenames / Extensions
        secretFilePatterns.add(Pattern.compile("(?i).*\\.env(\\..*)?"));
        secretFilePatterns.add(Pattern.compile("(?i).*\\.pem$"));
        secretFilePatterns.add(Pattern.compile("(?i).*\\.key$"));
        secretFilePatterns.add(Pattern.compile("(?i).*\\.p12$"));
        secretFilePatterns.add(Pattern.compile("(?i).*\\.jks$"));
        secretFilePatterns.add(Pattern.compile("(?i).*/?id_rsa.*"));
        secretFilePatterns.add(Pattern.compile("(?i).*/?credentials$"));
    }

    public boolean isSecretFilename(String path) {
        if (path == null) {
            return false;
        }
        for (Pattern p : secretFilePatterns) {
            if (p.matcher(path).matches()) {
                logger.warn("Secret Detection: Path '{}' matched secret file pattern", path);
                return true;
            }
        }
        return false;
    }

    public boolean containsSecret(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (Pattern p : secretPatterns) {
            if (p.matcher(content).find()) {
                // NEVER log the content or secret value
                logger.warn("Secret Detection: Sensitive credential pattern detected in chunk content (redacted)");
                return true;
            }
        }
        return false;
    }
}
