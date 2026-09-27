package com.repopilot.task.sse.dto;

import java.time.Instant;
import java.util.UUID;

public class SseTaskEventDto {

    private String id;
    private String event;
    private UUID taskId;
    private Instant timestamp;
    private Object data;

    public SseTaskEventDto() {
    }

    public SseTaskEventDto(String id, String event, UUID taskId, Instant timestamp, Object data) {
        this.id = id;
        this.event = event;
        this.taskId = taskId;
        this.timestamp = timestamp;
        this.data = data;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
