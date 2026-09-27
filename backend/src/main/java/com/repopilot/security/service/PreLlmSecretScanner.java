package com.repopilot.security.service;

import java.util.List;

public interface PreLlmSecretScanner {

    String REDACTION_MARKER = "[REDACTED_SECRET_PRE_LLM]";

    String redactSecrets(String content);

    boolean containsSecret(String content);

    List<SecretDetector> getDetectors();
}
