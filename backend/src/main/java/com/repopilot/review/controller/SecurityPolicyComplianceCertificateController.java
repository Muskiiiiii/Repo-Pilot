package com.repopilot.review.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.CreatePolicyComplianceCertificateRequestDto;
import com.repopilot.review.dto.SecurityPolicyComplianceCertificateDto;
import com.repopilot.review.service.SecurityPolicyComplianceCertificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * REST Controller for Phase 13 Slice 12.5 Security Policy Compliance Certificates.
 * Thin transport layer delegating authorization, hierarchy validation, policy evaluation,
 * canonical hashing, idempotency recovery, and audit logging to Slice 12.4 service.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/compliance-certificates")
public class SecurityPolicyComplianceCertificateController {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{8,128}$");

    private final SecurityPolicyComplianceCertificationService certificationService;

    public SecurityPolicyComplianceCertificateController(SecurityPolicyComplianceCertificationService certificationService) {
        this.certificationService = certificationService;
    }

    @PostMapping
    public ResponseEntity<SecurityPolicyComplianceCertificateDto> issueCertificate(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID reviewId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String xIdempotencyKeyHeader,
            @RequestBody(required = false) CreatePolicyComplianceCertificateRequestDto bodyDto,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank() &&
                xIdempotencyKeyHeader != null && !xIdempotencyKeyHeader.isBlank() &&
                !idempotencyKeyHeader.equals(xIdempotencyKeyHeader)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key and X-Idempotency-Key header values conflict");
        }

        String idempotencyKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
                ? idempotencyKeyHeader
                : (xIdempotencyKeyHeader != null && !xIdempotencyKeyHeader.isBlank()
                        ? xIdempotencyKeyHeader
                        : (bodyDto != null ? bodyDto.idempotencyKey() : null));

        if (idempotencyKey == null || idempotencyKey.isBlank() || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required and must match ^[a-zA-Z0-9_-]{8,128}$");
        }

        CreatePolicyComplianceCertificateRequestDto requestDto = new CreatePolicyComplianceCertificateRequestDto(idempotencyKey);

        SecurityPolicyComplianceCertificateDto certDto = certificationService.issueCertificate(
                projectId, repositoryId, taskId, reviewId, requestDto, currentUserPrincipal.getUser()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(certDto);
    }

    @GetMapping("/{certificateId}")
    public ResponseEntity<SecurityPolicyComplianceCertificateDto> getCertificate(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID reviewId,
            @PathVariable UUID certificateId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        SecurityPolicyComplianceCertificateDto certDto = certificationService.getCertificate(
                projectId, repositoryId, taskId, reviewId, certificateId, currentUserPrincipal.getUser()
        );

        return ResponseEntity.ok(certDto);
    }

    @GetMapping("/by-idempotency-key/{idempotencyKey}")
    public ResponseEntity<SecurityPolicyComplianceCertificateDto> getCertificateByIdempotencyKey(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID reviewId,
            @PathVariable String idempotencyKey,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        if (idempotencyKey == null || idempotencyKey.isBlank() || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must match ^[a-zA-Z0-9_-]{8,128}$");
        }

        SecurityPolicyComplianceCertificateDto certDto = certificationService.getCertificateByIdempotencyKey(
                projectId, reviewId, idempotencyKey, currentUserPrincipal.getUser()
        );

        return ResponseEntity.ok(certDto);
    }
}
