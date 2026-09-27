package com.repopilot.testing.daemon.service;

import com.repopilot.testing.daemon.model.DaemonExecutionRequest;
import com.repopilot.testing.daemon.model.DaemonExecutionResponse;
import com.repopilot.testing.daemon.model.DaemonOperationType;
import com.repopilot.testing.daemon.model.DaemonStatus;
import com.repopilot.testing.daemon.runtime.SandboxDockerRuntimeService;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.entity.SandboxJobExecution;
import com.repopilot.testing.entity.SandboxWorkerNode;
import com.repopilot.testing.repository.SandboxJobExecutionRepository;
import com.repopilot.testing.repository.SandboxWorkerNodeRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SandboxDaemonServiceImpl implements SandboxDaemonService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxDaemonServiceImpl.class);

    private static final int MAX_CONCURRENT_REQUESTS = 100;
    private static final long MAX_ALLOWED_TIME_SKEW_MS = 300_000L; // 5 minutes

    private static final List<String> PROHIBITED_SECURITY_TERMS = List.of(
            "--privileged", "--network=host", "--network host", "--cap-add", "--device",
            "-v /", "docker.sock", "DOCKER_HOST", "sh -c", "bash -c", "cmd /c",
            "GITHUB_TOKEN", "GH_TOKEN", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
            "DATABASE_URL", "DB_PASSWORD", "REDIS_PASSWORD", "JWT", "JWT_SECRET",
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY"
    );

    private final SandboxJobExecutionRepository jobExecutionRepository;
    private final SandboxWorkerNodeRepository workerNodeRepository;
    private final SandboxDockerRuntimeService dockerRuntimeService;

    private final AtomicInteger activeRequestCount = new AtomicInteger(0);
    private final Map<String, ExecutionBinding> ephemeralExecutionMap = new ConcurrentHashMap<>();

    @Autowired
    public SandboxDaemonServiceImpl(SandboxJobExecutionRepository jobExecutionRepository,
                                     SandboxWorkerNodeRepository workerNodeRepository,
                                     SandboxDockerRuntimeService dockerRuntimeService) {
        this.jobExecutionRepository = jobExecutionRepository;
        this.workerNodeRepository = workerNodeRepository;
        this.dockerRuntimeService = dockerRuntimeService;
    }

    public static class ExecutionBinding {
        private final UUID jobId;
        private final UUID workerNodeId;
        private final UUID leaseToken;
        private final Long fencingVersion;
        private final String containerName;
        private final Path workspaceDir;
        private final String commandType;
        private final Instant boundAt;

        public ExecutionBinding(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion,
                                String containerName, Path workspaceDir, String commandType) {
            this.jobId = jobId;
            this.workerNodeId = workerNodeId;
            this.leaseToken = leaseToken;
            this.fencingVersion = fencingVersion;
            this.containerName = containerName;
            this.workspaceDir = workspaceDir;
            this.commandType = commandType;
            this.boundAt = Instant.now();
        }

        public UUID getJobId() { return jobId; }
        public UUID getWorkerNodeId() { return workerNodeId; }
        public UUID getLeaseToken() { return leaseToken; }
        public Long getFencingVersion() { return fencingVersion; }
        public String getContainerName() { return containerName; }
        public Path getWorkspaceDir() { return workspaceDir; }
        public String getCommandType() { return commandType; }
        public Instant getBoundAt() { return boundAt; }
    }

    @Override
    @Transactional(readOnly = true)
    public DaemonExecutionResponse processRequest(DaemonExecutionRequest request) {
        if (activeRequestCount.incrementAndGet() > MAX_CONCURRENT_REQUESTS) {
            activeRequestCount.decrementAndGet();
            return DaemonExecutionResponse.error(
                    request != null ? request.getRequestId() : "unknown",
                    DaemonStatus.DAEMON_UNAVAILABLE,
                    "ERR_DAEMON_BUSY",
                    "Daemon active request capacity exceeded",
                    request != null ? request.getJobId() : null,
                    request != null ? request.getExecutionId() : null,
                    request != null ? request.getFencingVersion() : null
            );
        }

        try {
            return doProcessRequest(request);
        } finally {
            activeRequestCount.decrementAndGet();
        }
    }

    private DaemonExecutionResponse doProcessRequest(DaemonExecutionRequest request) {
        // 1. Strict Request Validation
        if (request == null) {
            return DaemonExecutionResponse.error(
                    "unknown", DaemonStatus.INVALID_REQUEST, "ERR_NULL_REQUEST",
                    "Request body must not be null", null, null, null);
        }

        String reqId = request.getRequestId() != null ? request.getRequestId() : "req-" + UUID.randomUUID();

        if (isBlank(request.getRequestId()) || request.getJobId() == null ||
            request.getWorkerNodeId() == null || request.getLeaseToken() == null ||
            request.getFencingVersion() == null || isBlank(request.getExecutionId()) ||
            request.getOperationType() == null) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.INVALID_REQUEST, "ERR_MALFORMED_FIELDS",
                    "Required request fields must be present and non-blank", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (request.getFencingVersion() < 1L) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.INVALID_REQUEST, "ERR_INVALID_FENCING_VERSION",
                    "Fencing version must be >= 1", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (request.getTimeoutSeconds() != null && (request.getTimeoutSeconds() <= 0 || request.getTimeoutSeconds() > 3600)) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.INVALID_REQUEST, "ERR_INVALID_TIMEOUT",
                    "Timeout seconds must be between 1 and 3600", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (request.getRequestTimestampMs() != null) {
            long skew = Math.abs(System.currentTimeMillis() - request.getRequestTimestampMs());
            if (skew > MAX_ALLOWED_TIME_SKEW_MS) {
                return DaemonExecutionResponse.error(
                        reqId, DaemonStatus.TIMEOUT, "ERR_TIMESTAMP_SKEW",
                        "Request timestamp skew exceeds maximum limit", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
            }
        }

        // 2. Reject Prohibited Security Overrides
        if (containsProhibitedSecurityOverride(request.getCommandArgs()) || containsProhibitedSecurityOverride(request.getCommandType())) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.INVALID_REQUEST, "ERR_PROHIBITED_SECURITY_OVERRIDE",
                    "Prohibited security override attempt detected in request arguments: worker is not trusted to supply container security settings",
                    request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        // 3. Worker Identity Authentication
        Optional<SandboxWorkerNode> workerOpt = workerNodeRepository.findById(request.getWorkerNodeId());
        if (workerOpt.isEmpty() || !"HEALTHY".equalsIgnoreCase(workerOpt.get().getStatus())) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.UNAUTHENTICATED, "ERR_UNAUTHENTICATED_WORKER",
                    "Worker node is unknown or not healthy", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        // 4. Job Existence Check
        Optional<SandboxJobExecution> jobOpt = jobExecutionRepository.findById(request.getJobId());
        if (jobOpt.isEmpty()) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.UNKNOWN_JOB, "ERR_UNKNOWN_JOB",
                    "Job execution not found in PostgreSQL authority: " + request.getJobId(), request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        SandboxJobExecution job = jobOpt.get();

        // 5. Critical Fencing & Lease Expiration Validation against PostgreSQL Authority
        Long dbFencingVersion = job.getFencingVersion();
        if (dbFencingVersion != null && dbFencingVersion > request.getFencingVersion()) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.STALE_FENCE, "ERR_STALE_FENCE_VERSION",
                    "Fencing version " + request.getFencingVersion() + " is stale; current PostgreSQL generation is " + dbFencingVersion,
                    request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (dbFencingVersion != null && !dbFencingVersion.equals(request.getFencingVersion())) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.INVALID_REQUEST, "ERR_FENCING_MISMATCH",
                    "Mismatched fencing version", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (job.getAssignedWorkerNode() == null || !job.getAssignedWorkerNode().getId().equals(request.getWorkerNodeId()) ||
            job.getLeaseToken() == null || !job.getLeaseToken().equals(request.getLeaseToken())) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.UNAUTHORIZED, "ERR_UNAUTHORIZED_OWNER",
                    "Worker or lease token does not match authoritative owner", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        Instant now = Instant.now();
        if (job.getLeaseExpiresAt() == null || !job.getLeaseExpiresAt().isAfter(now)) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.LEASE_EXPIRED, "ERR_LEASE_EXPIRED",
                    "Worker lease for job " + request.getJobId() + " has expired in PostgreSQL authority",
                    request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        // 6. Execution Identity Binding & Lifecycle Operations
        String execId = request.getExecutionId();
        DaemonOperationType op = request.getOperationType();

        if (op == DaemonOperationType.PREPARE) {
            ExecutionBinding existing = ephemeralExecutionMap.get(execId);
            if (existing != null) {
                if (!existing.getJobId().equals(request.getJobId()) || !existing.getFencingVersion().equals(request.getFencingVersion())) {
                    return DaemonExecutionResponse.error(
                            reqId, DaemonStatus.CONFLICT, "ERR_EXECUTION_CONFLICT",
                            "ExecutionId " + execId + " is already bound to a different job/generation",
                            request.getJobId(), request.getExecutionId(), request.getFencingVersion());
                }
            }

            String containerName = "repopilot_exec_" + execId.replace("-", "_");
            Path workspaceDir = dockerRuntimeService.prepareWorkspace(request.getJobId(), execId, null);

            ephemeralExecutionMap.put(execId, new ExecutionBinding(
                    request.getJobId(), request.getWorkerNodeId(), request.getLeaseToken(), request.getFencingVersion(),
                    containerName, workspaceDir, request.getCommandType()
            ));
            return DaemonExecutionResponse.success(reqId, request.getJobId(), execId, request.getFencingVersion(), "PREPARED");
        }

        // For START, TERMINATE, CLEANUP, INSPECT -> executionId MUST be bound
        ExecutionBinding binding = ephemeralExecutionMap.get(execId);
        if (binding == null) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.UNKNOWN_EXECUTION, "ERR_UNKNOWN_EXECUTION",
                    "Execution ID " + execId + " has not been prepared or was already cleaned up",
                    request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (!binding.getJobId().equals(request.getJobId())) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.INVALID_REQUEST, "ERR_EXECUTION_JOB_MISMATCH",
                    "Execution ID " + execId + " is bound to job " + binding.getJobId() + ", not " + request.getJobId(),
                    request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (binding.getFencingVersion() < request.getFencingVersion() || !binding.getFencingVersion().equals(request.getFencingVersion())) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.STALE_FENCE, "ERR_STALE_EXECUTION_BINDING",
                    "Execution ID binding generation mismatch", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (!binding.getWorkerNodeId().equals(request.getWorkerNodeId())) {
            return DaemonExecutionResponse.error(
                    reqId, DaemonStatus.UNAUTHORIZED, "ERR_UNAUTHORIZED_WORKER_BINDING",
                    "Execution ID binding worker mismatch", request.getJobId(), request.getExecutionId(), request.getFencingVersion());
        }

        if (op == DaemonOperationType.START) {
            List<String> commandArgsList = parseCommandArgsList(request.getCommandArgs());
            DockerExecutionResult result = dockerRuntimeService.executeSecureContainer(
                    request.getJobId(), execId, request.getWorkerNodeId(), request.getFencingVersion(),
                    binding.getContainerName(), request.getCommandType(), request.getCommandType(),
                    commandArgsList, binding.getWorkspaceDir(), request.getTimeoutSeconds()
            );

            return DaemonExecutionResponse.success(reqId, request.getJobId(), execId, request.getFencingVersion(),
                    "STARTED:exitCode=" + result.getExitCode() + ":container=" + binding.getContainerName());
        }

        if (op == DaemonOperationType.INSPECT) {
            boolean active = dockerRuntimeService.inspectContainer(binding.getContainerName());
            return DaemonExecutionResponse.success(reqId, request.getJobId(), execId, request.getFencingVersion(),
                    "INSPECTED:active=" + active);
        }

        if (op == DaemonOperationType.TERMINATE) {
            dockerRuntimeService.terminateContainer(binding.getContainerName());
            return DaemonExecutionResponse.success(reqId, request.getJobId(), execId, request.getFencingVersion(), "TERMINATED");
        }

        if (op == DaemonOperationType.CLEANUP) {
            dockerRuntimeService.terminateContainer(binding.getContainerName());
            dockerRuntimeService.cleanupWorkspace(binding.getWorkspaceDir());
            ephemeralExecutionMap.remove(execId);
            return DaemonExecutionResponse.success(reqId, request.getJobId(), execId, request.getFencingVersion(), "CLEANED_UP");
        }

        return DaemonExecutionResponse.success(reqId, request.getJobId(), execId, request.getFencingVersion(), op.name() + "_ACCEPTED");
    }

    private boolean containsProhibitedSecurityOverride(String text) {
        if (isBlank(text)) return false;
        String lower = text.toLowerCase();
        for (String term : PROHIBITED_SECURITY_TERMS) {
            if (lower.contains(term.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseCommandArgsList(String args) {
        if (isBlank(args)) return Collections.emptyList();
        return Arrays.asList(args.trim().split("\\s+"));
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
