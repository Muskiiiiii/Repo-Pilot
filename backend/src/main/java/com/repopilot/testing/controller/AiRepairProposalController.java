package com.repopilot.testing.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.testing.dto.AIRepairProposalResponseDto;
import com.repopilot.testing.dto.RepairExecutionResponseDto;
import com.repopilot.testing.service.AiRepairProposalService;
import com.repopilot.testing.service.PhysicalRepairApplicationService;
import com.repopilot.testing.service.SingleIterationRepairExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/executions/{executionId}/repair-iterations/{iterationId}")
public class AiRepairProposalController {

    private final AiRepairProposalService aiRepairProposalService;
    private final PhysicalRepairApplicationService physicalRepairApplicationService;
    private final SingleIterationRepairExecutionService singleIterationRepairExecutionService;

    @Autowired
    public AiRepairProposalController(AiRepairProposalService aiRepairProposalService,
                                      PhysicalRepairApplicationService physicalRepairApplicationService,
                                      SingleIterationRepairExecutionService singleIterationRepairExecutionService) {
        this.aiRepairProposalService = aiRepairProposalService;
        this.physicalRepairApplicationService = physicalRepairApplicationService;
        this.singleIterationRepairExecutionService = singleIterationRepairExecutionService;
    }

    @PostMapping("/generate-repair-proposal")
    public ResponseEntity<AIRepairProposalResponseDto> generateProposal(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @PathVariable UUID iterationId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        AIRepairProposalResponseDto response = aiRepairProposalService.generateProposal(
                projectId, repositoryId, taskId, executionId, iterationId, currentUserPrincipal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/repair-proposal")
    public ResponseEntity<AIRepairProposalResponseDto> getProposal(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @PathVariable UUID iterationId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        AIRepairProposalResponseDto response = aiRepairProposalService.getProposal(
                projectId, repositoryId, taskId, executionId, iterationId, currentUserPrincipal.getUser());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/apply-repair-proposal")
    public ResponseEntity<AIRepairProposalResponseDto> applyPhysicalRepair(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @PathVariable UUID iterationId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        AIRepairProposalResponseDto response = physicalRepairApplicationService.applyPhysicalRepair(
                projectId, repositoryId, taskId, executionId, iterationId, currentUserPrincipal.getUser());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/execute-repair")
    public ResponseEntity<RepairExecutionResponseDto> executeRepairIteration(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID executionId,
            @PathVariable UUID iterationId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        RepairExecutionResponseDto response = singleIterationRepairExecutionService.executeRepairIteration(
                projectId, repositoryId, taskId, executionId, iterationId, currentUserPrincipal.getUser());
        return ResponseEntity.ok(response);
    }
}
