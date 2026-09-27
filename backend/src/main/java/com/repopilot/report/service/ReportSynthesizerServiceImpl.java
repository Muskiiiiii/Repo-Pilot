package com.repopilot.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.security.SecretRedactor;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.report.dto.ReportSynthesisInputDto;
import com.repopilot.report.dto.ReportSynthesisOutputDto;
import com.repopilot.report.dto.ReportSynthesisResponseDto;
import com.repopilot.report.entity.ImplementationReport;
import com.repopilot.report.entity.ReportSynthesis;
import com.repopilot.report.repository.ImplementationReportRepository;
import com.repopilot.report.repository.ReportSynthesisRepository;
import com.repopilot.report.renderer.DeterministicMarkdownRenderer;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReportSynthesizerServiceImpl implements ReportSynthesizerService {

    private static final Logger log = LoggerFactory.getLogger(ReportSynthesizerServiceImpl.class);

    private final ConcurrentHashMap<UUID, Object> synthesisLockMap = new ConcurrentHashMap<>();

    private final ReportSynthesisRepository reportSynthesisRepository;
    private final ImplementationReportRepository implementationReportRepository;
    private final AiServiceClient aiServiceClient;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private final TransactionTemplate transactionTemplateWrite;
    private final TransactionTemplate transactionTemplateRead;

    public ReportSynthesizerServiceImpl(
            ReportSynthesisRepository reportSynthesisRepository,
            ImplementationReportRepository implementationReportRepository,
            AiServiceClient aiServiceClient,
            ProjectAuthorizationService projectAuthorizationService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.reportSynthesisRepository = reportSynthesisRepository;
        this.implementationReportRepository = implementationReportRepository;
        this.aiServiceClient = aiServiceClient;
        this.projectAuthorizationService = projectAuthorizationService;
        this.auditLogService = auditLogService;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        if (transactionManager != null) {
            this.transactionTemplateWrite = new TransactionTemplate(transactionManager);
            this.transactionTemplateWrite.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
            this.transactionTemplateWrite.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);

            this.transactionTemplateRead = new TransactionTemplate(transactionManager);
            this.transactionTemplateRead.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
            this.transactionTemplateRead.setReadOnly(true);
        } else {
            this.transactionTemplateWrite = null;
            this.transactionTemplateRead = null;
        }
    }

    @Override
    public ReportSynthesisResponseDto synthesizeReport(UUID executionId, User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot request report synthesis");
        }
        if (executionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Execution ID must not be null");
        }

        Object lock = synthesisLockMap.computeIfAbsent(executionId, k -> new Object());
        synchronized (lock) {
            // Stage 1: Short DB Read Transaction to load ImplementationReport
            ImplementationReport report = loadImplementationReportInReadTx(executionId, currentUser);

            // Audit Log: REPORT_SYNTHESIS_REQUESTED
            auditLogService.logAction(currentUser, report.getProject(), report.getTask(), "REPORT_SYNTHESIS_REQUESTED", "IMPLEMENTATION_REPORT", report.getId(), "{\"executionId\":\"" + executionId + "\"}");

            String taskTitle = (report.getTask() != null) ? report.getTask().getTitle() : "Task Execution";
            String changedFilesJson = report.getChangedFilesSummary();
            String testSummaryJson = report.getTestSummary();
            String securitySummaryJson = report.getSecurityReviewSummary();
            String confidenceScoreStr = (report.getConfidenceScore() != null) ? report.getConfidenceScore().toString() : "0.00";
            String failureReason = report.getFailureReason();

            // Compute ai_synthesis_input_hash outside DB transaction
            String inputHash = computeAiSynthesisInputHash(
                    report.getId(),
                    executionId,
                    report.getSourceContextHash(),
                    taskTitle,
                    report.getExecutionOutcome(),
                    changedFilesJson,
                    testSummaryJson,
                    securitySummaryJson,
                    confidenceScoreStr,
                    failureReason,
                    "v1.0",
                    "v1.0",
                    "v1.0"
            );

            // Check if existing synthesis already present in DB
            Optional<ReportSynthesis> existingOpt = findExistingSynthesisInReadTx(report.getId(), inputHash);
            if (existingOpt.isPresent()) {
                return mapToDto(existingOpt.get());
            }

            // Stage 2: Prepare Input DTO + Secret Scrubbing outside DB transaction
            ReportSynthesisInputDto inputDto = new ReportSynthesisInputDto();
            inputDto.setImplementationReportId(report.getId());
            inputDto.setTaskExecutionId(executionId);
            inputDto.setProjectId(report.getProject().getId());
            inputDto.setRepositoryId(report.getRepository() != null ? report.getRepository().getId() : null);
            inputDto.setTaskTitle(SecretRedactor.sanitizeLogs(taskTitle));
            inputDto.setExecutionOutcome(report.getExecutionOutcome());
            inputDto.setSourceContextHash(report.getSourceContextHash());
            inputDto.setChangedFilesSummaryJson(SecretRedactor.sanitizeLogs(changedFilesJson));
            inputDto.setTestSummaryJson(SecretRedactor.sanitizeLogs(testSummaryJson));
            inputDto.setSecurityReviewSummaryJson(SecretRedactor.sanitizeLogs(securitySummaryJson));
            inputDto.setConfidenceScore(report.getConfidenceScore());
            inputDto.setFailureReason(SecretRedactor.sanitizeLogs(failureReason));

            ReportSynthesisOutputDto outputDto;
            try {
                // Call AI Service outside DB transaction
                outputDto = aiServiceClient.synthesizeReport(inputDto);
            } catch (Exception e) {
                log.error("AI Service synthesis failed for report {}: {}", report.getId(), e.getMessage());
                auditLogService.logAction(currentUser, report.getProject(), report.getTask(), "REPORT_SYNTHESIS_FAILED", "IMPLEMENTATION_REPORT", report.getId(), "{\"error\":\"" + SecretRedactor.sanitizeLogs(e.getMessage()) + "\"}");
                throw (e instanceof ApiException) ? (ApiException) e : new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Report synthesis AI service execution failed: " + SecretRedactor.sanitizeLogs(e.getMessage()));
            }

            // Post-LLM Secret Safety Verification
            verifyNoSecretsInModelOutput(outputDto);

            // Render Deterministic Markdown outside DB transaction
            String renderedMarkdown = DeterministicMarkdownRenderer.renderMarkdown(report, outputDto, inputHash);

            // Stage 3: Short DB Write Transaction to persist synthesis artifact
            try {
                if (transactionTemplateWrite != null) {
                    return transactionTemplateWrite.execute(status -> persistSynthesis(report, inputHash, outputDto, renderedMarkdown, currentUser));
                } else {
                    return persistSynthesis(report, inputHash, outputDto, renderedMarkdown, currentUser);
                }
            } catch (DataIntegrityViolationException dive) {
                log.warn("PostgreSQL 23505 unique constraint race detected for synthesis input hash {}. Recovering in new read transaction.", inputHash);
                return recoverExistingSynthesisInReadTx(report.getId(), inputHash, currentUser);
            }
        }
    }

    private ImplementationReport loadImplementationReportInReadTx(UUID executionId, User currentUser) {
        if (transactionTemplateRead != null) {
            return transactionTemplateRead.execute(status -> {
                ImplementationReport r = implementationReportRepository.findByTaskExecutionId(executionId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ImplementationReport not found for execution: " + executionId));
                projectAuthorizationService.requireMinimumRole(r.getProject(), currentUser, ProjectRole.VIEWER);
                hydrateReportAssociations(r);
                return r;
            });
        } else {
            ImplementationReport r = implementationReportRepository.findByTaskExecutionId(executionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ImplementationReport not found for execution: " + executionId));
            projectAuthorizationService.requireMinimumRole(r.getProject(), currentUser, ProjectRole.VIEWER);
            hydrateReportAssociations(r);
            return r;
        }
    }

    private void hydrateReportAssociations(ImplementationReport r) {
        if (r.getProject() != null) {
            r.getProject().getId();
        }
        if (r.getTask() != null) {
            r.getTask().getId();
            r.getTask().getTitle();
            if (r.getTask().getProject() != null) {
                r.getTask().getProject().getId();
            }
        }
        if (r.getTaskExecution() != null) {
            r.getTaskExecution().getId();
        }
        if (r.getRepository() != null) {
            r.getRepository().getId();
        }
    }

    private Optional<ReportSynthesis> findExistingSynthesisInReadTx(UUID reportId, String inputHash) {
        if (transactionTemplateRead != null) {
            return transactionTemplateRead.execute(status -> reportSynthesisRepository.findByImplementationReportIdAndAiSynthesisInputHash(reportId, inputHash));
        } else {
            return reportSynthesisRepository.findByImplementationReportIdAndAiSynthesisInputHash(reportId, inputHash);
        }
    }

    private ReportSynthesisResponseDto recoverExistingSynthesisInReadTx(UUID reportId, String inputHash, User currentUser) {
        if (transactionTemplateRead != null) {
            return transactionTemplateRead.execute(status -> {
                ReportSynthesis synthesis = reportSynthesisRepository.findByImplementationReportIdAndAiSynthesisInputHash(reportId, inputHash)
                        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Report synthesis recovery failed after constraint race"));
                projectAuthorizationService.requireMinimumRole(synthesis.getProject(), currentUser, ProjectRole.VIEWER);
                return mapToDto(synthesis);
            });
        } else {
            ReportSynthesis synthesis = reportSynthesisRepository.findByImplementationReportIdAndAiSynthesisInputHash(reportId, inputHash)
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Report synthesis recovery failed after constraint race"));
            projectAuthorizationService.requireMinimumRole(synthesis.getProject(), currentUser, ProjectRole.VIEWER);
            return mapToDto(synthesis);
        }
    }

    private ReportSynthesisResponseDto persistSynthesis(
            ImplementationReport report,
            String inputHash,
            ReportSynthesisOutputDto outputDto,
            String renderedMarkdown,
            User currentUser) {

        ReportSynthesis synthesis = new ReportSynthesis();
        synthesis.setImplementationReport(report);
        synthesis.setTaskExecution(report.getTaskExecution());
        synthesis.setProject(report.getProject());
        synthesis.setSourceContextHash(report.getSourceContextHash());
        synthesis.setAiSynthesisInputHash(inputHash);
        synthesis.setSynthesizerVersion("v1.0");
        synthesis.setPromptVersion("v1.0");
        synthesis.setModelIdentifier("repopilot-report-synthesizer-v1");
        synthesis.setValidatorVersion("v1.0");
        synthesis.setExecutiveSummary(outputDto.getExecutiveSummary());
        synthesis.setImplementationExplanation(outputDto.getImplementationExplanation());
        synthesis.setTestingExplanation(outputDto.getTestingExplanation());
        synthesis.setSecurityExplanation(outputDto.getSecurityExplanation());

        try {
            synthesis.setLimitationsJson(objectMapper.writeValueAsString(outputDto.getLimitations()));
            synthesis.setDeveloperNotesJson(objectMapper.writeValueAsString(outputDto.getDeveloperNotes()));
        } catch (JsonProcessingException e) {
            synthesis.setLimitationsJson("[]");
            synthesis.setDeveloperNotesJson("[]");
        }

        synthesis.setRenderedMarkdown(renderedMarkdown);

        ReportSynthesis saved = reportSynthesisRepository.saveAndFlush(synthesis);

        // Audit Log: REPORT_SYNTHESIS_GENERATED
        auditLogService.logAction(currentUser, report.getProject(), report.getTask(), "REPORT_SYNTHESIS_GENERATED", "REPORT_SYNTHESIS", saved.getId(), "{\"aiSynthesisInputHash\":\"" + inputHash + "\"}");

        return mapToDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportSynthesisResponseDto getSynthesisByExecutionId(UUID executionId, User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot access report synthesis");
        }
        if (executionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Execution ID must not be null");
        }

        ReportSynthesis synthesis = reportSynthesisRepository.findByTaskExecutionId(executionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Report synthesis not found for execution: " + executionId));

        projectAuthorizationService.requireMinimumRole(synthesis.getProject(), currentUser, ProjectRole.VIEWER);

        // Audit Log: REPORT_SYNTHESIS_VIEWED
        auditLogService.logAction(currentUser, synthesis.getProject(), synthesis.getImplementationReport().getTask(), "REPORT_SYNTHESIS_VIEWED", "REPORT_SYNTHESIS", synthesis.getId(), "{}");

        return mapToDto(synthesis);
    }

    @Override
    @Transactional(readOnly = true)
    public ReportSynthesisResponseDto getSynthesisByReportId(UUID reportId, User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot access report synthesis");
        }
        if (reportId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Report ID must not be null");
        }

        ReportSynthesis synthesis = reportSynthesisRepository.findByImplementationReportId(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Report synthesis not found for report: " + reportId));

        projectAuthorizationService.requireMinimumRole(synthesis.getProject(), currentUser, ProjectRole.VIEWER);

        // Audit Log: REPORT_SYNTHESIS_VIEWED
        auditLogService.logAction(currentUser, synthesis.getProject(), synthesis.getImplementationReport().getTask(), "REPORT_SYNTHESIS_VIEWED", "REPORT_SYNTHESIS", synthesis.getId(), "{}");

        return mapToDto(synthesis);
    }

    @Override
    public String computeAiSynthesisInputHash(
            UUID reportId,
            UUID executionId,
            String sourceContextHash,
            String taskTitle,
            String executionOutcome,
            String changedFilesJson,
            String testSummaryJson,
            String securitySummaryJson,
            String confidenceScore,
            String failureReason,
            String synthesizerVersion,
            String promptVersion,
            String validatorVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();

            sb.append("report:").append(reportId != null ? reportId.toString() : "").append("|");
            sb.append("exec:").append(executionId != null ? executionId.toString() : "").append("|");
            sb.append("sourceHash:").append(sourceContextHash != null ? sourceContextHash : "").append("|");
            sb.append("title:").append(taskTitle != null ? taskTitle : "").append("|");
            sb.append("outcome:").append(executionOutcome != null ? executionOutcome : "").append("|");
            sb.append("files:").append(changedFilesJson != null ? changedFilesJson : "").append("|");
            sb.append("tests:").append(testSummaryJson != null ? testSummaryJson : "").append("|");
            sb.append("sec:").append(securitySummaryJson != null ? securitySummaryJson : "").append("|");
            sb.append("confidence:").append(confidenceScore != null ? confidenceScore : "").append("|");
            sb.append("failure:").append(failureReason != null ? failureReason : "").append("|");
            sb.append("synthVer:").append(synthesizerVersion != null ? synthesizerVersion : "").append("|");
            sb.append("promptVer:").append(promptVersion != null ? promptVersion : "").append("|");
            sb.append("valVer:").append(validatorVersion != null ? validatorVersion : "").append("|");

            byte[] hashBytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "hash-fallback-synthesis-" + UUID.randomUUID().toString().replace("-", "");
        }
    }

    private void verifyNoSecretsInModelOutput(ReportSynthesisOutputDto outputDto) {
        if (outputDto == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(outputDto.getExecutiveSummary() != null ? outputDto.getExecutiveSummary() : "").append(" ");
        sb.append(outputDto.getImplementationExplanation() != null ? outputDto.getImplementationExplanation() : "").append(" ");
        sb.append(outputDto.getTestingExplanation() != null ? outputDto.getTestingExplanation() : "").append(" ");
        sb.append(outputDto.getSecurityExplanation() != null ? outputDto.getSecurityExplanation() : "").append(" ");
        if (outputDto.getLimitations() != null) {
            for (String lim : outputDto.getLimitations()) sb.append(lim).append(" ");
        }
        if (outputDto.getDeveloperNotes() != null) {
            for (String note : outputDto.getDeveloperNotes()) sb.append(note).append(" ");
        }
        String combined = sb.toString();

        // Check using SecretRedactor
        String sanitized = SecretRedactor.sanitizeLogs(combined);
        if (!combined.equals(sanitized)) {
            log.warn("Secret credential pattern detected in AI synthesis model response text. Aborting synthesis persist.");
            throw new ApiException(HttpStatus.BAD_REQUEST, "SECRET_DETECTED", "Secret credential pattern detected in AI model output prose");
        }
    }

    private ReportSynthesisResponseDto mapToDto(ReportSynthesis synthesis) {
        ReportSynthesisResponseDto dto = new ReportSynthesisResponseDto();
        dto.setId(synthesis.getId());
        dto.setImplementationReportId(synthesis.getImplementationReport() != null ? synthesis.getImplementationReport().getId() : null);
        dto.setTaskExecutionId(synthesis.getTaskExecution() != null ? synthesis.getTaskExecution().getId() : null);
        dto.setProjectId(synthesis.getProject() != null ? synthesis.getProject().getId() : null);
        dto.setSourceContextHash(synthesis.getSourceContextHash());
        dto.setAiSynthesisInputHash(synthesis.getAiSynthesisInputHash());
        dto.setSynthesizerVersion(synthesis.getSynthesizerVersion());
        dto.setPromptVersion(synthesis.getPromptVersion());
        dto.setModelIdentifier(synthesis.getModelIdentifier());
        dto.setValidatorVersion(synthesis.getValidatorVersion());
        dto.setExecutiveSummary(synthesis.getExecutiveSummary());
        dto.setImplementationExplanation(synthesis.getImplementationExplanation());
        dto.setTestingExplanation(synthesis.getTestingExplanation());
        dto.setSecurityExplanation(synthesis.getSecurityExplanation());

        try {
            if (synthesis.getLimitationsJson() != null) {
                dto.setLimitations(objectMapper.readValue(synthesis.getLimitationsJson(), List.class));
            }
            if (synthesis.getDeveloperNotesJson() != null) {
                dto.setDeveloperNotes(objectMapper.readValue(synthesis.getDeveloperNotesJson(), List.class));
            }
        } catch (Exception e) {
            dto.setLimitations(Collections.emptyList());
            dto.setDeveloperNotes(Collections.emptyList());
        }

        dto.setRenderedMarkdown(synthesis.getRenderedMarkdown());
        dto.setCreatedAt(synthesis.getCreatedAt());
        return dto;
    }
}
