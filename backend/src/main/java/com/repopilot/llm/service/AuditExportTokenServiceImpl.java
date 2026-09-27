package com.repopilot.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.AuditExportTokenClaims;
import com.repopilot.llm.dto.AuditExportTokenResponse;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditExportJobStatus;
import com.repopilot.llm.entity.ConsumedDownloadToken;
import com.repopilot.llm.repository.AuditExportJobRepository;
import com.repopilot.llm.repository.ConsumedDownloadTokenRepository;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditExportTokenServiceImpl implements AuditExportTokenService {

    private static final long TOKEN_VALIDITY_SECONDS = 15 * 60L; // 15 minutes

    private final AuditExportJobRepository auditExportJobRepository;
    private final ConsumedDownloadTokenRepository consumedDownloadTokenRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final ObjectMapper objectMapper;
    private final String hmacSecret;

    @Autowired
    public AuditExportTokenServiceImpl(AuditExportJobRepository auditExportJobRepository,
                                       ConsumedDownloadTokenRepository consumedDownloadTokenRepository,
                                       OrganizationAuthorizationService organizationAuthorizationService,
                                       ObjectMapper objectMapper,
                                       @Value("${repopilot.audit.export.token-secret:RepoPilotAuditExportDownloadTokenSecretKey256BitMustBeStrong!}") String hmacSecret) {
        this.auditExportJobRepository = auditExportJobRepository;
        this.consumedDownloadTokenRepository = consumedDownloadTokenRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
        this.objectMapper = objectMapper;
        this.hmacSecret = hmacSecret;
    }

    @Override
    @Transactional
    public AuditExportTokenResponse createDownloadToken(UUID orgId, UUID jobId, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);

        AuditExportJob job = auditExportJobRepository.findByOrganizationIdAndId(orgId, jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Export job not found"));

        if (job.getStatus() != AuditExportJobStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot issue download token for export job in status: " + job.getStatus());
        }
        if (Boolean.TRUE.equals(job.getArtifactPurged())) {
            throw new ApiException(HttpStatus.GONE, "Export artifact has been purged or deleted");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(TOKEN_VALIDITY_SECONDS);
        String jti = UUID.randomUUID().toString();

        AuditExportTokenClaims claims = new AuditExportTokenClaims(jti, jobId, orgId, caller.getId(), now, expiresAt);
        String tokenString = buildSignedToken(claims);

        return new AuditExportTokenResponse(tokenString, expiresAt, jobId, orgId);
    }

    @Override
    @Transactional
    public AuditExportTokenClaims verifyAndConsumeToken(String tokenString, UUID requestedOrgId, UUID requestedJobId, User caller) {
        if (tokenString == null || tokenString.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Download token is missing");
        }

        organizationAuthorizationService.requireMinimumRole(requestedOrgId, caller, OrganizationRole.ORG_MEMBER);

        // 1. Verify HMAC Signature and parse claims
        AuditExportTokenClaims claims = parseAndVerifySignedToken(tokenString);

        // 2. Server-side Expiry Check
        Instant now = Instant.now();
        if (now.isAfter(claims.getExpiresAt())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Download token has expired");
        }
        if (claims.getIssuedAt() != null && now.isBefore(claims.getIssuedAt().minusSeconds(60))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Download token issued-at timestamp is invalid");
        }

        // 3. Claims Authorization Binding Checks
        if (!claims.getOrganizationId().equals(requestedOrgId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Download token organization mismatch");
        }
        if (!claims.getJobId().equals(requestedJobId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Download token job mismatch");
        }
        if (!claims.getUserId().equals(caller.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Download token user mismatch");
        }

        // 4. Verify Export Job Status
        AuditExportJob job = auditExportJobRepository.findByOrganizationIdAndId(claims.getOrganizationId(), claims.getJobId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Export job not found"));

        if (job.getStatus() != AuditExportJobStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "Export job is not downloadable in state: " + job.getStatus());
        }
        if (Boolean.TRUE.equals(job.getArtifactPurged())) {
            throw new ApiException(HttpStatus.GONE, "Export artifact has been purged or deleted");
        }

        // 5. Durable Atomic Consumption (Single-Use Enforcement via DB PK Unique Constraint)
        String jtiHash = hashJti(claims.getJti());
        if (consumedDownloadTokenRepository.existsById(jtiHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "Download token has already been consumed (single-use constraint)");
        }
        Organization org = organizationAuthorizationService.getAuthorizedOrganization(requestedOrgId, caller);

        try {
            ConsumedDownloadToken tokenEntity = new ConsumedDownloadToken(jtiHash, job, org, caller, claims.getExpiresAt());
            consumedDownloadTokenRepository.saveAndFlush(tokenEntity);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "Download token has already been consumed (single-use constraint)");
        }

        return claims;
    }

    private String buildSignedToken(AuditExportTokenClaims claims) {
        try {
            Map<String, Object> map = Map.of(
                    "jti", claims.getJti(),
                    "jobId", claims.getJobId().toString(),
                    "orgId", claims.getOrganizationId().toString(),
                    "userId", claims.getUserId().toString(),
                    "exp", claims.getExpiresAt().getEpochSecond(),
                    "iat", claims.getIssuedAt().getEpochSecond()
            );
            String payloadJson = objectMapper.writeValueAsString(map);
            String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = computeHmac(payloadB64);
            return payloadB64 + "." + signatureB64;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to build signed download token");
        }
    }

    private AuditExportTokenClaims parseAndVerifySignedToken(String tokenString) {
        String[] parts = tokenString.split("\\.");
        if (parts.length != 2) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid download token structure");
        }
        String payloadB64 = parts[0];
        String expectedSig = parts[1];

        String actualSig = computeHmac(payloadB64);
        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8), actualSig.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid download token signature");
        }

        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(payloadB64);
            Map<?, ?> map = objectMapper.readValue(decodedBytes, Map.class);

            String jti = (String) map.get("jti");
            UUID jobId = UUID.fromString((String) map.get("jobId"));
            UUID orgId = UUID.fromString((String) map.get("orgId"));
            UUID userId = UUID.fromString((String) map.get("userId"));
            long exp = ((Number) map.get("exp")).longValue();
            long iat = ((Number) map.get("iat")).longValue();

            return new AuditExportTokenClaims(jti, jobId, orgId, userId, Instant.ofEpochSecond(iat), Instant.ofEpochSecond(exp));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Failed to parse download token payload");
        }
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    private String hashJti(String jti) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(jti.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not found", e);
        }
    }
}
