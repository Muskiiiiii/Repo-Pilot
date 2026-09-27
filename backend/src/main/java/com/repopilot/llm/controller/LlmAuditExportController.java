package com.repopilot.llm.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.crypto.AuditExportCryptoService;
import com.repopilot.llm.dto.AuditExportJobResponse;
import com.repopilot.llm.dto.AuditExportTokenClaims;
import com.repopilot.llm.dto.AuditExportTokenResponse;
import com.repopilot.llm.dto.CreateAuditExportRequest;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditExportJobStatus;
import com.repopilot.llm.repository.AuditExportJobRepository;
import com.repopilot.llm.service.AuditExportJobEngineService;
import com.repopilot.llm.service.AuditExportTokenService;
import com.repopilot.llm.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/audit-exports")
public class LlmAuditExportController {

    private final AuditExportJobEngineService auditExportJobEngineService;
    private final AuditExportTokenService auditExportTokenService;
    private final AuditExportJobRepository auditExportJobRepository;
    private final ObjectStorageService objectStorageService;
    private final AuditExportCryptoService auditExportCryptoService;

    @Autowired
    public LlmAuditExportController(AuditExportJobEngineService auditExportJobEngineService,
                                    AuditExportTokenService auditExportTokenService,
                                    AuditExportJobRepository auditExportJobRepository,
                                    ObjectStorageService objectStorageService,
                                    AuditExportCryptoService auditExportCryptoService) {
        this.auditExportJobEngineService = auditExportJobEngineService;
        this.auditExportTokenService = auditExportTokenService;
        this.auditExportJobRepository = auditExportJobRepository;
        this.objectStorageService = objectStorageService;
        this.auditExportCryptoService = auditExportCryptoService;
    }

    @PostMapping
    public ResponseEntity<AuditExportJobResponse> createExportJob(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @RequestBody CreateAuditExportRequest request) {
        AuditExportJobResponse response = auditExportJobEngineService.createExportJob(orgId, principal.getUser(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<AuditExportJobResponse> getExportJob(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("jobId") UUID jobId) {
        AuditExportJobResponse response = auditExportJobEngineService.getExportJob(orgId, jobId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<AuditExportJobResponse>> getExportJobs(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<AuditExportJobResponse> response = auditExportJobEngineService.getExportJobs(orgId, principal.getUser(), page, size);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{jobId}/download-token")
    public ResponseEntity<AuditExportTokenResponse> createDownloadToken(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("jobId") UUID jobId) {
        AuditExportTokenResponse response = auditExportTokenService.createDownloadToken(orgId, jobId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{jobId}/download")
    public ResponseEntity<byte[]> downloadExport(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("jobId") UUID jobId,
            @RequestParam("token") String token) {
        AuditExportTokenClaims claims = auditExportTokenService.verifyAndConsumeToken(token, orgId, jobId, principal.getUser());

        AuditExportJob job = auditExportJobRepository.findByOrganizationIdAndId(orgId, jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Export job not found for organization"));

        if (job.getStatus() != AuditExportJobStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "Export job is not in COMPLETED state");
        }
        if (Boolean.TRUE.equals(job.getArtifactPurged())) {
            throw new ApiException(HttpStatus.GONE, "Export artifact has been purged");
        }

        byte[] ciphertext = objectStorageService.getObject(job.getArtifactStoragePath());

        byte[] decryptedGzip = auditExportCryptoService.decrypt(
                ciphertext,
                job.getEncryptedDek(),
                job.getGcmNonce(),
                job.getKeyVersion(),
                job.getArtifactChecksumSha256(),
                job.getEncryptedArtifactChecksumSha256(),
                orgId,
                jobId,
                job.getSnapshotTimestamp()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/gzip")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-export-" + jobId + ".jsonl.gz\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(decryptedGzip.length))
                .body(decryptedGzip);
    }

    @PostMapping("/{jobId}/rewrap-key")
    public ResponseEntity<AuditExportJobResponse> rewrapKey(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("jobId") UUID jobId) {
        AuditExportJobResponse response = auditExportJobEngineService.rewrapExportKey(orgId, jobId, principal.getUser());
        return ResponseEntity.ok(response);
    }
}
