package com.repopilot.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.approval.controller.HumanApprovalController;
import com.repopilot.approval.dto.GitPullRequestResult;
import com.repopilot.approval.dto.HumanApprovalCreateRequest;
import com.repopilot.approval.dto.HumanApprovalResponseDto;
import com.repopilot.approval.dto.PullRequestPublicationStatus;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.service.GitPublicationRecoveryBoundary;
import com.repopilot.approval.service.HumanApprovalService;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Phase12_2FHumanApprovalControllerTest {

    private MockMvc mockMvc;
    private HumanApprovalService humanApprovalService;
    private GitPublicationRecoveryBoundary gitPublicationRecoveryBoundary;
    private ObjectMapper objectMapper;

    private User currentUser;
    private UserPrincipal userPrincipal;

    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID codeChangeId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        humanApprovalService = mock(HumanApprovalService.class);
        gitPublicationRecoveryBoundary = mock(GitPublicationRecoveryBoundary.class);
        objectMapper = new ObjectMapper();

        currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        currentUser.setEmail("admin@repopilot.io");
        currentUser.setRole(UserRole.ADMIN);

        userPrincipal = new UserPrincipal(currentUser);

        HumanApprovalController controller = new HumanApprovalController(
                humanApprovalService,
                gitPublicationRecoveryBoundary
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType().equals(UserPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter,
                                                  ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest,
                                                  WebDataBinderFactory binderFactory) {
                        return userPrincipal;
                    }
                })
                .build();
    }

    private HumanApprovalResponseDto createSampleResponseDto(ApprovalStatus status) {
        return new HumanApprovalResponseDto(
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                UUID.randomUUID(),
                1,
                status,
                "1234567890abcdef1234567890abcdef12345678",
                "origHash123",
                "propHash456",
                "diffHash789",
                "ctxHash012",
                List.of("src/Main.java"),
                1,
                "recordHash999",
                currentUser.getId(),
                currentUser.getEmail(),
                "ADMIN",
                Instant.now(),
                null,
                "idemp-key-1",
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. POST createApproval returns 201 CREATED with approval DTO")
    void testCreateApproval() throws Exception {
        HumanApprovalResponseDto dto = createSampleResponseDto(ApprovalStatus.APPROVED);
        when(humanApprovalService.createApproval(
                eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), any(), any(), eq(currentUser)
        )).thenReturn(dto);

        HumanApprovalCreateRequest request = new HumanApprovalCreateRequest();
        request.setIdempotencyKey("test-idemp-1");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/code-changes/{codeChangeId}/approvals",
                projectId, repositoryId, taskId, codeChangeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvalId").value(approvalId.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByUserEmail").value("admin@repopilot.io"));
    }

    @Test
    @DisplayName("2. GET getApproval returns 200 OK with approval DTO")
    void testGetApproval() throws Exception {
        HumanApprovalResponseDto dto = createSampleResponseDto(ApprovalStatus.APPROVED);
        when(humanApprovalService.getApprovalById(eq(projectId), eq(approvalId), eq(currentUser)))
                .thenReturn(dto);

        mockMvc.perform(get("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/approvals/{approvalId}",
                projectId, repositoryId, taskId, approvalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(approvalId.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("3. GET getApprovalsForCodeChange returns 200 OK with list")
    void testGetApprovalsForCodeChange() throws Exception {
        HumanApprovalResponseDto dto = createSampleResponseDto(ApprovalStatus.APPROVED);
        when(humanApprovalService.getApprovalsForCodeChange(eq(projectId), eq(codeChangeId), eq(currentUser)))
                .thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/code-changes/{codeChangeId}/approvals",
                projectId, repositoryId, taskId, codeChangeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].approvalId").value(approvalId.toString()));
    }

    @Test
    @DisplayName("4. POST revokeApproval returns 200 OK with revoked status")
    void testRevokeApproval() throws Exception {
        HumanApprovalResponseDto dto = createSampleResponseDto(ApprovalStatus.REVOKED);
        when(humanApprovalService.revokeApproval(eq(projectId), eq(approvalId), eq("Security defect"), eq(currentUser)))
                .thenReturn(dto);

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/approvals/{approvalId}/revoke",
                projectId, repositoryId, taskId, approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "Security defect"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(approvalId.toString()))
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    @DisplayName("5. POST publishOrRecoverApproval returns 200 OK when PR created")
    void testPublishOrRecoverApprovalSuccess() throws Exception {
        GitPullRequestResult prResult = GitPullRequestResult.success(
                UUID.randomUUID(), approvalId, projectId, repositoryId, taskId, codeChangeId,
                42L, "https://github.com/org/repo/pull/42", "repopilot/task-1", "main", "sha123", "Title",
                PullRequestPublicationStatus.PR_CREATED
        );

        when(gitPublicationRecoveryBoundary.recoverOrPublish(
                eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(approvalId), eq(currentUser)
        )).thenReturn(prResult);

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/code-changes/{codeChangeId}/approvals/{approvalId}/publish",
                projectId, repositoryId, taskId, codeChangeId, approvalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PR_CREATED"))
                .andExpect(jsonPath("$.githubPrNumber").value(42));
    }

    @Test
    @DisplayName("6. POST publishOrRecoverApproval returns 409 CONFLICT when publish fails or conflicts")
    void testPublishOrRecoverApprovalConflict() throws Exception {
        GitPullRequestResult conflictResult = GitPullRequestResult.failure(
                UUID.randomUUID(), approvalId, projectId, repositoryId, taskId, codeChangeId,
                PullRequestPublicationStatus.FAILED_CONFLICT, "CONFLICT", "Remote branch sha mismatch"
        );

        when(gitPublicationRecoveryBoundary.recoverOrPublish(
                eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(approvalId), eq(currentUser)
        )).thenReturn(conflictResult);

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/code-changes/{codeChangeId}/approvals/{approvalId}/publish",
                projectId, repositoryId, taskId, codeChangeId, approvalId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILED_CONFLICT"))
                .andExpect(jsonPath("$.failureCategory").value("CONFLICT"));
    }

    @Test
    @DisplayName("7. POST publishOrRecoverApproval rejects cross-resource mismatch (wrong project or task)")
    void testPublishOrRecoverCrossResourceMismatch() throws Exception {
        UUID wrongProjectId = UUID.randomUUID();
        GitPullRequestResult mismatchResult = GitPullRequestResult.failure(
                UUID.randomUUID(), approvalId, wrongProjectId, repositoryId, taskId, codeChangeId,
                PullRequestPublicationStatus.FAILED_VERIFICATION, "RELATIONSHIP_MISMATCH", "Approval entity bindings do not match request parameters"
        );

        when(gitPublicationRecoveryBoundary.recoverOrPublish(
                eq(wrongProjectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(approvalId), eq(currentUser)
        )).thenReturn(mismatchResult);

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/code-changes/{codeChangeId}/approvals/{approvalId}/publish",
                wrongProjectId, repositoryId, taskId, codeChangeId, approvalId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILED_VERIFICATION"))
                .andExpect(jsonPath("$.failureCategory").value("RELATIONSHIP_MISMATCH"));
    }

    @Test
    @DisplayName("8. Controller is strictly an API orchestration boundary and delegates directly to recovery boundary")
    void testControllerIsStrictlyOrchestrationBoundary() throws Exception {
        GitPullRequestResult prResult = GitPullRequestResult.success(
                UUID.randomUUID(), approvalId, projectId, repositoryId, taskId, codeChangeId,
                101L, "https://github.com/org/repo/pull/101", "repopilot/task-101", "main", "sha101", "Title",
                PullRequestPublicationStatus.PR_CREATED
        );

        when(gitPublicationRecoveryBoundary.recoverOrPublish(
                eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(approvalId), eq(currentUser)
        )).thenReturn(prResult);

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/code-changes/{codeChangeId}/approvals/{approvalId}/publish",
                projectId, repositoryId, taskId, codeChangeId, approvalId))
                .andExpect(status().isOk());

        verify(gitPublicationRecoveryBoundary, times(1)).recoverOrPublish(
                eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(approvalId), eq(currentUser)
        );
        verifyNoMoreInteractions(gitPublicationRecoveryBoundary);
    }
}
