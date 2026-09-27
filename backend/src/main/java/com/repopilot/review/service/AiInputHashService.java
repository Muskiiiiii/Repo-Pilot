package com.repopilot.review.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repopilot.review.dto.AiReviewPayload;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Service
public class AiInputHashService {

    private final ObjectMapper mapper;

    public AiInputHashService() {
        this.mapper = new ObjectMapper()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String computeAiInputHash(AiReviewPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("AiReviewPayload cannot be null");
        }
        try {
            List<String> sortedFileList = payload.fileList() != null
                    ? payload.fileList().stream().distinct().sorted().toList()
                    : List.of();

            CanonicalPayload canonical = new CanonicalPayload(
                    payload.codeChangeId() != null ? payload.codeChangeId().toString() : "",
                    sortedFileList,
                    payload.modelIdentifier() != null ? payload.modelIdentifier() : "gemini-2.5-pro",
                    payload.projectId() != null ? payload.projectId().toString() : "",
                    payload.promptVersion() != null ? payload.promptVersion() : "p2026.1",
                    payload.redactedDiff() != null ? payload.redactedDiff() : "",
                    payload.repositoryId() != null ? payload.repositoryId().toString() : "",
                    payload.reviewMode() != null ? payload.reviewMode() : "",
                    payload.sanitizedTitle() != null ? payload.sanitizedTitle() : "",
                    payload.taskId() != null ? payload.taskId().toString() : ""
            );

            String canonicalJson = mapper.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute ai_input_hash", e);
        }
    }

    private record CanonicalPayload(
            String codeChangeId,
            List<String> fileList,
            String modelIdentifier,
            String projectId,
            String promptVersion,
            String redactedDiff,
            String repositoryId,
            String reviewMode,
            String sanitizedTitle,
            String taskId
    ) {}
}
