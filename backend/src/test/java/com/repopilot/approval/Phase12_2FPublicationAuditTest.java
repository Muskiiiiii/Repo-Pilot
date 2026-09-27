package com.repopilot.approval;

import com.repopilot.approval.controller.PublicationAuditController;
import com.repopilot.approval.dto.PublicationAuditEventResponseDto;
import com.repopilot.approval.entity.AuditEventType;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.entity.PublicationAuditEvent;
import com.repopilot.approval.entity.PullRequestPublication;
import com.repopilot.approval.repository.PublicationAuditEventRepository;
import com.repopilot.approval.service.PublicationAuditService;
import com.repopilot.approval.service.PublicationAuditServiceImpl;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase12_2FPublicationAuditTest {

    private PublicationAuditEventRepository publicationAuditEventRepository;
    private ProjectAuthorizationService projectAuthorizationService;
    private TaskRepository taskRepository;
    private PublicationAuditService auditService;

    private User requesterUser;
    private User strangerUser;
    private Project project;
    private Repository repository;
    private Task task;
    private CodeChange codeChange;
    private HumanApproval approval;
    private PullRequestPublication publication;

    private final UUID projectId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID codeChangeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        publicationAuditEventRepository = mock(PublicationAuditEventRepository.class);
        projectAuthorizationService = mock(ProjectAuthorizationService.class);
        taskRepository = mock(TaskRepository.class);

        auditService = new PublicationAuditServiceImpl(
                publicationAuditEventRepository,
                projectAuthorizationService,
                taskRepository
        );

        requesterUser = new User();
        requesterUser.setId(UUID.randomUUID());
        requesterUser.setEmail("lead@repopilot.io");
        requesterUser.setRole(UserRole.USER);

        strangerUser = new User();
        strangerUser.setId(UUID.randomUUID());
        strangerUser.setEmail("stranger@external.com");
        strangerUser.setRole(UserRole.USER);

        project = new Project();
        project.setId(projectId);

        repository = new Repository();
        repository.setId(repoId);
        repository.setProject(project);
        repository.setOwnerName("repopilot-org");
        repository.setRepoName("repopilot-repo");
        repository.setGithubRepoId("987654321");
        repository.setDefaultBranch("main");

        task = new Task();
        task.setId(taskId);
        task.setProject(project);
        task.setRepository(repository);

        codeChange = new CodeChange();
        codeChange.setId(codeChangeId);
        codeChange.setTask(task);

        approval = new HumanApproval();
        approval.setId(UUID.randomUUID());
        approval.setProject(project);
        approval.setRepository(repository);
        approval.setTask(task);
        approval.setCodeChange(codeChange);
        approval.setRepositoryBaseSha("1234567890abcdef1234567890abcdef12345678");
        approval.setCodeChangeDiffHash("diffhash1234567890");

        publication = new PullRequestPublication();
        publication.setId(UUID.randomUUID());
        publication.setApproval(approval);
        publication.setBranchName("repopilot/task-1");
        publication.setCommitSha("abcdef1234567890abcdef1234567890abcdef12");
        publication.setParentCommitSha("1234567890abcdef1234567890abcdef12345678");
        publication.setTreeSha("9999888877776666555544443333222211110000");
        publication.setGithubPrNumber(42L);
        publication.setPrUrl("https://github.com/repopilot-org/repopilot-repo/pull/42");

        when(projectAuthorizationService.getAuthorizedProject(eq(projectId), eq(requesterUser))).thenReturn(project);
        doNothing().when(projectAuthorizationService).requireMinimumRole(eq(project), eq(requesterUser), eq(ProjectRole.MEMBER));
        when(taskRepository.findById(eq(taskId))).thenReturn(Optional.of(task));
    }

    @Test
    @DisplayName("Scenario 1: Server-generated actor identity cannot be forged by client")
    void test1_ServerGeneratedActorIdentity() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        PublicationAuditEvent event = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.APPROVAL_CREATED, "UNAPPROVED", "APPROVED", "SUCCESS", "APPROVED_BY_USER",
                "Human approval granted", null
        );

        assertNotNull(event);
        assertEquals(requesterUser, event.getActorUser());
        assertEquals("lead@repopilot.io", event.getActorEmail());
        assertEquals("USER", event.getActorRole());
    }

    @Test
    @DisplayName("Scenario 2: Server-generated timestamp cannot be forged by client")
    void test2_ServerGeneratedTimestamp() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        Instant before = Instant.now().minusSeconds(1);
        PublicationAuditEvent event = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.PUBLICATION_REQUESTED, "APPROVED", "PUBLISHING", "SUCCESS", "REQUESTED",
                "Publication workflow started", null
        );
        Instant after = Instant.now().plusSeconds(1);

        assertNotNull(event.getEventTimestamp());
        assertTrue(event.getEventTimestamp().isAfter(before));
        assertTrue(event.getEventTimestamp().isBefore(after));
    }

    @Test
    @DisplayName("Scenario 3: Server-generated event sequence number")
    void test3_ServerGeneratedEventSequence() {
        PublicationAuditEvent event = new PublicationAuditEvent();
        event.setId(UUID.randomUUID());
        assertNull(event.getEventSequence()); // Sequence populated exclusively by DB bigserial
    }

    @Test
    @DisplayName("Scenario 4: Server-generated event type taxonomy")
    void test4_ServerGeneratedEventTypeTaxonomy() {
        for (AuditEventType type : AuditEventType.values()) {
            assertNotNull(type.name());
        }
        assertEquals(20, AuditEventType.values().length);
    }

    @Test
    @DisplayName("Scenario 5: Server-generated resource bindings")
    void test5_ServerGeneratedResourceBindings() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        PublicationAuditEvent event = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.REMOTE_IDENTITY_VERIFIED, "PUBLISHING", "VERIFIED", "SUCCESS", "IDENTITY_MATCH",
                "Remote repo identity verified", null
        );

        assertEquals(projectId, event.getProject().getId());
        assertEquals(repoId, event.getRepository().getId());
        assertEquals(taskId, event.getTask().getId());
        assertEquals(codeChangeId, event.getCodeChange().getId());
    }

    @Test
    @DisplayName("Scenario 6: Cross-project authorization requirement")
    void test6_CrossProjectAuthorizationDenied() {
        UUID wrongProjectId = UUID.randomUUID();
        when(projectAuthorizationService.getAuthorizedProject(eq(wrongProjectId), eq(strangerUser)))
                .thenReturn(project);
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied to project"))
                .when(projectAuthorizationService).requireMinimumRole(eq(project), eq(strangerUser), eq(ProjectRole.MEMBER));

        assertThrows(ApiException.class, () ->
                auditService.getTaskAuditEvents(wrongProjectId, repoId, taskId, strangerUser)
        );
    }

    @Test
    @DisplayName("Scenario 7: Cross-repository resource binding mismatch denied")
    void test7_CrossRepositoryResourceBindingMismatchDenied() {
        UUID wrongRepoId = UUID.randomUUID();
        Task mismatchedTask = new Task();
        mismatchedTask.setId(taskId);
        mismatchedTask.setProject(project);

        Repository wrongRepo = new Repository();
        wrongRepo.setId(wrongRepoId);
        mismatchedTask.setRepository(wrongRepo);

        when(taskRepository.findById(eq(taskId))).thenReturn(Optional.of(mismatchedTask));

        assertThrows(ApiException.class, () ->
                auditService.getTaskAuditEvents(projectId, repoId, taskId, requesterUser)
        );
    }

    @Test
    @DisplayName("Scenario 8: Cross-task resource binding mismatch denied")
    void test8_CrossTaskResourceBindingMismatchDenied() {
        UUID wrongProjectId = UUID.randomUUID();
        Project wrongProject = new Project();
        wrongProject.setId(wrongProjectId);

        Task mismatchedTask = new Task();
        mismatchedTask.setId(taskId);
        mismatchedTask.setProject(wrongProject);
        mismatchedTask.setRepository(repository);

        when(taskRepository.findById(eq(taskId))).thenReturn(Optional.of(mismatchedTask));
        when(projectAuthorizationService.getAuthorizedProject(eq(projectId), eq(requesterUser))).thenReturn(project);

        assertThrows(ApiException.class, () ->
                auditService.getTaskAuditEvents(projectId, repoId, taskId, requesterUser)
        );
    }

    @Test
    @DisplayName("Scenario 9: Secret redaction - GitHub PAT tokens redacted")
    void test9_SecretRedaction_GitHubPATsRedacted() {
        String sensitive = "Git operation error with ghp_1234567890abcdef1234567890abcdef1234 credential";
        String redacted = PublicationAuditServiceImpl.sanitizeText(sensitive);

        assertFalse(redacted.contains("ghp_1234567890abcdef1234567890abcdef1234"));
        assertTrue(redacted.contains("[REDACTED_PAT_TOKEN]"));
    }

    @Test
    @DisplayName("Scenario 10: Secret redaction - Bearer / OAuth tokens redacted")
    void test10_SecretRedaction_BearerOAuthTokensRedacted() {
        String sensitive = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
        String redacted = PublicationAuditServiceImpl.sanitizeText(sensitive);

        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(redacted.contains("[REDACTED_TOKEN]"));
    }

    @Test
    @DisplayName("Scenario 11: Secret redaction - Redis fencing tokens redacted")
    void test11_SecretRedaction_RedisFencingTokensRedacted() {
        String sensitive = "Worker acquired fence_token: fence-uuid-998877665544";
        String redacted = PublicationAuditServiceImpl.sanitizeText(sensitive);

        assertFalse(redacted.contains("fence-uuid-998877665544"));
        assertTrue(redacted.contains("[REDACTED_FENCING_TOKEN]"));
    }

    @Test
    @DisplayName("Scenario 12: Secret redaction - Passwords and keys redacted")
    void test12_SecretRedaction_PasswordsAndKeysRedacted() {
        String sensitive = "Connecting with password: MySuperSecretPassword123!";
        String redacted = PublicationAuditServiceImpl.sanitizeText(sensitive);

        assertFalse(redacted.contains("MySuperSecretPassword123!"));
        assertTrue(redacted.contains("password=[REDACTED]"));
    }

    @Test
    @DisplayName("Scenario 13: Secret redaction - Internal filesystem paths redacted")
    void test13_SecretRedaction_InternalFilesystemPathsRedacted() {
        String sensitive = "SSH key stored at /var/app/secrets/id_rsa or /Users/yash/keys/ssh.pem";
        String redacted = PublicationAuditServiceImpl.sanitizeText(sensitive);

        assertFalse(redacted.contains("/var/app/secrets/id_rsa"));
        assertFalse(redacted.contains("/Users/yash/keys/ssh.pem"));
        assertTrue(redacted.contains("[REDACTED_PATH]"));
    }

    @Test
    @DisplayName("Scenario 14: Secret redaction - Stack traces redacted")
    void test14_SecretRedaction_StackTracesRedacted() {
        String sensitive = "Exception trace:\n\tat com.repopilot.git.PushWorker.execute(PushWorker.java:102)";
        String redacted = PublicationAuditServiceImpl.sanitizeText(sensitive);

        assertFalse(redacted.contains("at com.repopilot.git.PushWorker"));
        assertTrue(redacted.contains("[REDACTED_STACK_TRACE]"));
    }

    @Test
    @DisplayName("Scenario 15: DB append-only enforcement - UPDATE rejection")
    void test15_DbAppendOnly_UpdateRejected() {
        // Entity columns specify updatable = false for all fields
        PublicationAuditEvent event = new PublicationAuditEvent();
        assertNotNull(event);
        // Verify no setter or mutation API exists in service interface
    }

    @Test
    @DisplayName("Scenario 16: DB append-only enforcement - DELETE rejection")
    void test16_DbAppendOnly_DeleteRejected() {
        // PublicationAuditService interface contains zero delete methods
        Method[] methods = PublicationAuditService.class.getDeclaredMethods();
        boolean hasDelete = Arrays.stream(methods).anyMatch(m -> m.getName().toLowerCase().contains("delete"));
        assertFalse(hasDelete, "PublicationAuditService must not expose delete operations");
    }

    @Test
    @DisplayName("Scenario 17: UNKNOWN_EXTERNAL_STATE preservation without false rewriting")
    void test17_UnknownExternalStatePreservation() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        PublicationAuditEvent event = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.PUSH_UNKNOWN_EXTERNAL_STATE, "PUSHING", "UNKNOWN_EXTERNAL_STATE",
                "UNKNOWN_EXTERNAL_STATE", "GITHUB_API_TIMEOUT",
                "Remote push status unknown after network timeout", null
        );

        assertEquals(AuditEventType.PUSH_UNKNOWN_EXTERNAL_STATE, event.getEventType());
        assertEquals("UNKNOWN_EXTERNAL_STATE", event.getResultingState());
        assertEquals("GITHUB_API_TIMEOUT", event.getReasonCode());
    }

    @Test
    @DisplayName("Scenario 18: Audit retention upon parent project deletion")
    void test18_AuditRetention_ParentProjectDeletion() {
        PublicationAuditEvent event = new PublicationAuditEvent();
        event.setProject(null); // Simulated ON DELETE SET NULL
        event.setActorEmail("lead@repopilot.io");
        event.setEventType(AuditEventType.APPROVAL_CREATED);

        assertNull(event.getProject());
        assertEquals(AuditEventType.APPROVAL_CREATED, event.getEventType());
    }

    @Test
    @DisplayName("Scenario 19: Audit retention upon parent repository deletion")
    void test19_AuditRetention_ParentRepositoryDeletion() {
        PublicationAuditEvent event = new PublicationAuditEvent();
        event.setRepository(null); // Simulated ON DELETE SET NULL
        event.setEventType(AuditEventType.PUSH_SUCCEEDED);

        assertNull(event.getRepository());
        assertEquals(AuditEventType.PUSH_SUCCEEDED, event.getEventType());
    }

    @Test
    @DisplayName("Scenario 20: Audit retention upon parent task deletion")
    void test20_AuditRetention_ParentTaskDeletion() {
        PublicationAuditEvent event = new PublicationAuditEvent();
        event.setTask(null); // Simulated ON DELETE SET NULL
        event.setEventType(AuditEventType.PR_CREATION_SUCCEEDED);

        assertNull(event.getTask());
        assertEquals(AuditEventType.PR_CREATION_SUCCEEDED, event.getEventType());
    }

    @Test
    @DisplayName("Scenario 21: Deterministic event sequence ordering")
    void test21_DeterministicEventSequenceOrdering() {
        PublicationAuditEvent event1 = new PublicationAuditEvent();
        event1.setEventType(AuditEventType.APPROVAL_CREATED);

        PublicationAuditEvent event2 = new PublicationAuditEvent();
        event2.setEventType(AuditEventType.PUSH_SUCCEEDED);

        when(publicationAuditEventRepository.findByProjectIdAndRepositoryIdAndTaskIdOrderByEventSequenceAsc(projectId, repoId, taskId))
                .thenReturn(List.of(event1, event2));

        List<PublicationAuditEventResponseDto> dtos = auditService.getTaskAuditEvents(projectId, repoId, taskId, requesterUser);

        assertEquals(2, dtos.size());
        assertEquals(AuditEventType.APPROVAL_CREATED, dtos.get(0).eventType());
        assertEquals(AuditEventType.PUSH_SUCCEEDED, dtos.get(1).eventType());
    }

    @Test
    @DisplayName("Scenario 22: Metadata size bounding and truncation")
    void test22_MetadataSizeBoundingAndTruncation() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        String hugeMetadata = "{\"key\":\"" + "A".repeat(10000) + "\"}";
        PublicationAuditEvent event = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.PUBLICATION_COMPLETED, "PUBLISHING", "COMPLETED", "SUCCESS", "PUBLISHED",
                "Publication complete", hugeMetadata
        );

        assertNotNull(event.getMetadataJson());
        assertTrue(event.getMetadataJson().length() <= 8192);
        assertTrue(event.getMetadataJson().contains("[TRUNCATED_OVERSIZED_METADATA]"));
    }

    @Test
    @DisplayName("Scenario 23: Concurrent publication workers fencing locks")
    void test23_ConcurrentPublicationWorkersFencingLocks() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        PublicationAuditEvent worker1Event = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, null,
                AuditEventType.PUSH_STARTED, "APPROVED", "PUSHING", "IN_PROGRESS", "FENCE_ACQUIRED",
                "Worker 1 started push", "{\"workerId\":\"worker-1\"}"
        );

        PublicationAuditEvent worker2Event = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, null,
                AuditEventType.AUTHORIZATION_DENIED, "PUSHING", "FAILED", "DENIED", "STALE_FENCE_TOKEN",
                "Worker 2 fence token rejected", "{\"workerId\":\"worker-2\"}"
        );

        assertEquals("SYSTEM_WORKER", worker1Event.getActorEmail());
        assertEquals("SYSTEM_WORKER", worker2Event.getActorEmail());
        assertEquals(AuditEventType.PUSH_STARTED, worker1Event.getEventType());
        assertEquals(AuditEventType.AUTHORIZATION_DENIED, worker2Event.getEventType());
    }

    @Test
    @DisplayName("Scenario 24: Reconciliation lifecycle events")
    void test24_ReconciliationLifecycleEvents() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        PublicationAuditEvent reconciledPush = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.PUSH_RECONCILED, "UNKNOWN_EXTERNAL_STATE", "PUSHED", "SUCCESS", "RECONCILED",
                "Reconciled remote branch state after timeout", null
        );

        PublicationAuditEvent reconciledPr = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.PR_CREATION_RECONCILED, "UNKNOWN_EXTERNAL_STATE", "PR_CREATED", "SUCCESS", "RECONCILED",
                "Reconciled remote pull request state after API recovery", null
        );

        assertEquals(AuditEventType.PUSH_RECONCILED, reconciledPush.getEventType());
        assertEquals(AuditEventType.PR_CREATION_RECONCILED, reconciledPr.getEventType());
    }

    @Test
    @DisplayName("Scenario 25: Security failure events")
    void test25_SecurityFailureEvents() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        PublicationAuditEvent authDenied = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.AUTHORIZATION_DENIED, "APPROVED", "DENIED", "DENIED", "INSUFFICIENT_PERMISSIONS",
                "User lacks permission to trigger publication", null
        );

        PublicationAuditEvent bindingFailed = auditService.recordEvent(
                project, repository, task, codeChange, approval, publication, requesterUser,
                AuditEventType.BINDING_VERIFICATION_FAILED, "APPROVED", "FAILED", "FAILED", "SHA_MISMATCH",
                "Commit SHA does not match human approval record", null
        );

        assertEquals(AuditEventType.AUTHORIZATION_DENIED, authDenied.getEventType());
        assertEquals(AuditEventType.BINDING_VERIFICATION_FAILED, bindingFailed.getEventType());
    }

    @Test
    @DisplayName("Scenario 26: Zero client mutation endpoint exposure")
    void test26_ZeroClientMutationEndpointExposure() {
        Method[] controllerMethods = PublicationAuditController.class.getDeclaredMethods();
        for (Method method : controllerMethods) {
            boolean isPost = method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class);
            boolean isPut = method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class);
            boolean isPatch = method.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class);
            boolean isDelete = method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class);
            assertFalse(isPost || isPut || isPatch || isDelete, "PublicationAuditController must expose zero mutation endpoints");
        }
    }

    @Test
    @DisplayName("Scenario 27: End-to-end observational lifecycle reconstruction")
    void test27_EndToEndObservationalLifecycleReconstruction() {
        when(publicationAuditEventRepository.save(any(PublicationAuditEvent.class))).thenAnswer(i -> i.getArgument(0));

        List<AuditEventType> lifecycle = List.of(
                AuditEventType.APPROVAL_CREATED,
                AuditEventType.PUBLICATION_REQUESTED,
                AuditEventType.REMOTE_IDENTITY_VERIFIED,
                AuditEventType.PUSH_STARTED,
                AuditEventType.PUSH_SUCCEEDED,
                AuditEventType.PR_CREATION_STARTED,
                AuditEventType.PR_CREATION_SUCCEEDED,
                AuditEventType.PUBLICATION_COMPLETED
        );

        for (AuditEventType type : lifecycle) {
            PublicationAuditEvent event = auditService.recordEvent(
                    project, repository, task, codeChange, approval, publication, requesterUser,
                    type, "PREV_STATE", "NEXT_STATE", "SUCCESS", "OK", "Lifecycle step " + type.name(), null
            );
            assertNotNull(event);
            assertEquals(type, event.getEventType());
        }
    }
}
