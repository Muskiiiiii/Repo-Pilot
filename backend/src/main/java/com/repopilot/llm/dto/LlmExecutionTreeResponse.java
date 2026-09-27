package com.repopilot.llm.dto;

import java.util.ArrayList;
import java.util.List;

public class LlmExecutionTreeResponse {

    private LogicalRequestInspectionResponse logicalRequest;
    private List<ExecutionTreeNode> nodes = new ArrayList<>();
    private Integer totalNodes = 0;

    public LlmExecutionTreeResponse() {
    }

    public LlmExecutionTreeResponse(LogicalRequestInspectionResponse logicalRequest, List<ExecutionTreeNode> nodes, Integer totalNodes) {
        this.logicalRequest = logicalRequest;
        this.nodes = nodes != null ? nodes : new ArrayList<>();
        this.totalNodes = totalNodes;
    }

    public LogicalRequestInspectionResponse getLogicalRequest() {
        return logicalRequest;
    }

    public void setLogicalRequest(LogicalRequestInspectionResponse logicalRequest) {
        this.logicalRequest = logicalRequest;
    }

    public List<ExecutionTreeNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<ExecutionTreeNode> nodes) {
        this.nodes = nodes;
    }

    public Integer getTotalNodes() {
        return totalNodes;
    }

    public void setTotalNodes(Integer totalNodes) {
        this.totalNodes = totalNodes;
    }

    public static class ExecutionTreeNode {
        private ProviderAttemptInspectionResponse attempt;
        private List<ExecutionTreeDecisionNode> triggeredDecisions = new ArrayList<>();

        public ExecutionTreeNode() {
        }

        public ExecutionTreeNode(ProviderAttemptInspectionResponse attempt, List<ExecutionTreeDecisionNode> triggeredDecisions) {
            this.attempt = attempt;
            this.triggeredDecisions = triggeredDecisions != null ? triggeredDecisions : new ArrayList<>();
        }

        public ProviderAttemptInspectionResponse getAttempt() {
            return attempt;
        }

        public void setAttempt(ProviderAttemptInspectionResponse attempt) {
            this.attempt = attempt;
        }

        public List<ExecutionTreeDecisionNode> getTriggeredDecisions() {
            return triggeredDecisions;
        }

        public void setTriggeredDecisions(List<ExecutionTreeDecisionNode> triggeredDecisions) {
            this.triggeredDecisions = triggeredDecisions;
        }
    }

    public static class ExecutionTreeDecisionNode {
        private FallbackDecisionInspectionResponse decision;
        private ProviderAttemptInspectionResponse targetAttempt;

        public ExecutionTreeDecisionNode() {
        }

        public ExecutionTreeDecisionNode(FallbackDecisionInspectionResponse decision, ProviderAttemptInspectionResponse targetAttempt) {
            this.decision = decision;
            this.targetAttempt = targetAttempt;
        }

        public FallbackDecisionInspectionResponse getDecision() {
            return decision;
        }

        public void setDecision(FallbackDecisionInspectionResponse decision) {
            this.decision = decision;
        }

        public ProviderAttemptInspectionResponse getTargetAttempt() {
            return targetAttempt;
        }

        public void setTargetAttempt(ProviderAttemptInspectionResponse targetAttempt) {
            this.targetAttempt = targetAttempt;
        }
    }
}
