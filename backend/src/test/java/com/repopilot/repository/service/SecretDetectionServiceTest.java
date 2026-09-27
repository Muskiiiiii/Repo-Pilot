package com.repopilot.repository.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretDetectionServiceTest {

    private SecretDetectionService secretDetectionService;

    @BeforeEach
    void setUp() {
        secretDetectionService = new SecretDetectionService();
    }

    @Test
    void containsSecret_detectsAwsKey() {
        String content = "String awsKey = \"AKIAIOSFODNN7EXAMPLE\";";
        assertTrue(secretDetectionService.containsSecret(content));
    }

    @Test
    void containsSecret_detectsGithubPatToken() {
        String content = "github_pat_11AAAAAAA0123456789_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        assertTrue(secretDetectionService.containsSecret(content));
    }

    @Test
    void containsSecret_detectsPrivateKeyBlock() {
        String content = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----";
        assertTrue(secretDetectionService.containsSecret(content));
    }

    @Test
    void containsSecret_returnsFalseForNormalCode() {
        String content = "public class AuthService { public void login() { System.out.println(\"Hello\"); } }";
        assertFalse(secretDetectionService.containsSecret(content));
    }

    @Test
    void isSecretFilename_detectsEnvFile() {
        assertTrue(secretDetectionService.isSecretFilename(".env"));
        assertTrue(secretDetectionService.isSecretFilename(".env.production"));
        assertTrue(secretDetectionService.isSecretFilename("config/id_rsa"));
        assertTrue(secretDetectionService.isSecretFilename("ssl/server.key"));
    }

    @Test
    void isSecretFilename_returnsFalseForNormalFiles() {
        assertFalse(secretDetectionService.isSecretFilename("src/main/java/App.java"));
        assertFalse(secretDetectionService.isSecretFilename("package.json"));
    }
}
