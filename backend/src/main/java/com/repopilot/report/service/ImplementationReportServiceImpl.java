package com.repopilot.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.security.SecretRedactor;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.report.dto.ImplementationReportResponseDto;
import com.repopilot.report.entity.ImplementationReport;
import com.repopilot.report.entity.ReportStatus;
import com.repopilot.report.repository.ImplementationReportRepository;
import com.repopilot.report.repository.ReportSynthesisRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.entity.ComplianceCertificate;
import com.repopilot.review.entity.ReviewFinding;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ComplianceCertificateRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.entity.TestResult;
import com.repopilot.testing.entity.TestRun;
import com.repopilot.testing.repository.TestResultRepository;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ImplementationReportServiceImpl implements ImplementationReportService {

    private static final Logger log = LoggerFactory.getLogger(ImplementationReportServiceImpl.class);

    private static final int MAX_CHANGED_FILES = 50;
    private static final int MAX_TEST_RESULTS = 100;
    private static final int MAX_SECURITY_FINDINGS = 50;
    private static final int MAX_SUMMARY_SIZE_BYTES = 32768; // 32KB

    private final ConcurrentHashMap<UUID, Object> generatingLockMap = new ConcurrentHashMap<>();

    private final ImplementationReportRepository implementationReportRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final ComplianceCertificateRepository complianceCertificateRepository;
    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final ReportSynthesisRepository reportSynthesisRepository;
    private final com.repopilot.task.repository.TaskRepository taskRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private final TransactionTemplate transactionTemplateWrite;
    private final TransactionTemplate transactionTemplateRead;

    public ImplementationReportServiceImpl(
            ImplementationReportRepository implementationReportRepository,
            TaskExecutionRepository taskExecutionRepository,
            CodeChangeRepository codeChangeRepository,
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            @Autowired(required = false) ComplianceCertificateRepository complianceCertificateRepository,
            @Autowired(required = false) TestRunRepository testRunRepository,
            @Autowired(required = false) TestResultRepository testResultRepository,
            @Autowired(required = false) ReportSynthesisRepository reportSynthesisRepository,
            @Autowired(required = false) com.repopilot.task.repository.TaskRepository taskRepository,
            ProjectAuthorizationService projectAuthorizationService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.implementationReportRepository = implementationReportRepository;
        this.taskExecutionRepository = taskExecutionRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.complianceCertificateRepository = complianceCertificateRepository;
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.reportSynthesisRepository = reportSynthesisRepository;
        this.taskRepository = taskRepository;
        this.projectAuthorizationService = projectAuthorizationService;
        this.auditLogService = auditLogService;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
        if (transactionManager != null) {
            this.transactionTemplateWrite = new TransactionTemplate(transactionManager);
            this.transactionTemplateWrite.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
            this.transactionTemplateWrite.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);

            this.transactionTemplateRead = new TransactionTemplate(transactionManager);
            this.transactionTemplateRead.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
            this.transactionTemplateRead.setReadOnly(true);
        } else {
            this.transactionTemplateWrite = null;
            this.transactionTemplateRead = null;
        }
    }

    @Override
    public ImplementationReportResponseDto generateReport(UUID executionId, User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot generate implementation report");
        }
        if (executionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Execution ID must not be null");
        }

        Object lock = generatingLockMap.computeIfAbsent(executionId, k -> new Object());
        synchronized (lock) {
            try {
                if (transactionTemplateWrite != null) {
                    return transactionTemplateWrite.execute(status -> executeGenerateReport(executionId, currentUser));
                } else {
                    return executeGenerateReport(executionId, currentUser);
                }
            } catch (DataIntegrityViolationException dive) {
                log.warn("PostgreSQL 23505 unique constraint race detected for execution {}. Recovering in new read transaction.", executionId);
                return recoverExistingReportInNewReadTransaction(executionId, currentUser);
            }
        }
    }

    private ImplementationReportResponseDto recoverExistingReportInNewReadTransaction(UUID executionId, User currentUser) {
        if (transactionTemplateRead != null) {
            return transactionTemplateRead.execute(status -> {
                ImplementationReport report = implementationReportRepository.findByTaskExecutionId(executionId)
                        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Implementation report recovery failed after constraint race"));
                projectAuthorizationService.requireMinimumRole(report.getProject(), currentUser, ProjectRole.VIEWER);
                return mapToDto(report);
            });
        } else {
            ImplementationReport report = implementationReportRepository.findByTaskExecutionId(executionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Implementation report recovery failed after constraint race"));
            projectAuthorizationService.requireMinimumRole(report.getProject(), currentUser, ProjectRole.VIEWER);
            return mapToDto(report);
        }
    }

    private ImplementationReportResponseDto executeGenerateReport(UUID executionId, User currentUser) {

        // Fetch TaskExecution
        TaskExecution execution = taskExecutionRepository.findById(executionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TaskExecution not found: " + executionId));

        Task task = execution.getTask();
        Project project = execution.getProject();
        Repository repository = execution.getRepository();

        if (task == null || project == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid task execution structural hierarchy");
        }

        // IDOR Authorization Check
        projectAuthorizationService.requireMinimumRole(project, currentUser, ProjectRole.VIEWER);

        String correlationId = "report-" + UUID.randomUUID().toString().substring(0, 8);

        // Audit Log: REPORT_GENERATION_REQUESTED
        auditLogService.logAction(currentUser, project, task, "REPORT_GENERATION_REQUESTED", "TASK_EXECUTION", executionId, "{\"correlationId\":\"" + correlationId + "\"}");

        // Idempotency Check: Return existing report if already generated for this execution
        Optional<ImplementationReport> existing = implementationReportRepository.findByTaskExecutionId(executionId);
        if (existing.isPresent()) {
            return mapToDto(existing.get());
        }

        // Execution Completion Gate: Execution status MUST be COMPLETED or FAILED
        ExecutionStatus execStatus = execution.getStatus();
        if (execStatus != ExecutionStatus.COMPLETED && execStatus != ExecutionStatus.FAILED) {
            log.warn("Attempted to generate report for non-terminal execution {} in status {}", executionId, execStatus);
            auditLogService.logAction(currentUser, project, task, "REPORT_FAILED", "TASK_EXECUTION", executionId, "{\"reason\":\"Non-terminal execution status: " + execStatus + "\"}");
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot generate implementation report for execution in non-terminal status " + execStatus);
        }

        try {
            // Build Point-In-Time Snapshot Evidence
            List<CodeChange> changes = codeChangeRepository.findByTaskId(task.getId());
            if (changes == null) changes = Collections.emptyList();
            changes.sort(Comparator.comparing(CodeChange::getFilePath, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(CodeChange::getId));

            List<TestResult> testResults = new ArrayList<>();
            if (testRunRepository != null && testResultRepository != null) {
                List<TestRun> testRuns = testRunRepository.findByTaskExecutionId(executionId);
                if (testRuns != null) {
                    for (TestRun tr : testRuns) {
                        List<TestResult> resList = testResultRepository.findByTestRunId(tr.getId());
                        if (resList != null) testResults.addAll(resList);
                    }
                }
            }
            testResults.sort(Comparator.comparing(TestResult::getTestName, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(TestResult::getId));

            List<CodeReview> reviews = codeReviewRepository.findByTaskId(task.getId());
            if (reviews == null) reviews = Collections.emptyList();
            reviews.sort(Comparator.comparing(CodeReview::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(CodeReview::getId));

            List<ReviewFinding> findings = (reviews.isEmpty()) ? Collections.emptyList() :
                    reviewFindingRepository.findByReviewIdIn(reviews.stream().map(CodeReview::getId).collect(Collectors.toList()));
            if (findings == null) findings = Collections.emptyList();
            findings.sort(Comparator.comparing(ReviewFinding::getSeverity, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(ReviewFinding::getId));

            List<ComplianceCertificate> certificates = (complianceCertificateRepository != null) ?
                    complianceCertificateRepository.findByTaskId(task.getId()) : Collections.emptyList();
            if (certificates == null) certificates = Collections.emptyList();

            // Compute Deterministic Source Context Hash
            String sourceContextHash = computeSourceContextHash(execution, changes, testResults, reviews, findings, certificates);

            // Assemble Bounded Summaries with Secret Redaction
            String changedFilesSummary = buildChangedFilesSummary(changes);
            String testSummary = buildTestSummary(testResults);
            String securityReviewSummary = buildSecurityReviewSummary(reviews, findings, certificates);
            BigDecimal confidenceScore = calculateConfidenceScore(execStatus, changes, testResults, reviews, findings);

            ImplementationReport report = new ImplementationReport();
            report.setProject(project);
            report.setRepository(repository);
            report.setTask(task);
            report.setTaskExecution(execution);
            report.setStatus(ReportStatus.GENERATED);
            report.setExecutionOutcome(execStatus.name());
            report.setChangedFilesSummary(changedFilesSummary);
            report.setTestSummary(testSummary);
            report.setSecurityReviewSummary(securityReviewSummary);
            report.setConfidenceScore(confidenceScore);
            report.setSourceContextHash(sourceContextHash);
            report.setCorrelationId(correlationId);
            report.setFailureReason(SecretRedactor.sanitizeLogs(execution.getErrorMessage()));

            ImplementationReport savedReport = implementationReportRepository.saveAndFlush(report);

            // Audit Log: REPORT_GENERATED
            auditLogService.logAction(currentUser, project, task, "REPORT_GENERATED", "IMPLEMENTATION_REPORT", savedReport.getId(), "{\"sourceContextHash\":\"" + sourceContextHash + "\",\"executionOutcome\":\"" + execStatus.name() + "\"}");

            return mapToDto(savedReport);

        } catch (DataIntegrityViolationException dive) {
            throw dive;
        } catch (Exception e) {
            log.error("Implementation report generation failed for execution {}: {}", executionId, e.getMessage(), e);
            auditLogService.logAction(currentUser, project, task, "REPORT_FAILED", "TASK_EXECUTION", executionId, "{\"error\":\"" + SecretRedactor.sanitizeLogs(e.getMessage()) + "\"}");
            throw (e instanceof ApiException) ? (ApiException) e : new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Implementation report generation failed: " + SecretRedactor.sanitizeLogs(e.getMessage()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ImplementationReportResponseDto getReportByExecutionId(UUID executionId, User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot access implementation report");
        }
        if (executionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Execution ID must not be null");
        }

        ImplementationReport report = implementationReportRepository.findByTaskExecutionId(executionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Implementation report not found for execution: " + executionId));

        projectAuthorizationService.requireMinimumRole(report.getProject(), currentUser, ProjectRole.VIEWER);

        return mapToDto(report);
    }

    @Override
    @Transactional(readOnly = true)
    public ImplementationReportResponseDto getReportById(UUID reportId, User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot access implementation report");
        }
        if (reportId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Report ID must not be null");
        }

        ImplementationReport report = implementationReportRepository.findById(reportId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Implementation report not found: " + reportId));

        projectAuthorizationService.requireMinimumRole(report.getProject(), currentUser, ProjectRole.VIEWER);

        return mapToDto(report);
    }

    @Override
    @Transactional(readOnly = true)
    public ImplementationReportResponseDto getReportByTaskId(UUID taskId, User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot access implementation report");
        }
        if (taskId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Task ID must not be null");
        }

        ImplementationReport report = implementationReportRepository.findByTaskId(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Implementation report not found for task: " + taskId));

        projectAuthorizationService.requireMinimumRole(report.getProject(), currentUser, ProjectRole.VIEWER);

        return mapToDto(report);
    }

    @Override
    @Transactional(readOnly = true)
    public com.repopilot.report.dto.TaskReportResponseDto getTaskReport(UUID projectId, UUID repositoryId, UUID taskId, User currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthenticated user cannot access task report");
        }
        if (taskId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Task ID must not be null");
        }

        Optional<ImplementationReport> reportOpt = implementationReportRepository.findByTaskId(taskId);
        if (reportOpt.isEmpty()) {
            if (taskRepository != null) {
                Optional<com.repopilot.task.entity.Task> taskOpt = taskRepository.findById(taskId);
                if (taskOpt.isPresent()) {
                    com.repopilot.task.entity.Task task = taskOpt.get();
                    projectAuthorizationService.requireMinimumRole(task.getProject(), currentUser, ProjectRole.VIEWER);

                    if (projectId != null && !task.getProject().getId().equals(projectId)) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "Task does not belong to specified project");
                    }
                    if (repositoryId != null && task.getRepository() != null && !task.getRepository().getId().equals(repositoryId)) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "Task does not belong to specified repository");
                    }
                }
            }
            throw new ApiException(HttpStatus.NOT_FOUND, "Implementation report not found for task: " + taskId);
        }

        ImplementationReport report = reportOpt.get();

        projectAuthorizationService.requireMinimumRole(report.getProject(), currentUser, ProjectRole.VIEWER);

        if (projectId != null && !report.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Report does not belong to specified project");
        }
        if (repositoryId != null && report.getRepository() != null && !report.getRepository().getId().equals(repositoryId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Report does not belong to specified repository");
        }

        ImplementationReportResponseDto reportDto = mapToDto(report);

        com.repopilot.report.dto.ReportSynthesisResponseDto synthesisDto = null;
        String synthesisStatus = "NONE";

        if (reportSynthesisRepository != null) {
            Optional<com.repopilot.report.entity.ReportSynthesis> synthesisOpt = reportSynthesisRepository.findByImplementationReportId(report.getId());
            if (synthesisOpt.isPresent()) {
                synthesisDto = mapSynthesisToDto(synthesisOpt.get());
                synthesisStatus = "AVAILABLE";
            }
        }

        auditLogService.logAction(currentUser, report.getProject(), report.getTask(), "REPORT_VIEWED", "IMPLEMENTATION_REPORT", report.getId(), "{}");

        return new com.repopilot.report.dto.TaskReportResponseDto(
                report.getProject().getId(),
                report.getRepository() != null ? report.getRepository().getId() : null,
                report.getTask().getId(),
                report.getTaskExecution().getId(),
                reportDto,
                synthesisDto,
                synthesisStatus
        );
    }

    private com.repopilot.report.dto.ReportSynthesisResponseDto mapSynthesisToDto(com.repopilot.report.entity.ReportSynthesis s) {
        com.repopilot.report.dto.ReportSynthesisResponseDto dto = new com.repopilot.report.dto.ReportSynthesisResponseDto();
        dto.setId(s.getId());
        dto.setImplementationReportId(s.getImplementationReport() != null ? s.getImplementationReport().getId() : null);
        dto.setTaskExecutionId(s.getTaskExecution() != null ? s.getTaskExecution().getId() : null);
        dto.setProjectId(s.getProject() != null ? s.getProject().getId() : null);
        dto.setSourceContextHash(s.getSourceContextHash());
        dto.setAiSynthesisInputHash(s.getAiSynthesisInputHash());
        dto.setSynthesizerVersion(s.getSynthesizerVersion());
        dto.setPromptVersion(s.getPromptVersion());
        dto.setModelIdentifier(s.getModelIdentifier());
        dto.setValidatorVersion(s.getValidatorVersion());
        dto.setExecutiveSummary(s.getExecutiveSummary());
        dto.setImplementationExplanation(s.getImplementationExplanation());
        dto.setTestingExplanation(s.getTestingExplanation());
        dto.setSecurityExplanation(s.getSecurityExplanation());

        if (s.getLimitationsJson() != null) {
            try {
                List<String> limitations = objectMapper.readValue(s.getLimitationsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                dto.setLimitations(limitations);
            } catch (Exception e) {
                dto.setLimitations(Collections.emptyList());
            }
        }
        if (s.getDeveloperNotesJson() != null) {
            try {
                List<String> notes = objectMapper.readValue(s.getDeveloperNotesJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                dto.setDeveloperNotes(notes);
            } catch (Exception e) {
                dto.setDeveloperNotes(Collections.emptyList());
            }
        }
        dto.setRenderedMarkdown(s.getRenderedMarkdown());
        dto.setCreatedAt(s.getCreatedAt());
        return dto;
    }

    private String computeSourceContextHash(
            TaskExecution execution,
            List<CodeChange> changes,
            List<TestResult> testResults,
            List<CodeReview> reviews,
            List<ReviewFinding> findings,
            List<ComplianceCertificate> certificates) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();

            sb.append("exec:").append(execution.getId()).append("|");
            sb.append("status:").append(execution.getStatus()).append("|");
            sb.append("execNum:").append(execution.getExecutionNumber()).append("|");
            sb.append("started:").append(execution.getStartedAt() != null ? execution.getStartedAt().toEpochMilli() : 0).append("|");
            sb.append("completed:").append(execution.getCompletedAt() != null ? execution.getCompletedAt().toEpochMilli() : 0).append("|");

            for (CodeChange c : changes) {
                sb.append("change:").append(c.getId())
                        .append(":").append(c.getFilePath())
                        .append(":").append(c.getOperation())
                        .append(":").append(c.getProposedHash() != null ? c.getProposedHash() : "")
                        .append("|");
            }

            for (TestResult tr : testResults) {
                sb.append("test:").append(tr.getId())
                        .append(":").append(tr.getTestName())
                        .append(":").append(tr.getStatus())
                        .append("|");
            }

            for (CodeReview r : reviews) {
                sb.append("review:").append(r.getId())
                        .append(":").append(r.getStatus())
                        .append(":").append(r.getOverallRiskScore())
                        .append("|");
            }

            for (ReviewFinding f : findings) {
                sb.append("finding:").append(f.getId())
                        .append(":").append(f.getSeverity())
                        .append(":").append(f.getFingerprint() != null ? f.getFingerprint() : "")
                        .append("|");
            }

            for (ComplianceCertificate cert : certificates) {
                sb.append("cert:").append(cert.getId())
                        .append(":").append(cert.getComplianceStatus())
                        .append("|");
            }

            byte[] hashBytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "hash-fallback-" + UUID.nameUUIDFromBytes(execution.getId().toString().getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        }
    }

    private String buildChangedFilesSummary(List<CodeChange> changes) {
        try {
            List<Map<String, Object>> summaryList = new ArrayList<>();
            int count = 0;
            for (CodeChange c : changes) {
                if (count >= MAX_CHANGED_FILES) break;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("filePath", SecretRedactor.sanitizeLogs(c.getFilePath()));
                item.put("operation", c.getOperation());
                item.put("originalHash", c.getOriginalHash());
                item.put("proposedHash", c.getProposedHash());
                item.put("confidenceLevel", c.getConfidenceLevel());
                summaryList.add(item);
                count++;
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("totalChanges", changes.size());
            root.put("boundedFiles", summaryList);
            return truncateJson(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            return "{\"totalChanges\":" + changes.size() + "}";
        }
    }

    private String buildTestSummary(List<TestResult> testResults) {
        try {
            int passed = 0;
            int failed = 0;
            int skipped = 0;
            List<Map<String, Object>> sampleList = new ArrayList<>();

            int count = 0;
            for (TestResult tr : testResults) {
                String status = tr.getStatus() != null ? tr.getStatus() : "UNKNOWN";
                if ("PASSED".equalsIgnoreCase(status) || "PASS".equalsIgnoreCase(status)) passed++;
                else if ("FAILED".equalsIgnoreCase(status) || "FAIL".equalsIgnoreCase(status)) failed++;
                else skipped++;

                if (count < MAX_TEST_RESULTS) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", SecretRedactor.sanitizeLogs(tr.getTestName()));
                    item.put("status", status);
                    sampleList.add(item);
                    count++;
                }
            }

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("totalTests", testResults.size());
            root.put("passed", passed);
            root.put("failed", failed);
            root.put("skipped", skipped);
            root.put("resultsSample", sampleList);
            return truncateJson(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            return "{\"totalTests\":" + testResults.size() + "}";
        }
    }

    private String buildSecurityReviewSummary(List<CodeReview> reviews, List<ReviewFinding> findings, List<ComplianceCertificate> certificates) {
        try {
            Map<String, Integer> severityCounts = new LinkedHashMap<>();
            severityCounts.put("CRITICAL", 0);
            severityCounts.put("HIGH", 0);
            severityCounts.put("MEDIUM", 0);
            severityCounts.put("LOW", 0);

            List<Map<String, Object>> sampleFindings = new ArrayList<>();
            int count = 0;
            for (ReviewFinding f : findings) {
                String sev = f.getSeverity() != null ? f.getSeverity().name() : "LOW";
                severityCounts.put(sev, severityCounts.getOrDefault(sev, 0) + 1);

                if (count < MAX_SECURITY_FINDINGS) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", SecretRedactor.sanitizeLogs(f.getTitle()));
                    item.put("severity", sev);
                    item.put("filePath", SecretRedactor.sanitizeLogs(f.getFilePath()));
                    sampleFindings.add(item);
                    count++;
                }
            }

            String certStatus = certificates.isEmpty() ? "NONE" :
                    certificates.get(0).getComplianceStatus() != null ? certificates.get(0).getComplianceStatus().name() : "UNKNOWN";

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("totalReviews", reviews.size());
            root.put("totalFindings", findings.size());
            root.put("severityCounts", severityCounts);
            root.put("complianceCertificateStatus", certStatus);
            root.put("sampleFindings", sampleFindings);
            return truncateJson(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            return "{\"totalReviews\":" + reviews.size() + ",\"totalFindings\":" + findings.size() + "}";
        }
    }

    private BigDecimal calculateConfidenceScore(
            ExecutionStatus status,
            List<CodeChange> changes,
            List<TestResult> testResults,
            List<CodeReview> reviews,
            List<ReviewFinding> findings) {
        if (status == ExecutionStatus.FAILED) {
            return new BigDecimal("0.00");
        }
        double base = 100.0;
        for (ReviewFinding f : findings) {
            String sev = f.getSeverity() != null ? f.getSeverity().name() : "LOW";
            if ("CRITICAL".equalsIgnoreCase(sev)) base -= 25.0;
            else if ("HIGH".equalsIgnoreCase(sev)) base -= 15.0;
            else if ("MEDIUM".equalsIgnoreCase(sev)) base -= 5.0;
            else if ("LOW".equalsIgnoreCase(sev)) base -= 1.0;
        }
        if (base < 0.0) base = 0.0;
        return BigDecimal.valueOf(base).setScale(2, RoundingMode.HALF_UP);
    }

    private String truncateJson(String json) {
        if (json == null) return null;
        String sanitized = SecretRedactor.sanitizeLogs(json);
        if (sanitized.length() > MAX_SUMMARY_SIZE_BYTES) {
            return sanitized.substring(0, MAX_SUMMARY_SIZE_BYTES - 20) + "...[TRUNCATED]";
        }
        return sanitized;
    }

    private ImplementationReportResponseDto mapToDto(ImplementationReport report) {
        ImplementationReportResponseDto dto = new ImplementationReportResponseDto();
        dto.setId(report.getId());
        dto.setProjectId(report.getProject() != null ? report.getProject().getId() : null);
        dto.setRepositoryId(report.getRepository() != null ? report.getRepository().getId() : null);
        dto.setTaskId(report.getTask() != null ? report.getTask().getId() : null);
        dto.setTaskExecutionId(report.getTaskExecution() != null ? report.getTaskExecution().getId() : null);
        dto.setStatus(report.getStatus());
        dto.setExecutionOutcome(report.getExecutionOutcome());
        dto.setChangedFilesSummary(SecretRedactor.sanitizeLogs(report.getChangedFilesSummary()));
        dto.setTestSummary(SecretRedactor.sanitizeLogs(report.getTestSummary()));
        dto.setSecurityReviewSummary(SecretRedactor.sanitizeLogs(report.getSecurityReviewSummary()));
        dto.setConfidenceScore(report.getConfidenceScore());
        dto.setSourceContextHash(report.getSourceContextHash());
        dto.setCorrelationId(report.getCorrelationId());
        dto.setFailureReason(SecretRedactor.sanitizeLogs(report.getFailureReason()));
        dto.setCreatedAt(report.getCreatedAt());
        return dto;
    }
}
