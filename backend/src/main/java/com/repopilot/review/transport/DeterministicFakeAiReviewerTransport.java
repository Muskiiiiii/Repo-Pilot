package com.repopilot.review.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.review.dto.AiReviewerRequestDto;
import com.repopilot.review.dto.AiReviewerResponseDto;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.ReviewStatus;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic in-process fake transport for AI Reviewer testing.
 * Prevents test dependencies on external network or real LLM providers.
 */
@Component
@Primary
public class DeterministicFakeAiReviewerTransport implements AiReviewerTransport {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiReviewerResponseDto cannedResponse;
    private String cannedRawJson;
    private RuntimeException cannedException;
    private AiReviewerRequestDto lastReceivedRequest;
    private java.util.function.Consumer<AiReviewerRequestDto> onRequestCallback;
    private long delayMs = 0;

    public DeterministicFakeAiReviewerTransport() {
        this.cannedResponse = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED,
                "Deterministic automated review complete",
                FindingSeverity.LOW,
                List.of()
        );
    }

    public void setCannedResponse(AiReviewerResponseDto response) {
        this.cannedResponse = response;
        this.cannedRawJson = null;
        this.cannedException = null;
    }

    public void setCannedRawJson(String rawJson) {
        this.cannedRawJson = rawJson;
        this.cannedResponse = null;
        this.cannedException = null;
    }

    public void setCannedException(RuntimeException exception) {
        this.cannedException = exception;
    }

    public void setOnRequestCallback(java.util.function.Consumer<AiReviewerRequestDto> callback) {
        this.onRequestCallback = callback;
    }

    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    public void reset() {
        this.cannedResponse = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED,
                "Deterministic automated review complete",
                FindingSeverity.LOW,
                List.of()
        );
        this.cannedRawJson = null;
        this.cannedException = null;
        this.lastReceivedRequest = null;
        this.onRequestCallback = null;
        this.delayMs = 0;
    }

    public AiReviewerRequestDto getLastReceivedRequest() {
        return lastReceivedRequest;
    }

    @Override
    public AiReviewerResponseDto sendReviewRequest(AiReviewerRequestDto request) {
        String rawJson = sendReviewRequestRawJson(request);
        try {
            return objectMapper.readValue(rawJson, AiReviewerResponseDto.class);
        } catch (Exception e) {
            throw AiReviewerTransportException.malformedResponse("Failed to deserialize fake response JSON: " + e.getMessage());
        }
    }

    @Override
    public String sendReviewRequestRawJson(AiReviewerRequestDto request) {
        this.lastReceivedRequest = request;
        if (onRequestCallback != null) {
            onRequestCallback.accept(request);
        }

        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw AiReviewerTransportException.transportFailure("Fake transport interrupted during delay simulation");
            }
        }

        if (cannedException != null) {
            throw cannedException;
        }

        if (cannedRawJson != null) {
            return cannedRawJson;
        }

        if (cannedResponse != null) {
            try {
                return objectMapper.writeValueAsString(cannedResponse);
            } catch (Exception e) {
                throw AiReviewerTransportException.malformedResponse("Failed to serialize canned response to JSON: " + e.getMessage());
            }
        }

        return "{\"status\":\"COMPLETED\",\"summary\":\"Default fake review response\",\"overallRiskScore\":\"LOW\",\"findings\":[]}";
    }
}
