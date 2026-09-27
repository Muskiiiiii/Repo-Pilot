package com.repopilot.ai.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.ReviewContextRequestDto;
import com.repopilot.review.dto.ReviewFindingResponseDto;
import com.repopilot.review.dto.ReviewResponseDto;

import java.util.*;

@Service
public class AiServiceClientImpl implements AiServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(AiServiceClientImpl.class);

    public static final int MAX_SYNTHESIS_WALL_CLOCK_MS = 15000;
    private static final int SYNTHESIS_CONNECT_TIMEOUT_MS = 2000;
    private static final int SYNTHESIS_READ_TIMEOUT_MS = 4000;
    private static final int SYNTHESIS_MAX_ATTEMPTS = 2;
    private static final long SYNTHESIS_BACKOFF_MS = 500;
    private static final long MIN_RETRY_MARGIN_MS = 2000;

    private final RestOperations restTemplate;
    private final String aiServiceUrl;
    private final String internalServiceSecret;

    @Autowired
    public AiServiceClientImpl(RestOperations restTemplate,
                               @Value("${repopilot.ai-service.url:http://localhost:8000}") String aiServiceUrl,
                               @Value("${repopilot.ai-service.secret:}") String internalServiceSecret) {
        this.restTemplate = restTemplate;
        this.aiServiceUrl = aiServiceUrl;
        this.internalServiceSecret = internalServiceSecret;
    }

    public AiServiceClientImpl(RestOperations restTemplate, String aiServiceUrl) {
        this(restTemplate, aiServiceUrl, null);
    }

    @Override
    public List<String> generateEmbeddings(List<String> texts) {
        return generateEmbeddingsWithType(texts, "document");
    }

    @Override
    public List<String> generateQueryEmbeddings(List<String> queries) {
        return generateEmbeddingsWithType(queries, "query");
    }

    @Override
    public String generateQueryEmbedding(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query cannot be empty");
        }
        List<String> vectors = generateQueryEmbeddings(List.of(query));
        if (vectors == null || vectors.isEmpty()) {
            throw new RuntimeException("AI Service returned empty embedding for query");
        }
        return vectors.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<String> generateEmbeddingsWithType(List<String> texts, String inputType) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        String endpoint = aiServiceUrl + "/internal/ai/embeddings";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("texts", texts);
        requestBody.put("input_type", inputType != null ? inputType : "document");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        int maxAttempts = 3;
        long backoffMs = 500;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    List<List<Number>> rawEmbeddings = (List<List<Number>>) response.getBody().get("embeddings");
                    if (rawEmbeddings != null) {
                        List<String> formattedVectors = new ArrayList<>();
                        for (List<Number> vec : rawEmbeddings) {
                            formattedVectors.add(formatPgVector(vec));
                        }
                        return formattedVectors;
                    }
                }
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status == 503) && attempt < maxAttempts) {
                    logger.warn("AI Service returned HTTP {}. Retrying attempt {}/{} after backoff...", status, attempt, maxAttempts);
                    try {
                        Thread.sleep(backoffMs + (long) (Math.random() * 200));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    backoffMs *= 2;
                    continue;
                }
                logger.error("AI Service embedding request failed with HTTP {}: {}", status, e.getResponseBodyAsString());
                throw e;
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    logger.warn("AI Service connection error on attempt {}/{}: {}. Retrying...", attempt, maxAttempts, e.getMessage());
                    try {
                        Thread.sleep(backoffMs + (long) (Math.random() * 200));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    backoffMs *= 2;
                    continue;
                }
                logger.error("AI Service embedding call failed after {} attempts: {}", maxAttempts, e.getMessage());
                throw new RuntimeException("Failed to generate embeddings from AI service: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException("Failed to generate embeddings from AI service after retries");
    }

    public static String formatPgVector(List<Number> vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            sb.append(vec.get(i).doubleValue());
            if (i < vec.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeIssue(String title, String description) {
        String endpoint = aiServiceUrl + "/api/v1/planning/issue-understanding";
        Map<String, Object> requestBody = Map.of(
                "title", title,
                "description", description != null ? description : ""
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (Map<String, Object>) response.getBody();
            }
        } catch (Exception e) {
            logger.error("AI Service analyzeIssue failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to analyze issue via AI Service: " + e.getMessage(), e);
        }
        throw new RuntimeException("AI Service analyzeIssue returned empty response");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> generatePlan(Map<String, Object> payload) {
        String endpoint = aiServiceUrl + "/api/v1/planning/generate-plan";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (Map<String, Object>) response.getBody();
            }
        } catch (Exception e) {
            logger.error("AI Service generatePlan failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate plan via AI Service: " + e.getMessage(), e);
        }
        throw new RuntimeException("AI Service generatePlan returned empty response");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateCode(Map<String, Object> payload) {
        String endpoint = aiServiceUrl + "/api/v1/planning/generate-code";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (Map<String, Object>) response.getBody();
            }
        } catch (Exception e) {
            logger.error("AI Service generateCode failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate code changes via AI Service: " + e.getMessage(), e);
        }
        throw new RuntimeException("AI Service generateCode returned empty response");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> diagnoseFailure(Map<String, Object> payload) {
        String endpoint = aiServiceUrl + "/api/v1/testing/diagnose-failure";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        int maxAttempts = 2; // Max 1 retry for transient provider error
        long backoffMs = 500;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return (Map<String, Object>) response.getBody();
                }
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status == 503 || status == 504) && attempt < maxAttempts) {
                    logger.warn("AI Service returned HTTP {}. Retrying failure diagnosis attempt {}/{}...", status, attempt, maxAttempts);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                logger.error("AI Service failure diagnosis request failed with HTTP {}: {}", status, e.getResponseBodyAsString());
                throw e;
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    logger.warn("AI Service connection error on diagnosis attempt {}/{}: {}. Retrying...", attempt, maxAttempts, e.getMessage());
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                logger.error("AI Service failure diagnosis call failed after {} attempts: {}", maxAttempts, e.getMessage());
                throw new RuntimeException("Failed to diagnose failure via AI Service: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("AI Service failure diagnosis returned empty response");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateRepairProposal(Map<String, Object> payload) {
        String endpoint = aiServiceUrl + "/api/v1/testing/generate-repair-proposal";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        int maxAttempts = 2; // Max 1 retry for transient provider error
        long backoffMs = 500;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return (Map<String, Object>) response.getBody();
                }
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status == 503 || status == 504) && attempt < maxAttempts) {
                    logger.warn("AI Service returned HTTP {}. Retrying repair proposal generation attempt {}/{}...", status, attempt, maxAttempts);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                logger.error("AI Service repair proposal generation failed with HTTP {}: {}", status, e.getResponseBodyAsString());
                throw e;
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    logger.warn("AI Service connection error on repair proposal attempt {}/{}: {}. Retrying...", attempt, maxAttempts, e.getMessage());
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                logger.error("AI Service repair proposal call failed after {} attempts: {}", maxAttempts, e.getMessage());
                throw new RuntimeException("Failed to generate repair proposal via AI Service: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("AI Service repair proposal generation returned empty response");
    }

    @Override
    public ReviewResponseDto executeCodeReview(ReviewContext context) {
        if (context == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT", "ReviewContext cannot be null");
        }
        if (context.isStale()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STALE_CONTEXT", "ReviewContext is stale");
        }
        if (context.reviewContextHash() == null || !context.reviewContextHash().matches("^[a-fA-F0-9]{64}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_HASH", "Invalid reviewContextHash format");
        }
        if (!"v1.0".equals(context.contextVersion())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERSION", "Invalid contextVersion: " + context.contextVersion());
        }
        if (context.projectId() == null || context.repositoryId() == null || context.reviewMode() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_FIELDS", "Required context fields missing");
        }

        String endpoint = aiServiceUrl + "/internal/ai/review";

        ReviewContextRequestDto requestDto = new ReviewContextRequestDto(
                context.projectId(),
                context.repositoryId(),
                context.taskId(),
                context.codeChangeId(),
                context.reviewMode(),
                context.filePath(),
                context.operation(),
                context.originalContent(),
                context.proposedContent(),
                context.unifiedDiff(),
                context.originalHash(),
                context.proposedHash(),
                context.reviewContextHash(),
                context.contextVersion(),
                context.evidenceReferences()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalServiceSecret != null && !internalServiceSecret.isBlank()) {
            headers.set("X-Internal-Service-Key", internalServiceSecret);
        }

        HttpEntity<ReviewContextRequestDto> entity = new HttpEntity<>(requestDto, headers);

        int maxAttempts = 2;
        long backoffMs = 500;
        long startTime = System.currentTimeMillis();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<ReviewResponseDto> response = restTemplate.exchange(
                        endpoint, HttpMethod.POST, entity, ReviewResponseDto.class
                );
                long durationMs = System.currentTimeMillis() - startTime;
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    ReviewResponseDto responseBody = response.getBody();
                    validateReviewResponseDto(responseBody);
                    logger.info("AI code review completed contextHash={}, durationMs={}, findingsCount={}",
                            context.reviewContextHash(), durationMs, responseBody.getFindings().size());
                    return responseBody;
                }
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status == 503) && attempt < maxAttempts) {
                    logger.warn("AI Service review returned HTTP {}. Retrying attempt {}/{} after backoff...", status, attempt, maxAttempts);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                logger.error("AI Service review request failed with HTTP {}: {}", status, e.getResponseBodyAsString());
                if (status == 400) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "AI service rejected request: " + e.getResponseBodyAsString(), e);
                } else if (status == 422) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILURE", "AI service validation failure: " + e.getResponseBodyAsString(), e);
                } else if (status == 502) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "AI service returned malformed output: " + e.getResponseBodyAsString(), e);
                } else if (status == 429) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LLM_RATE_LIMITED", "AI service rate limited: " + e.getResponseBodyAsString(), e);
                } else if (status == 503) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_UNAVAILABLE", "AI service unavailable: " + e.getResponseBodyAsString(), e);
                } else {
                    throw new ApiException(HttpStatus.valueOf(status), "AI_SERVICE_ERROR", "AI service error: " + e.getResponseBodyAsString(), e);
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    logger.warn("AI Service connection error on review attempt {}/{}: {}. Retrying...", attempt, maxAttempts, e.getMessage());
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                logger.error("AI Service code review call failed after {} attempts: {}", maxAttempts, e.getMessage());
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_UNAVAILABLE", "Failed to communicate with AI service: " + e.getMessage(), e);
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_UNAVAILABLE", "AI Service code review returned empty response after retries");
    }

    private void validateReviewResponseDto(ReviewResponseDto dto) {
        if (dto == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "ReviewResponseDto is null");
        }
        if (dto.getSummary() == null || dto.getSummary().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "ReviewResponseDto summary is null or empty");
        }
        if (dto.getFindings() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "ReviewResponseDto findings list is null");
        }
        if (dto.getFindings().size() > 50) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "ReviewResponseDto findings count exceeds maximum limit 50");
        }
        for (ReviewFindingResponseDto finding : dto.getFindings()) {
            if (finding == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "ReviewFindingResponseDto element is null");
            }
            if (finding.getStartLine() != null && finding.getEndLine() != null && finding.getEndLine() < finding.getStartLine()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "Invalid line range in finding: startLine=" + finding.getStartLine() + ", endLine=" + finding.getEndLine());
            }
            if (finding.getFindingType() == null || finding.getSeverity() == null || finding.getConfidence() == null || finding.getSide() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "Finding required enum field is null");
            }
        }
    }

    @Override
    public com.repopilot.report.dto.ReportSynthesisOutputDto synthesizeReport(com.repopilot.report.dto.ReportSynthesisInputDto inputDto) {
        if (inputDto == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "ReportSynthesisInputDto cannot be null");
        }
        if (inputDto.getImplementationReportId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "ImplementationReportId cannot be null");
        }
        if (inputDto.getSourceContextHash() == null || !inputDto.getSourceContextHash().matches("^[a-fA-F0-9]{64}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_HASH", "Invalid sourceContextHash format");
        }

        String endpoint = aiServiceUrl + "/internal/ai/synthesize-report";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalServiceSecret != null && !internalServiceSecret.isBlank()) {
            headers.set("X-Internal-Service-Key", internalServiceSecret);
        }

        HttpEntity<com.repopilot.report.dto.ReportSynthesisInputDto> entity = new HttpEntity<>(inputDto, headers);

        long wallClockStartTime = System.currentTimeMillis();
        long wallClockDeadline = wallClockStartTime + MAX_SYNTHESIS_WALL_CLOCK_MS;

        for (int attempt = 1; attempt <= SYNTHESIS_MAX_ATTEMPTS; attempt++) {
            long now = System.currentTimeMillis();
            long timeRemainingMs = wallClockDeadline - now;

            if (timeRemainingMs <= 0) {
                logger.error("AI Service synthesis wall-clock deadline exceeded before attempt {}/{}. Elapsed: {}ms",
                        attempt, SYNTHESIS_MAX_ATTEMPTS, (now - wallClockStartTime));
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_SERVICE_TIMEOUT",
                        "AI Service execution timed out: wall-clock deadline of " + MAX_SYNTHESIS_WALL_CLOCK_MS + "ms exceeded");
            }

            if (attempt > 1 && timeRemainingMs < MIN_RETRY_MARGIN_MS) {
                logger.warn("Insufficient time remaining ({}ms < {}ms) for retry attempt {}/{}. Suppressing retry.",
                        timeRemainingMs, MIN_RETRY_MARGIN_MS, attempt, SYNTHESIS_MAX_ATTEMPTS);
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_SERVICE_TIMEOUT",
                        "AI Service execution timed out: insufficient deadline remaining for retry attempt");
            }

            if (attempt > 1) {
                long delayMs = Math.min(SYNTHESIS_BACKOFF_MS, timeRemainingMs - MIN_RETRY_MARGIN_MS);
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_INTERRUPTED",
                                "AI service synthesis interrupted during backoff", ie);
                    }
                }
                now = System.currentTimeMillis();
                timeRemainingMs = wallClockDeadline - now;
                if (timeRemainingMs < MIN_RETRY_MARGIN_MS) {
                    logger.warn("Wall-clock deadline exceeded after backoff sleep. Suppressing attempt {}/{}", attempt, SYNTHESIS_MAX_ATTEMPTS);
                    throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_SERVICE_TIMEOUT",
                            "AI Service execution timed out: deadline exceeded during retry backoff");
                }
            }

            int effectiveConnectTimeout = (int) Math.min(SYNTHESIS_CONNECT_TIMEOUT_MS, timeRemainingMs);
            int effectiveReadTimeout = (int) Math.min(SYNTHESIS_READ_TIMEOUT_MS, Math.max(1, timeRemainingMs - effectiveConnectTimeout));

            try {
                if (restTemplate instanceof RestTemplate) {
                    org.springframework.http.client.ClientHttpRequestFactory reqFactory = ((RestTemplate) restTemplate).getRequestFactory();
                    if (reqFactory instanceof SimpleClientHttpRequestFactory) {
                        ((SimpleClientHttpRequestFactory) reqFactory).setConnectTimeout(effectiveConnectTimeout);
                        ((SimpleClientHttpRequestFactory) reqFactory).setReadTimeout(effectiveReadTimeout);
                    }
                }

                ResponseEntity<com.repopilot.report.dto.ReportSynthesisOutputDto> response = restTemplate.exchange(
                        endpoint, HttpMethod.POST, entity, com.repopilot.report.dto.ReportSynthesisOutputDto.class
                );
                long durationMs = System.currentTimeMillis() - wallClockStartTime;

                if (durationMs > MAX_SYNTHESIS_WALL_CLOCK_MS) {
                    logger.error("AI synthesis completed but exceeded wall-clock limit: {}ms > {}ms", durationMs, MAX_SYNTHESIS_WALL_CLOCK_MS);
                    throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_SERVICE_TIMEOUT",
                            "AI Service execution timed out: total wall-clock duration " + durationMs + "ms exceeded " + MAX_SYNTHESIS_WALL_CLOCK_MS + "ms");
                }

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    com.repopilot.report.dto.ReportSynthesisOutputDto responseBody = response.getBody();
                    validateSynthesisOutputDto(responseBody);
                    logger.info("AI report synthesis completed reportId={}, durationMs={}",
                            inputDto.getImplementationReportId(), durationMs);
                    return responseBody;
                }
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status == 503) && attempt < SYNTHESIS_MAX_ATTEMPTS) {
                    logger.warn("AI Service synthesis returned HTTP {}. Retrying attempt {}/{} after backoff...", status, attempt, SYNTHESIS_MAX_ATTEMPTS);
                    continue;
                }
                logger.error("AI Service synthesis request failed with HTTP {}: {}", status, e.getResponseBodyAsString());
                if (status == 400) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "AI service rejected synthesis request: " + e.getResponseBodyAsString(), e);
                } else if (status == 502) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "AI service returned malformed synthesis output: " + e.getResponseBodyAsString(), e);
                } else if (status == 429) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LLM_RATE_LIMITED", "AI service rate limited: " + e.getResponseBodyAsString(), e);
                } else if (status == 503) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_UNAVAILABLE", "AI service unavailable: " + e.getResponseBodyAsString(), e);
                } else {
                    throw new ApiException(HttpStatus.valueOf(status), "AI_SERVICE_ERROR", "AI service error: " + e.getResponseBodyAsString(), e);
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                if (attempt < SYNTHESIS_MAX_ATTEMPTS) {
                    logger.warn("AI Service connection error on synthesis attempt {}/{}: {}. Retrying...", attempt, SYNTHESIS_MAX_ATTEMPTS, e.getMessage());
                    continue;
                }
                logger.error("AI Service synthesis call failed after {} attempts: {}", SYNTHESIS_MAX_ATTEMPTS, e.getMessage());
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_SERVICE_TIMEOUT", "AI Service execution timed out: " + e.getMessage(), e);
            }
        }
        throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_SERVICE_TIMEOUT", "AI Service report synthesis timed out after retries");
    }

    private void validateSynthesisOutputDto(com.repopilot.report.dto.ReportSynthesisOutputDto dto) {
        if (dto == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "ReportSynthesisOutputDto is null");
        }
        if (dto.getExecutiveSummary() == null || dto.getExecutiveSummary().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "Executive summary is null or empty");
        }
        if (dto.getImplementationExplanation() == null || dto.getImplementationExplanation().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "Implementation explanation is null or empty");
        }
        if (dto.getTestingExplanation() == null || dto.getTestingExplanation().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "Testing explanation is null or empty");
        }
        if (dto.getSecurityExplanation() == null || dto.getSecurityExplanation().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "Security explanation is null or empty");
        }
        if (dto.getLimitations() != null && dto.getLimitations().size() > 10) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "Limitations count exceeds maximum limit 10");
        }
        if (dto.getDeveloperNotes() != null && dto.getDeveloperNotes().size() > 10) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM_MALFORMED_OUTPUT", "Developer notes count exceeds maximum limit 10");
        }
    }
}
