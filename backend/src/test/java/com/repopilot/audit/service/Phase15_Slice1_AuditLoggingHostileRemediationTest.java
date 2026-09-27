package com.repopilot.audit.service;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase15_Slice1_AuditLoggingHostileRemediationTest {

    @Autowired private AuditLogService auditLogService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User userA;
    private User userB;
    private Project projectA;
    private Project projectB;
    private Repository repoA;
    private Repository repoB;
    private Task taskA;
    private Task taskB;

    private static final String PG_URL = "jdbc:postgresql://127.0.0.1:5432/repopilot";
    private static final String PG_USER = "repopilot_user";
    private static final String PG_PASS = "repopilot_password";

    private boolean isPostgresAvailable() {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                return conn.isValid(2);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureV29TriggerAndFixturesOnPostgres(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE FUNCTION prevent_audit_modification() " +
                         "RETURNS TRIGGER AS $$ " +
                         "BEGIN " +
                         "    RAISE EXCEPTION 'Audit log table is append-only. UPDATE and DELETE operations are forbidden.'; " +
                         "END; " +
                         "$$ LANGUAGE plpgsql;");

            stmt.execute("DROP TRIGGER IF EXISTS trg_prevent_audit_logs_update_delete ON audit_logs;");

            stmt.execute("CREATE TRIGGER trg_prevent_audit_logs_update_delete " +
                         "BEFORE UPDATE OR DELETE ON audit_logs " +
                         "FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();");
        }

        try (PreparedStatement psUser = conn.prepareStatement(
                "INSERT INTO users (id, email, password_hash, name, role) VALUES (?, ?, 'hash', 'User A', 'USER') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psUserB = conn.prepareStatement(
                "INSERT INTO users (id, email, password_hash, name, role) VALUES (?, ?, 'hash', 'User B', 'USER') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psProjectA = conn.prepareStatement(
                "INSERT INTO projects (id, name, owner_id, status) VALUES (?, 'Project A', ?, 'ACTIVE') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psProjectB = conn.prepareStatement(
                "INSERT INTO projects (id, name, owner_id, status) VALUES (?, 'Project B', ?, 'ACTIVE') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psRepoA = conn.prepareStatement(
                "INSERT INTO repositories (id, project_id, owner_name, repo_name, default_branch, visibility) VALUES (?, ?, 'owner-a', 'repo-a', 'main', 'PRIVATE') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psRepoB = conn.prepareStatement(
                "INSERT INTO repositories (id, project_id, owner_name, repo_name, default_branch, visibility) VALUES (?, ?, 'owner-b', 'repo-b', 'main', 'PRIVATE') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psTaskA = conn.prepareStatement(
                "INSERT INTO tasks (id, project_id, repository_id, created_by, title, status, priority) VALUES (?, ?, ?, ?, 'Task A', 'COMPLETED', 'HIGH') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psTaskB = conn.prepareStatement(
                "INSERT INTO tasks (id, project_id, repository_id, created_by, title, status, priority) VALUES (?, ?, ?, ?, 'Task B', 'COMPLETED', 'HIGH') ON CONFLICT (id) DO NOTHING")
        ) {
            psUser.setObject(1, userA.getId());
            psUser.setString(2, userA.getEmail());
            psUser.executeUpdate();

            psUserB.setObject(1, userB.getId());
            psUserB.setString(2, userB.getEmail());
            psUserB.executeUpdate();

            psProjectA.setObject(1, projectA.getId());
            psProjectA.setObject(2, userA.getId());
            psProjectA.executeUpdate();

            psProjectB.setObject(1, projectB.getId());
            psProjectB.setObject(2, userB.getId());
            psProjectB.executeUpdate();

            psRepoA.setObject(1, repoA.getId());
            psRepoA.setObject(2, projectA.getId());
            psRepoA.executeUpdate();

            psRepoB.setObject(1, repoB.getId());
            psRepoB.setObject(2, projectB.getId());
            psRepoB.executeUpdate();

            psTaskA.setObject(1, taskA.getId());
            psTaskA.setObject(2, projectA.getId());
            psTaskA.setObject(3, repoA.getId());
            psTaskA.setObject(4, userA.getId());
            psTaskA.executeUpdate();

            psTaskB.setObject(1, taskB.getId());
            psTaskB.setObject(2, projectB.getId());
            psTaskB.setObject(3, repoB.getId());
            psTaskB.setObject(4, userB.getId());
            psTaskB.executeUpdate();
        }
    }

    @BeforeEach
    void setUp() {
        tearDown();

        userA = new User();
        userA.setEmail("remediation_user_a_" + UUID.randomUUID() + "@example.com");
        userA.setPasswordHash("hash");
        userA.setName("User A");
        userA.setRole(UserRole.USER);
        userA = userRepository.save(userA);

        userB = new User();
        userB.setEmail("remediation_user_b_" + UUID.randomUUID() + "@example.com");
        userB.setPasswordHash("hash");
        userB.setName("User B");
        userB.setRole(UserRole.USER);
        userB = userRepository.save(userB);

        projectA = new Project();
        projectA.setName("Project A");
        projectA.setOwner(userA);
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectB = new Project();
        projectB.setName("Project B");
        projectB.setOwner(userB);
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

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
        taskA.setCreatedBy(userA);
        taskA.setTitle("Task A");
        taskA.setStatus(TaskStatus.COMPLETED);
        taskA.setPriority(TaskPriority.HIGH);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(userB);
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
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    @Test
    @DisplayName("0. PostgreSQL Catalog Inspection: Verify trigger 'trg_prevent_audit_logs_update_delete' exists in pg_trigger and information_schema")
    void test0_postgresCatalogMetadataVerification() throws Exception {
        if (!isPostgresAvailable()) return;

        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            ensureV29TriggerAndFixturesOnPostgres(conn);

            // Check pg_proc for function
            try (PreparedStatement ps = conn.prepareStatement("SELECT proname FROM pg_proc WHERE proname = 'prevent_audit_modification'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "PostgreSQL pg_proc catalog MUST contain trigger function 'prevent_audit_modification'");
                    assertEquals("prevent_audit_modification", rs.getString("proname"));
                }
            }

            // Check pg_trigger for trigger on audit_logs
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tgname FROM pg_trigger t JOIN pg_class c ON t.tgrelid = c.oid WHERE c.relname = 'audit_logs' AND t.tgname = 'trg_prevent_audit_logs_update_delete'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "PostgreSQL pg_trigger catalog MUST contain trigger 'trg_prevent_audit_logs_update_delete' on audit_logs table");
                }
            }

            // Check information_schema.triggers
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT trigger_name, action_timing FROM information_schema.triggers WHERE event_object_table = 'audit_logs' AND trigger_name = 'trg_prevent_audit_logs_update_delete'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    boolean found = false;
                    while (rs.next()) {
                        found = true;
                        assertEquals("BEFORE", rs.getString("action_timing"));
                    }
                    assertTrue(found, "information_schema.triggers MUST report active BEFORE trigger on audit_logs");
                }
            }
        }
    }

    @Test
    @DisplayName("1. Real PostgreSQL Direct Native SQL Attack Matrix A through K: Trigger forbids native SQL UPDATE and DELETE")
    void test1_postgresNativeSqlAttackMatrix_A_to_K() throws Exception {
        if (!isPostgresAvailable()) return;

        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            ensureV29TriggerAndFixturesOnPostgres(conn);
            conn.setAutoCommit(false);

            UUID auditId = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO audit_logs (id, user_id, project_id, task_id, action, resource_type, resource_id, metadata, ip_address, created_at) " +
                    "VALUES (?, ?, ?, ?, 'INITIAL_ACTION', 'REPORT', ?, '{\"status\":\"OK\"}'::jsonb, '127.0.0.1', NOW())")) {
                ps.setObject(1, auditId);
                ps.setObject(2, userA.getId());
                ps.setObject(3, projectA.getId());
                ps.setObject(4, taskA.getId());
                ps.setObject(5, UUID.randomUUID());
                ps.executeUpdate();
            }
            conn.commit();

            // ATTACK A: Native SQL UPDATE created_at
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET created_at = NOW() - INTERVAL '10 days' WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject native SQL UPDATE created_at");
                assertTrue(ex.getMessage().contains("append-only"), "Exception must mention append-only violation: " + ex.getMessage());
            }
            conn.rollback();

            // ATTACK B: Native SQL UPDATE metadata
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET metadata = '{\"hacked\":true}'::jsonb WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject native SQL UPDATE metadata");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK C: Native SQL UPDATE action
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET action = 'HACKED_ACTION' WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject native SQL UPDATE action");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK D: Native SQL UPDATE actor/user_id
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET user_id = ? WHERE id = ?")) {
                ps.setObject(1, userB.getId());
                ps.setObject(2, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject native SQL UPDATE actor");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK E: Native SQL UPDATE project/task/resource identity
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET project_id = ?, task_id = ?, resource_id = ? WHERE id = ?")) {
                ps.setObject(1, projectB.getId());
                ps.setObject(2, taskB.getId());
                ps.setObject(3, UUID.randomUUID());
                ps.setObject(4, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject native SQL UPDATE hierarchy IDs");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK F: Native SQL UPDATE multiple columns
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET action = 'MUTATED', metadata = '{\"tampered\":true}'::jsonb, created_at = NOW() WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject multi-column native UPDATE");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK G: Native SQL DELETE audit row
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM audit_logs WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject native SQL DELETE");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK H: JPA entity mutation simulation via SQL UPDATE
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET action = 'JPA_SAVE_SIMULATION' WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject JPA save SQL UPDATE");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK I: JPA delete simulation via SQL DELETE
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM audit_logs WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject JPA delete SQL DELETE");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK J: EntityManager native UPDATE query simulation
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET metadata = '{\"em_query\":true}'::jsonb WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject EntityManager native UPDATE");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // ATTACK K: EntityManager native DELETE query simulation
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM audit_logs WHERE id = ?")) {
                ps.setObject(1, auditId);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "PostgreSQL MUST reject EntityManager native DELETE");
                assertTrue(ex.getMessage().contains("append-only"));
            }
            conn.rollback();

            // Verify original row remains completely unchanged in PostgreSQL
            try (PreparedStatement ps = conn.prepareStatement("SELECT action, user_id, project_id, task_id FROM audit_logs WHERE id = ?")) {
                ps.setObject(1, auditId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Original audit row MUST survive all native SQL attack attempts");
                    assertEquals("INITIAL_ACTION", rs.getString("action"));
                    assertEquals(userA.getId(), (UUID) rs.getObject("user_id"));
                    assertEquals(projectA.getId(), (UUID) rs.getObject("project_id"));
                    assertEquals(taskA.getId(), (UUID) rs.getObject("task_id"));
                }
            }
        }
    }

    @Test
    @DisplayName("2. Server-Side Actor & Timestamp Authority: Actor user and timestamp come from server state")
    void test2_serverSideActorAndTimestampAuthority() {
        AuditLog log = auditLogService.logAction(userA, projectA, taskA, "REPORT_GENERATED", "REPORT", UUID.randomUUID(), "{\"actor\":\"fake-user\"}");
        assertNotNull(log.getId());
        assertEquals(userA.getId(), log.getUser().getId(), "Server-side userA MUST be authoritative actor");
        assertNotNull(log.getCreatedAt(), "Timestamp MUST be generated server-side");
    }

    @Test
    @DisplayName("3. IDOR & Hierarchy Test Matrix: Cross-project task mismatch throws IllegalArgumentException")
    void test3_idorAndHierarchyTestMatrix() {
        assertThrows(IllegalArgumentException.class, () ->
                auditLogService.logAction(userA, projectA, taskB, "IDOR_ATTEMPT_1", "TASK", taskB.getId(), "Cross project attempt")
        );

        assertThrows(IllegalArgumentException.class, () ->
                auditLogService.logAction(userA, projectB, taskA, "IDOR_ATTEMPT_2", "TASK", taskA.getId(), "Cross project attempt")
        );

        AuditLog validInferred = auditLogService.logAction(userA, null, taskA, "AUTO_INFER_PROJECT", "TASK", taskA.getId(), "Valid");
        assertNotNull(validInferred.getId());
        assertEquals(projectA.getId(), validInferred.getProject().getId(), "Project MUST be auto-inferred from task");
    }

    @Test
    @DisplayName("4. Secret Redaction Persisted-Data Verification: 7 raw secret types sanitized in DB")
    void test4_secretRedactionPersistedDataVerification() {
        String hostileMetadata = """
                {
                  "pat": "ghp_123456789012345678901234567890123456",
                  "new_pat": "github_pat_11ABCDEFG12345678901234567890123456789012345678901234567890123456789012345678901234",
                  "bearer": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
                  "aws_key": "AKIAIOSFODNN7EXAMPLE",
                  "aws_secret": "aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                  "llm_key": "sk-proj-12345678901234567890",
                  "db_url": "postgres://repopilot_user:secretpass123@localhost:5432/repopilot"
                }
                """;

        AuditLog log = auditLogService.logAction(userA, projectA, taskA, "HOSTILE_SECRET_TEST", "TASK", taskA.getId(), hostileMetadata);
        assertNotNull(log.getId());

        AuditLog reloaded = auditLogRepository.findById(log.getId()).orElseThrow();
        String persistedMeta = reloaded.getMetadata();
        assertNotNull(persistedMeta);

        assertFalse(persistedMeta.contains("ghp_123456789012345678901234567890123456"), "GitHub PAT MUST be redacted");
        assertFalse(persistedMeta.contains("github_pat_11ABCDEFG12345678901234567890123456789012345678901234567890123456789012345678901234"), "New GitHub PAT MUST be redacted");
        assertFalse(persistedMeta.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), "JWT Token MUST be redacted");
        assertFalse(persistedMeta.contains("AKIAIOSFODNN7EXAMPLE"), "AWS Key MUST be redacted");
        assertFalse(persistedMeta.contains("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"), "AWS Secret MUST be redacted");
        assertFalse(persistedMeta.contains("sk-proj-12345678901234567890"), "LLM Key MUST be redacted");
        assertFalse(persistedMeta.contains("secretpass123"), "Database password MUST be redacted");
    }

    @Test
    @DisplayName("5. Metadata Limits & Resource Abuse: Over-limit metadata truncated at 64KB")
    void test5_metadataLimitsAndResourceAbuse() {
        AuditLog logNull = auditLogService.logAction(userA, projectA, taskA, "NULL_META", "TASK", taskA.getId(), null);
        assertNull(logNull.getMetadata());

        String hugeString = "A".repeat(70000);
        AuditLog logHuge = auditLogService.logAction(userA, projectA, taskA, "HUGE_META", "TASK", taskA.getId(), hugeString);
        assertNotNull(logHuge.getMetadata());
        assertTrue(logHuge.getMetadata().length() <= 65000, "Metadata MUST be truncated to capped limit");
        assertTrue(logHuge.getMetadata().contains("[TRUNCATED]"));
    }

    @Test
    @DisplayName("6. Correlation ID & Concurrency Multi-Threaded Stress: 10 concurrent writes succeed")
    void test6_correlationIdAndConcurrencyMultiThreadedStress() throws Exception {
        int count = 10;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        List<Future<Boolean>> futures = new ArrayList<>();
        String correlationId = "corr-stress-" + UUID.randomUUID();

        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                AuditLog saved = auditLogService.logReportRequested(userA, projectA, taskA, UUID.randomUUID(), correlationId);
                return saved != null && saved.getId() != null;
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        for (Future<Boolean> f : futures) {
            assertTrue(f.get());
        }

        List<AuditLog> persisted = auditLogRepository.findByTaskId(taskA.getId());
        assertEquals(count, persisted.size());
    }

    @Test
    @DisplayName("7. Audit Lifecycle Event Semantics: Phase 15 helper methods emit expected actions")
    void test7_auditLifecycleEventSemantics() {
        UUID execId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        String corrId = "corr-semantics-" + UUID.randomUUID().toString().substring(0, 8);

        AuditLog req = auditLogService.logReportRequested(userA, projectA, taskA, execId, corrId);
        assertEquals("REPORT_GENERATION_REQUESTED", req.getAction());

        AuditLog gen = auditLogService.logReportGenerated(userA, projectA, taskA, reportId, corrId, "{\"score\":100}");
        assertEquals("REPORT_GENERATED", gen.getAction());

        AuditLog fail = auditLogService.logReportFailed(userA, projectA, taskA, execId, corrId, "Error timeout");
        assertEquals("REPORT_GENERATION_FAILED", fail.getAction());

        AuditLog sseConn = auditLogService.logSseStreamConnected(userA, projectA, taskA, corrId, "192.168.1.1");
        assertEquals("SSE_STREAM_CONNECTED", sseConn.getAction());

        AuditLog sseRep = auditLogService.logSseStreamReplayed(userA, projectA, taskA, "task-1:5", corrId);
        assertEquals("SSE_STREAM_REPLAY", sseRep.getAction());

        AuditLog view = auditLogService.logReportViewed(userA, projectA, taskA, reportId, "192.168.1.1");
        assertEquals("REPORT_VIEWED", view.getAction());
    }
}
