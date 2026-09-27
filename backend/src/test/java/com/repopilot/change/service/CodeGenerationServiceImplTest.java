package com.repopilot.change.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.change.dto.CodeChangeResponseDto;
import com.repopilot.change.dto.CodeChangeSetResponseDto;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.security.service.SecretScannerService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodeGenerationServiceImplTest {

    @Mock
    private ProjectAuthorizationService projectAuthorizationService;
    @Mock
    private RepositoryRepository repositoryRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private CodeChangeRepository codeChangeRepository;
    @Mock
    private com.repopilot.repository.repository.CodeChunkRepository codeChunkRepository;
    @Mock
    private AiServiceClient aiServiceClient;

    private DiffGeneratorService diffGeneratorService;
    private SecretScannerService secretScannerService;
    private ObjectMapper objectMapper;

    private CodeGenerationServiceImpl codeGenerationService;

    private User testUser;
    private Project testProject;
    private Repository testRepo;
    private Task testTask;
    private Plan testPlan;

    @BeforeEach
    void setUp() {
        diffGeneratorService = new DiffGeneratorService();
        secretScannerService = new SecretScannerService();
        objectMapper = new ObjectMapper();

        codeGenerationService = new CodeGenerationServiceImpl(
                projectAuthorizationService,
                repositoryRepository,
                taskRepository,
                planRepository,
                codeChangeRepository,
                codeChunkRepository,
                aiServiceClient,
                diffGeneratorService,
                secretScannerService,
                objectMapper
        );

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("dev@repopilot.com");

        testProject = new Project();
        testProject.setId(UUID.randomUUID());
        testProject.setName("Test Project");

        testRepo = new Repository();
        testRepo.setId(UUID.randomUUID());
        testRepo.setProject(testProject);
        testRepo.setRepoName("test-repo");
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);

        testTask = new Task();
        testTask.setId(UUID.randomUUID());
        testTask.setProject(testProject);
        testTask.setRepository(testRepo);
        testTask.setTitle("Improve voting validation");
        testTask.setDescription("Validate vote payloads");
        testTask.setStatus(TaskStatus.WAITING_FOR_PLAN_APPROVAL);

        testPlan = new Plan();
        testPlan.setId(UUID.randomUUID());
        testPlan.setTask(testTask);
        testPlan.setObjective("Plan for voting validation");
        testPlan.setProposedChanges("[{\"file_path\":\"voting.html\",\"reason\":\"Add JS validation\"}]");
        testPlan.setEvidenceReferences("[]");

        lenient().when(taskRepository.verifyGenerationTokenAtomic(any(), any())).thenReturn(1);
    }

    @Test
    void testGenerateCodeSuccess() {
        when(projectAuthorizationService.getAuthorizedProject(testProject.getId(), testUser)).thenReturn(testProject);
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(testTask.getId(), testProject.getId(), testRepo.getId()))
                .thenReturn(Optional.of(testTask));
        when(planRepository.findById(testPlan.getId())).thenReturn(Optional.of(testPlan));

        Map<String, Object> aiResponse = new HashMap<>();
        aiResponse.put("status", "SUCCESS");
        aiResponse.put("message", "Generated 1 code change");
        aiResponse.put("changes", List.of(Map.of(
                "file_path", "voting.html",
                "change_type", "MODIFY",
                "reason", "Add JS validation",
                "original_content", "<html><body></body></html>",
                "proposed_content", "<html><body><script>validate();</script></body></html>",
                "affected_symbols", List.of("validate"),
                "confidence", "HIGH",
                "confidence_score", 0.95
        )));

        when(aiServiceClient.generateCode(any())).thenReturn(aiResponse);
        when(codeChangeRepository.save(any())).thenAnswer(invocation -> {
            CodeChange c = invocation.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CodeChangeSetResponseDto response = codeGenerationService.generateCode(
                testProject.getId(), testRepo.getId(), testTask.getId(), testPlan.getId(), testUser
        );

        assertNotNull(response);
        assertEquals("GENERATED", response.getStatus());
        assertEquals(1, response.getChanges().size());

        CodeChangeResponseDto changeDto = response.getChanges().get(0);
        assertEquals("voting.html", changeDto.getFilePath());
        assertEquals("MODIFY", changeDto.getChangeType());
        assertEquals("GENERATED", changeDto.getStatus());
        assertNotNull(changeDto.getOriginalHash());
        assertNotNull(changeDto.getProposedHash());
        assertNotNull(changeDto.getDiff());
        assertTrue(changeDto.getDiff().contains("--- a/voting.html"));
    }

    @Test
    void testGenerateCodeSecretDetected() {
        when(projectAuthorizationService.getAuthorizedProject(testProject.getId(), testUser)).thenReturn(testProject);
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(testTask.getId(), testProject.getId(), testRepo.getId()))
                .thenReturn(Optional.of(testTask));
        when(planRepository.findById(testPlan.getId())).thenReturn(Optional.of(testPlan));

        Map<String, Object> aiResponse = new HashMap<>();
        aiResponse.put("status", "SUCCESS");
        aiResponse.put("changes", List.of(Map.of(
                "file_path", "config.js",
                "change_type", "MODIFY",
                "reason", "Add secret key",
                "original_content", "const key = '';",
                "proposed_content", "const key = 'AKIA1234567890ABCDEF';", // AWS Key!
                "confidence", "HIGH"
        )));

        when(aiServiceClient.generateCode(any())).thenReturn(aiResponse);
        when(codeChangeRepository.save(any())).thenAnswer(invocation -> {
            CodeChange c = invocation.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CodeChangeSetResponseDto response = codeGenerationService.generateCode(
                testProject.getId(), testRepo.getId(), testTask.getId(), testPlan.getId(), testUser
        );

        assertNotNull(response);
        assertEquals(1, response.getChanges().size());

        CodeChangeResponseDto changeDto = response.getChanges().get(0);
        assertEquals("VALIDATION_FAILED", changeDto.getStatus());
        assertTrue(changeDto.getValidationFailureReason().contains("SECRET_DETECTED"));
    }

    @Test
    void testPathTraversalRejected() {
        when(projectAuthorizationService.getAuthorizedProject(testProject.getId(), testUser)).thenReturn(testProject);
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(testTask.getId(), testProject.getId(), testRepo.getId()))
                .thenReturn(Optional.of(testTask));

        Plan badPlan = new Plan();
        badPlan.setId(UUID.randomUUID());
        badPlan.setTask(testTask);
        badPlan.setProposedChanges("[{\"file_path\":\"../secret.txt\",\"reason\":\"Bad path\"}]");
        when(planRepository.findById(badPlan.getId())).thenReturn(Optional.of(badPlan));

        assertThrows(ApiException.class, () -> {
            codeGenerationService.generateCode(
                    testProject.getId(), testRepo.getId(), testTask.getId(), badPlan.getId(), testUser
            );
        });
    }
}
