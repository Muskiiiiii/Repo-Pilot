package com.repopilot.audit.service;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.project.entity.Project;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface AuditLogService {

    AuditLog logAction(User user, Project project, Task task, String action, String resourceType, UUID resourceId, String metadata);

    default AuditLog logAction(User user, com.repopilot.organization.entity.Organization explicitOrg, Project project, Task task, String action, String resourceType, UUID resourceId, String metadata) {
        return logAction(user, project, task, action, resourceType, resourceId, metadata);
    }

    // Phase 15 Report & SSE Lifecycle Audit Helpers (default methods to preserve FunctionalInterface compatibility)
    default AuditLog logReportRequested(User user, Project project, Task task, UUID taskExecutionId, String correlationId) {
        String meta = "{\"taskExecutionId\":\"" + (taskExecutionId != null ? taskExecutionId : "") + "\",\"correlationId\":\"" + (correlationId != null ? correlationId : "") + "\",\"status\":\"REQUESTED\"}";
        return logAction(user, project, task, "REPORT_GENERATION_REQUESTED", "IMPLEMENTATION_REPORT", taskExecutionId, meta);
    }

    default AuditLog logReportGenerated(User user, Project project, Task task, UUID reportId, String correlationId, String metadataJson) {
        String meta = "{\"reportId\":\"" + (reportId != null ? reportId : "") + "\",\"correlationId\":\"" + (correlationId != null ? correlationId : "") + "\",\"status\":\"GENERATED\"" + (metadataJson != null ? ",\"details\":" + (metadataJson.startsWith("{") ? metadataJson : "\"" + metadataJson + "\"") : "") + "}";
        return logAction(user, project, task, "REPORT_GENERATED", "IMPLEMENTATION_REPORT", reportId, meta);
    }

    default AuditLog logReportFailed(User user, Project project, Task task, UUID taskExecutionId, String correlationId, String errorMessage) {
        String meta = "{\"taskExecutionId\":\"" + (taskExecutionId != null ? taskExecutionId : "") + "\",\"correlationId\":\"" + (correlationId != null ? correlationId : "") + "\",\"status\":\"FAILED\",\"error\":\"" + (errorMessage != null ? errorMessage.replace("\"", "\\\"") : "") + "\"}";
        return logAction(user, project, task, "REPORT_GENERATION_FAILED", "IMPLEMENTATION_REPORT", taskExecutionId, meta);
    }

    default AuditLog logSseStreamConnected(User user, Project project, Task task, String correlationId, String clientIp) {
        String meta = "{\"correlationId\":\"" + (correlationId != null ? correlationId : "") + "\",\"clientIp\":\"" + (clientIp != null ? clientIp : "") + "\",\"eventType\":\"STREAM_CONNECT\"}";
        return logAction(user, project, task, "SSE_STREAM_CONNECTED", "SSE_STREAM", task != null ? task.getId() : null, meta);
    }

    default AuditLog logSseStreamReplayed(User user, Project project, Task task, String lastEventId, String correlationId) {
        String meta = "{\"lastEventId\":\"" + (lastEventId != null ? lastEventId : "") + "\",\"correlationId\":\"" + (correlationId != null ? correlationId : "") + "\",\"eventType\":\"STREAM_REPLAY\"}";
        return logAction(user, project, task, "SSE_STREAM_REPLAY", "SSE_STREAM", task != null ? task.getId() : null, meta);
    }

    default AuditLog logReportViewed(User user, Project project, Task task, UUID reportId, String clientIp) {
        String meta = "{\"reportId\":\"" + (reportId != null ? reportId : "") + "\",\"clientIp\":\"" + (clientIp != null ? clientIp : "") + "\"}";
        return logAction(user, project, task, "REPORT_VIEWED", "IMPLEMENTATION_REPORT", reportId, meta);
    }
}
