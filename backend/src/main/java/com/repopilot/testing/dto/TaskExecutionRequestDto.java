package com.repopilot.testing.dto;

import com.repopilot.testing.entity.Ecosystem;

public class TaskExecutionRequestDto {

    private Ecosystem ecosystem = Ecosystem.PYTHON;
    private String testTarget;

    public TaskExecutionRequestDto() {
    }

    public TaskExecutionRequestDto(Ecosystem ecosystem, String testTarget) {
        this.ecosystem = ecosystem;
        this.testTarget = testTarget;
    }

    public Ecosystem getEcosystem() {
        return ecosystem;
    }

    public void setEcosystem(Ecosystem ecosystem) {
        this.ecosystem = ecosystem;
    }

    public String getTestTarget() {
        return testTarget;
    }

    public void setTestTarget(String testTarget) {
        this.testTarget = testTarget;
    }
}
