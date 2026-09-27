package com.repopilot.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.CanonicalAuditExportRecord;
import com.repopilot.llm.dto.ExportStreamingResult;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.repository.AuditExportJobRepository;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

@Service
public class AuditExportStreamingServiceImpl implements AuditExportStreamingService {

    private final AuditLogOutboxRepository auditLogOutboxRepository;
    private final AuditExportJobRepository auditExportJobRepository;
    private final AuditPayloadSanitizer auditPayloadSanitizer;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AuditExportStreamingServiceImpl(AuditLogOutboxRepository auditLogOutboxRepository,
                                           AuditExportJobRepository auditExportJobRepository,
                                           AuditPayloadSanitizer auditPayloadSanitizer,
                                           OrganizationAuthorizationService organizationAuthorizationService) {
        this.auditLogOutboxRepository = auditLogOutboxRepository;
        this.auditExportJobRepository = auditExportJobRepository;
        this.auditPayloadSanitizer = auditPayloadSanitizer;
        this.organizationAuthorizationService = organizationAuthorizationService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Override
    @Transactional(readOnly = true)
    public ExportStreamingResult streamAuditExport(UUID orgId, UUID jobId, User caller, OutputStream targetOutputStream) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_ADMIN);

        AuditExportJob job = auditExportJobRepository.findByOrganizationIdAndId(orgId, jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Export job not found for organization"));

        return processExportStream(job, targetOutputStream);
    }

    @Override
    @Transactional(readOnly = true)
    public ExportStreamingResult processExportStream(AuditExportJob job, OutputStream targetOutputStream) {
        if (job == null || job.getOrganization() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JOB", "Job or organization is null");
        }

        UUID orgId = job.getOrganization().getId();
        Long maxSeq = job.getMaxSequenceNumber();
        if (maxSeq == null) {
            maxSeq = 0L;
        }

        CountingOutputStream countingStream = new CountingOutputStream(targetOutputStream);
        long recordCount = 0L;

        try (GZIPOutputStream gzipOut = new GZIPOutputStream(countingStream)) {
            long lastSeenSeq = 0L;
            int batchSize = 200;
            boolean hasMore = true;

            while (hasMore) {
                Pageable pageable = PageRequest.of(0, batchSize);
                List<AuditLogOutbox> chunk;

                if (job.getFilterFromTimestamp() != null && job.getFilterToTimestamp() != null) {
                    chunk = auditLogOutboxRepository.findChunkForExportFiltered(
                            orgId, lastSeenSeq, maxSeq,
                            job.getFilterFromTimestamp(), job.getFilterToTimestamp(), pageable
                    );
                } else {
                    chunk = auditLogOutboxRepository.findChunkForExport(orgId, lastSeenSeq, maxSeq, pageable);
                }

                if (chunk == null || chunk.isEmpty()) {
                    hasMore = false;
                    break;
                }

                for (AuditLogOutbox event : chunk) {
                    // Tenant boundary defense-in-depth check
                    if (!belongsToOrganization(event, orgId)) {
                        continue;
                    }

                    // Sequence boundary defense-in-depth check
                    if (event.getSequenceNumber() > maxSeq) {
                        continue;
                    }

                    // AST payload sanitization
                    JsonNode sanitizedPayload = auditPayloadSanitizer.sanitizePayloadJson(event.getPayloadJson());

                    // Map to canonical record DTO with deterministic property ordering
                    CanonicalAuditExportRecord record = new CanonicalAuditExportRecord();
                    record.setSequenceNumber(event.getSequenceNumber());
                    record.setCanonicalAuditEventId(event.getCanonicalAuditEventId());
                    record.setOrganizationId(orgId.toString());
                    record.setOccurrenceId(event.getOccurrenceId());
                    record.setEventType(event.getEventType());
                    record.setAggregateType(event.getAggregateType());
                    record.setAggregateId(event.getAggregateId());
                    record.setCreatedAt(event.getCreatedAt().toString());
                    record.setPayload(sanitizedPayload);

                    // Serialize single-line canonical JSONL record followed by newline
                    byte[] lineBytes = objectMapper.writeValueAsBytes(record);
                    gzipOut.write(lineBytes);
                    gzipOut.write('\n');

                    recordCount++;
                    lastSeenSeq = event.getSequenceNumber();
                }

                if (chunk.size() < batchSize) {
                    hasMore = false;
                }
            }

            gzipOut.finish();
            gzipOut.flush();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_STREAMING_FAILED", "Failed to stream audit export: " + e.getMessage());
        }

        return new ExportStreamingResult(job.getId(), orgId, recordCount, countingStream.getByteCount(), maxSeq);
    }

    private boolean belongsToOrganization(AuditLogOutbox event, UUID orgId) {
        if (event == null || orgId == null) return false;
        if (event.getOrganization() != null) {
            return orgId.equals(event.getOrganization().getId());
        }
        String orgIdStr = orgId.toString();
        if (orgIdStr.equals(event.getAggregateId())) return true;
        if (event.getCanonicalAuditEventId() != null && event.getCanonicalAuditEventId().contains(orgIdStr)) return true;
        if (event.getPayloadJson() != null && event.getPayloadJson().contains(orgIdStr)) return true;
        return false;
    }

    private static class CountingOutputStream extends OutputStream {
        private final OutputStream out;
        private long count = 0L;

        public CountingOutputStream(OutputStream out) {
            this.out = out;
        }

        public long getByteCount() {
            return count;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws java.io.IOException {
            out.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            out.close();
        }
    }
}
