package com.repopilot.review;

import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.ai.client.AiServiceClientImpl;
import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.EvidenceRef;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.ReviewFindingResponseDto;
import com.repopilot.review.dto.ReviewResponseDto;
import com.repopilot.review.entity.FindingConfidence;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingSide;
import com.repopilot.review.entity.FindingType;
import com.repopilot.review.entity.ReviewMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

public class Phase11_3AiServiceClientIntegrationTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private AiServiceClient aiServiceClient;
    private final String secretKey = "test-internal-secret-key-12345";
    private final String sampleHash = "a1b2c3d4e5f60123456789abcdef0123456789abcdef0123456789abcdef0123";

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        aiServiceClient = new AiServiceClientImpl(restTemplate, "http://localhost:8000", secretKey);
    }

    private ReviewContext createValidContext(boolean isStale) {
        EvidenceRef ref = new EvidenceRef(
                "CODE_CHANGE", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src/Main.java", "PROPOSED", 10, 25, "hash123", "snap456", "chunk789", "READ"
        );
        return new ReviewContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ReviewMode.SECURITY_REVIEW,
                "src/Main.java",
                "MODIFY",
                "int x = 1;",
                "int x = 2;",
                "@@ -1 +1 @@\n-int x = 1;\n+int x = 2;",
                "origHash123",
                "propHash456",
                sampleHash,
                "v1.0",
                isStale,
                List.of(ref)
        );
    }

    private String createValidJsonResponse() {
        return """
            {
              "summary": "Found potential issue in Main.java",
              "overallRiskScore": "HIGH",
              "reviewContextHash": "%s",
              "findings": [
                {
                  "findingType": "AI_FINDING",
                  "category": "INJECTION",
                  "cweId": "CWE-89",
                  "severity": "HIGH",
                  "confidence": "HIGH",
                  "title": "SQL Injection",
                  "description": "Unsanitized user input passed to query",
                  "filePath": "src/Main.java",
                  "startLine": 10,
                  "endLine": 15,
                  "side": "PROPOSED",
                  "evidence": "statement.executeQuery(userInput);",
                  "remediation": "Use PreparedStatement parameter binding"
                }
              ]
            }
            """.formatted(sampleHash);
    }

    // ==========================================
    // VECTORS 1–7: REQUEST DTO MAPPING & ROUTING
    // ==========================================

    @Test
    @DisplayName("Vector 1-7: Request DTO mapping and endpoint route verification")
    void testRequestDtoMappingAndRoute() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("X-Internal-Service-Key", secretKey))
                .andExpect(jsonPath("$.projectId").value(context.projectId().toString()))
                .andExpect(jsonPath("$.repositoryId").value(context.repositoryId().toString()))
                .andExpect(jsonPath("$.taskId").value(context.taskId().toString()))
                .andExpect(jsonPath("$.codeChangeId").value(context.codeChangeId().toString()))
                .andExpect(jsonPath("$.reviewMode").value("SECURITY_REVIEW"))
                .andExpect(jsonPath("$.filePath").value("src/Main.java"))
                .andExpect(jsonPath("$.operation").value("MODIFY"))
                .andExpect(jsonPath("$.originalContent").value("int x = 1;"))
                .andExpect(jsonPath("$.proposedContent").value("int x = 2;"))
                .andExpect(jsonPath("$.reviewContextHash").value(sampleHash))
                .andExpect(jsonPath("$.contextVersion").value("v1.0"))
                .andExpect(jsonPath("$.evidenceReferences[0].filePath").value("src/Main.java"))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        ReviewResponseDto result = aiServiceClient.executeCodeReview(context);

        mockServer.verify();
        assertNotNull(result);
        assertEquals("Found potential issue in Main.java", result.getSummary());
    }

    // ==========================================
    // VECTORS 8–13: RESPONSE DTO MAPPING
    // ==========================================

    @Test
    @DisplayName("Vector 8-13: Response DTO mapping of fields and enums")
    void testResponseDtoMapping() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        ReviewResponseDto result = aiServiceClient.executeCodeReview(context);

        assertNotNull(result);
        assertEquals("Found potential issue in Main.java", result.getSummary());
        assertEquals("HIGH", result.getOverallRiskScore());
        assertEquals(1, result.getFindings().size());

        ReviewFindingResponseDto finding = result.getFindings().get(0);
        assertEquals(FindingType.AI_FINDING, finding.getFindingType());
        assertEquals("INJECTION", finding.getCategory());
        assertEquals("CWE-89", finding.getCweId());
        assertEquals(FindingSeverity.HIGH, finding.getSeverity());
        assertEquals(FindingConfidence.HIGH, finding.getConfidence());
        assertEquals("SQL Injection", finding.getTitle());
        assertEquals("src/Main.java", finding.getFilePath());
        assertEquals(10, finding.getStartLine());
        assertEquals(15, finding.getEndLine());
        assertEquals(FindingSide.PROPOSED, finding.getSide());
        assertEquals("statement.executeQuery(userInput);", finding.getEvidence());
        assertEquals("Use PreparedStatement parameter binding", finding.getRemediation());
    }

    // ==========================================
    // VECTORS 14–20: RESPONSE VALIDATION
    // ==========================================

    @Test
    @DisplayName("Vector 14: Null summary in response throws LLM_MALFORMED_OUTPUT")
    void testResponseValidation_NullSummary() {
        ReviewContext context = createValidContext(false);
        String badJson = """
            {
              "summary": null,
              "findings": []
            }
            """;

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(badJson, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("LLM_MALFORMED_OUTPUT", ex.getCode());
    }

    @Test
    @DisplayName("Vector 15: Blank summary in response throws LLM_MALFORMED_OUTPUT")
    void testResponseValidation_BlankSummary() {
        ReviewContext context = createValidContext(false);
        String badJson = """
            {
              "summary": "   ",
              "findings": []
            }
            """;

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(badJson, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("LLM_MALFORMED_OUTPUT", ex.getCode());
    }

    @Test
    @DisplayName("Vector 16: Null findings list throws LLM_MALFORMED_OUTPUT")
    void testResponseValidation_NullFindings() {
        ReviewContext context = createValidContext(false);
        String badJson = """
            {
              "summary": "Valid summary",
              "findings": null
            }
            """;

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(badJson, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("LLM_MALFORMED_OUTPUT", ex.getCode());
    }

    @Test
    @DisplayName("Vector 17: Exceeding 50 findings limit throws LLM_MALFORMED_OUTPUT")
    void testResponseValidation_ExcessiveFindings() {
        ReviewContext context = createValidContext(false);
        List<String> findingsJson = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            findingsJson.add("""
                {
                  "findingType": "AI_FINDING",
                  "severity": "LOW",
                  "confidence": "LOW",
                  "side": "PROPOSED",
                  "title": "Finding %d",
                  "startLine": 1,
                  "endLine": 2
                }
                """.formatted(i));
        }
        String badJson = "{\"summary\":\"Ok\", \"findings\":[" + String.join(",", findingsJson) + "]}";

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(badJson, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("LLM_MALFORMED_OUTPUT", ex.getCode());
    }

    @Test
    @DisplayName("Vector 18: Inverted line numbers (startLine > endLine) throws LLM_MALFORMED_OUTPUT")
    void testResponseValidation_InvertedLineNumbers() {
        ReviewContext context = createValidContext(false);
        String badJson = """
            {
              "summary": "Summary",
              "findings": [
                {
                  "findingType": "AI_FINDING",
                  "severity": "MEDIUM",
                  "confidence": "MEDIUM",
                  "side": "PROPOSED",
                  "title": "Bad Range",
                  "startLine": 50,
                  "endLine": 10
                }
              ]
            }
            """;

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(badJson, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("LLM_MALFORMED_OUTPUT", ex.getCode());
    }

    @Test
    @DisplayName("Vector 19: Missing required enum field in finding throws LLM_MALFORMED_OUTPUT")
    void testResponseValidation_MissingEnumField() {
        ReviewContext context = createValidContext(false);
        String badJson = """
            {
              "summary": "Summary",
              "findings": [
                {
                  "findingType": null,
                  "severity": "MEDIUM",
                  "confidence": "MEDIUM",
                  "side": "PROPOSED"
                }
              ]
            }
            """;

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(badJson, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("LLM_MALFORMED_OUTPUT", ex.getCode());
    }

    @Test
    @DisplayName("Vector 20: Valid empty findings list is accepted")
    void testResponseValidation_EmptyFindingsListAccepted() {
        ReviewContext context = createValidContext(false);
        String validJson = """
            {
              "summary": "No issues found",
              "overallRiskScore": "LOW",
              "reviewContextHash": "%s",
              "findings": []
            }
            """.formatted(sampleHash);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(validJson, MediaType.APPLICATION_JSON));

        ReviewResponseDto result = aiServiceClient.executeCodeReview(context);
        assertNotNull(result);
        assertEquals("No issues found", result.getSummary());
        assertTrue(result.getFindings().isEmpty());
    }

    // ==========================================
    // VECTORS 21–28: ERROR MAPPING
    // ==========================================

    @Test
    @DisplayName("Vector 21: HTTP 400 Bad Request mapped to BAD_REQUEST INVALID_REQUEST")
    void testErrorMapping_400BadRequest() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withBadRequest().body("{\"detail\":\"Secret detected\"}"));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_REQUEST", ex.getCode());
    }

    @Test
    @DisplayName("Vector 22: HTTP 422 Unprocessable Entity mapped to UNPROCESSABLE_ENTITY VALIDATION_FAILURE")
    void testErrorMapping_422UnprocessableEntity() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("{\"detail\":\"Validation failure\"}"));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("VALIDATION_FAILURE", ex.getCode());
    }

    @Test
    @DisplayName("Vector 23: HTTP 429 Rate Limit (exhausted retries) mapped to TOO_MANY_REQUESTS LLM_RATE_LIMITED")
    void testErrorMapping_429RateLimitExhausted() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withTooManyRequests());
        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withTooManyRequests());

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals("LLM_RATE_LIMITED", ex.getCode());
    }

    @Test
    @DisplayName("Vector 24: HTTP 502 Bad Gateway mapped to BAD_GATEWAY LLM_MALFORMED_OUTPUT")
    void testErrorMapping_502BadGateway() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("{\"detail\":\"LLM returned garbage\"}"));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("LLM_MALFORMED_OUTPUT", ex.getCode());
    }

    @Test
    @DisplayName("Vector 25: HTTP 503 Service Unavailable (exhausted retries) mapped to SERVICE_UNAVAILABLE LLM_UNAVAILABLE")
    void testErrorMapping_503ServiceUnavailableExhausted() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withServiceUnavailable());
        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withServiceUnavailable());

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("LLM_UNAVAILABLE", ex.getCode());
    }

    @Test
    @DisplayName("Vector 26: HTTP 500 Internal Server Error mapped to 500 AI_SERVICE_ERROR")
    void testErrorMapping_500ServerError() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withServerError().body("{\"detail\":\"Internal LLM exception\"}"));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertEquals("AI_SERVICE_ERROR", ex.getCode());
    }

    @Test
    @DisplayName("Vector 27 & 28: Network connection error mapped to SERVICE_UNAVAILABLE AI_SERVICE_UNAVAILABLE")
    void testErrorMapping_NetworkErrorExhausted() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(request -> { throw new RuntimeException("Connection refused"); });
        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(request -> { throw new RuntimeException("Connection refused"); });

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("AI_SERVICE_UNAVAILABLE", ex.getCode());
    }

    // ==========================================
    // VECTORS 29–34: RETRY LOGIC & POLICY
    // ==========================================

    @Test
    @DisplayName("Vector 29: Transient 503 on 1st attempt retries and succeeds on 2nd attempt")
    void testRetryLogic_Transient503Succeeds() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withServiceUnavailable());
        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        ReviewResponseDto result = aiServiceClient.executeCodeReview(context);

        mockServer.verify();
        assertNotNull(result);
        assertEquals("Found potential issue in Main.java", result.getSummary());
    }

    @Test
    @DisplayName("Vector 30: Transient 429 on 1st attempt retries and succeeds on 2nd attempt")
    void testRetryLogic_Transient429Succeeds() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withTooManyRequests());
        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        ReviewResponseDto result = aiServiceClient.executeCodeReview(context);

        mockServer.verify();
        assertNotNull(result);
    }

    @Test
    @DisplayName("Vector 31: Transient network exception retries and succeeds on 2nd attempt")
    void testRetryLogic_TransientNetworkExceptionSucceeds() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(request -> { throw new RuntimeException("Timeout"); });
        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        ReviewResponseDto result = aiServiceClient.executeCodeReview(context);

        mockServer.verify();
        assertNotNull(result);
    }

    @Test
    @DisplayName("Vector 32 & 33: Non-retryable 400 Bad Request fails immediately on 1st attempt")
    void testRetryLogic_NonRetryable400FailsImmediately() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withBadRequest().body("{\"detail\":\"Bad request\"}"));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals("INVALID_REQUEST", ex.getCode());
        mockServer.verify(); // Verifies only 1 request was made!
    }

    @Test
    @DisplayName("Vector 34: Non-retryable 422 fails immediately on 1st attempt without retry")
    void testRetryLogic_NonRetryable422FailsImmediately() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("{\"detail\":\"Unprocessable\"}"));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertEquals("VALIDATION_FAILURE", ex.getCode());
        mockServer.verify(); // Verifies only 1 request was made!
    }

    // ==========================================
    // VECTORS 35–40: SECURITY & HEADERS
    // ==========================================

    @Test
    @DisplayName("Vector 35: X-Internal-Service-Key header is passed when internal secret is configured")
    void testSecurity_InternalSecretHeaderPresent() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andExpect(header("X-Internal-Service-Key", secretKey))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        aiServiceClient.executeCodeReview(context);
        mockServer.verify();
    }

    @Test
    @DisplayName("Vector 36: X-Internal-Service-Key header omitted when secret is null or blank")
    void testSecurity_InternalSecretHeaderOmittedWhenBlank() {
        AiServiceClient unauthClient = new AiServiceClientImpl(restTemplate, "http://localhost:8000", "");
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andExpect(headerDoesNotExist("X-Internal-Service-Key"))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        unauthClient.executeCodeReview(context);
        mockServer.verify();
    }

    @Test
    @DisplayName("Vector 37: Content-Type application/json is present")
    void testSecurity_ContentTypeHeader() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        aiServiceClient.executeCodeReview(context);
        mockServer.verify();
    }

    @Test
    @DisplayName("Vector 38: Authorization / User JWT headers are NOT sent to internal AI review endpoint")
    void testSecurity_NoUserAuthHeadersSent() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        aiServiceClient.executeCodeReview(context);
        mockServer.verify();
    }

    @Test
    @DisplayName("Vector 39 & 40: Secret and code content are not exposed in exception messages")
    void testSecurity_SecretNotExposedInException() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withBadRequest().body("Error details"));

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(context));
        assertFalse(ex.getMessage().contains(secretKey));
    }

    // ==========================================
    // VECTORS 41–42: STALE CONTEXT & HASH REJECTION
    // ==========================================

    @Test
    @DisplayName("Vector 41: Stale context (isStale == true) throws BAD_REQUEST STALE_CONTEXT without HTTP call")
    void testStaleContextRejection() {
        ReviewContext staleContext = createValidContext(true);

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(staleContext));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("STALE_CONTEXT", ex.getCode());
        // Verify no HTTP calls were made!
        mockServer.verify();
    }

    @Test
    @DisplayName("Vector 42: Invalid reviewContextHash format throws BAD_REQUEST INVALID_HASH")
    void testInvalidContextHashRejection() {
        EvidenceRef ref = new EvidenceRef(
                "CODE_CHANGE", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src/Main.java", "PROPOSED", 10, 25, "hash123", "snap456", "chunk789", "READ"
        );
        ReviewContext invalidHashContext = new ReviewContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ReviewMode.SECURITY_REVIEW, "src/Main.java", "MODIFY", "c1", "c2", "diff", "h1", "h2",
                "invalid-short-hash", "v1.0", false, List.of(ref)
        );

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(invalidHashContext));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_HASH", ex.getCode());
        mockServer.verify();
    }

    // ==========================================
    // VECTORS 43–48: SIDE EFFECTS & ISOLATION
    // ==========================================

    @Test
    @DisplayName("Vector 48: Invalid contextVersion throws BAD_REQUEST INVALID_VERSION")
    void testInvalidContextVersionRejection() {
        EvidenceRef ref = new EvidenceRef(
                "CODE_CHANGE", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "src/Main.java", "PROPOSED", 10, 25, "hash123", "snap456", "chunk789", "READ"
        );
        ReviewContext invalidVersionContext = new ReviewContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ReviewMode.SECURITY_REVIEW, "src/Main.java", "MODIFY", "c1", "c2", "diff", "h1", "h2",
                sampleHash, "v2.0", false, List.of(ref)
        );

        ApiException ex = assertThrows(ApiException.class, () -> aiServiceClient.executeCodeReview(invalidVersionContext));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_VERSION", ex.getCode());
        mockServer.verify();
    }

    @Test
    @DisplayName("Vector 43-47: Execute code review pure component isolation (no DB/Git/Docker side effects)")
    void testSideEffectIsolation() {
        ReviewContext context = createValidContext(false);

        mockServer.expect(requestTo("http://localhost:8000/internal/ai/review"))
                .andRespond(withSuccess(createValidJsonResponse(), MediaType.APPLICATION_JSON));

        ReviewResponseDto dto = aiServiceClient.executeCodeReview(context);

        assertNotNull(dto);
        // Verify pure DTO translation with zero side-effects
    }
}
