package com.repopilot.approval.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.approval.entity.HumanApproval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;

public class ApprovalCanonicalEncoder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static String encodeCanonicalRecord(HumanApproval approval) {
        if (approval == null) {
            throw new IllegalArgumentException("HumanApproval cannot be null");
        }

        Map<String, String> map = new TreeMap<>();

        map.put("approvedAt", normalizeString(approval.getApprovedAt() != null ? approval.getApprovedAt().toString() : ""));
        map.put("approvedByUserEmail", normalizeString(approval.getApprovedByUserEmail()));
        map.put("approvedByUserId", approval.getApprovedByUser() != null ? approval.getApprovedByUser().getId().toString().toLowerCase(Locale.ROOT) : "");
        map.put("approvedByUserRole", normalizeString(approval.getApprovedByUserRole()));
        map.put("approvedChangeCount", String.valueOf(approval.getApprovedChangeCount() != null ? approval.getApprovedChangeCount() : 0));
        map.put("approvedFiles", canonicalizeJsonArray(approval.getApprovedFiles()));
        map.put("approvalVersion", String.valueOf(approval.getApprovalVersion() != null ? approval.getApprovalVersion() : 1));
        map.put("codeChangeDiffHash", normalizeHash(approval.getCodeChangeDiffHash()));
        map.put("codeChangeId", approval.getCodeChange() != null ? approval.getCodeChange().getId().toString().toLowerCase(Locale.ROOT) : "");
        map.put("codeChangeOriginalHash", normalizeHash(approval.getCodeChangeOriginalHash()));
        map.put("codeChangeProposedHash", normalizeHash(approval.getCodeChangeProposedHash()));
        map.put("projectId", approval.getProject() != null ? approval.getProject().getId().toString().toLowerCase(Locale.ROOT) : "");
        map.put("repositoryBaseSha", normalizeHash(approval.getRepositoryBaseSha()));
        map.put("repositoryId", approval.getRepository() != null ? approval.getRepository().getId().toString().toLowerCase(Locale.ROOT) : "");
        map.put("reviewContextHash", normalizeHash(approval.getReviewContextHash()));
        map.put("reviewId", approval.getReview() != null ? approval.getReview().getId().toString().toLowerCase(Locale.ROOT) : "");
        map.put("taskId", approval.getTask() != null ? approval.getTask().getId().toString().toLowerCase(Locale.ROOT) : "");

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            sb.append(key).append(":").append(val.getBytes(StandardCharsets.UTF_8).length).append(":").append(val).append("\n");
        }
        return sb.toString();
    }

    public static String computeApprovalRecordHash(HumanApproval approval) {
        String canonical = encodeCanonicalRecord(approval);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            HexFormat hexFormat = HexFormat.of();
            return hexFormat.formatHex(hashBytes).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }

    public static boolean verifyApprovalRecordHash(HumanApproval approval) {
        if (approval == null || approval.getApprovalRecordHash() == null) {
            return false;
        }
        String computedHash = computeApprovalRecordHash(approval);
        return MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                approval.getApprovalRecordHash().trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static String normalizeString(String input) {
        if (input == null) return "";
        return Normalizer.normalize(input.trim(), Normalizer.Form.NFC);
    }

    public static String normalizeHash(String hash) {
        if (hash == null) return "";
        return hash.trim().toLowerCase(Locale.ROOT);
    }

    public static String canonicalizeJsonArray(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return "[]";
        try {
            List<String> list = OBJECT_MAPPER.readValue(jsonArray, new TypeReference<List<String>>() {});
            List<String> sortedList = list.stream()
                    .map(ApprovalCanonicalEncoder::normalizeString)
                    .sorted()
                    .toList();
            return OBJECT_MAPPER.writeValueAsString(sortedList);
        } catch (Exception e) {
            return jsonArray.trim();
        }
    }
}
