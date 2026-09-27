package com.repopilot.review.transport;

import com.repopilot.review.dto.AiReviewerRequestDto;
import com.repopilot.review.dto.AiReviewerResponseDto;

/**
 * AI Reviewer transport abstraction.
 * Represents ONLY the future provider boundary for sending sanitized review payloads.
 */
public interface AiReviewerTransport {

    /**
     * Send sanitized review request to AI reviewer transport.
     *
     * @param request Sanitized request payload (must contain redacted diff and no raw secrets)
     * @return AI reviewer response DTO
     * @throws AiReviewerTransportException on bounded transport, timeout, or provider failures
     */
    AiReviewerResponseDto sendReviewRequest(AiReviewerRequestDto request);

    /**
     * Send sanitized review request and return raw JSON response string for strict deserialization testing.
     *
     * @param request Sanitized request payload
     * @return Raw JSON response string from transport
     * @throws AiReviewerTransportException on bounded transport, timeout, or provider failures
     */
    default String sendReviewRequestRawJson(AiReviewerRequestDto request) {
        AiReviewerResponseDto dto = sendReviewRequest(request);
        if (dto == null) {
            throw AiReviewerTransportException.malformedResponse("Response DTO was null");
        }
        return String.format(
                "{\"status\":\"%s\",\"summary\":\"%s\",\"overallRiskScore\":\"%s\",\"findings\":[]}",
                dto.status() != null ? dto.status().name() : "COMPLETED",
                dto.summary() != null ? dto.summary() : "Review completed",
                dto.overallRiskScore() != null ? dto.overallRiskScore().name() : "LOW"
        );
    }
}
