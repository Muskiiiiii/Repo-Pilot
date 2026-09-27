package com.repopilot.change.service;

import com.repopilot.security.service.SecretScannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiffGeneratorServiceTest {

    private DiffGeneratorService diffGeneratorService;
    private SecretScannerService secretScannerService;

    @BeforeEach
    void setUp() {
        diffGeneratorService = new DiffGeneratorService();
        secretScannerService = new SecretScannerService();
    }

    @Test
    void testComputeSha256Hash() {
        String hash1 = diffGeneratorService.computeSha256Hash("hello world");
        String hash2 = diffGeneratorService.computeSha256Hash("hello world");
        String hash3 = diffGeneratorService.computeSha256Hash("different text");

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
        assertNotEquals(hash1, hash3);
    }

    @Test
    void testGenerateUnifiedDiffAndValidation() {
        String oldContent = "public void vote() {\n    System.out.println(\"vote\");\n}";
        String newContent = "public void vote() {\n    if (invalid) return;\n    System.out.println(\"vote\");\n}";

        String diff = diffGeneratorService.generateUnifiedDiff("src/Vote.java", oldContent, newContent);

        assertNotNull(diff);
        assertTrue(diff.contains("--- a/src/Vote.java"));
        assertTrue(diff.contains("+++ b/src/Vote.java"));
        assertTrue(diff.contains("+    if (invalid) return;"));

        boolean isValid = diffGeneratorService.validateDiffApplication(oldContent, newContent, diff);
        assertTrue(isValid);
    }

    @Test
    void testSecretScannerDetection() {
        SecretScannerService.SecretScanResult res1 = secretScannerService.scanContent("const api_key = 'AKIA1234567890ABCDEF';");
        assertTrue(res1.hasSecret());
        assertTrue(res1.reason().contains("AKI"));

        SecretScannerService.SecretScanResult res2 = secretScannerService.scanContent("const token = 'ghp_1234567890abcdef1234567890abcdef1234';");
        assertTrue(res2.hasSecret());

        SecretScannerService.SecretScanResult res3 = secretScannerService.scanContent("function regularCode() { return 42; }");
        assertFalse(res3.hasSecret());
    }

    @Test
    void testDiffFallbackApplicability_UnderLimit() {
        String oldContent = "line1\nline2\nline3";
        String newContent = "line1\nline2_modified\nline3";
        String diff = diffGeneratorService.generateUnifiedDiff("file.txt", oldContent, newContent);
        assertTrue(diffGeneratorService.validateDiffApplication(oldContent, newContent, diff));
    }

    @Test
    void testDiffFallbackApplicability_OverLineLimit() {
        StringBuilder oldSb = new StringBuilder();
        StringBuilder newSb = new StringBuilder();
        // Generate 10005 lines (exceeding MAX_DIFF_LINES = 10000)
        for (int i = 0; i < 10005; i++) {
            oldSb.append("old_line_").append(i).append("\n");
            newSb.append("new_line_").append(i).append("\n");
        }
        String oldContent = oldSb.toString().trim();
        String newContent = newSb.toString().trim();

        String diff = diffGeneratorService.generateUnifiedDiff("large_lines.txt", oldContent, newContent);
        assertNotNull(diff);
        assertTrue(diffGeneratorService.validateDiffApplication(oldContent, newContent, diff),
                "Fallback diff over line limit MUST validate application successfully");
    }

    @Test
    void testDiffFallbackApplicability_OverCharLimit() {
        // Generate content exceeding MAX_DIFF_CHARS = 500000
        String longLineOld = "A".repeat(260000) + "\n" + "B".repeat(260000);
        String longLineNew = "X".repeat(260000) + "\n" + "Y".repeat(260000);

        String diff = diffGeneratorService.generateUnifiedDiff("large_chars.txt", longLineOld, longLineNew);
        assertNotNull(diff);
        assertTrue(diffGeneratorService.validateDiffApplication(longLineOld, longLineNew, diff),
                "Fallback diff over character limit MUST validate application successfully");
    }
}
