package com.repopilot.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.github.security.SecretRedactor;
import com.repopilot.project.entity.Project;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final int MAX_METADATA_CHARS = 64000;
    private static final Pattern AWS_KEY_PATTERN = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern AWS_SECRET_PATTERN = Pattern.compile("(?i)(aws_secret_access_key|aws_secret|secret_key)\\s*[:=]\\s*[\"']?[A-Za-z0-9/+=_-]{16,64}[\"']?");
    private static final Pattern GENERIC_SECRET_PATTERN = Pattern.compile("(?i)(\"?(?:password|token|secret|api_key|access_key)\"?\\s*[:=]\\s*\")[^\"]+(\")");
    private static final Pattern LLM_KEY_PATTERN = Pattern.compile("sk-[a-zA-Z0-9_-]{20,}");
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}");
    private static final Pattern GITHUB_PAT_PATTERN = Pattern.compile("github_pat_[a-zA-Z0-9_]{30,90}");
    private static final Pattern DB_URL_PATTERN = Pattern.compile("(postgres|postgresql|mysql|mongodb|redis)://[^\\s\"']+");

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogServiceImpl(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    @Override
    @Transactional
    public AuditLog logAction(User user, Project project, Task task, String action, String resourceType, UUID resourceId, String metadata) {
        return logAction(user, null, project, task, action, resourceType, resourceId, metadata);
    }

    @Override
    @Transactional
    public AuditLog logAction(User user, com.repopilot.organization.entity.Organization explicitOrg, Project project, Task task, String action, String resourceType, UUID resourceId, String metadata) {
        // Auto-infer project from task if project is null but task has assigned project
        if (project == null && task != null && task.getProject() != null) {
            project = task.getProject();
        }

        // Cross-hierarchy IDOR protection
        if (task != null && project != null && task.getProject() != null) {
            if (!task.getProject().getId().equals(project.getId())) {
                throw new IllegalArgumentException("Cross-project audit IDOR violation: task " + task.getId() + " does not belong to project " + project.getId());
            }
        }

        // Resolve organization & enforce strict organizational consistency
        com.repopilot.organization.entity.Organization resolvedOrg = null;
        if (project != null && project.getOrganization() != null) {
            resolvedOrg = project.getOrganization();
        } else if (task != null && task.getProject() != null && task.getProject().getOrganization() != null) {
            resolvedOrg = task.getProject().getOrganization();
        }

        if (explicitOrg != null && resolvedOrg != null) {
            if (!explicitOrg.getId().equals(resolvedOrg.getId())) {
                throw new IllegalArgumentException("Cross-tenant audit organization mismatch: explicit org " + explicitOrg.getId() + " does not match project/task org " + resolvedOrg.getId());
            }
        }

        AuditLog auditLog = new AuditLog();
        auditLog.setUser(user);
        auditLog.setProject(project);
        auditLog.setTask(task);
        com.repopilot.organization.entity.Organization finalOrg = (explicitOrg != null) ? explicitOrg : resolvedOrg;
        auditLog.setOrganization(finalOrg);
        auditLog.setAction(action);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);

        String redacted = sanitizeMetadata(metadata);

        if (redacted != null && !redacted.trim().isEmpty()) {
            String trimmed = redacted.trim();
            if (trimmed.length() > MAX_METADATA_CHARS) {
                trimmed = trimmed.substring(0, MAX_METADATA_CHARS) + "...[TRUNCATED]";
            }
            if (isValidJson(trimmed)) {
                auditLog.setMetadata(trimmed);
            } else {
                try {
                    auditLog.setMetadata(objectMapper.writeValueAsString(Map.of("details", trimmed)));
                } catch (Exception e) {
                    auditLog.setMetadata("{\"details\":\"" + escapeJson(trimmed) + "\"}");
                }
            }
        } else {
            auditLog.setMetadata(null);
        }

        AuditLog saved = auditLogRepository.save(auditLog);
        try {
            auditLogRepository.flush();
        } catch (Exception ignored) {}
        return saved;
    }

    @Override
    @Transactional
    public AuditLog logReportRequested(User user, Project project, Task task, UUID taskExecutionId, String correlationId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("taskExecutionId", taskExecutionId != null ? taskExecutionId.toString() : null);
        meta.put("correlationId", correlationId);
        meta.put("status", "REQUESTED");
        return logAction(user, project, task, "REPORT_GENERATION_REQUESTED", "IMPLEMENTATION_REPORT", taskExecutionId, toJson(meta));
    }

    @Override
    @Transactional
    public AuditLog logReportGenerated(User user, Project project, Task task, UUID reportId, String correlationId, String metadataJson) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("reportId", reportId != null ? reportId.toString() : null);
        meta.put("correlationId", correlationId);
        meta.put("status", "GENERATED");
        if (metadataJson != null) {
            meta.put("details", sanitizeMetadata(metadataJson));
        }
        return logAction(user, project, task, "REPORT_GENERATED", "IMPLEMENTATION_REPORT", reportId, toJson(meta));
    }

    @Override
    @Transactional
    public AuditLog logReportFailed(User user, Project project, Task task, UUID taskExecutionId, String correlationId, String errorMessage) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("taskExecutionId", taskExecutionId != null ? taskExecutionId.toString() : null);
        meta.put("correlationId", correlationId);
        meta.put("status", "FAILED");
        meta.put("error", sanitizeMetadata(errorMessage));
        return logAction(user, project, task, "REPORT_GENERATION_FAILED", "IMPLEMENTATION_REPORT", taskExecutionId, toJson(meta));
    }

    @Override
    @Transactional
    public AuditLog logSseStreamConnected(User user, Project project, Task task, String correlationId, String clientIp) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("correlationId", correlationId);
        meta.put("clientIp", clientIp);
        meta.put("eventType", "STREAM_CONNECT");
        return logAction(user, project, task, "SSE_STREAM_CONNECTED", "SSE_STREAM", task != null ? task.getId() : null, toJson(meta));
    }

    @Override
    @Transactional
    public AuditLog logSseStreamReplayed(User user, Project project, Task task, String lastEventId, String correlationId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("lastEventId", sanitizeMetadata(lastEventId));
        meta.put("correlationId", correlationId);
        meta.put("eventType", "STREAM_REPLAY");
        return logAction(user, project, task, "SSE_STREAM_REPLAY", "SSE_STREAM", task != null ? task.getId() : null, toJson(meta));
    }

    @Override
    @Transactional
    public AuditLog logReportViewed(User user, Project project, Task task, UUID reportId, String clientIp) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("reportId", reportId != null ? reportId.toString() : null);
        meta.put("clientIp", clientIp);
        return logAction(user, project, task, "REPORT_VIEWED", "IMPLEMENTATION_REPORT", reportId, toJson(meta));
    }

    private String sanitizeMetadata(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String sanitized = SecretRedactor.sanitizeLogs(input);
        sanitized = AWS_KEY_PATTERN.matcher(sanitized).replaceAll("[REDACTED_AWS_KEY]");
        sanitized = AWS_SECRET_PATTERN.matcher(sanitized).replaceAll("$1 = [REDACTED_AWS_SECRET]");
        sanitized = GENERIC_SECRET_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED_SECRET]$2");
        sanitized = LLM_KEY_PATTERN.matcher(sanitized).replaceAll("[REDACTED_LLM_KEY]");
        sanitized = DB_URL_PATTERN.matcher(sanitized).replaceAll("$1://[REDACTED_DB_CREDENTIALS]");
        return sanitized;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean isValidJson(String input) {
        if (input == null || input.isEmpty()) return false;
        if (!input.startsWith("{") && !input.startsWith("[")) return false;
        try {
            objectMapper.readTree(input);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
