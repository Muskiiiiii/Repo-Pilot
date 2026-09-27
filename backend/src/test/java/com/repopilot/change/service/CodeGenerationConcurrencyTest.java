package com.repopilot.change.service;

import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.change.dto.CodeChangeResponseDto;
import com.repopilot.change.dto.CodeChangeSetResponseDto;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class CodeGenerationConcurrencyTest {

    @Autowired
    private CodeGenerationService codeGenerationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    @MockBean
    private AiServiceClient aiServiceClient;

    private User testUser;
    private Project testProject;
    private Repository testRepo;
    private Task testTask1;
    private Plan testPlan1;
    private Task testTask2;
    private Plan testPlan2;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setEmail("concurrent_test_" + UUID.randomUUID() + "@repopilot.ai");
        testUser.setPasswordHash("hash123");
        testUser.setName("Concurrency Test User");
        testUser.setRole(UserRole.USER);
        testUser = userRepository.save(testUser);

        testProject = new Project();
        testProject.setName("Concurrency Test Project");
        testProject.setOwner(testUser);
        testProject.setStatus(ProjectStatus.ACTIVE);
        testProject = projectRepository.save(testProject);

        testRepo = new Repository();
        testRepo.setProject(testProject);
        testRepo.setOwnerName("repopilot");
        testRepo.setRepoName("concurrency-repo-" + UUID.randomUUID().toString().substring(0, 8));
        testRepo.setDefaultBranch("main");
        testRepo.setVisibility(RepositoryVisibility.PUBLIC);
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);
        testRepo = repositoryRepository.save(testRepo);

        testTask1 = new Task();
        testTask1.setProject(testProject);
        testTask1.setRepository(testRepo);
        testTask1.setCreatedBy(testUser);
        testTask1.setTitle("Task 1 for concurrency test");
        testTask1.setStatus(TaskStatus.WAITING_FOR_PLAN_APPROVAL);
        testTask1 = taskRepository.save(testTask1);

        testPlan1 = new Plan();
        testPlan1.setTask(testTask1);
        testPlan1.setObjective("Plan 1 objective");
        testPlan1.setProposedChanges("[{\"file_path\":\"src/Task1.java\",\"reason\":\"Task 1 change\"}]");
        testPlan1.setEvidenceReferences("[]");
        testPlan1 = planRepository.save(testPlan1);

        testTask2 = new Task();
        testTask2.setProject(testProject);
        testTask2.setRepository(testRepo);
        testTask2.setCreatedBy(testUser);
        testTask2.setTitle("Task 2 for concurrency test");
        testTask2.setStatus(TaskStatus.WAITING_FOR_PLAN_APPROVAL);
        testTask2 = taskRepository.save(testTask2);

        testPlan2 = new Plan();
        testPlan2.setTask(testTask2);
        testPlan2.setObjective("Plan 2 objective");
        testPlan2.setProposedChanges("[{\"file_path\":\"src/Task2.java\",\"reason\":\"Task 2 change\"}]");
        testPlan2.setEvidenceReferences("[]");
        testPlan2 = planRepository.save(testPlan2);
    }

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            if (testTask1 != null) codeChangeRepository.deleteByTaskId(testTask1.getId());
            if (testTask2 != null) codeChangeRepository.deleteByTaskId(testTask2.getId());
            if (testPlan1 != null) planRepository.delete(testPlan1);
            if (testPlan2 != null) planRepository.delete(testPlan2);
            if (testTask1 != null) taskRepository.delete(testTask1);
            if (testTask2 != null) taskRepository.delete(testTask2);
            if (testRepo != null) repositoryRepository.delete(testRepo);
            if (testProject != null) projectRepository.delete(testProject);
            if (testUser != null) userRepository.delete(testUser);
            return null;
        });
    }

    @Test
    void testConcurrentGenerationsSameTask_SerializesAndPreventsOrphans() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        when(aiServiceClient.generateCode(any())).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            Thread.sleep(300);
            Map<String, Object> res = new HashMap<>();
            res.put("status", "SUCCESS");
            res.put("message", "Generated code call " + count);
            res.put("changes", List.of(Map.of(
                    "file_path", "src/Task1.java",
                    "change_type", "MODIFY",
                    "reason", "Change from invocation " + count,
                    "original_content", "// orig " + count,
                    "proposed_content", "// prop " + count,
                    "confidence", "HIGH"
            )));
            return res;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Object> taskCall1 = () -> {
            startLatch.await();
            return codeGenerationService.generateCode(testProject.getId(), testRepo.getId(), testTask1.getId(), testPlan1.getId(), testUser);
        };

        Callable<Object> taskCall2 = () -> {
            startLatch.await();
            Thread.sleep(20);
            return codeGenerationService.generateCode(testProject.getId(), testRepo.getId(), testTask1.getId(), testPlan1.getId(), testUser);
        };

        Future<Object> future1 = executor.submit(taskCall1);
        Future<Object> future2 = executor.submit(taskCall2);

        startLatch.countDown();

        Object res1 = null;
        Object res2 = null;
        try {
            res1 = future1.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            res1 = e.getCause();
        }

        try {
            res2 = future2.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            res2 = e.getCause();
        }

        executor.shutdown();

        boolean oneSucceeded = (res1 instanceof CodeChangeSetResponseDto) || (res2 instanceof CodeChangeSetResponseDto);
        assertTrue(oneSucceeded, "At least one concurrent generation must succeed");

        List<CodeChangeSetResponseDto> successfulResponses = new ArrayList<>();
        if (res1 instanceof CodeChangeSetResponseDto dto) successfulResponses.add(dto);
        if (res2 instanceof CodeChangeSetResponseDto dto) successfulResponses.add(dto);

        List<CodeChange> persistedChanges = codeChangeRepository.findByTaskIdOrderByCreatedAtDesc(testTask1.getId());
        assertFalse(persistedChanges.isEmpty(), "Persisted code changes must exist in database");

        for (CodeChangeSetResponseDto dto : successfulResponses) {
            for (CodeChangeResponseDto changeDto : dto.getChanges()) {
                Optional<CodeChange> inDb = codeChangeRepository.findById(changeDto.getId());
                assertTrue(inDb.isPresent(), "Returned CodeChange UUID " + changeDto.getId() + " MUST exist in PostgreSQL!");
            }
        }
    }

    @Test
    void testConcurrentGenerationsDistinctTasks_ExecutesIndependently() throws Exception {
        when(aiServiceClient.generateCode(any())).thenAnswer(invocation -> {
            Thread.sleep(200);
            Map<String, Object> res = new HashMap<>();
            res.put("status", "SUCCESS");
            res.put("message", "Generated code");
            res.put("changes", List.of(Map.of(
                    "file_path", "src/File.java",
                    "change_type", "MODIFY",
                    "reason", "Distinct change",
                    "original_content", "// orig",
                    "proposed_content", "// prop",
                    "confidence", "HIGH"
            )));
            return res;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<CodeChangeSetResponseDto> call1 = () -> {
            startLatch.await();
            return codeGenerationService.generateCode(testProject.getId(), testRepo.getId(), testTask1.getId(), testPlan1.getId(), testUser);
        };

        Callable<CodeChangeSetResponseDto> call2 = () -> {
            startLatch.await();
            return codeGenerationService.generateCode(testProject.getId(), testRepo.getId(), testTask2.getId(), testPlan2.getId(), testUser);
        };

        Future<CodeChangeSetResponseDto> f1 = executor.submit(call1);
        Future<CodeChangeSetResponseDto> f2 = executor.submit(call2);

        startLatch.countDown();

        CodeChangeSetResponseDto dto1 = f1.get(10, TimeUnit.SECONDS);
        CodeChangeSetResponseDto dto2 = f2.get(10, TimeUnit.SECONDS);

        executor.shutdown();

        assertNotNull(dto1);
        assertNotNull(dto2);
        assertEquals(testTask1.getId(), dto1.getTaskId());
        assertEquals(testTask2.getId(), dto2.getTaskId());

        assertEquals(1, codeChangeRepository.findByTaskIdOrderByCreatedAtDesc(testTask1.getId()).size());
        assertEquals(1, codeChangeRepository.findByTaskIdOrderByCreatedAtDesc(testTask2.getId()).size());
    }
}
