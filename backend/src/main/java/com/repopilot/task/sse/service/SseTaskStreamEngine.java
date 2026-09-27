package com.repopilot.task.sse.service;

import com.repopilot.project.entity.Project;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

public interface SseTaskStreamEngine {

    int MAX_USER_STREAMS = 5;
    int MAX_TASK_STREAMS = 20;
    long STREAM_TIMEOUT_MS = 60_000L; // 60 seconds max stream lifetime

    SseEmitter createTaskStream(UUID taskId, User user, Project project, Task task, String lastEventIdHeader, String clientIp);

    void closeStream(SseEmitter emitter);

    void publishTaskEvent(UUID taskId, String eventType, Object eventData);

    int getActiveUserStreamCount(UUID userId);

    int getActiveTaskStreamCount(UUID taskId);
}
