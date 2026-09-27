package com.repopilot.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.repopilot.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class AuditPayloadSanitizer {

    public static final int MAX_NESTING_DEPTH = 32;
    public static final int MAX_PAYLOAD_BYTES = 512 * 1024; // 512 KB
    public static final String REDACTED_VALUE = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEY_NAMES = Set.of(
            "rawprompt", "rawcompletion", "apikey", "api_key", "password", "token",
            "authorization", "authorizationheader", "accesstoken", "access_token",
            "jwttoken", "githubaccesstoken", "workerleasetoken", "privatekey",
            "secret", "clientsecret", "credential", "bearer", "bearertoken",
            "refreshtoken", "jwt_token", "github_access_token",
            "worker_lease_token", "private_key", "client_secret"
    );

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[a-zA-Z0-9_.~+-]+");
    private static final Pattern GITHUB_TOKEN_PATTERN = Pattern.compile("(?i)(ghp|gho|ghu|ghs|ghr|github_pat)_[a-zA-Z0-9_]{16,255}");
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile("(?s)-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----.*?-----END (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern LLM_KEY_PATTERN = Pattern.compile("sk-[a-zA-Z0-9_-]{16,}");
    private static final Pattern GENERIC_SECRET_PAIR_PATTERN = Pattern.compile("(?i)(\"?(?:password|token|secret|apiKey|api_key|authorization|authorizationHeader|accessToken|refreshToken|jwtToken|githubAccessToken|workerLeaseToken|privateKey|clientSecret|credential)\"?\\s*[:=]\\s*\")[^\"]+(\")");

    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode sanitizePayloadJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return NullNode.getInstance();
        }

        if (rawJson.length() > MAX_PAYLOAD_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYLOAD_TOO_LARGE", "Audit payload size exceeds maximum allowed size of 512KB");
        }

        try {
            JsonNode root = mapper.readTree(rawJson);
            return sanitizeNode(root, 0);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // Malformed JSON handling: redact using regex fallback without leaking secret
            String redacted = sanitizeStringScalar(rawJson);
            if (redacted.equals(rawJson)) {
                return TextNode.valueOf("[MALFORMED_PAYLOAD_REDACTED]");
            }
            return TextNode.valueOf(redacted);
        }
    }

    public JsonNode sanitizeNode(JsonNode node, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXCESSIVE_NESTING_DEPTH", "Audit payload nesting depth exceeds maximum allowed limit of " + MAX_NESTING_DEPTH);
        }

        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            ObjectNode sanitizedObj = mapper.createObjectNode();

            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);

            for (String fieldName : fieldNames) {
                JsonNode childNode = objectNode.get(fieldName);
                if (isSensitiveKey(fieldName)) {
                    sanitizedObj.put(fieldName, REDACTED_VALUE);
                } else {
                    sanitizedObj.set(fieldName, sanitizeNode(childNode, depth + 1));
                }
            }
            return sanitizedObj;
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            ArrayNode sanitizedArray = mapper.createArrayNode();
            for (JsonNode element : arrayNode) {
                sanitizedArray.add(sanitizeNode(element, depth + 1));
            }
            return sanitizedArray;
        } else if (node.isTextual()) {
            String text = node.asText();
            if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) {
                try {
                    JsonNode nestedNode = mapper.readTree(text);
                    return sanitizeNode(nestedNode, depth + 1);
                } catch (Exception ignored) {}
            }
            return TextNode.valueOf(sanitizeStringScalar(text));
        } else {
            return node;
        }
    }

    public boolean isSensitiveKey(String keyName) {
        if (keyName == null || keyName.isBlank()) return false;
        String normalized = keyName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (SENSITIVE_KEY_NAMES.contains(normalized)) {
            return true;
        }
        for (String target : SENSITIVE_KEY_NAMES) {
            if (normalized.equals(target) || normalized.contains(target)) {
                return true;
            }
        }
        return false;
    }

    public String sanitizeStringScalar(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = BEARER_PATTERN.matcher(text).replaceAll("Bearer " + REDACTED_VALUE);
        result = GITHUB_TOKEN_PATTERN.matcher(result).replaceAll(REDACTED_VALUE);
        result = PRIVATE_KEY_PATTERN.matcher(result).replaceAll(REDACTED_VALUE);
        result = LLM_KEY_PATTERN.matcher(result).replaceAll(REDACTED_VALUE);
        result = GENERIC_SECRET_PAIR_PATTERN.matcher(result).replaceAll("$1" + REDACTED_VALUE + "$2");
        return result;
    }
}
