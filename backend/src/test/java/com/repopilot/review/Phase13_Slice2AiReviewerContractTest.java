package com.repopilot.review;

import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.AiReviewerFindingDto;
import com.repopilot.review.dto.AiReviewerResponseDto;
import com.repopilot.review.entity.FindingConfidence;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingSide;
import com.repopilot.review.entity.FindingType;
import com.repopilot.review.entity.ReviewStatus;
import com.repopilot.review.service.AiReviewerContractValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Phase13_Slice2AiReviewerContractTest {

    private AiReviewerContractValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AiReviewerContractValidator();
    }

    @Test
    @DisplayName("DTO/Schema: Valid response passes validation")
    void testValidResponsePassesValidation() {
        AiReviewerFindingDto finding = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "SQL_INJECTION", "CWE-89",
                FindingSeverity.HIGH, FindingConfidence.HIGH,
                "SQL Injection in Query", "User input concatenated into query string",
                "src/Main.java", 10, 20, FindingSide.PROPOSED,
                "String q = \"SELECT * FROM users WHERE name=\" + input;",
                "Use parameterized query"
        );

        AiReviewerResponseDto response = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Found 1 security issue", FindingSeverity.HIGH, List.of(finding)
        );

        assertDoesNotThrow(() -> validator.validateResponse(response));
    }

    @Test
    @DisplayName("DTO/Schema: Unknown top-level fields in contract JSON are REJECTED by strict ObjectMapper")
    void testUnknownTopLevelFieldsRejectedInStrictMode() {
        String invalidJson = """
                {
                    "status": "COMPLETED",
                    "summary": "Review complete",
                    "overallRiskScore": "LOW",
                    "findings": [],
                    "unauthorizedExtraField": "malicious payload"
                }
                """;

        ApiException ex = assertThrows(ApiException.class, () -> validator.deserializeStrictJson(invalidJson));
        assertEquals("INVALID_CONTRACT_JSON", ex.getCode());
        assertTrue(ex.getMessage().contains("Strict contract deserialization failed"), ex.getMessage());
    }

    @Test
    @DisplayName("DTO/Schema: Unknown nested finding fields in contract JSON are REJECTED by strict ObjectMapper")
    void testUnknownNestedFindingFieldsRejectedInStrictMode() {
        String invalidJson = """
                {
                    "status": "COMPLETED",
                    "summary": "Review complete",
                    "overallRiskScore": "HIGH",
                    "findings": [
                        {
                            "findingType": "AI_FINDING",
                            "category": "SQL_INJECTION",
                            "severity": "HIGH",
                            "confidence": "HIGH",
                            "title": "Title",
                            "description": "Desc",
                            "filePath": "src/Main.java",
                            "startLine": 1,
                            "endLine": 5,
                            "side": "PROPOSED",
                            "extraNestedField": "invalid"
                        }
                    ]
                }
                """;

        ApiException ex = assertThrows(ApiException.class, () -> validator.deserializeStrictJson(invalidJson));
        assertEquals("INVALID_CONTRACT_JSON", ex.getCode());
        assertTrue(ex.getMessage().contains("Strict contract deserialization failed"), ex.getMessage());
    }

    @Test
    @DisplayName("DTO/Schema: Wrong JSON types are REJECTED by strict ObjectMapper")
    void testWrongJsonTypesRejected() {
        String invalidJson = """
                {
                    "status": "COMPLETED",
                    "summary": "Summary",
                    "overallRiskScore": "HIGH",
                    "findings": "not-an-array"
                }
                """;

        ApiException ex = assertThrows(ApiException.class, () -> validator.deserializeStrictJson(invalidJson));
        assertEquals("INVALID_CONTRACT_JSON", ex.getCode());
    }

    @Test
    @DisplayName("DTO/Schema: Invalid enum values are REJECTED")
    void testInvalidEnumValuesRejected() {
        String invalidJson = """
                {
                    "status": "UNKNOWN_STATUS",
                    "summary": "Summary",
                    "overallRiskScore": "HIGH",
                    "findings": []
                }
                """;

        ApiException ex = assertThrows(ApiException.class, () -> validator.deserializeStrictJson(invalidJson));
        assertEquals("INVALID_CONTRACT_JSON", ex.getCode());
    }

    @Test
    @DisplayName("DTO/Schema: Finding count 50 -> ACCEPTED")
    void testFindingCount50Accepted() {
        List<AiReviewerFindingDto> findings = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            findings.add(new AiReviewerFindingDto(
                    FindingType.AI_FINDING, "SQL_INJECTION", "CWE-89",
                    FindingSeverity.HIGH, FindingConfidence.HIGH,
                    "Title " + i, "Description " + i, "src/Main.java", 1, 10,
                    FindingSide.PROPOSED, "evidence", "remediation"
            ));
        }

        AiReviewerResponseDto response = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Summary", FindingSeverity.HIGH, findings
        );

        assertDoesNotThrow(() -> validator.validateResponse(response));
    }

    @Test
    @DisplayName("DTO/Schema: Finding count 51 -> REJECTED with RESOURCE_LIMIT_EXCEEDED")
    void testFindingCount51Rejected() {
        List<AiReviewerFindingDto> findings = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            findings.add(new AiReviewerFindingDto(
                    FindingType.AI_FINDING, "SQL_INJECTION", "CWE-89",
                    FindingSeverity.HIGH, FindingConfidence.HIGH,
                    "Title " + i, "Description " + i, "src/Main.java", 1, 10,
                    FindingSide.PROPOSED, "evidence", "remediation"
            ));
        }

        AiReviewerResponseDto response = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Summary", FindingSeverity.HIGH, findings
        );

        ApiException ex = assertThrows(ApiException.class, () -> validator.validateResponse(response));
        assertEquals("RESOURCE_LIMIT_EXCEEDED", ex.getCode());
        assertTrue(ex.getMessage().contains("Finding count exceeds maximum limit"), ex.getMessage());
    }

    @Test
    @DisplayName("DTO/Schema: Maximum allowed string lengths -> ACCEPTED")
    void testMaxAllowedStringLengthsAccepted() {
        String summary = "S".repeat(5_000);
        String title = "T".repeat(255);
        String description = "D".repeat(10_000);
        String evidence = "E".repeat(10_000);
        String remediation = "R".repeat(10_000);

        AiReviewerFindingDto finding = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "SQL_INJECTION", "CWE-89",
                FindingSeverity.HIGH, FindingConfidence.HIGH,
                title, description, "src/Main.java", 1, 10,
                FindingSide.PROPOSED, evidence, remediation
        );

        AiReviewerResponseDto response = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, summary, FindingSeverity.HIGH, List.of(finding)
        );

        assertDoesNotThrow(() -> validator.validateResponse(response));
    }

    @Test
    @DisplayName("DTO/Schema: Maximum string length + 1 -> REJECTED")
    void testMaxStringLengthPlus1Rejected() {
        // Case 1: summary 5,001 chars -> REJECTED
        String longSummary = "S".repeat(5_001);
        AiReviewerResponseDto resp1 = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, longSummary, FindingSeverity.HIGH, List.of()
        );
        ApiException ex1 = assertThrows(ApiException.class, () -> validator.validateResponse(resp1));
        assertTrue(ex1.getMessage().contains("Summary exceeds maximum length"), ex1.getMessage());

        // Case 2: title 256 chars -> REJECTED
        String longTitle = "T".repeat(256);
        AiReviewerFindingDto finding2 = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "SQL_INJECTION", "CWE-89",
                FindingSeverity.HIGH, FindingConfidence.HIGH,
                longTitle, "Description", "src/Main.java", 1, 10,
                FindingSide.PROPOSED, "evidence", "remediation"
        );
        AiReviewerResponseDto resp2 = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Summary", FindingSeverity.HIGH, List.of(finding2)
        );
        ApiException ex2 = assertThrows(ApiException.class, () -> validator.validateResponse(resp2));
        assertTrue(ex2.getMessage().contains("title exceeds max length"), ex2.getMessage());
    }

    @Test
    @DisplayName("DTO/Schema: Line range rules (startLine=1/endLine=1, startLine=endLine accepted; startLine=10/endLine=9, startLine=0, negative lines rejected)")
    void testLineRangeRules() {
        // startLine=1 / endLine=1 -> ACCEPTED
        AiReviewerFindingDto f1 = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "CAT", "CWE-1", FindingSeverity.LOW, FindingConfidence.LOW,
                "Title", "Desc", "src/Main.java", 1, 1, FindingSide.PROPOSED, "e", "r"
        );
        assertDoesNotThrow(() -> validator.validateResponse(new AiReviewerResponseDto(ReviewStatus.COMPLETED, "S", FindingSeverity.LOW, List.of(f1))));

        // startLine=endLine=42 -> ACCEPTED
        AiReviewerFindingDto f2 = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "CAT", "CWE-1", FindingSeverity.LOW, FindingConfidence.LOW,
                "Title", "Desc", "src/Main.java", 42, 42, FindingSide.PROPOSED, "e", "r"
        );
        assertDoesNotThrow(() -> validator.validateResponse(new AiReviewerResponseDto(ReviewStatus.COMPLETED, "S", FindingSeverity.LOW, List.of(f2))));

        // startLine=10 / endLine=9 -> REJECTED
        AiReviewerFindingDto f3 = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "CAT", "CWE-1", FindingSeverity.LOW, FindingConfidence.LOW,
                "Title", "Desc", "src/Main.java", 10, 9, FindingSide.PROPOSED, "e", "r"
        );
        assertThrows(ApiException.class, () -> validator.validateResponse(new AiReviewerResponseDto(ReviewStatus.COMPLETED, "S", FindingSeverity.LOW, List.of(f3))));

        // startLine=0 -> REJECTED
        AiReviewerFindingDto f4 = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "CAT", "CWE-1", FindingSeverity.LOW, FindingConfidence.LOW,
                "Title", "Desc", "src/Main.java", 0, 5, FindingSide.PROPOSED, "e", "r"
        );
        assertThrows(ApiException.class, () -> validator.validateResponse(new AiReviewerResponseDto(ReviewStatus.COMPLETED, "S", FindingSeverity.LOW, List.of(f4))));

        // Negative lines -> REJECTED
        AiReviewerFindingDto f5 = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "CAT", "CWE-1", FindingSeverity.LOW, FindingConfidence.LOW,
                "Title", "Desc", "src/Main.java", -5, -1, FindingSide.PROPOSED, "e", "r"
        );
        assertThrows(ApiException.class, () -> validator.validateResponse(new AiReviewerResponseDto(ReviewStatus.COMPLETED, "S", FindingSeverity.LOW, List.of(f5))));
    }
}
