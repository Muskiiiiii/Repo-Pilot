package com.repopilot.security.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SecretScannerService {

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"), // AWS Access Key ID
            Pattern.compile("(?i)(?:aws_secret_access_key|aws_secret_key)\\s*[:=]\\s*[\"']?[a-zA-Z0-9/+=]{40}[\"']?"), // AWS Secret Access Key
            Pattern.compile("gh[pousr]_[a-zA-Z0-9]{30,40}"), // GitHub Personal/OAuth Tokens
            Pattern.compile("github_pat_[a-zA-Z0-9_]{30,90}"), // GitHub Fine-Grained PAT
            Pattern.compile("xox[baprs]-[a-zA-Z0-9_-]{10,90}"), // Slack Tokens
            Pattern.compile("(?:sk|rk)_live_[a-zA-Z0-9]{24,}"), // Stripe Live Secret Keys
            Pattern.compile("eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+"), // JWT Token
            Pattern.compile("sk-[a-zA-Z0-9]{32,}"), // OpenAI API Key
            Pattern.compile("pa-[a-zA-Z0-9]{32,}"), // Voyage API Key
            Pattern.compile("-----BEGIN (?:RSA |EC |PGP |OPENSSH )?PRIVATE KEY-----"), // Private Key Header
            Pattern.compile("(?i)(?:api_key|apikey|secret_key|jwt_secret|password|token|access_token|private_key)\\s*[:=]\\s*[\"'][a-zA-Z0-9_\\-+=/]{8,}[\"']") // Generic Hardcoded Secret Assignment
    );

    public SecretScanResult scanContent(String content) {
        if (content == null || content.isBlank()) {
            return new SecretScanResult(false, null);
        }

        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                String matchSample = matcher.group();
                String maskedMatch = matchSample.length() > 6
                        ? matchSample.substring(0, 3) + "***" + matchSample.substring(matchSample.length() - 3)
                        : "***";
                return new SecretScanResult(true, "Potential secret detected matching pattern: " + maskedMatch);
            }
        }

        return new SecretScanResult(false, null);
    }

    public String redactSecrets(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        String redacted = content;
        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher matcher = pattern.matcher(redacted);
            if (matcher.find()) {
                redacted = matcher.replaceAll("[REDACTED_SECRET]");
            }
        }
        return redacted;
    }

    public record SecretScanResult(boolean hasSecret, String reason) {}
}
