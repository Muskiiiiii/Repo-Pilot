package com.repopilot.task.entity;

public enum TaskStatus {
    CREATED,
    ANALYZING,
    PLANNING,
    WAITING_FOR_PLAN_APPROVAL,
    CODING,
    TESTING,
    REVIEWING,
    WAITING_FOR_APPROVAL,
    CREATING_PR,
    COMPLETED,
    FAILED,
    CANCELLED
}
