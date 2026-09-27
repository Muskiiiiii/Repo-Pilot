package com.repopilot.review;

import com.repopilot.review.dto.AiReviewPayload;
import com.repopilot.review.service.AiInputHashService;
import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class Phase13_Slice2AiPayloadHashTest {

    private AiInputHashService aiInputHashService;
    private PreLlmSecretScanner preLlmSecretScanner;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID codeChangeId;

    @BeforeEach
    void setUp() {
        aiInputHashService = new AiInputHashService();
        preLlmSecretScanner = new PreLlmSecretScannerImpl();

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        codeChangeId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Hashing: Deterministic same-input produces identical SHA-256 hash")
    void testDeterministicSameInputHash() {
        AiReviewPayload payload1 = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                "CODE_CHANGE_REVIEW", "diff content", "Main.java",
                List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro"
        );

        AiReviewPayload payload2 = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                "CODE_CHANGE_REVIEW", "diff content", "Main.java",
                List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro"
        );

        String hash1 = aiInputHashService.computeAiInputHash(payload1);
        String hash2 = aiInputHashService.computeAiInputHash(payload2);

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Hashing: Canonical fileList ordering and duplicate removal produces identical hash")
    void testCanonicalFileListOrderingAndDuplicates() {
        AiReviewPayload payloadUnordered = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                "CODE_CHANGE_REVIEW", "diff content", "Title",
                List.of("src/Util.java", "src/Main.java", "src/Main.java"), "p2026.1", "gemini-2.5-pro"
        );

        AiReviewPayload payloadOrdered = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                "CODE_CHANGE_REVIEW", "diff content", "Title",
                List.of("src/Main.java", "src/Util.java"), "p2026.1", "gemini-2.5-pro"
        );

        String hash1 = aiInputHashService.computeAiInputHash(payloadUnordered);
        String hash2 = aiInputHashService.computeAiInputHash(payloadOrdered);

        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Hashing: Null/empty values and Unicode characters are handled deterministically")
    void testNullEmptyUnicodeHandling() {
        AiReviewPayload payloadUnicode = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                "CODE_CHANGE_REVIEW", "diff with Unicode unicodë_tëst_µ¶", null,
                null, null, null
        );

        String hash1 = aiInputHashService.computeAiInputHash(payloadUnicode);
        String hash2 = aiInputHashService.computeAiInputHash(payloadUnicode);

        assertNotNull(hash1);
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Hashing: Every material payload-field mutation changes ai_input_hash")
    void testEveryMaterialPayloadFieldMutationChangesHash() {
        AiReviewPayload base = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                "CODE_CHANGE_REVIEW", "diff content", "Title",
                List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro"
        );
        String baseHash = aiInputHashService.computeAiInputHash(base);

        // 1. projectId changed
        AiReviewPayload mut1 = new AiReviewPayload(UUID.randomUUID(), repositoryId, taskId, codeChangeId, "CODE_CHANGE_REVIEW", "diff content", "Title", List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut1));

        // 2. repositoryId changed
        AiReviewPayload mut2 = new AiReviewPayload(projectId, UUID.randomUUID(), taskId, codeChangeId, "CODE_CHANGE_REVIEW", "diff content", "Title", List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut2));

        // 3. taskId changed
        AiReviewPayload mut3 = new AiReviewPayload(projectId, repositoryId, UUID.randomUUID(), codeChangeId, "CODE_CHANGE_REVIEW", "diff content", "Title", List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut3));

        // 4. codeChangeId changed
        AiReviewPayload mut4 = new AiReviewPayload(projectId, repositoryId, taskId, UUID.randomUUID(), "CODE_CHANGE_REVIEW", "diff content", "Title", List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut4));

        // 5. reviewMode changed
        AiReviewPayload mut5 = new AiReviewPayload(projectId, repositoryId, taskId, codeChangeId, "SECURITY_REVIEW", "diff content", "Title", List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut5));

        // 6. redactedDiff changed
        AiReviewPayload mut6 = new AiReviewPayload(projectId, repositoryId, taskId, codeChangeId, "CODE_CHANGE_REVIEW", "diff content modified", "Title", List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut6));

        // 7. sanitizedTitle changed
        AiReviewPayload mut7 = new AiReviewPayload(projectId, repositoryId, taskId, codeChangeId, "CODE_CHANGE_REVIEW", "diff content", "Modified Title", List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut7));

        // 8. fileList changed
        AiReviewPayload mut8 = new AiReviewPayload(projectId, repositoryId, taskId, codeChangeId, "CODE_CHANGE_REVIEW", "diff content", "Title", List.of("src/Main.java", "src/Other.java"), "p2026.1", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut8));

        // 9. promptVersion changed
        AiReviewPayload mut9 = new AiReviewPayload(projectId, repositoryId, taskId, codeChangeId, "CODE_CHANGE_REVIEW", "diff content", "Title", List.of("src/Main.java"), "p2026.2", "gemini-2.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut9));

        // 10. modelIdentifier changed
        AiReviewPayload mut10 = new AiReviewPayload(projectId, repositoryId, taskId, codeChangeId, "CODE_CHANGE_REVIEW", "diff content", "Title", List.of("src/Main.java"), "p2026.1", "gemini-1.5-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(mut10));
    }

    @Test
    @DisplayName("Hashing: Raw secret vs different raw secret produces identical redacted payload and identical ai_input_hash")
    void testRawVsRedactedSecretHashIdentity() {
        String rawDiff1 = "String token = \"ghp_111111111111111111111111111111111111\";";
        String rawDiff2 = "String token = \"ghp_999999999999999999999999999999999999\";";

        String redactedDiff1 = preLlmSecretScanner.redactSecrets(rawDiff1);
        String redactedDiff2 = preLlmSecretScanner.redactSecrets(rawDiff2);

        assertEquals(redactedDiff1, redactedDiff2);
        assertTrue(redactedDiff1.contains("[REDACTED_SECRET_PRE_LLM]"));

        AiReviewPayload payload1 = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                "CODE_CHANGE_REVIEW", redactedDiff1, "Main.java",
                List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro"
        );

        AiReviewPayload payload2 = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                "CODE_CHANGE_REVIEW", redactedDiff2, "Main.java",
                List.of("src/Main.java"), "p2026.1", "gemini-2.5-pro"
        );

        String hash1 = aiInputHashService.computeAiInputHash(payload1);
        String hash2 = aiInputHashService.computeAiInputHash(payload2);

        assertEquals(hash1, hash2);
    }
}
