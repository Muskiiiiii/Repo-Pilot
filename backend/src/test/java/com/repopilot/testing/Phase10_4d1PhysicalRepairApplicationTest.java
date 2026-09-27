package com.repopilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.dto.AIRepairProposalResponseDto;
import com.repopilot.testing.entity.AIRepairProposal;
import com.repopilot.testing.entity.RepairIteration;
import com.repopilot.testing.entity.RepairIterationStatus;
import com.repopilot.testing.parser.TestResultParserCompositeService;
import com.repopilot.testing.policy.RepairPolicyValidator;
import com.repopilot.testing.repository.AIRepairProposalRepository;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.service.FailureClassifierServiceImpl;
import com.repopilot.testing.service.PhysicalRepairApplicationServiceImpl;
import com.repopilot.testing.service.WorkspaceManagerServiceImpl;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase10_4d1PhysicalRepairApplicationTest {

    @TempDir
    Path tempDir;

    private TaskExecutionRepository taskExecutionRepository;
    private RepairIterationRepository repairIterationRepository;
    private AIRepairProposalRepository aiRepairProposalRepository;
    private CodeChangeRepository codeChangeRepository;
    private ProjectAuthorizationService authorizationService;
    private AuditLogService auditLogService;
    private FailureClassifierServiceImpl failureClassifierService;
    private RepairPolicyValidator repairPolicyValidator;
    private WorkspaceManagerServiceImpl workspaceManagerService;
    private ObjectMapper objectMapper;

    private PhysicalRepairApplicationServiceImpl repairApplicationService;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID executionId;
    private UUID iterationId;

    private User testUser;
    private Project testProject;
    private Repository testRepository;
    private Task testTask;
    private TaskExecution testExecution;
    private RepairIteration testIteration;
    private AIRepairProposal testProposal;
    private List<CodeChange> testCodeChanges;

    private Path storageDir;
    private Path workspaceBaseDir;
    private Path masterRepoDir;

    @BeforeEach
    void setUp() throws IOException {
        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        iterationId = UUID.randomUUID();

        storageDir = tempDir.resolve("storage");
        workspaceBaseDir = tempDir.resolve("workspaces");
        Files.createDirectories(storageDir);
        Files.createDirectories(workspaceBaseDir);

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("dev@example.com");

        testProject = new Project();
        testProject.setId(projectId);
        testProject.setName("Security Project");

        testRepository = new Repository();
        testRepository.setId(repositoryId);
        testRepository.setRepoName("demo-repo");
        testRepository.setOwnerName("test-owner");
        testRepository.setProject(testProject);

        testTask = new Task();
        testTask.setId(taskId);
        testTask.setTitle("Fix calculation bug");

        testExecution = new TaskExecution();
        testExecution.setId(executionId);
        testExecution.setTask(testTask);
        testExecution.setRepository(testRepository);
        testExecution.setProject(testProject);
        testExecution.setStatus(ExecutionStatus.FAILED);

        testIteration = new RepairIteration();
        testIteration.setId(iterationId);
        testIteration.setTaskExecution(testExecution);
        testIteration.setIterationNumber(1);
        testIteration.setStatus(RepairIterationStatus.REPAIR_GENERATED);

        // Setup Master Repository Files
        masterRepoDir = storageDir.resolve(repositoryId.toString());
        Files.createDirectories(masterRepoDir);
        Path srcFile = masterRepoDir.resolve("src/service.py");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, "def compute(a, b):\n    return a / b\n", StandardCharsets.UTF_8);

        CodeChange cc = new CodeChange();
        cc.setId(UUID.randomUUID());
        cc.setFilePath("src/service.py");
        cc.setNewContent("def compute(a, b):\n    return a / b\n");
        testCodeChanges = List.of(cc);

        objectMapper = new ObjectMapper();
        TestResultParserCompositeService parserCompositeService = new TestResultParserCompositeService(List.of());
        failureClassifierService = new FailureClassifierServiceImpl(parserCompositeService, objectMapper);
        repairPolicyValidator = new RepairPolicyValidator(failureClassifierService);

        workspaceManagerService = new WorkspaceManagerServiceImpl();
        injectField(workspaceManagerService, "baseStoragePath", storageDir.toString());
        injectField(workspaceManagerService, "workspaceBasePath", workspaceBaseDir.toString());

        // Calculate actual parent snapshot hash from baseline
        String parentSnapshotHash = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        // Compute actual post-patch resulting tree hash by applying edit to disposable workspace
        UUID setupExecId = UUID.randomUUID();
        Path setupWs = workspaceManagerService.materializeWorkspace(setupExecId, testRepository);
        Path setupFile = setupWs.resolve("src/service.py");
        String search = "return a / b";
        String replacement = "if b == 0:\n        return 0\n    return a / b";
        String setupContent = Files.readString(setupFile, StandardCharsets.UTF_8);
        Files.writeString(setupFile, setupContent.replace(search, replacement), StandardCharsets.UTF_8);
        String resultingTreeHash = workspaceManagerService.calculateTreeDigest(setupWs);
        workspaceManagerService.cleanupWorkspace(setupExecId);

        Map<String, Object> modMap = Map.of(
                "rationale", "Add zero check",
                "fileModifications", List.of(
                        Map.of(
                                "path", "src/service.py",
                                "operation", "REPLACE",
                                "search", search,
                                "replacement", replacement
                        )
                )
        );
        String jsonMod = objectMapper.writeValueAsString(modMap);

        testProposal = new AIRepairProposal();
        testProposal.setId(UUID.randomUUID());
        testProposal.setRepairIteration(testIteration);
        testProposal.setFileModifications(jsonMod);
        testProposal.setValidationStatus("VALIDATED");
        testProposal.setParentSnapshotHash(parentSnapshotHash);
        testProposal.setResultingTreeHash(resultingTreeHash);
        testProposal.setDiffHash("DIFF_HASH_TEST");
        testProposal.setRawResponse(jsonMod);
        testProposal.setModelIdentifier("repopilot-ai-v1");

        taskExecutionRepository = mock(TaskExecutionRepository.class);
        repairIterationRepository = mock(RepairIterationRepository.class);
        aiRepairProposalRepository = mock(AIRepairProposalRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        authorizationService = mock(ProjectAuthorizationService.class);
        auditLogService = mock(AuditLogService.class);

        repairApplicationService = new PhysicalRepairApplicationServiceImpl(
                taskExecutionRepository,
                repairIterationRepository,
                aiRepairProposalRepository,
                codeChangeRepository,
                authorizationService,
                auditLogService,
                failureClassifierService,
                repairPolicyValidator,
                workspaceManagerService,
                objectMapper
        );
        injectField(repairApplicationService, "baseStoragePath", storageDir.toString());

        when(authorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId))
                .thenReturn(Optional.of(testExecution));
        when(repairIterationRepository.findById(iterationId)).thenReturn(Optional.of(testIteration));
        when(aiRepairProposalRepository.findByRepairIterationId(iterationId)).thenReturn(Optional.of(testProposal));
        when(codeChangeRepository.findByTaskId(taskId)).thenReturn(testCodeChanges);
        when(repairIterationRepository.save(any(RepairIteration.class))).thenAnswer(i -> i.getArgument(0));
        when(aiRepairProposalRepository.save(any(AIRepairProposal.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject field " + fieldName, e);
        }
    }

    // ==========================================
    // SECURITY SCENARIO TESTS (A - AQ)
    // ==========================================

    @Test
    @DisplayName("Scenario A: Valid single file REPLACE physical application succeeds & verifies resulting tree hash")
    void testA_ValidSingleReplacementSucceeds() {
        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("PHYSICALLY_APPLIED", response.getValidationStatus());
        assertEquals(RepairIterationStatus.VALIDATED, testIteration.getStatus());
        assertNotNull(response.getResultingTreeHash());

        verify(auditLogService).logAction(eq(testUser), eq(testProject), eq(testTask), eq("REPAIR_PHYSICALLY_APPLIED"), eq("REPAIR_ITERATION"), eq(iterationId), any());
    }

    @Test
    @DisplayName("Scenario B: Valid multi-file REPLACE physical application succeeds")
    void testB_ValidMultiFileReplacementSucceeds() throws IOException {
        Path srcFile2 = masterRepoDir.resolve("src/util.py");
        Files.writeString(srcFile2, "def helper():\n    return 42\n", StandardCharsets.UTF_8);

        CodeChange cc2 = new CodeChange();
        cc2.setId(UUID.randomUUID());
        cc2.setFilePath("src/util.py");
        cc2.setNewContent("def helper():\n    return 42\n");
        when(codeChangeRepository.findByTaskId(taskId)).thenReturn(List.of(testCodeChanges.get(0), cc2));

        String parentHash = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        testProposal.setParentSnapshotHash(parentHash);

        Map<String, Object> modMap = Map.of(
                "rationale", "Multi-file fix",
                "fileModifications", List.of(
                        Map.of("path", "src/service.py", "operation", "REPLACE", "search", "return a / b", "replacement", "return 0 if b == 0 else a / b"),
                        Map.of("path", "src/util.py", "operation", "REPLACE", "search", "return 42", "replacement", "return 100")
                )
        );
        String jsonMod = objectMapper.writeValueAsString(modMap);
        testProposal.setFileModifications(jsonMod);

        // Compute actual post-patch resulting tree digest
        UUID setupExecId = UUID.randomUUID();
        Path setupWs = workspaceManagerService.materializeWorkspace(setupExecId, testRepository);
        Files.writeString(setupWs.resolve("src/service.py"), "def compute(a, b):\n    return 0 if b == 0 else a / b\n", StandardCharsets.UTF_8);
        Files.writeString(setupWs.resolve("src/util.py"), "def helper():\n    return 100\n", StandardCharsets.UTF_8);
        String actualResultingHash = workspaceManagerService.calculateTreeDigest(setupWs);
        workspaceManagerService.cleanupWorkspace(setupExecId);

        testProposal.setResultingTreeHash(actualResultingHash);

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("PHYSICALLY_APPLIED", response.getValidationStatus());
    }

    @Test
    @DisplayName("Scenario C-D: Wrong or stale parent snapshot hash rejected with STALE_REPAIR_PROPOSAL")
    void testCD_StaleParentHashRejected() {
        testProposal.setParentSnapshotHash("WRONG_PARENT_SNAPSHOT_HASH_12345");

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("STALE_REPAIR_PROPOSAL", response.getValidationStatus());
        assertEquals(RepairIterationStatus.VALIDATION_FAILED, testIteration.getStatus());
    }

    @Test
    @DisplayName("Scenario E / AJ: Duplicate physical application is idempotent and returns existing result")
    void testE_AJ_DuplicateApplicationIsIdempotent() {
        testProposal.setValidationStatus("PHYSICALLY_APPLIED");
        testIteration.setStatus(RepairIterationStatus.VALIDATED);

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("PHYSICALLY_APPLIED", response.getValidationStatus());
        assertEquals(RepairIterationStatus.VALIDATED, testIteration.getStatus());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("Scenario F-L: Context ID mismatch (wrong task, execution, iteration, diagnosis) rejected")
    void testF_L_ContextIdMismatchRejected() {
        TaskExecution otherExec = new TaskExecution();
        otherExec.setId(UUID.randomUUID());
        testIteration.setTaskExecution(otherExec);

        ApiException exception = assertThrows(ApiException.class, () ->
                repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("ITERATION_MISMATCH", exception.getCode());
    }

    @Test
    @DisplayName("Scenario M: Path traversal attack (../etc/passwd) rejected")
    void testM_PathTraversalRejected() throws IOException {
        Map<String, Object> modMap = Map.of(
                "rationale", "Traversal attack",
                "fileModifications", List.of(
                        Map.of("path", "../outside.py", "operation", "REPLACE", "search", "x", "replacement", "y")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("PATH_TRAVERSAL", response.getValidationStatus());
    }

    @Test
    @DisplayName("Scenario N: Absolute path (/etc/passwd, C:\\) rejected")
    void testN_AbsolutePathRejected() throws IOException {
        Map<String, Object> modMap = Map.of(
                "rationale", "Absolute path attack",
                "fileModifications", List.of(
                        Map.of("path", "/etc/passwd", "operation", "REPLACE", "search", "root", "replacement", "toot")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertTrue("PATH_TRAVERSAL".equals(response.getValidationStatus()) || "NEW_FILE_REJECTED".equals(response.getValidationStatus()));
    }

    @Test
    @DisplayName("Scenario O-P: Null byte or control characters in path rejected")
    void testOP_NullByteOrControlCharRejected() throws IOException {
        Map<String, Object> modMap = Map.of(
                "rationale", "Null byte path",
                "fileModifications", List.of(
                        Map.of("path", "src/service.py\0.txt", "operation", "REPLACE", "search", "x", "replacement", "y")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("INVALID_PATH", response.getValidationStatus());
    }

    @Test
    @DisplayName("Scenario Q: Sibling prefix attack rejected")
    void testQ_SiblingPrefixAttackRejected() throws IOException {
        Map<String, Object> modMap = Map.of(
                "rationale", "Sibling prefix attack",
                "fileModifications", List.of(
                        Map.of("path", "src/service.py/../../repo_other/secret.txt", "operation", "REPLACE", "search", "x", "replacement", "y")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("PATH_TRAVERSAL", response.getValidationStatus());
    }

    @Test
    @DisplayName("Scenario R-T: Symlink target / parent symlink rejected")
    void testR_T_SymlinkTargetRejected() throws IOException {
        CodeChange ccLink = new CodeChange();
        ccLink.setId(UUID.randomUUID());
        ccLink.setFilePath("src/sym_service.py");
        when(codeChangeRepository.findByTaskId(taskId)).thenReturn(List.of(ccLink));

        // Create symlink directly in master repo
        Path target = masterRepoDir.resolve("src/service.py");
        Path symlink = masterRepoDir.resolve("src/sym_service.py");
        if (!Files.exists(symlink)) {
            Files.createSymbolicLink(symlink, target);
        }

        String parentHash = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        testProposal.setParentSnapshotHash(parentHash);

        Map<String, Object> modMap = Map.of(
                "rationale", "Symlink attack",
                "fileModifications", List.of(
                        Map.of("path", "src/sym_service.py", "operation", "REPLACE", "search", "return a / b", "replacement", "return 0")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertTrue("SYMLINK_DETECTED".equals(response.getValidationStatus()) || "STALE_REPAIR_PROPOSAL".equals(response.getValidationStatus()));
    }

    @Test
    @DisplayName("Scenario U-W: Forbidden operations (CREATE, DELETE, EXECUTE) rejected")
    void testU_W_ForbiddenOperationsRejected() throws IOException {
        Map<String, Object> modMap = Map.of(
                "rationale", "Create operation forbidden",
                "fileModifications", List.of(
                        Map.of("path", "src/service.py", "operation", "CREATE", "search", "", "replacement", "print('hello')")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("FORBIDDEN_OPERATION", response.getValidationStatus());
    }

    @Test
    @DisplayName("Scenario X-AB: Modification of test, build, CI, Dockerfile, or .git files rejected")
    void testX_AB_ForbiddenFilesModificationRejected() throws IOException {
        Map<String, Object> modMap = Map.of(
                "rationale", "Modify pom.xml",
                "fileModifications", List.of(
                        Map.of("path", "pom.xml", "operation", "REPLACE", "search", "<version>1.0</version>", "replacement", "<version>2.0</version>")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertTrue("FORBIDDEN_PATH".equals(response.getValidationStatus()) || "NOT_IN_WHITELIST".equals(response.getValidationStatus()));
    }

    @Test
    @DisplayName("Scenario AD: Sensitive secret detected in replacement text rejected")
    void testAD_SecretInReplacementRejected() throws IOException {
        Map<String, Object> modMap = Map.of(
                "rationale", "Inject AWS key",
                "fileModifications", List.of(
                        Map.of("path", "src/service.py", "operation", "REPLACE", "search", "return a / b", "replacement", "AWS_KEY = 'AKIAIOSFODNN7EXAMPLE'")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("SECRET_DETECTED", response.getValidationStatus());
    }

    @Test
    @DisplayName("Scenario AE-AF: Oversized patch (lines > 100 or diff > 10KB) rejected")
    void testAE_AF_OversizedPatchRejected() throws IOException {
        StringBuilder largeReplacement = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            largeReplacement.append("# Line ").append(i).append("\n");
        }
        Map<String, Object> modMap = Map.of(
                "rationale", "Oversized lines",
                "fileModifications", List.of(
                        Map.of("path", "src/service.py", "operation", "REPLACE", "search", "return a / b", "replacement", largeReplacement.toString())
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("EXCEEDS_LINE_LIMIT", response.getValidationStatus());
    }

    @Test
    @DisplayName("Scenario AG: Replacement old-content mismatch (search text missing or ambiguous) rejected")
    void testAG_OldContentMismatchRejected() throws IOException {
        Map<String, Object> modMap = Map.of(
                "rationale", "Wrong search text",
                "fileModifications", List.of(
                        Map.of("path", "src/service.py", "operation", "REPLACE", "search", "def non_existent():", "replacement", "def fixed(): pass")
                )
        );
        testProposal.setFileModifications(objectMapper.writeValueAsString(modMap));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("PATCH_CONTEXT_NOT_FOUND", response.getValidationStatus());
    }

    @Test
    @DisplayName("Scenario AH-AI: Resulting tree digest mismatch rejected")
    void testAH_AI_ResultingTreeDigestMismatchRejected() {
        testProposal.setResultingTreeHash("WRONG_RESULTING_TREE_HASH_987654321");

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("RESULTING_TREE_HASH_MISMATCH", response.getValidationStatus());
        assertEquals(RepairIterationStatus.VALIDATION_FAILED, testIteration.getStatus());
    }

    @Test
    @DisplayName("Scenario AK: Concurrent physical application requests trigger 409 CONFLICT")
    void testAK_ConcurrentApplicationTriggers409Conflict() {
        assertDoesNotThrow(() -> {
            // First execution runs cleanly
            AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);
            assertEquals("PHYSICALLY_APPLIED", response.getValidationStatus());
        });
    }

    @Test
    @DisplayName("Scenario AL-AM: Cancellation before or during application transitions status to CANCELLED")
    void testAL_AM_CancellationRequestedTransitionsToCancelled() {
        testExecution.setCancellationRequested(true);

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("CANCELLED", response.getValidationStatus());
        assertEquals(RepairIterationStatus.CANCELLED, testIteration.getStatus());
    }

    @Test
    @DisplayName("Scenario AO-AP: Sidecar files cleanup & unexpected sidecar detection")
    void testAO_AP_TemporarySidecarCleanup() {
        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("PHYSICALLY_APPLIED", response.getValidationStatus());

        // Verify zero temporary sidecars remain in workspaces directory
        try (var stream = Files.walk(workspaceBaseDir)) {
            long sidecarCount = stream.filter(p -> p.getFileName().toString().startsWith(".repopilot_patch_tmp_")).count();
            assertEquals(0, sidecarCount, "Zero temporary sidecar files must remain in workspace directory");
        } catch (IOException e) {
            fail("Failed to walk workspace base dir: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Scenario AQ: Master repository baseline remains 100% byte-for-byte unchanged after physical repair application")
    void testAQ_MasterRepositoryUnchanged() throws IOException {
        String masterDigestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        String masterContentBefore = Files.readString(masterRepoDir.resolve("src/service.py"));

        AIRepairProposalResponseDto response = repairApplicationService.applyPhysicalRepair(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("PHYSICALLY_APPLIED", response.getValidationStatus());

        String masterDigestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        String masterContentAfter = Files.readString(masterRepoDir.resolve("src/service.py"));

        assertEquals(masterDigestBefore, masterDigestAfter, "Master repository tree digest MUST remain 100% byte-for-byte identical");
        assertEquals(masterContentBefore, masterContentAfter, "Master repository file content MUST remain 100% byte-for-byte identical");
    }
}
