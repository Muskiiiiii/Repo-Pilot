package com.repopilot.testing.entity;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.task.entity.TaskExecution;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repair_iterations", uniqueConstraints = {
    @UniqueConstraint(name = "uk_repair_iterations_exec_num", columnNames = {"execution_id", "iteration_number"})
})
public class RepairIteration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private TaskExecution taskExecution;

    @Column(name = "iteration_number", nullable = false)
    private Integer iterationNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RepairIterationStatus status = RepairIterationStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category", length = 50)
    private FailureType failureCategory;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private Repairability repairability;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_code_change_id")
    private CodeChange parentCodeChange;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_code_change_id")
    private CodeChange appliedCodeChange;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public RepairIteration() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public TaskExecution getTaskExecution() {
        return taskExecution;
    }

    public void setTaskExecution(TaskExecution taskExecution) {
        this.taskExecution = taskExecution;
    }

    public Integer getIterationNumber() {
        return iterationNumber;
    }

    public void setIterationNumber(Integer iterationNumber) {
        this.iterationNumber = iterationNumber;
    }

    public RepairIterationStatus getStatus() {
        return status;
    }

    public void setStatus(RepairIterationStatus status) {
        if (this.status != null && this.status.isTerminal()) {
            throw new IllegalStateException("Cannot transition RepairIteration out of terminal state: " + this.status);
        }
        this.status = status;
    }

    public FailureType getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(FailureType failureCategory) {
        this.failureCategory = failureCategory;
    }

    public Repairability getRepairability() {
        return repairability;
    }

    public void setRepairability(Repairability repairability) {
        this.repairability = repairability;
    }

    public CodeChange getParentCodeChange() {
        return parentCodeChange;
    }

    public void setParentCodeChange(CodeChange parentCodeChange) {
        this.parentCodeChange = parentCodeChange;
    }

    public CodeChange getAppliedCodeChange() {
        return appliedCodeChange;
    }

    public void setAppliedCodeChange(CodeChange appliedCodeChange) {
        this.appliedCodeChange = appliedCodeChange;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
