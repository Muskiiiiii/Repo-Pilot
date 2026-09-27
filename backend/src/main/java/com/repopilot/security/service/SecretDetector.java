package com.repopilot.security.service;

public interface SecretDetector {
    boolean containsSecret(String content);
    String redact(String content, String replacementMarker);
}
