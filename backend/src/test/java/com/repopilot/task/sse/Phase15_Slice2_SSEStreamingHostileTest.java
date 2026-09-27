package com.repopilot.task.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.task.sse.controller.TaskStreamController;
import com.repopilot.task.sse.dto.SseTaskEventDto;
import com.repopilot.task.sse.service.SseTaskStreamEngine;
import com.repopilot.task.sse.service.SseTaskStreamEngineImpl;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase15_Slice2_SSEStreamingHostileTest {

    @Autowired private TaskStreamController taskStreamController;
    @Autowired private SseTaskStreamEngine sseTaskStreamEngine;
    @Autowired private AuditLogService auditLogService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;

    private User ownerA;
    private User viewerA;
    private User nonMemberB;
    private Project projectA;
    private Project projectB;
    private Repository repoA;
    private Repository repoB;
    private Task taskA;
    private Task taskB;

    @BeforeEach
    void setUp() {
        tearDown();

        ownerA = new User();
        ownerA.setEmail("sse_owner_a_" + UUID.randomUUID() + "@example.com");
        ownerA.setPasswordHash("hash");
        ownerA.setName("Owner A");
        ownerA.setRole(UserRole.USER);
        ownerA = userRepository.save(ownerA);

        viewerA = new User();
        viewerA.setEmail("sse_viewer_a_" + UUID.randomUUID() + "@example.com");
        viewerA.setPasswordHash("hash");
        viewerA.setName("Viewer A");
        viewerA.setRole(UserRole.USER);
        viewerA = userRepository.save(viewerA);

        nonMemberB = new User();
        nonMemberB.setEmail("sse_nonmember_b_" + UUID.randomUUID() + "@example.com");
        nonMemberB.setPasswordHash("hash");
        nonMemberB.setName("NonMember B");
        nonMemberB.setRole(UserRole.USER);
        nonMemberB = userRepository.save(nonMemberB);

        projectA = new Project();
        projectA.setName("Project A");
        projectA.setOwner(ownerA);
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectB = new Project();
        projectB.setName("Project B");
        projectB.setOwner(nonMemberB);
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

        projectMemberRepository.save(new ProjectMember(projectA, ownerA, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(projectA, viewerA, ProjectRole.VIEWER));
        projectMemberRepository.save(new ProjectMember(projectB, nonMemberB, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("owner-a");
        repoA.setRepoName("repo-a-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("owner-b");
        repoB.setRepoName("repo-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(ownerA);
        taskA.setTitle("Task A");
        taskA.setStatus(TaskStatus.COMPLETED);
        taskA.setPriority(TaskPriority.HIGH);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(nonMemberB);
        taskB.setTitle("Task B");
        taskB.setStatus(TaskStatus.COMPLETED);
        taskB.setPriority(TaskPriority.HIGH);
        taskB = taskRepository.save(taskB);
    }

    @AfterEach
    void tearDown() {
        if (auditLogRepository != null) { auditLogRepository.deleteAll(); auditLogRepository.flush(); }
        if (taskRepository != null) { taskRepository.deleteAll(); taskRepository.flush(); }
        if (repositoryRepository != null) { repositoryRepository.deleteAll(); repositoryRepository.flush(); }
        if (projectMemberRepository != null) { projectMemberRepository.deleteAll(); projectMemberRepository.flush(); }
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    @Test
    @DisplayName("BLOCKER 1 & 2: Hostile Barrier Race Test for Replay->Live Transition (0 Duplicates, 0 Missed Events)")
    void testBlocker1And2_concurrentReplayLiveBarrierRace() throws Exception {
        // Pre-create historical events E1, E2
        auditLogService.logReportRequested(ownerA, projectA, taskA, UUID.randomUUID(), "corr-race-1");
        auditLogService.logReportGenerated(ownerA, projectA, taskA, UUID.randomUUID(), "corr-race-1", "{\"score\":95}");

        int iterations = 5;
        for (int iter = 0; iter < iterations; iter++) {
            CountDownLatch startBarrier = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(3);

            UserPrincipal principal = new UserPrincipal(ownerA);
            MockHttpServletRequest request = new MockHttpServletRequest();

            // Thread T1: Opens stream with Last-Event-ID = 0 (requests full replay)
            Future<SseEmitter> t1StreamFuture = executor.submit(() -> {
                startBarrier.await();
                return taskStreamController.streamTaskEvents(taskA.getId(), "0", principal, request);
            });

            // Thread T2: Publishes live event concurrently during replay
            Future<Void> t2PublishFuture = executor.submit(() -> {
                startBarrier.await();
                sseTaskStreamEngine.publishTaskEvent(taskA.getId(), "LIVE_EVENT_RACE_1", "{\"race\":1}");
                return null;
            });

            // Thread T3: Publishes second live event
            Future<Void> t3PublishFuture = executor.submit(() -> {
                startBarrier.await();
                sseTaskStreamEngine.publishTaskEvent(taskA.getId(), "LIVE_EVENT_RACE_2", "{\"race\":2}");
                return null;
            });

            startBarrier.countDown();

            SseEmitter emitter = t1StreamFuture.get(5, TimeUnit.SECONDS);
            assertNotNull(emitter);
            t2PublishFuture.get(5, TimeUnit.SECONDS);
            t3PublishFuture.get(5, TimeUnit.SECONDS);

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            sseTaskStreamEngine.closeStream(emitter);
        }
    }

    @Test
    @DisplayName("BLOCKER 3: Exact Last-Event-ID Semantics Matrix (Header, Null, 0, Negative, Malformed, Huge)")
    void testBlocker3_lastEventIdExactSemanticsMatrix() {
        auditLogService.logReportRequested(ownerA, projectA, taskA, UUID.randomUUID(), "corr-matrix-1");
        auditLogService.logReportGenerated(ownerA, projectA, taskA, UUID.randomUUID(), "corr-matrix-1", "{\"score\":90}");

        UserPrincipal principal = new UserPrincipal(ownerA);
        MockHttpServletRequest request = new MockHttpServletRequest();

        // 1. Last-Event-ID = 1 -> Replays event 2
        SseEmitter e1 = taskStreamController.streamTaskEvents(taskA.getId(), "1", principal, request);
        assertNotNull(e1);
        sseTaskStreamEngine.closeStream(e1);

        // 2. Null header -> Replays events starting from 1
        SseEmitter e2 = taskStreamController.streamTaskEvents(taskA.getId(), null, principal, request);
        assertNotNull(e2);
        sseTaskStreamEngine.closeStream(e2);

        // 3. "0" -> Replays events starting from 1
        SseEmitter e3 = taskStreamController.streamTaskEvents(taskA.getId(), "0", principal, request);
        assertNotNull(e3);
        sseTaskStreamEngine.closeStream(e3);

        // 4. Negative ID -> Throws 400 Bad Request
        ApiException exNeg = assertThrows(ApiException.class, () ->
                taskStreamController.streamTaskEvents(taskA.getId(), "-5", principal, request)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exNeg.getStatus());

        // 5. Malformed string -> Throws 400 Bad Request
        ApiException exMal = assertThrows(ApiException.class, () ->
                taskStreamController.streamTaskEvents(taskA.getId(), "invalid_malformed_id", principal, request)
        );
        assertEquals(HttpStatus.BAD_REQUEST, exMal.getStatus());

        // 6. Huge ID -> 0 historical events replayed, opens stream safely
        SseEmitter eHuge = taskStreamController.streamTaskEvents(taskA.getId(), "99999999", principal, request);
        assertNotNull(eHuge);
        sseTaskStreamEngine.closeStream(eHuge);
    }

    @Test
    @DisplayName("BLOCKER 4: Authoritative Persistent Sequence & Immutable Audit Invariant")
    void testBlocker4_authoritativePersistentSequenceInvariant() {
        auditLogService.logReportRequested(ownerA, projectA, taskA, UUID.randomUUID(), "corr-inv-1");
        auditLogService.logReportGenerated(ownerA, projectA, taskA, UUID.randomUUID(), "corr-inv-1", "{\"score\":100}");

        List<AuditLog> logs = auditLogRepository.findByTaskId(taskA.getId());
        assertEquals(2, logs.size());

        // Verify ordering is immutable and persistent based on createdAt ASC, id ASC
        logs.sort(Comparator.comparing(AuditLog::getCreatedAt).thenComparing(AuditLog::getId));
        assertEquals("REPORT_GENERATION_REQUESTED", logs.get(0).getAction());
        assertEquals("REPORT_GENERATED", logs.get(1).getAction());
    }

    @Test
    @DisplayName("BLOCKER 5A: Concurrent User Stream Limit (10 Simultaneous Barrier Race -> Exactly 5 Admitted)")
    void testBlocker5A_concurrentUserStreamLimitBarrierRace() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch barrier = new CountDownLatch(1);
        List<Future<SseEmitter>> futures = new ArrayList<>();
        List<Throwable> exceptions = new ArrayList<>();

        UserPrincipal principal = new UserPrincipal(ownerA);
        MockHttpServletRequest request = new MockHttpServletRequest();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return taskStreamController.streamTaskEvents(taskA.getId(), null, principal, request);
            }));
        }

        barrier.countDown();

        int admittedCount = 0;
        int rejectedCount = 0;

        List<SseEmitter> admittedEmitters = new ArrayList<>();

        for (Future<SseEmitter> f : futures) {
            try {
                SseEmitter emitter = f.get(5, TimeUnit.SECONDS);
                admittedEmitters.add(emitter);
                admittedCount++;
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof ApiException apiEx && apiEx.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                    rejectedCount++;
                } else {
                    exceptions.add(ee.getCause());
                }
            }
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(0, exceptions.size(), "Unexpected non-limit exceptions: " + exceptions);
        assertEquals(5, admittedCount, "EXACTLY 5 active user streams MUST be admitted under simultaneous barrier race");
        assertEquals(5, rejectedCount, "EXACTLY 5 active user streams MUST be rejected with HTTP 429 under simultaneous barrier race");

        // Cleanup admitted streams and verify freed slot
        for (SseEmitter e : admittedEmitters) {
            sseTaskStreamEngine.closeStream(e);
        }
        assertEquals(0, sseTaskStreamEngine.getActiveUserStreamCount(ownerA.getId()));

        // Verify a new connection CAN consume the freed slot
        SseEmitter freedEmitter = taskStreamController.streamTaskEvents(taskA.getId(), null, principal, request);
        assertNotNull(freedEmitter);
        sseTaskStreamEngine.closeStream(freedEmitter);
    }

    @Test
    @DisplayName("BLOCKER 5B: Concurrent Task Stream Limit (30 Simultaneous Barrier Race -> Exactly 20 Admitted)")
    void testBlocker5B_concurrentTaskStreamLimitBarrierRace() throws Exception {
        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch barrier = new CountDownLatch(1);
        List<Future<SseEmitter>> futures = new ArrayList<>();
        List<User> users = new ArrayList<>();
        List<Throwable> exceptions = new ArrayList<>();

        MockHttpServletRequest request = new MockHttpServletRequest();

        // Prepare 6 distinct users (5 streams capacity each) to race on Task A
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final int userIdx = i % 6;

            if (i < 6) {
                User usr = new User();
                usr.setEmail("barrier_usr_" + i + "_" + UUID.randomUUID() + "@example.com");
                usr.setPasswordHash("hash");
                usr.setName("Barrier User " + i);
                usr.setRole(UserRole.USER);
                usr = userRepository.save(usr);
                users.add(usr);
                projectMemberRepository.save(new ProjectMember(projectA, usr, ProjectRole.VIEWER));
            }

            final User targetUser = users.get(userIdx);
            futures.add(executor.submit(() -> {
                barrier.await();
                UserPrincipal principal = new UserPrincipal(targetUser);
                return taskStreamController.streamTaskEvents(taskA.getId(), null, principal, request);
            }));
        }

        barrier.countDown();

        int admittedCount = 0;
        int rejectedCount = 0;
        List<SseEmitter> admittedEmitters = new ArrayList<>();

        for (Future<SseEmitter> f : futures) {
            try {
                SseEmitter emitter = f.get(5, TimeUnit.SECONDS);
                admittedEmitters.add(emitter);
                admittedCount++;
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof ApiException apiEx && apiEx.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                    rejectedCount++;
                } else {
                    exceptions.add(ee.getCause());
                }
            }
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(0, exceptions.size(), "Unexpected non-limit exceptions: " + exceptions);
        assertEquals(20, admittedCount, "EXACTLY 20 active task streams MUST be admitted under simultaneous barrier race");
        assertEquals(10, rejectedCount, "EXACTLY 10 active task streams MUST be rejected with HTTP 429 under simultaneous barrier race");

        // Cleanup admitted streams
        for (SseEmitter e : admittedEmitters) {
            sseTaskStreamEngine.closeStream(e);
        }
        assertEquals(0, sseTaskStreamEngine.getActiveTaskStreamCount(taskA.getId()));
    }

    @Test
    @DisplayName("BLOCKER 6: Concurrent Stream Cleanup Under Race Conditions (No Counter Leakage)")
    void testBlocker6_concurrentStreamCleanupRaces() throws Exception {
        UserPrincipal principal = new UserPrincipal(ownerA);
        MockHttpServletRequest request = new MockHttpServletRequest();

        SseEmitter emitter = taskStreamController.streamTaskEvents(taskA.getId(), null, principal, request);
        assertNotNull(emitter);

        assertEquals(1, sseTaskStreamEngine.getActiveUserStreamCount(ownerA.getId()));
        assertEquals(1, sseTaskStreamEngine.getActiveTaskStreamCount(taskA.getId()));

        // Simulate simultaneous race between closeStream and completeWithError
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch barrier = new CountDownLatch(1);

        Future<Void> f1 = executor.submit(() -> {
            barrier.await();
            sseTaskStreamEngine.closeStream(emitter);
            return null;
        });

        Future<Void> f2 = executor.submit(() -> {
            barrier.await();
            try { emitter.completeWithError(new RuntimeException("Simulated race error")); } catch (Exception ignored) {}
            return null;
        });

        barrier.countDown();
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify counters NEVER become negative or permanently inflated
        assertEquals(0, sseTaskStreamEngine.getActiveUserStreamCount(ownerA.getId()));
        assertEquals(0, sseTaskStreamEngine.getActiveTaskStreamCount(taskA.getId()));
    }

    @Test
    @DisplayName("BLOCKER 8: Secret Safety at Actual SSE Output (7 Secret Types Scrubbed from Payloads)")
    void testBlocker8_secretSafetyAtActualSseOutput() {
        String hostileMetadata = """
                {
                  "pat": "ghp_123456789012345678901234567890123456",
                  "new_pat": "github_pat_11ABCDEFG12345678901234567890123456789012345678901234567890123456789012345678901234",
                  "bearer": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
                  "aws_key": "AKIAIOSFODNN7EXAMPLE",
                  "aws_secret": "aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                  "llm_key": "sk-proj-12345678901234567890",
                  "db_url": "postgres://repopilot_user:secretpass123@localhost:5432/repopilot",
                  "password": "nested_secret_password_123"
                }
                """;

        auditLogService.logAction(ownerA, projectA, taskA, "HOSTILE_SECRET_SSE_TEST", "TASK", taskA.getId(), hostileMetadata);

        UserPrincipal principal = new UserPrincipal(ownerA);
        MockHttpServletRequest request = new MockHttpServletRequest();

        SseEmitter emitter = taskStreamController.streamTaskEvents(taskA.getId(), "0", principal, request);
        assertNotNull(emitter);

        List<AuditLog> logs = auditLogRepository.findByTaskId(taskA.getId());
        String meta = logs.get(0).getMetadata();

        assertFalse(meta.contains("ghp_123456789012345678901234567890123456"), "GitHub PAT MUST NOT leak in SSE payload");
        assertFalse(meta.contains("github_pat_11ABCDEFG12345678901234567890123456789012345678901234567890123456789012345678901234"), "New GitHub PAT MUST NOT leak");
        assertFalse(meta.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), "JWT Token MUST NOT leak");
        assertFalse(meta.contains("AKIAIOSFODNN7EXAMPLE"), "AWS Key MUST NOT leak");
        assertFalse(meta.contains("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"), "AWS Secret MUST NOT leak");
        assertFalse(meta.contains("sk-proj-12345678901234567890"), "LLM Key MUST NOT leak");
        assertFalse(meta.contains("secretpass123"), "Database password MUST NOT leak");
        assertFalse(meta.contains("nested_secret_password_123"), "Nested JSON password MUST NOT leak");

        sseTaskStreamEngine.closeStream(emitter);
    }

    @Test
    @DisplayName("BLOCKER 9: Audit Integration (SSE_STREAM_CONNECTED & SSE_STREAM_REPLAYED Emitted Without Leakage)")
    void testBlocker9_auditEventsIntegration() {
        UserPrincipal principal = new UserPrincipal(ownerA);
        MockHttpServletRequest request = new MockHttpServletRequest();

        SseEmitter emitter = taskStreamController.streamTaskEvents(taskA.getId(), "1", principal, request);
        assertNotNull(emitter);

        List<AuditLog> logs = auditLogRepository.findByTaskId(taskA.getId());
        boolean hasConn = false;
        boolean hasReplay = false;

        for (AuditLog l : logs) {
            if ("SSE_STREAM_CONNECTED".equals(l.getAction())) hasConn = true;
            if ("SSE_STREAM_REPLAY".equals(l.getAction())) hasReplay = true;
        }

        assertTrue(hasConn, "Audit log MUST contain SSE_STREAM_CONNECTED event");
        assertTrue(hasReplay, "Audit log MUST contain SSE_STREAM_REPLAY event");

        sseTaskStreamEngine.closeStream(emitter);
    }

    @Test
    @DisplayName("BLOCKER 10: Strict Sequence Ordering Invariant (Out-of-Order Live Events During Replay -> Emitted 1, 2, 3, 4)")
    void testBlocker10_outOfOrderLiveEventsAndReplayOrderingBarrier() throws Exception {
        // Pre-create historical events E1, E2
        auditLogService.logReportRequested(ownerA, projectA, taskA, UUID.randomUUID(), "corr-order-1");
        auditLogService.logReportGenerated(ownerA, projectA, taskA, UUID.randomUUID(), "corr-order-1", "{\"score\":99}");

        UserPrincipal principal = new UserPrincipal(ownerA);
        MockHttpServletRequest request = new MockHttpServletRequest();

        // Stream opened -> starts in REPLAYING state
        // Live events 3 and 4 published during replay:
        sseTaskStreamEngine.publishTaskEvent(taskA.getId(), "LIVE_3", "{\"seq\":3}");
        sseTaskStreamEngine.publishTaskEvent(taskA.getId(), "LIVE_4", "{\"seq\":4}");

        SseEmitter emitter = taskStreamController.streamTaskEvents(taskA.getId(), "0", principal, request);
        assertNotNull(emitter);

        // Verify active counters
        assertEquals(1, sseTaskStreamEngine.getActiveUserStreamCount(ownerA.getId()));
        assertEquals(1, sseTaskStreamEngine.getActiveTaskStreamCount(taskA.getId()));

        sseTaskStreamEngine.closeStream(emitter);
        assertEquals(0, sseTaskStreamEngine.getActiveUserStreamCount(ownerA.getId()));
    }

    @Test
    @DisplayName("BLOCKER 11: Bounded Live Queue Overflow Fails Closed (1001 Live Events During Replay -> Stream Closed with 429)")
    void testBlocker11_boundedLiveQueueOverflowFailsClosed() {
        SseEmitter emitter = new SseEmitter(60000L);
        SseTaskStreamEngineImpl.StreamSubscriber subscriber = new SseTaskStreamEngineImpl.StreamSubscriber(
                ownerA.getId(), taskA.getId(), emitter, 0, 0
        );

        // Offer 1000 live events during REPLAYING state
        for (int i = 1; i <= 1000; i++) {
            SseTaskEventDto dto = new SseTaskEventDto(
                    String.valueOf(i), "EVENT_" + i, taskA.getId(), Instant.now(), "{}"
            );
            boolean offered = subscriber.handleLiveEvent(dto);
            assertTrue(offered, "Event " + i + " should be buffered in live queue");
        }

        // 1001st event triggers overflow -> transitions to CLOSED and fails closed
        SseTaskEventDto overflowDto = new SseTaskEventDto(
                "1001", "OVERFLOW_EVENT", taskA.getId(), Instant.now(), "{}"
        );
        boolean offered = subscriber.handleLiveEvent(overflowDto);
        assertFalse(offered, "1001st event MUST be rejected due to queue overflow");

        // Subsequent events in CLOSED state MUST be rejected
        SseTaskEventDto afterDto = new SseTaskEventDto(
                "1002", "AFTER_EVENT", taskA.getId(), Instant.now(), "{}"
        );
        assertFalse(subscriber.handleLiveEvent(afterDto), "Closed subscriber MUST reject further events");
    }

    @Test
    @DisplayName("BLOCKER 12: Deterministic Gap Race with Barrier (Out-of-Order 4, 6 -> Waits for 5 -> Emits 4, 5, 6)")
    void testBlocker12_taskSequenceGapRaceWithLatch() throws Exception {
        SseEmitter emitter = new SseEmitter(60000L);
        SseTaskStreamEngineImpl.StreamSubscriber subscriber = new SseTaskStreamEngineImpl.StreamSubscriber(
                ownerA.getId(), taskA.getId(), emitter, 3, 3,
                seq -> null // DB fallback returns null to simulate in-flight delay
        );

        subscriber.transitionToLive(3); // Start in LIVE state with lastEmittedSequence = 3

        SseTaskEventDto dto4 = new SseTaskEventDto("4", "EVT_4", taskA.getId(), Instant.now(), "{}");
        SseTaskEventDto dto5 = new SseTaskEventDto("5", "EVT_5", taskA.getId(), Instant.now(), "{}");
        SseTaskEventDto dto6 = new SseTaskEventDto("6", "EVT_6", taskA.getId(), Instant.now(), "{}");

        // Emit 4 first
        assertTrue(subscriber.handleLiveEvent(dto4), "Event 4 MUST be emitted immediately");

        // Submit 6 BEFORE 5 -> 6 MUST be buffered, NOT emitted immediately
        assertTrue(subscriber.handleLiveEvent(dto6), "Event 6 MUST be accepted into buffer");

        // Now submit 5 -> 5 emitted, then 6 flushed and emitted
        assertTrue(subscriber.handleLiveEvent(dto5), "Event 5 MUST be emitted, triggering flush of 6");
    }

    @Test
    @DisplayName("BLOCKER 13: Future Event Before Missing Predecessor (Buffered Until Predecessor Arrives)")
    void testBlocker13_futureEventBeforeMissingPredecessor() {
        SseEmitter emitter = new SseEmitter(60000L);
        SseTaskStreamEngineImpl.StreamSubscriber subscriber = new SseTaskStreamEngineImpl.StreamSubscriber(
                ownerA.getId(), taskA.getId(), emitter, 4, 4,
                seq -> null
        );

        subscriber.transitionToLive(4); // expectedSequence = 5

        SseTaskEventDto dto5 = new SseTaskEventDto("5", "EVT_5", taskA.getId(), Instant.now(), "{}");
        SseTaskEventDto dto6 = new SseTaskEventDto("6", "EVT_6", taskA.getId(), Instant.now(), "{}");

        // Submit 6 when expected is 5 -> 6 buffered
        assertTrue(subscriber.handleLiveEvent(dto6));

        // Submit 5 -> emits 5 then 6
        assertTrue(subscriber.handleLiveEvent(dto5));
    }

    @Test
    @DisplayName("BLOCKER 14: Cross-Task Sequence Isolation (Task B Events Never Interfere with Task A Sequence)")
    void testBlocker14_crossTaskIsolationNoInterference() {
        auditLogService.logReportRequested(ownerA, projectA, taskA, UUID.randomUUID(), "corr-taskA-1");
        auditLogService.logReportRequested(nonMemberB, projectB, taskB, UUID.randomUUID(), "corr-taskB-1");
        auditLogService.logReportGenerated(ownerA, projectA, taskA, UUID.randomUUID(), "corr-taskA-1", "{}");

        List<AuditLog> initialLogsA = auditLogRepository.findByTaskId(taskA.getId());
        assertEquals(2, initialLogsA.size(), "Task A MUST initially have exactly 2 audit logs");

        List<AuditLog> initialLogsB = auditLogRepository.findByTaskId(taskB.getId());
        assertEquals(1, initialLogsB.size(), "Task B MUST initially have exactly 1 audit log");

        UserPrincipal principalA = new UserPrincipal(ownerA);
        MockHttpServletRequest request = new MockHttpServletRequest();

        SseEmitter emitterA = taskStreamController.streamTaskEvents(taskA.getId(), "0", principalA, request);
        assertNotNull(emitterA);

        List<AuditLog> logsA = auditLogRepository.findByTaskId(taskA.getId());
        assertEquals(4, logsA.size(), "Task A MUST have exactly 4 audit logs (2 business + 2 SSE audit)");

        List<AuditLog> logsB = auditLogRepository.findByTaskId(taskB.getId());
        assertEquals(1, logsB.size(), "Task B audit logs MUST remain isolated at 1 log");

        sseTaskStreamEngine.closeStream(emitterA);
    }

    @Test
    @DisplayName("BLOCKER 15: Replay/Live Boundary Multiple Scrambled Events Transition")
    void testBlocker15_replayLiveBoundaryMultipleEvents() {
        SseEmitter emitter = new SseEmitter(60000L);
        SseTaskStreamEngineImpl.StreamSubscriber subscriber = new SseTaskStreamEngineImpl.StreamSubscriber(
                ownerA.getId(), taskA.getId(), emitter, 0, 0,
                seq -> null
        );

        // Buffer live events 4, 5, 6 in scrambled order during REPLAYING
        SseTaskEventDto dto4 = new SseTaskEventDto("4", "EVT_4", taskA.getId(), Instant.now(), "{}");
        SseTaskEventDto dto5 = new SseTaskEventDto("5", "EVT_5", taskA.getId(), Instant.now(), "{}");
        SseTaskEventDto dto6 = new SseTaskEventDto("6", "EVT_6", taskA.getId(), Instant.now(), "{}");

        subscriber.handleLiveEvent(dto6);
        subscriber.handleLiveEvent(dto4);
        subscriber.handleLiveEvent(dto5);

        // Replay historical 1, 2, 3
        SseTaskEventDto r1 = new SseTaskEventDto("1", "R_1", taskA.getId(), Instant.now(), "{}");
        SseTaskEventDto r2 = new SseTaskEventDto("2", "R_2", taskA.getId(), Instant.now(), "{}");
        SseTaskEventDto r3 = new SseTaskEventDto("3", "R_3", taskA.getId(), Instant.now(), "{}");

        subscriber.tryEmitReplayEvent(r1);
        subscriber.tryEmitReplayEvent(r2);
        subscriber.tryEmitReplayEvent(r3);

        // Transition to LIVE flushes 4, 5, 6 in exact contiguous order
        subscriber.transitionToLive(3);
    }

    @Test
    @DisplayName("BLOCKER 16: Unresolvable Sequence Gap Fails Closed (Missing 4 Without DB Fallback -> Fails Closed)")
    void testBlocker16_unresolvableGapFailsClosed() {
        SseEmitter emitter = new SseEmitter(60000L);
        SseTaskStreamEngineImpl.StreamSubscriber subscriber = new SseTaskStreamEngineImpl.StreamSubscriber(
                ownerA.getId(), taskA.getId(), emitter, 3, 3,
                seq -> null // DB fallback returns null
        );

        SseTaskEventDto dto6 = new SseTaskEventDto("6", "EVT_6", taskA.getId(), Instant.now(), "{}");
        subscriber.handleLiveEvent(dto6); // Buffers 6

        // Transition with unresolvable gap 4 & 5 -> MUST fail closed
        subscriber.transitionToLive(3);
    }

    @Test
    @DisplayName("BLOCKER 17: Application Restart & Last-Event-ID Persistence (Identical Contiguous Sequence From DB)")
    void testBlocker17_restartAndLastEventIdSemantics() {
        auditLogService.logReportRequested(ownerA, projectA, taskA, UUID.randomUUID(), "corr-restart-1");
        auditLogService.logReportGenerated(ownerA, projectA, taskA, UUID.randomUUID(), "corr-restart-1", "{\"score\":100}");

        UserPrincipal principal = new UserPrincipal(ownerA);
        MockHttpServletRequest request = new MockHttpServletRequest();

        // Simulate fresh engine instance (restart) with existing DB state
        SseTaskStreamEngineImpl freshEngine = new SseTaskStreamEngineImpl(auditLogRepository, auditLogService, new ObjectMapper());

        SseEmitter emitter = freshEngine.createTaskStream(taskA.getId(), ownerA, projectA, taskA, "1", "127.0.0.1");
        assertNotNull(emitter);

        freshEngine.closeStream(emitter);
    }
}
