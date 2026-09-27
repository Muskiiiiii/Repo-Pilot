package com.repopilot.repository.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileFilterServiceTest {

    private FileFilterService fileFilterService;

    @BeforeEach
    void setUp() {
        SecretDetectionService secretDetectionService = new SecretDetectionService();
        fileFilterService = new FileFilterService(secretDetectionService);
    }

    @Test
    void shouldIndexFile_acceptsValidSourceCode() {
        assertTrue(fileFilterService.shouldIndexFile("src/main/java/App.java", 1024, 1048576));
        assertTrue(fileFilterService.shouldIndexFile("app/main.py", 512, 1048576));
        assertTrue(fileFilterService.shouldIndexFile("package.json", 256, 1048576));
    }

    @Test
    void shouldIndexFile_rejectsExcludedDirectories() {
        assertFalse(fileFilterService.shouldIndexFile("node_modules/express/index.js", 1024, 1048576));
        assertFalse(fileFilterService.shouldIndexFile("target/classes/App.class", 1024, 1048576));
        assertFalse(fileFilterService.shouldIndexFile(".git/HEAD", 64, 1048576));
        assertFalse(fileFilterService.shouldIndexFile(".next/static/chunks/app.js", 1024, 1048576));
    }

    @Test
    void shouldIndexFile_rejectsExcludedExtensionsAndBinaries() {
        assertFalse(fileFilterService.shouldIndexFile("logo.png", 5000, 1048576));
        assertFalse(fileFilterService.shouldIndexFile("app.jar", 50000, 1048576));
        assertFalse(fileFilterService.shouldIndexFile("bundle.min.js", 5000, 1048576));
        assertFalse(fileFilterService.shouldIndexFile("package-lock.json", 5000, 1048576));
    }

    @Test
    void shouldIndexFile_rejectsSecretFiles() {
        assertFalse(fileFilterService.shouldIndexFile(".env", 100, 1048576));
        assertFalse(fileFilterService.shouldIndexFile("certs/server.pem", 100, 1048576));
    }

    @Test
    void shouldIndexFile_rejectsOversizedFile() {
        assertFalse(fileFilterService.shouldIndexFile("src/Large.java", 2000000, 1048576));
    }

    @Test
    void detectLanguage_identifiesCommonLanguages() {
        assertEquals("java", fileFilterService.detectLanguage("src/App.java"));
        assertEquals("python", fileFilterService.detectLanguage("main.py"));
        assertEquals("typescript", fileFilterService.detectLanguage("components/Header.tsx"));
        assertEquals("dockerfile", fileFilterService.detectLanguage("Dockerfile"));
    }
}
