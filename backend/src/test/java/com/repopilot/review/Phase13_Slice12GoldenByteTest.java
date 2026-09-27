package com.repopilot.review;

import com.repopilot.review.dto.CanonicalFindingInput;
import com.repopilot.review.dto.SecurityPolicyComplianceCertificateDto;
import com.repopilot.review.entity.PolicyComplianceStatus;
import com.repopilot.review.service.CanonicalCertificateHashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class Phase13_Slice12GoldenByteTest {

    private CanonicalCertificateHashService hashService;

    @BeforeEach
    void setUp() {
        hashService = new CanonicalCertificateHashService();
    }

    // =========================================================================
    // TEST 1 — SPECIAL CHARACTERS
    // =========================================================================
    @Test
    @DisplayName("Test 1: Special Characters canonical JSON, UTF-8 bytes, and hardcoded SHA-256 vector")
    void test1_SpecialCharactersGoldenVector() {
        UUID findingId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CanonicalFindingInput finding = new CanonicalFindingInput(
                findingId,
                "sec:col,val\"quote\\slash\nline\rret\ttab\u0001ctrl",
                "ch_spec_123",
                "CWE-79",
                "Desc with \"quotes\" & \\slashes\\",
                10,
                "src/App.java",
                "AI_FINDING",
                "fp_spec_123",
                "HIGH",
                "PROPOSED",
                1,
                "OPEN",
                "Special \"Chars\" Title"
        );

        String canonicalJson = hashService.canonicalizeFindings(List.of(finding));
        byte[] actualBytes = hashService.getCanonicalFindingBytes(List.of(finding));
        String actualSha256 = hashService.hashFindingEvidence(List.of(finding));

        String expectedJson = "[{\"category\":\"sec:col,val\\\"quote\\\\slash\\nline\\rret\\ttab\\u0001ctrl\",\"contentHash\":\"ch_spec_123\",\"cweId\":\"CWE-79\",\"description\":\"Desc with \\\"quotes\\\" & \\\\slashes\\\\\",\"endLine\":10,\"filePath\":\"src/App.java\",\"findingType\":\"AI_FINDING\",\"fingerprint\":\"fp_spec_123\",\"id\":\"11111111-1111-1111-1111-111111111111\",\"severity\":\"HIGH\",\"side\":\"PROPOSED\",\"startLine\":1,\"status\":\"OPEN\",\"title\":\"Special \\\"Chars\\\" Title\"}]";
        byte[] expectedBytes = expectedJson.getBytes(StandardCharsets.UTF_8);
        String expectedSha256 = "1c497783cffee317200cca97ba11198cd800d4aaf22e747bc87cb87acbc03cc3";

        assertEquals(expectedJson, canonicalJson, "Canonical JSON string must match hardcoded golden string");
        assertArrayEquals(expectedBytes, actualBytes, "UTF-8 bytes must match hardcoded byte expectation");
        assertEquals(expectedSha256, actualSha256, "SHA-256 hex must match hardcoded golden vector");
    }

    // =========================================================================
    // TEST 2 — UNICODE / EMOJI
    // =========================================================================
    @Test
    @DisplayName("Test 2: Unicode & Emoji canonical JSON, UTF-8 bytes, and hardcoded SHA-256 vector")
    void test2_UnicodeAndEmojiGoldenVector() {
        UUID findingId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        CanonicalFindingInput finding = new CanonicalFindingInput(
                findingId,
                "category_é_日本語",
                "ch_uni_456",
                "CWE-200",
                "Unicode description with emoji 🔒 and 🛡️",
                20,
                "src/Security.java",
                "AI_FINDING",
                "fp_uni_456",
                "CRITICAL",
                "PROPOSED",
                5,
                "OPEN",
                "Security Check 日本語"
        );

        String canonicalJson = hashService.canonicalizeFindings(List.of(finding));
        byte[] actualBytes = hashService.getCanonicalFindingBytes(List.of(finding));
        String actualSha256 = hashService.hashFindingEvidence(List.of(finding));

        String expectedJson = "[{\"category\":\"category_é_日本語\",\"contentHash\":\"ch_uni_456\",\"cweId\":\"CWE-200\",\"description\":\"Unicode description with emoji 🔒 and 🛡️\",\"endLine\":20,\"filePath\":\"src/Security.java\",\"findingType\":\"AI_FINDING\",\"fingerprint\":\"fp_uni_456\",\"id\":\"22222222-2222-2222-2222-222222222222\",\"severity\":\"CRITICAL\",\"side\":\"PROPOSED\",\"startLine\":5,\"status\":\"OPEN\",\"title\":\"Security Check 日本語\"}]";
        byte[] expectedBytes = expectedJson.getBytes(StandardCharsets.UTF_8);
        String expectedSha256 = "b32698f32016ab761fdb5b68f7c98647e9c8a38fdb4dadeb3feabd9cf5f2fdbf";

        assertEquals(expectedJson, canonicalJson);
        assertArrayEquals(expectedBytes, actualBytes);
        assertEquals(expectedSha256, actualSha256);
    }

    // =========================================================================
    // TEST 3 — WHITESPACE + NULL
    // =========================================================================
    @Test
    @DisplayName("Test 3: Whitespace preservation vs explicit null serialization")
    void test3_WhitespaceAndNullSemantics() {
        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");

        // 1. Finding with leading/trailing spaces and explicit null cweId
        CanonicalFindingInput f1 = new CanonicalFindingInput(
                id, "  cat_with_spaces  ", "ch1", null, "  desc  ", 1, "path", "AI", "fp", "LOW", "SIDE", 1, "OPEN", ""
        );

        String json1 = hashService.canonicalizeFindings(List.of(f1));
        assertTrue(json1.contains("\"category\":\"  cat_with_spaces  \""), "String whitespace must be preserved");
        assertTrue(json1.contains("\"cweId\":null"), "Null cweId must be explicitly serialized as null");
        assertTrue(json1.contains("\"title\":\"\""), "Empty string title must be serialized as empty quotes");

        // 2. Finding with empty string category vs null category
        CanonicalFindingInput fNullCat = new CanonicalFindingInput(
                id, null, "ch1", "CWE-1", "desc", 1, "path", "AI", "fp", "LOW", "SIDE", 1, "OPEN", "title"
        );
        CanonicalFindingInput fEmptyCat = new CanonicalFindingInput(
                id, "", "ch1", "CWE-1", "desc", 1, "path", "AI", "fp", "LOW", "SIDE", 1, "OPEN", "title"
        );

        String jsonNull = hashService.canonicalizeFindings(List.of(fNullCat));
        String jsonEmpty = hashService.canonicalizeFindings(List.of(fEmptyCat));

        assertNotEquals(jsonNull, jsonEmpty, "Canonical JSON for null must NOT equal empty string");
        assertNotEquals(hashService.hashFindingEvidence(List.of(fNullCat)), hashService.hashFindingEvidence(List.of(fEmptyCat)), "Hashes MUST differ when null becomes empty string");
    }

    // =========================================================================
    // TEST 4 — FINDING ORDER INDEPENDENCE & MUTATION SENSITIVITY
    // =========================================================================
    @Test
    @DisplayName("Test 4: Finding sorting by UUID guarantees input order independence")
    void test4_FindingOrderIndependenceAndMutationSensitivity() {
        UUID idA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID idB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID idC = UUID.fromString("00000000-0000-0000-0000-00000000000c");

        CanonicalFindingInput fA = new CanonicalFindingInput(idA, "catA", "chA", "CWE-1", "descA", 10, "A.java", "AI", "fpA", "HIGH", "PROPOSED", 1, "OPEN", "TitleA");
        CanonicalFindingInput fB = new CanonicalFindingInput(idB, "catB", "chB", "CWE-2", "descB", 20, "B.java", "AI", "fpB", "MEDIUM", "PROPOSED", 5, "OPEN", "TitleB");
        CanonicalFindingInput fC = new CanonicalFindingInput(idC, "catC", "chC", "CWE-3", "descC", 30, "C.java", "AI", "fpC", "LOW", "PROPOSED", 10, "OPEN", "TitleC");

        // Order 1: A, B, C
        List<CanonicalFindingInput> list1 = List.of(fA, fB, fC);
        // Order 2: C, A, B
        List<CanonicalFindingInput> list2 = List.of(fC, fA, fB);
        // Order 3: B, C, A
        List<CanonicalFindingInput> list3 = List.of(fB, fC, fA);

        String json1 = hashService.canonicalizeFindings(list1);
        String json2 = hashService.canonicalizeFindings(list2);
        String json3 = hashService.canonicalizeFindings(list3);

        assertEquals(json1, json2, "Canonical JSON must be identical regardless of input list order");
        assertEquals(json1, json3, "Canonical JSON must be identical regardless of input list order");
        assertEquals(hashService.hashFindingEvidence(list1), hashService.hashFindingEvidence(list2));

        // Test field mutations alter findingEvidenceHash
        String baseHash = hashService.hashFindingEvidence(list1);

        // Mutate severity
        CanonicalFindingInput fA_sev = new CanonicalFindingInput(idA, "catA", "chA", "CWE-1", "descA", 10, "A.java", "AI", "fpA", "CRITICAL", "PROPOSED", 1, "OPEN", "TitleA");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(fA_sev, fB, fC)), "Mutating severity MUST change hash");

        // Mutate status
        CanonicalFindingInput fA_status = new CanonicalFindingInput(idA, "catA", "chA", "CWE-1", "descA", 10, "A.java", "AI", "fpA", "HIGH", "PROPOSED", 1, "RESOLVED", "TitleA");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(fA_status, fB, fC)), "Mutating status MUST change hash");

        // Mutate fingerprint
        CanonicalFindingInput fA_fp = new CanonicalFindingInput(idA, "catA", "chA", "CWE-1", "descA", 10, "A.java", "AI", "fpA_mod", "HIGH", "PROPOSED", 1, "OPEN", "TitleA");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(fA_fp, fB, fC)), "Mutating fingerprint MUST change hash");

        // Mutate contentHash
        CanonicalFindingInput fA_ch = new CanonicalFindingInput(idA, "catA", "chA_mod", "CWE-1", "descA", 10, "A.java", "AI", "fpA", "HIGH", "PROPOSED", 1, "OPEN", "TitleA");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(fA_ch, fB, fC)), "Mutating contentHash MUST change hash");

        // Mutate filePath
        CanonicalFindingInput fA_path = new CanonicalFindingInput(idA, "catA", "chA", "CWE-1", "descA", 10, "A_mod.java", "AI", "fpA", "HIGH", "PROPOSED", 1, "OPEN", "TitleA");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(fA_path, fB, fC)), "Mutating filePath MUST change hash");

        // Mutate ID
        UUID idA_mod = UUID.fromString("00000000-0000-0000-0000-000000000009");
        CanonicalFindingInput fA_id = new CanonicalFindingInput(idA_mod, "catA", "chA", "CWE-1", "descA", 10, "A.java", "AI", "fpA", "HIGH", "PROPOSED", 1, "OPEN", "TitleA");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(fA_id, fB, fC)), "Mutating ID MUST change hash");
    }

    // =========================================================================
    // TEST 5 — CERTIFICATE FIELD BINDING (ALL 20 FIELDS)
    // =========================================================================
    @Test
    @DisplayName("Test 5: Cryptographic binding of ALL 20 canonical certificate fields")
    void test5_All20CertificateFieldsCryptographicallyBound() {
        UUID certId = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
        UUID reviewId = UUID.fromString("ffffffff-1111-2222-3333-444444444444");
        UUID projId = UUID.fromString("dddddddd-1111-2222-3333-444444444444");
        UUID repoId = UUID.fromString("eeeeeeee-1111-2222-3333-444444444444");
        UUID taskId = UUID.fromString("11112222-3333-4444-5555-666677778888");
        UUID changeId = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
        UUID issuedUserId = UUID.fromString("cccccccc-1111-2222-3333-444444444444");
        UUID overrideUserId = UUID.fromString("99999999-1111-2222-3333-444444444444");
        Instant issuedAt = Instant.parse("2026-09-17T19:45:00.123Z");

        SecurityPolicyComplianceCertificateDto base = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId,
                PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
                0, 1, 2, 3, 5,
                null, null, issuedUserId,
                "idem_seq_golden_12345678",
                "placeholder_hash",
                issuedAt
        );

        String baseJson = hashService.canonicalizeCertificate(base);
        String baseHash = hashService.hashCertificate(base);

        // Verify hardcoded golden baseline
        String expectedBaseJson = "{\"adminOverrideByUserId\":null,\"adminOverrideReason\":null,\"certificateId\":\"aaaaaaaa-1111-2222-3333-444444444444\",\"codeChangeId\":\"bbbbbbbb-1111-2222-3333-444444444444\",\"complianceStatus\":\"PASSED\",\"criticalUnmitigatedCount\":0,\"findingEvidenceHash\":\"4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945\",\"highUnmitigatedCount\":1,\"idempotencyKey\":\"idem_seq_golden_12345678\",\"issuedAt\":\"2026-09-17T19:45:00.123Z\",\"issuedByUserId\":\"cccccccc-1111-2222-3333-444444444444\",\"lowUnmitigatedCount\":3,\"mediumUnmitigatedCount\":2,\"mitigatedFindingsCount\":5,\"policyVersion\":\"v1.0.0\",\"projectId\":\"dddddddd-1111-2222-3333-444444444444\",\"repositoryId\":\"eeeeeeee-1111-2222-3333-444444444444\",\"reviewContextHash\":\"ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef\",\"reviewId\":\"ffffffff-1111-2222-3333-444444444444\",\"taskId\":\"11112222-3333-4444-5555-666677778888\"}";
        String expectedBaseHash = "b8f9a50e37512e4fcc15ebd9f365d5990846488efcda44d98b0f156b3c346463";

        assertEquals(expectedBaseJson, baseJson);
        assertEquals(expectedBaseHash, baseHash);

        // Mutate Field 1: adminOverrideByUserId
        SecurityPolicyComplianceCertificateDto m1 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, overrideUserId, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m1), "Field 1 (adminOverrideByUserId) mutation MUST change hash");

        // Mutate Field 2: adminOverrideReason
        SecurityPolicyComplianceCertificateDto m2 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                "Override reason", null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m2), "Field 2 (adminOverrideReason) mutation MUST change hash");

        // Mutate Field 3: certificateId
        SecurityPolicyComplianceCertificateDto m3 = new SecurityPolicyComplianceCertificateDto(
                UUID.randomUUID(), reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m3), "Field 3 (certificateId) mutation MUST change hash");

        // Mutate Field 4: codeChangeId
        SecurityPolicyComplianceCertificateDto m4 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, UUID.randomUUID(), PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m4), "Field 4 (codeChangeId) mutation MUST change hash");

        // Mutate Field 5: complianceStatus
        SecurityPolicyComplianceCertificateDto m5 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.FAILED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m5), "Field 5 (complianceStatus) mutation MUST change hash");

        // Mutate Field 6: criticalUnmitigatedCount
        SecurityPolicyComplianceCertificateDto m6 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 99, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m6), "Field 6 (criticalUnmitigatedCount) mutation MUST change hash");

        // Mutate Field 7: findingEvidenceHash
        SecurityPolicyComplianceCertificateDto m7 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "0000000000000000000000000000000000000000000000000000000000000000", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m7), "Field 7 (findingEvidenceHash) mutation MUST change hash");

        // Mutate Field 8: highUnmitigatedCount
        SecurityPolicyComplianceCertificateDto m8 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 99, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m8), "Field 8 (highUnmitigatedCount) mutation MUST change hash");

        // Mutate Field 9: idempotencyKey
        SecurityPolicyComplianceCertificateDto m9 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_99999999", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m9), "Field 9 (idempotencyKey) mutation MUST change hash");

        // Mutate Field 10: issuedAt
        SecurityPolicyComplianceCertificateDto m10 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", Instant.parse("2026-09-17T20:00:00.000Z")
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m10), "Field 10 (issuedAt) mutation MUST change hash");

        // Mutate Field 11: issuedByUserId
        SecurityPolicyComplianceCertificateDto m11 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, UUID.randomUUID(), "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m11), "Field 11 (issuedByUserId) mutation MUST change hash");

        // Mutate Field 12: lowUnmitigatedCount
        SecurityPolicyComplianceCertificateDto m12 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 99, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m12), "Field 12 (lowUnmitigatedCount) mutation MUST change hash");

        // Mutate Field 13: mediumUnmitigatedCount
        SecurityPolicyComplianceCertificateDto m13 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 99, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m13), "Field 13 (mediumUnmitigatedCount) mutation MUST change hash");

        // Mutate Field 14: mitigatedFindingsCount
        SecurityPolicyComplianceCertificateDto m14 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 99,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m14), "Field 14 (mitigatedFindingsCount) mutation MUST change hash");

        // Mutate Field 15: policyVersion
        SecurityPolicyComplianceCertificateDto m15 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v2.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m15), "Field 15 (policyVersion) mutation MUST change hash");

        // Mutate Field 16: projectId
        SecurityPolicyComplianceCertificateDto m16 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, UUID.randomUUID(), repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m16), "Field 16 (projectId) mutation MUST change hash");

        // Mutate Field 17: repositoryId
        SecurityPolicyComplianceCertificateDto m17 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, UUID.randomUUID(), taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m17), "Field 17 (repositoryId) mutation MUST change hash");

        // Mutate Field 18: reviewContextHash
        SecurityPolicyComplianceCertificateDto m18 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m18), "Field 18 (reviewContextHash) mutation MUST change hash");

        // Mutate Field 19: reviewId
        SecurityPolicyComplianceCertificateDto m19 = new SecurityPolicyComplianceCertificateDto(
                certId, UUID.randomUUID(), projId, repoId, taskId, changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m19), "Field 19 (reviewId) mutation MUST change hash");

        // Mutate Field 20: taskId
        SecurityPolicyComplianceCertificateDto m20 = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, UUID.randomUUID(), changeId, PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", 0, 1, 2, 3, 5,
                null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );
        assertNotEquals(baseHash, hashService.hashCertificate(m20), "Field 20 (taskId) mutation MUST change hash");
    }

    // =========================================================================
    // TEST 6 — UUID CANONICALIZATION
    // =========================================================================
    @Test
    @DisplayName("Test 6: UUID canonicalization is lowercase, 36-character, hyphenated")
    void test6_UuidCanonicalization() {
        UUID uppercaseUuid = UUID.fromString("ABCDEF12-3456-7890-ABCD-EF1234567890");
        CanonicalFindingInput finding = new CanonicalFindingInput(
                uppercaseUuid, "cat", "ch", "CWE-1", "desc", 1, "path", "AI", "fp", "HIGH", "PROPOSED", 1, "OPEN", "title"
        );

        String json = hashService.canonicalizeFindings(List.of(finding));
        assertTrue(json.contains("\"id\":\"abcdef12-3456-7890-abcd-ef1234567890\""), "UUID MUST be formatted in lowercase 36-char hyphenated format");
    }

    // =========================================================================
    // TEST 7 — TIMESTAMP GOLDEN VECTOR
    // =========================================================================
    @Test
    @DisplayName("Test 7: Timestamp formatting golden vector (yyyy-MM-dd'T'HH:mm:ss.SSS'Z')")
    void test7_TimestampGoldenVector() {
        Instant subsecondInstant = Instant.parse("2026-09-17T19:45:00.123456789Z");

        UUID certId = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
        UUID reviewId = UUID.fromString("ffffffff-1111-2222-3333-444444444444");
        UUID projId = UUID.fromString("dddddddd-1111-2222-3333-444444444444");
        UUID repoId = UUID.fromString("eeeeeeee-1111-2222-3333-444444444444");
        UUID taskId = UUID.fromString("11112222-3333-4444-5555-666677778888");
        UUID changeId = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
        UUID issuedUserId = UUID.fromString("cccccccc-1111-2222-3333-444444444444");

        SecurityPolicyComplianceCertificateDto dto = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId,
                PolicyComplianceStatus.PASSED, "v1.0.0", "ctx", "ev",
                0, 0, 0, 0, 0, null, null, issuedUserId, "idem_ts_test", "hash", subsecondInstant
        );

        String json = hashService.canonicalizeCertificate(dto);
        assertTrue(json.contains("\"issuedAt\":\"2026-09-17T19:45:00.123Z\""), "Timestamp MUST format with exactly 3 millisecond digits and Z suffix in UTC");
        assertFalse(json.contains("123456789"), "Nanoseconds MUST NOT leak into canonical JSON");
    }

    // =========================================================================
    // TEST 8 — EMPTY FINDINGS
    // =========================================================================
    @Test
    @DisplayName("Test 8: Empty findings canonical JSON, exact bytes, and hardcoded SHA-256 vector")
    void test8_EmptyFindingsGoldenVector() {
        String canonicalJson = hashService.canonicalizeFindings(Collections.emptyList());
        byte[] actualBytes = hashService.getCanonicalFindingBytes(Collections.emptyList());
        String actualSha256 = hashService.hashFindingEvidence(Collections.emptyList());

        String expectedJson = "[]";
        byte[] expectedBytes = new byte[] { 0x5B, 0x5D }; // [91, 93]
        String expectedSha256 = "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945";

        assertEquals(expectedJson, canonicalJson);
        assertArrayEquals(expectedBytes, actualBytes);
        assertEquals(expectedSha256, actualSha256);
    }

    // =========================================================================
    // TEST 9 — INDEPENDENT MESSAGEDIGEST SHA-256 VERIFICATION
    // =========================================================================
    @Test
    @DisplayName("Test 9: Independent Java MessageDigest SHA-256 Verification without service dependency")
    void test9_IndependentMessageDigestVerification() throws java.security.NoSuchAlgorithmException {
        // Frozen expected string for empty findings
        String expectedEmptyJson = "[]";
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(expectedEmptyJson.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        String independentSha256 = sb.toString();

        assertEquals("4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", independentSha256);
        assertEquals(independentSha256, hashService.hashFindingEvidence(Collections.emptyList()));
    }

    // =========================================================================
    // TEST 10 — ALL 14 FINDING FIELDS INDEPENDENT MUTATION
    // =========================================================================
    @Test
    @DisplayName("Test 10: Cryptographic binding of ALL 14 finding fields")
    void test10_All14FindingFieldsCryptographicallyBound() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CanonicalFindingInput base = new CanonicalFindingInput(
                id, "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title"
        );

        String baseHash = hashService.hashFindingEvidence(List.of(base));

        // 1. Mutate category
        CanonicalFindingInput m1 = new CanonicalFindingInput(id, "cat_mod", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m1)));

        // 2. Mutate contentHash
        CanonicalFindingInput m2 = new CanonicalFindingInput(id, "cat", "ch_mod", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m2)));

        // 3. Mutate cweId (non-null -> null, null -> non-null)
        CanonicalFindingInput m3a = new CanonicalFindingInput(id, "cat", "ch123", null, "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m3a)));

        // 4. Mutate description
        CanonicalFindingInput m4 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc_mod", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m4)));

        // 5. Mutate endLine
        CanonicalFindingInput m5 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 99, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m5)));

        // 6. Mutate filePath
        CanonicalFindingInput m6 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 10, "src/Mod.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m6)));

        // 7. Mutate findingType
        CanonicalFindingInput m7 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "RULE_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m7)));

        // 8. Mutate fingerprint
        CanonicalFindingInput m8 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp_mod", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m8)));

        // 9. Mutate id
        CanonicalFindingInput m9 = new CanonicalFindingInput(UUID.randomUUID(), "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m9)));

        // 10. Mutate severity
        CanonicalFindingInput m10 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "CRITICAL", "PROPOSED", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m10)));

        // 11. Mutate side
        CanonicalFindingInput m11 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "BASE", 1, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m11)));

        // 12. Mutate startLine
        CanonicalFindingInput m12 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 99, "OPEN", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m12)));

        // 13. Mutate status
        CanonicalFindingInput m13 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "RESOLVED", "Title");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m13)));

        // 14. Mutate title
        CanonicalFindingInput m14 = new CanonicalFindingInput(id, "cat", "ch123", "CWE-79", "desc", 10, "src/App.java", "AI_FINDING", "fp123", "HIGH", "PROPOSED", 1, "OPEN", "Title_Mod");
        assertNotEquals(baseHash, hashService.hashFindingEvidence(List.of(m14)));
    }

    // =========================================================================
    // TEST 11 — CONCURRENT MULTITHREADED EXECUTION
    // =========================================================================
    @Test
    @DisplayName("Test 11: Multi-threaded concurrent execution guarantees zero state contamination")
    void test11_ConcurrentMultithreadedExecution() throws Exception {
        UUID certId = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
        UUID reviewId = UUID.fromString("ffffffff-1111-2222-3333-444444444444");
        UUID projId = UUID.fromString("dddddddd-1111-2222-3333-444444444444");
        UUID repoId = UUID.fromString("eeeeeeee-1111-2222-3333-444444444444");
        UUID taskId = UUID.fromString("11112222-3333-4444-5555-666677778888");
        UUID changeId = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
        UUID issuedUserId = UUID.fromString("cccccccc-1111-2222-3333-444444444444");
        Instant issuedAt = Instant.parse("2026-09-17T19:45:00.123Z");

        SecurityPolicyComplianceCertificateDto dto = new SecurityPolicyComplianceCertificateDto(
                certId, reviewId, projId, repoId, taskId, changeId,
                PolicyComplianceStatus.PASSED, "v1.0.0",
                "ctx_hash_golden_1234567890abcdef1234567890abcdef1234567890abcdef",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
                0, 1, 2, 3, 5, null, null, issuedUserId, "idem_seq_golden_12345678", "placeholder", issuedAt
        );

        String expectedHash = "b8f9a50e37512e4fcc15ebd9f365d5990846488efcda44d98b0f156b3c346463";

        int threads = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> hashService.hashCertificate(dto)));
        }

        for (java.util.concurrent.Future<String> f : futures) {
            assertEquals(expectedHash, f.get(), "Thread result MUST match expected golden hash");
        }

        executor.shutdown();
    }
}
