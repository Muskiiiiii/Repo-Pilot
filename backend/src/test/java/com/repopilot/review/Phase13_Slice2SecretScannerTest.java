package com.repopilot.review;

import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class Phase13_Slice2SecretScannerTest {

    private PreLlmSecretScanner secretScanner;

    @BeforeEach
    void setUp() {
        secretScanner = new PreLlmSecretScannerImpl();
    }

    @Test
    @DisplayName("Secrets: Single GitHub PAT is redacted with [REDACTED_SECRET_PRE_LLM]")
    void testGithubPatRedacted() {
        String raw = "String token = \"ghp_123456789012345678901234567890abcdef\";";
        assertTrue(secretScanner.containsSecret(raw));
        String redacted = secretScanner.redactSecrets(raw);
        assertFalse(redacted.contains("ghp_123456789012345678901234567890abcdef"));
        assertTrue(redacted.contains("[REDACTED_SECRET_PRE_LLM]"));
    }

    @Test
    @DisplayName("Secrets: Multiple secrets in one diff are ALL redacted")
    void testMultipleSecretsInOneDiffRedacted() {
        String rawDiff = """
                --- src/Config.java
                +++ src/Config.java
                +String githubToken = "ghp_123456789012345678901234567890abcdef";
                +String awsKey = "AKIAIOSFODNN7EXAMPLE";
                +String slackToken = "xoxb-" + "123456789012-1234567890123-abcdefghijklmnopqrstuvwx";
                """;

        assertTrue(secretScanner.containsSecret(rawDiff));
        String redacted = secretScanner.redactSecrets(rawDiff);

        assertFalse(redacted.contains("ghp_123456789012345678901234567890abcdef"));
        assertFalse(redacted.contains("AKIAIOSFODNN7EXAMPLE"));
        assertFalse(redacted.contains("xoxb-" + "123456789012-1234567890123-abcdefghijklmnopqrstuvwx"));
        assertTrue(redacted.contains("[REDACTED_SECRET_PRE_LLM]"));
    }

    @Test
    @DisplayName("Secrets: Multiple detector types in one input (PAT + AWS Secret + RSA Key + JWT)")
    void testMultipleDetectorTypesInOneInput() {
        String input = "github: ghp_123456789012345678901234567890abcdef, aws: aws_secret_access_key = \"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\", rsa: -----BEGIN RSA PRIVATE KEY-----";
        assertTrue(secretScanner.containsSecret(input));
        String redacted = secretScanner.redactSecrets(input);

        assertFalse(redacted.contains("ghp_123456789012345678901234567890abcdef"));
        assertFalse(redacted.contains("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));
        assertFalse(redacted.contains("-----BEGIN RSA PRIVATE KEY-----"));
        assertTrue(redacted.contains("[REDACTED_SECRET_PRE_LLM]"));
    }

    @Test
    @DisplayName("Secrets: Secret in title or task description is redacted")
    void testSecretInTitleRedacted() {
        String rawTitle = "Fix bug with token ghp_123456789012345678901234567890abcdef in auth layer";
        assertTrue(secretScanner.containsSecret(rawTitle));
        String redacted = secretScanner.redactSecrets(rawTitle);
        assertFalse(redacted.contains("ghp_123456789012345678901234567890abcdef"));
        assertTrue(redacted.contains("[REDACTED_SECRET_PRE_LLM]"));
    }

    @Test
    @DisplayName("Secrets: Secret in filename or file content is redacted")
    void testSecretInFilenameContentRedacted() {
        String content = "Reading credentials from ghp_123456789012345678901234567890abcdef file";
        assertTrue(secretScanner.containsSecret(content));
        String redacted = secretScanner.redactSecrets(content);
        assertFalse(redacted.contains("ghp_123456789012345678901234567890abcdef"));
    }

    @Test
    @DisplayName("Secrets: Multiline private key block is completely redacted")
    void testMultilinePrivateKeyRedacted() {
        String multilineKey = """
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEA0M2d486...
                +MIIEowIBAAKCAQEA0M2d486...
                -----END RSA PRIVATE KEY-----
                """;
        assertTrue(secretScanner.containsSecret(multilineKey));
        String redacted = secretScanner.redactSecrets(multilineKey);
        assertFalse(redacted.contains("-----BEGIN RSA PRIVATE KEY-----"));
        assertFalse(redacted.contains("-----END RSA PRIVATE KEY-----"));
        assertTrue(redacted.contains("[REDACTED_SECRET_PRE_LLM]"));
    }

    @Test
    @DisplayName("Secrets: Punctuation surrounding secrets is handled properly")
    void testPunctuationSurroundingSecretsRedacted() {
        String raw = "Config(token=\"ghp_123456789012345678901234567890abcdef\", key='AKIAIOSFODNN7EXAMPLE');";
        assertTrue(secretScanner.containsSecret(raw));
        String redacted = secretScanner.redactSecrets(raw);
        assertFalse(redacted.contains("ghp_123456789012345678901234567890abcdef"));
        assertFalse(redacted.contains("AKIAIOSFODNN7EXAMPLE"));
        assertTrue(redacted.contains("[REDACTED_SECRET_PRE_LLM]"));
    }

    @Test
    @DisplayName("Secrets: Repeated secret occurrences are all redacted")
    void testRepeatedSecretOccurrencesRedacted() {
        String raw = "token1 = ghp_123456789012345678901234567890abcdef, token2 = ghp_123456789012345678901234567890abcdef";
        assertTrue(secretScanner.containsSecret(raw));
        String redacted = secretScanner.redactSecrets(raw);
        assertFalse(redacted.contains("ghp_123456789012345678901234567890abcdef"));
    }

    @Test
    @DisplayName("Secrets: Already-redacted content is preserved safely")
    void testAlreadyRedactedContentPreserved() {
        String alreadyRedacted = "Status: [REDACTED_SECRET_PRE_LLM] processed successfully";
        String redacted = secretScanner.redactSecrets(alreadyRedacted);
        assertEquals(alreadyRedacted, redacted);
    }

    @Test
    @DisplayName("Secrets: Unique canary secret leakage test across exception/failure_reason paths")
    void testUniqueCanarySecretLeakage() {
        String canarySecret = "ghp_CANARY_UNIQUE_TOKEN_99999999999";
        String rawFailure = "Task failed while using credential " + canarySecret;

        String redactedFailure = secretScanner.redactSecrets(rawFailure);
        assertFalse(redactedFailure.contains(canarySecret));
        assertTrue(redactedFailure.contains("[REDACTED_SECRET_PRE_LLM]"));

        Exception ex = new IllegalArgumentException("Invalid token provided: " + canarySecret);
        String redactedExceptionMsg = secretScanner.redactSecrets(ex.getMessage());
        assertFalse(redactedExceptionMsg.contains(canarySecret));
        assertTrue(redactedExceptionMsg.contains("[REDACTED_SECRET_PRE_LLM]"));
    }
}
