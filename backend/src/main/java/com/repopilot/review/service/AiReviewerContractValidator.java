package com.repopilot.review.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.AiReviewerFindingDto;
import com.repopilot.review.dto.AiReviewerResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiReviewerContractValidator {

    public static final int MAX_FINDINGS_LIMIT = 50;
    public static final int MAX_SUMMARY_LENGTH = 5_000;
    public static final int MAX_TITLE_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 10_000;
    public static final int MAX_EVIDENCE_LENGTH = 10_000;
    public static final int MAX_REMEDIATION_LENGTH = 10_000;
    public static final int MAX_CATEGORY_LENGTH = 100;
    public static final int MAX_FILEPATH_LENGTH = 500;

    private final ObjectMapper strictObjectMapper;

    public AiReviewerContractValidator() {
        this.strictObjectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public AiReviewerResponseDto deserializeStrictJson(String json) {
        if (json == null || json.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_JSON", "Reviewer response JSON cannot be null or blank");
        }
        try {
            AiReviewerResponseDto response = strictObjectMapper.readValue(json, AiReviewerResponseDto.class);
            validateResponse(response);
            return response;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_JSON", "Strict contract deserialization failed: " + e.getMessage());
        }
    }

    public void validateResponse(AiReviewerResponseDto response) {
        if (response == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_RESPONSE", "AiReviewerResponseDto cannot be null");
        }
        if (response.status() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_RESPONSE", "Review status cannot be null");
        }
        if (response.overallRiskScore() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_RESPONSE", "Overall risk score cannot be null");
        }
        if (response.summary() != null && response.summary().length() > MAX_SUMMARY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_RESPONSE", "Summary exceeds maximum length of " + MAX_SUMMARY_LENGTH);
        }

        List<AiReviewerFindingDto> findings = response.findings();
        if (findings != null) {
            if (findings.size() > MAX_FINDINGS_LIMIT) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RESOURCE_LIMIT_EXCEEDED", "Finding count exceeds maximum limit of " + MAX_FINDINGS_LIMIT);
            }
            for (int i = 0; i < findings.size(); i++) {
                validateFinding(findings.get(i), i);
            }
        }
    }

    private void validateFinding(AiReviewerFindingDto finding, int index) {
        if (finding == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding at index " + index + " is null");
        }
        if (finding.findingType() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": findingType cannot be null");
        }
        if (finding.severity() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": severity cannot be null");
        }
        if (finding.confidence() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": confidence cannot be null");
        }
        if (finding.side() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": side cannot be null");
        }
        if (finding.title() == null || finding.title().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": title cannot be null or blank");
        }
        if (finding.title().length() > MAX_TITLE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": title exceeds max length of " + MAX_TITLE_LENGTH);
        }
        if (finding.description() == null || finding.description().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": description cannot be null or blank");
        }
        if (finding.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": description exceeds max length of " + MAX_DESCRIPTION_LENGTH);
        }
        if (finding.filePath() == null || finding.filePath().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": filePath cannot be null or blank");
        }
        if (finding.filePath().length() > MAX_FILEPATH_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": filePath exceeds max length of " + MAX_FILEPATH_LENGTH);
        }
        if (finding.category() != null && finding.category().length() > MAX_CATEGORY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": category exceeds max length of " + MAX_CATEGORY_LENGTH);
        }
        if (finding.evidence() != null && finding.evidence().length() > MAX_EVIDENCE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": evidence exceeds max length of " + MAX_EVIDENCE_LENGTH);
        }
        if (finding.remediation() != null && finding.remediation().length() > MAX_REMEDIATION_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": remediation exceeds max length of " + MAX_REMEDIATION_LENGTH);
        }

        Integer startLine = finding.startLine();
        Integer endLine = finding.endLine();
        if (startLine == null || endLine == null || startLine < 1 || endLine < startLine) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTRACT_FINDING", "Finding #" + index + ": invalid line range [" + startLine + ", " + endLine + "]");
        }
    }
}
