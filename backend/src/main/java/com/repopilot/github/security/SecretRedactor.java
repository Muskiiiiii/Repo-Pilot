package com.repopilot.github.security;

import java.util.Locale;
import java.util.regex.Pattern;

public class SecretRedactor {

    private static final String REDACTED = "[REDACTED_SECRET]";
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[a-zA-Z0-9_.~+-]+");
    private static final Pattern GITHUB_TOKEN_PATTERN = Pattern.compile("(?i)(ghp|gho|ghu|ghs|ghr|github_pat)_[a-zA-Z0-9_]{16,255}");
    private static final Pattern AUTHORIZATION_HEADER_PATTERN = Pattern.compile("(?i)Authorization:\\s*[^\\r\\n]+");

    private static final Pattern AWS_KEY_PATTERN = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern AWS_SECRET_PATTERN = Pattern.compile("(?i)(aws_secret_access_key|aws_secret|secret_key)\\s*[:=]\\s*[\"']?[A-Za-z0-9/+=_-]{16,64}[\"']?");
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile("(?s)-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----.*?-----END (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern GENERIC_SECRET_PATTERN = Pattern.compile("(?i)(\"?(?:password|token|secret|api_key|access_key)\"?\\s*[:=]\\s*\")[^\"]+(\")");
    private static final Pattern LLM_KEY_PATTERN = Pattern.compile("sk-[a-zA-Z0-9_-]{16,}");
    private static final Pattern DB_URL_PATTERN = Pattern.compile("(postgres|postgresql|mysql|mongodb|redis)://[^\\s\"']+");

    private SecretRedactor() {
    }

    public static String redact(String text, String secret) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        if (secret != null && !secret.isEmpty() && secret.length() >= 4) {
            result = result.replace(secret, REDACTED);
        }
        return sanitizeLogs(result);
    }

    public static String redact(String text, char[] secretChars) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (secretChars == null || secretChars.length < 4) {
            return redact(text, (String) null);
        }
        return redact(text, new String(secretChars));
    }

    public static String sanitizeLogs(String logMessage) {
        if (logMessage == null || logMessage.isEmpty()) {
            return logMessage;
        }
        String sanitized = BEARER_PATTERN.matcher(logMessage).replaceAll("Bearer " + REDACTED);
        sanitized = GITHUB_TOKEN_PATTERN.matcher(sanitized).replaceAll(REDACTED);
        sanitized = AUTHORIZATION_HEADER_PATTERN.matcher(sanitized).replaceAll("Authorization: " + REDACTED);
        sanitized = PRIVATE_KEY_PATTERN.matcher(sanitized).replaceAll("[REDACTED_PRIVATE_KEY]");
        sanitized = AWS_KEY_PATTERN.matcher(sanitized).replaceAll("[REDACTED_AWS_KEY]");
        sanitized = AWS_SECRET_PATTERN.matcher(sanitized).replaceAll("$1 = [REDACTED_AWS_SECRET]");
        sanitized = GENERIC_SECRET_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED_SECRET]$2");
        sanitized = LLM_KEY_PATTERN.matcher(sanitized).replaceAll("[REDACTED_LLM_KEY]");
        sanitized = DB_URL_PATTERN.matcher(sanitized).replaceAll("$1://[REDACTED_DB_CREDENTIALS]");
        return sanitized;
    }
}
