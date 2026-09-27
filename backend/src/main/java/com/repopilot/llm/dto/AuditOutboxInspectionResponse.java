package com.repopilot.llm.dto;

import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.AuditOutboxDeliveryState;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public class AuditOutboxInspectionResponse {

    private UUID eventId;
    private String canonicalAuditEventId;
    private UUID occurrenceId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payloadJson;
    private AuditOutboxDeliveryState deliveryState;
    private Integer retryCount;
    private Instant createdAt;
    private Instant processedAt;

    private static final com.fasterxml.jackson.databind.ObjectMapper SANITIZE_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "workerleasetoken", "leasetoken", "apikey", "api_key", "password", "passwordhash",
            "secret", "clientsecret", "providersecret", "authorization", "bearer", "bearertoken",
            "accesstoken", "access_token", "jwttoken", "privatekey", "githubaccesstoken",
            "rawprompt", "prompt", "prompttext", "rawcompletion", "completion", "completiontext",
            "token", "tokens", "headers"
    );

    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "\"(workerLeaseToken|leaseToken|apiKey|api_key|password|passwordHash|secret|clientSecret|providerSecret|authorization|bearer|bearerToken|accessToken|access_token|jwtToken|privateKey|githubAccessToken|rawPrompt|prompt|promptText|rawCompletion|completion|completionText|token|tokens|headers)\"\\s*:\\s*\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE
    );

    public AuditOutboxInspectionResponse() {
    }

    public static AuditOutboxInspectionResponse fromEntity(AuditLogOutbox entity) {
        if (entity == null) return null;
        AuditOutboxInspectionResponse dto = new AuditOutboxInspectionResponse();
        dto.setEventId(entity.getId());
        dto.setCanonicalAuditEventId(entity.getCanonicalAuditEventId());
        dto.setOccurrenceId(entity.getOccurrenceId());
        dto.setAggregateType(entity.getAggregateType());
        dto.setAggregateId(entity.getAggregateId());
        dto.setEventType(entity.getEventType());
        dto.setPayloadJson(sanitizePayload(entity.getPayloadJson()));
        dto.setDeliveryState(entity.getDeliveryState());
        dto.setRetryCount(entity.getRetryCount());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setProcessedAt(entity.getProcessedAt());
        return dto;
    }

    public static String sanitizePayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return rawPayload;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = SANITIZE_MAPPER.readTree(rawPayload);
            sanitizeNode(root);
            return SANITIZE_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return SENSITIVE_KEY_PATTERN.matcher(rawPayload).replaceAll("\"$1\":\"[REDACTED]\"");
        }
    }

    private static void sanitizeNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            java.util.Iterator<String> fieldNames = obj.fieldNames();
            java.util.List<String> fieldsToRedact = new java.util.ArrayList<>();

            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                String lower = fieldName.toLowerCase(java.util.Locale.ROOT);
                if (SENSITIVE_KEYS.contains(lower) || lower.contains("secret") || lower.contains("key") || lower.contains("token") || lower.contains("password") || lower.contains("prompt") || lower.contains("completion") || lower.contains("auth")) {
                    fieldsToRedact.add(fieldName);
                } else {
                    sanitizeNode(obj.get(fieldName));
                }
            }

            for (String field : fieldsToRedact) {
                obj.put(field, "[REDACTED]");
            }
        } else if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
            for (com.fasterxml.jackson.databind.JsonNode item : array) {
                sanitizeNode(item);
            }
        }
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getCanonicalAuditEventId() {
        return canonicalAuditEventId;
    }

    public void setCanonicalAuditEventId(String canonicalAuditEventId) {
        this.canonicalAuditEventId = canonicalAuditEventId;
    }

    public UUID getOccurrenceId() {
        return occurrenceId;
    }

    public void setOccurrenceId(UUID occurrenceId) {
        this.occurrenceId = occurrenceId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public AuditOutboxDeliveryState getDeliveryState() {
        return deliveryState;
    }

    public void setDeliveryState(AuditOutboxDeliveryState deliveryState) {
        this.deliveryState = deliveryState;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
