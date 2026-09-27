package com.repopilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.RepairLoopIdempotencyRecordRepository;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.testing.service.WorkspaceManagerService;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class Phase10_4d3dFullStackE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskExecutionRepository taskExecutionRepository;

    @Autowired
    private RepairIterationRepository repairIterationRepository;

    @Autowired
    private RepairLoopIdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private WorkspaceManagerService workspaceManagerService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempStorageDir;

    private User memberUser;
    private User viewerUser;
    private Project testProject;
    private Repository testRepository;
    private Task testTask;
    private TaskExecution testExecution;
    private String memberToken;
    private String viewerToken;
    private Path masterRepoDir;
    private String masterDigestBefore;

    @BeforeEach
    void setUp() throws Exception {
        memberUser = new User();
        memberUser.setEmail("e2e_member_" + UUID.randomUUID() + "@repopilot.com");
        memberUser.setName("E2E Member User");
        memberUser.setPasswordHash("hashedpass");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        viewerUser = new User();
        viewerUser.setEmail("e2e_viewer_" + UUID.randomUUID() + "@repopilot.com");
        viewerUser.setName("E2E Viewer User");
        viewerUser.setPasswordHash("hashedpass");
        viewerUser.setRole(UserRole.USER);
        viewerUser = userRepository.save(viewerUser);

        testProject = new Project();
        testProject.setName("E2E Test Project " + UUID.randomUUID());
        testProject.setOwner(memberUser);
        testProject = projectRepository.save(testProject);

        ProjectMember pmMember = new ProjectMember();
        pmMember.setProject(testProject);
        pmMember.setUser(memberUser);
        pmMember.setRole(ProjectRole.MEMBER);
        projectMemberRepository.save(pmMember);

        ProjectMember pmViewer = new ProjectMember();
        pmViewer.setProject(testProject);
        pmViewer.setUser(viewerUser);
        pmViewer.setRole(ProjectRole.VIEWER);
        projectMemberRepository.save(pmViewer);

        testRepository = new Repository();
        testRepository.setRepoName("e2e-test-repo");
        testRepository.setOwnerName("repopilot");
        testRepository.setProject(testProject);
        testRepository = repositoryRepository.save(testRepository);

        masterRepoDir = tempStorageDir.resolve(testRepository.getId().toString());
        Files.createDirectories(masterRepoDir);
        Files.writeString(masterRepoDir.resolve("main.py"), "def test_app():\n    assert True\n");
        masterDigestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        testTask = new Task();
        testTask.setProject(testProject);
        testTask.setRepository(testRepository);
        testTask.setCreatedBy(memberUser);
        testTask.setTitle("E2E Task");
        testTask = taskRepository.save(testTask);

        testExecution = new TaskExecution();
        testExecution.setProject(testProject);
        testExecution.setRepository(testRepository);
        testExecution.setTask(testTask);
        testExecution.setEcosystem(Ecosystem.PYTHON);
        testExecution.setStatus(ExecutionStatus.RUNNING);
        testExecution.setStartedAt(Instant.now());
        testExecution = taskExecutionRepository.save(testExecution);

        // Populate Baseline Canonical TestRun for D3-C
        TestRun baselineRun = new TestRun();
        baselineRun.setTask(testTask);
        baselineRun.setTaskExecution(testExecution);
        baselineRun.setExecutionNumber(1);
        baselineRun.setEcosystem(Ecosystem.PYTHON);
        baselineRun.setStatus("PASSED");
        baselineRun.setExitCode(0);
        baselineRun.setStdoutSummary("OK (tests=1)");
        baselineRun.setStderrSummary("");
        baselineRun.setTestsRun(1);
        baselineRun.setFailuresCount(0);
        baselineRun.setErrorsCount(0);
        testRunRepository.save(baselineRun);

        memberToken = jwtTokenProvider.generateAccessToken(memberUser);
        viewerToken = jwtTokenProvider.generateAccessToken(viewerUser);
    }

    @Test
    @DisplayName("E2E 1: Full Unmocked Trigger Repair Loop -> Clean Pass Baseline")
    void testE2E_TriggerRepairLoop_Success() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());
        String idempotencyKey = "e2e-idempotency-key-" + UUID.randomUUID();

        MvcResult result = mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + memberToken)
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(testExecution.getId().toString()))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.maxIterations").value(3))
                .andReturn();

        assertNotNull(result.getResponse().getContentAsString());

        // Verify Real DB Idempotency Record Persisted
        var persistedIdempotency = idempotencyRecordRepository
                .findByUserIdAndProjectIdAndExecutionIdAndOperationAndIdempotencyKey(
                        memberUser.getId(), testProject.getId(), testExecution.getId(), "REPAIR_ORCHESTRATION", idempotencyKey);
        assertTrue(persistedIdempotency.isPresent(), "Durable PostgreSQL Idempotency record must exist");
        assertEquals(200, persistedIdempotency.get().getResponseStatus());

        // Verify Master Repository Unmutated (SHA-256 Before/After Equal)
        String masterDigestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        assertEquals(masterDigestBefore, masterDigestAfter, "Master repository storage must remain completely unmutated!");
    }

    @Test
    @DisplayName("E2E 2: Full Unmocked GET Repair Loop Status Projection")
    void testE2E_GetRepairLoopStatus_Sanitized() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());

        MvcResult result = mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(testExecution.getId().toString()))
                .andExpect(jsonPath("$.executionStatus").exists())
                .andExpect(jsonPath("$.repairStatus").exists())
                .andExpect(jsonPath("$.iterations").isArray())
                .andReturn();

        String jsonResponse = result.getResponse().getContentAsString();
        assertFalse(jsonResponse.contains("password"), "Secrets must not be present in status projection");
        assertFalse(jsonResponse.contains("/var/folders"), "Internal filesystem paths must not be present in status projection");
        assertFalse(jsonResponse.contains("docker_container"), "Container IDs must not be present in status projection");
    }

    @Test
    @DisplayName("E2E 3: Full Unmocked Cancel Repair Loop -> Authoritative DB State")
    void testE2E_CancelRepairLoop_Success() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair/cancel",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());

        mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(testExecution.getId().toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Verify Authoritative DB Execution Status Updated
        TaskExecution updatedExecution = taskExecutionRepository.findById(testExecution.getId()).orElseThrow();
        assertEquals(ExecutionStatus.CANCELLED, updatedExecution.getStatus());
        assertTrue(updatedExecution.getCancellationRequested());
    }

    @Test
    @DisplayName("E2E 4: Authorization Safety Matrix (401, 403, 404)")
    void testE2E_AuthorizationMatrix() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());

        // Unauthenticated -> 401
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        // Viewer POST -> 403
        mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // Viewer CANCEL -> 403
        mockMvc.perform(post(url + "/cancel")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // Non-existent execution -> 404
        String notFoundUrl = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                testProject.getId(), testRepository.getId(), testTask.getId(), UUID.randomUUID());
        mockMvc.perform(post(notFoundUrl)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
