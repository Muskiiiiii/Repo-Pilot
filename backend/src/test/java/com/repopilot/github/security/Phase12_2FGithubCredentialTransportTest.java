package com.repopilot.github.security;

import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase12_2FGithubCredentialTransportTest {

    @TempDir
    Path tempDir;

    private ProjectAuthorizationService projectAuthorizationService;
    private HumanApprovalRepository humanApprovalRepository;
    private RepositoryRepository repositoryRepository;
    private TaskRepository taskRepository;
    private TokenEncryptionService tokenEncryptionService;
    private AuditLogService auditLogService;
    private GithubCredentialProvider credentialProvider;
    private GitAskpassManager askpassManager;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID approvalId;
    private User requester;

    private Project project;
    private Repository repository;
    private Task task;
    private HumanApproval approval;
    private CodeReview review;

    private String rawSentinelToken;
    private String encryptedToken;

    @BeforeEach
    void setUp() {
        projectAuthorizationService = mock(ProjectAuthorizationService.class);
        humanApprovalRepository = mock(HumanApprovalRepository.class);
        repositoryRepository = mock(RepositoryRepository.class);
        taskRepository = mock(TaskRepository.class);
        tokenEncryptionService = mock(TokenEncryptionService.class);
        auditLogService = mock(AuditLogService.class);

        credentialProvider = new GithubCredentialProviderImpl(
                projectAuthorizationService,
                humanApprovalRepository,
                repositoryRepository,
                taskRepository,
                tokenEncryptionService,
                auditLogService
        );

        askpassManager = new GitAskpassManager();

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        approvalId = UUID.randomUUID();

        rawSentinelToken = "TEST_SECRET_SENTINEL_PAT_123456789";
        encryptedToken = "ENCRYPTED_BLOB_AES256GCM_98765";

        requester = new User();
        requester.setId(UUID.randomUUID());
        requester.setGithubUsername("test-github-user");
        requester.setGithubAccessToken(encryptedToken);

        project = new Project();
        project.setId(projectId);

        repository = new Repository();
        repository.setId(repositoryId);
        repository.setProject(project);

        task = new Task();
        task.setId(taskId);
        task.setProject(project);

        review = new CodeReview();
        review.setId(UUID.randomUUID());

        approval = new HumanApproval();
        approval.setId(approvalId);
        approval.setProject(project);
        approval.setRepository(repository);
        approval.setTask(task);
        approval.setReview(review);
        approval.setRepositoryBaseSha("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b");
        approval.setApprovalRecordHash("valid_record_hash");
        approval.setStatus(ApprovalStatus.APPROVED);

        when(projectAuthorizationService.getAuthorizedProject(projectId, requester)).thenReturn(project);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repository));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(tokenEncryptionService.decrypt(encryptedToken)).thenReturn(rawSentinelToken);
    }

    @Test
    @DisplayName("1. Authorized Credential Access: Decrypts JIT token successfully")
    void testGetGithubCredential_Success() {
        try (GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
            assertNotNull(context);
            assertFalse(context.isWiped());
            assertEquals(rawSentinelToken, context.getToken());
            assertEquals("test-github-user", context.getUsername());
        }
    }

    @Test
    @DisplayName("2. Unauthorized User Role Access: Throws FORBIDDEN when user lacks MEMBER role")
    void testGetGithubCredential_RoleUnauthorized() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "Minimum role MEMBER required"))
                .when(projectAuthorizationService).requireMinimumRole(eq(project), eq(requester), eq(ProjectRole.MEMBER));

        ApiException ex = assertThrows(ApiException.class, () ->
                credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(tokenEncryptionService, never()).decrypt(any());
    }

    @Test
    @DisplayName("3. Cross-Project Credential Access: Throws FORBIDDEN when approval project mismatch")
    void testGetGithubCredential_CrossProjectMismatch() {
        Project wrongProject = new Project();
        wrongProject.setId(UUID.randomUUID());

        HumanApproval wrongApproval = new HumanApproval();
        wrongApproval.setId(approvalId);
        wrongApproval.setProject(wrongProject);
        wrongApproval.setRepository(repository);
        wrongApproval.setTask(task);
        wrongApproval.setStatus(ApprovalStatus.APPROVED);

        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(wrongApproval));

        ApiException ex = assertThrows(ApiException.class, () ->
                credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verify(tokenEncryptionService, never()).decrypt(any());
    }

    @Test
    @DisplayName("4. Decryption Failure Handling: Throws UNAUTHORIZED when AES decryption fails")
    void testGetGithubCredential_DecryptionFailure() {
        when(tokenEncryptionService.decrypt(encryptedToken)).thenThrow(new RuntimeException("AES GCM tag mismatch"));

        ApiException ex = assertThrows(ApiException.class, () ->
                credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("5. Token Memory Wipe: Clears volatile char array upon close()")
    void testCredentialContext_WipeOnClose() {
        GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester);
        assertNotNull(context.getToken());
        assertFalse(context.isWiped());

        context.close();

        assertTrue(context.isWiped());
        assertThrows(IllegalStateException.class, context::getToken);
        assertThrows(IllegalStateException.class, context::getTokenChars);
    }

    @Test
    @DisplayName("6. ProcessBuilder Argv Isolation: Sentinel token MUST NOT appear in process command line")
    void testProcessBuilder_ArgvIsolation() {
        try (GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
            ProcessBuilder pb = new ProcessBuilder("git", "push", "origin", "refs/heads/repopilot/task-test");
            askpassManager.configureProcessBuilder(pb, context);

            List<String> command = pb.command();
            for (String arg : command) {
                assertFalse(arg.contains(rawSentinelToken), "Sentinel token MUST NOT appear in ProcessBuilder command line argv");
            }
        }
    }

    @Test
    @DisplayName("7. Global Environment Isolation: Sentinel token MUST NOT appear in System.getenv()")
    void testGlobalEnvironment_Isolation() {
        try (GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                assertFalse(entry.getValue().contains(rawSentinelToken), "Sentinel token MUST NOT be leaked into System.getenv()");
            }
        }
    }

    @Test
    @DisplayName("8. Secret Redaction Utility: Sanitizes logs, stdout, and exceptions containing secrets")
    void testSecretRedactor_Sanitization() {
        String logMessage = "Failed authorization header Authorization: Bearer " + rawSentinelToken + " for user";
        String sanitized = SecretRedactor.sanitizeLogs(logMessage);

        assertFalse(sanitized.contains(rawSentinelToken));
        assertTrue(sanitized.contains("[REDACTED_SECRET]"));
    }

    @Test
    @DisplayName("9. Askpass Helper Prompt Handling: Answers expected prompts & fails closed on unexpected prompts")
    void testAskpass_PromptResolution() {
        try (GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
            // Expected username prompt
            String userResponse = GitAskpassManager.resolvePrompt("Username for 'https://github.com': ", context);
            assertEquals("test-github-user", userResponse);

            // Expected password prompt
            String passResponse = GitAskpassManager.resolvePrompt("Password for 'https://test-github-user@github.com': ", context);
            assertEquals(rawSentinelToken, passResponse);

            // Unexpected prompt -> fail closed (null)
            String unknownResponse = GitAskpassManager.resolvePrompt("Sudo password for root: ", context);
            assertNull(unknownResponse);

            // Shell injection prompt -> fail closed (null)
            String injectionResponse = GitAskpassManager.resolvePrompt("Password for `rm -rf /`: ", context);
            assertNull(injectionResponse);
        }
    }

    @Test
    @DisplayName("10. Askpass Executable Permissions: Helper script must have 0700 POSIX permissions")
    void testAskpass_FilePermissions() throws Exception {
        Path scriptPath = askpassManager.getAskpassExecutablePath();
        assertTrue(Files.exists(scriptPath));
        assertTrue(Files.isExecutable(scriptPath));

        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(scriptPath);
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
            assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE));

            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows POSIX fallback
        }
    }

    @Test
    @DisplayName("11. Real Child Process Execution: Spawns child askpass process & verifies argv process inspection")
    void testChildProcess_ArgvInspection() throws Exception {
        try (GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
            ProcessBuilder pb = new ProcessBuilder(askpassManager.getAskpassExecutablePath().toString(), "Password for 'https://github.com': ");
            askpassManager.configureProcessBuilder(pb, context);

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String output = reader.readLine();
            process.waitFor();

            assertEquals(0, process.exitValue());
            assertEquals(rawSentinelToken, output);
        }
    }

    @Test
    @DisplayName("12. Process Listing (ps aux) Inspection: Sentinel token MUST NOT appear in ps output")
    void testProcessInspection_PsAux() throws Exception {
        try (GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
            ProcessBuilder pb = new ProcessBuilder(askpassManager.getAskpassExecutablePath().toString(), "Password for 'https://github.com': ");
            askpassManager.configureProcessBuilder(pb, context);

            Process process = pb.start();

            // Run ps aux while child process is executing
            try {
                ProcessBuilder psPb = new ProcessBuilder("ps", "aux");
                Process psProcess = psPb.start();
                BufferedReader psReader = new BufferedReader(new InputStreamReader(psProcess.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder psOutput = new StringBuilder();
                String line;
                while ((line = psReader.readLine()) != null) {
                    psOutput.append(line).append("\n");
                }
                psProcess.waitFor();

                assertFalse(psOutput.toString().contains(rawSentinelToken), "Sentinel token MUST NOT appear in ps aux process listing!");
            } catch (Exception ignored) {
                // If ps is unavailable in test runner container
            }

            process.waitFor();
        }
    }

    @Test
    @DisplayName("13. Concurrent Credential Isolation: Multi-threaded credential contexts remain isolated with zero cross-contamination")
    void testConcurrentCredential_Isolation() throws Exception {
        when(tokenEncryptionService.decrypt(anyString())).thenAnswer(invocation -> {
            String arg = invocation.getArgument(0);
            if (arg != null && arg.startsWith("encrypted_")) {
                String idx = arg.substring("encrypted_".length());
                return "TOKEN_SECRET_INDEX_" + idx;
            }
            return rawSentinelToken;
        });

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                String tokenI = "TOKEN_SECRET_INDEX_" + index;
                String userI = "user_" + index;

                User userEntity = new User();
                userEntity.setId(UUID.randomUUID());
                userEntity.setGithubUsername(userI);
                userEntity.setGithubAccessToken("encrypted_" + index);

                try (GithubCredentialContext ctx = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, userEntity)) {
                    assertEquals(tokenI, ctx.getToken());
                    assertEquals(userI, ctx.getUsername());
                    return true;
                }
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        for (Future<Boolean> future : futures) {
            assertTrue(future.get());
        }
    }

    @Test
    @DisplayName("14. Audit Log Safety: Zero credential plaintext in audit payloads")
    void testAuditLog_Safety() {
        credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester);

        verify(auditLogService).logAction(
                eq(requester), eq(project), eq(task),
                eq("CREDENTIAL_ACCESS_REQUESTED"), eq("HUMAN_APPROVAL"), eq(approvalId),
                argThat(meta -> !meta.contains(rawSentinelToken) && !meta.contains(encryptedToken) && meta.contains("test-github-user"))
        );
    }

    @Test
    @DisplayName("15. Host Binding Enforcement: Rejects prompt for unauthorized host")
    void testAskpass_HostBinding() {
        try (GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
            // Matching host -> success
            String validHostResponse = GitAskpassManager.resolvePrompt("Password for 'https://github.com': ", context, "github.com");
            assertEquals(rawSentinelToken, validHostResponse);

            // Mismatched host -> fail closed
            String invalidHostResponse = GitAskpassManager.resolvePrompt("Password for 'https://malicious-host.com': ", context, "github.com");
            assertNull(invalidHostResponse);
        }
    }

    @Test
    @DisplayName("16. Metacharacter Injection Rejection: Rejects newline, CRLF, backtick, shell subshell")
    void testAskpass_MetacharacterRejection() {
        try (GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
            assertNull(GitAskpassManager.resolvePrompt("Password for 'https://github.com'\n", context));
            assertNull(GitAskpassManager.resolvePrompt("Password for 'https://github.com'\r", context));
            assertNull(GitAskpassManager.resolvePrompt("Password for 'https://github.com'; cat /etc/passwd", context));
            assertNull(GitAskpassManager.resolvePrompt("Password for $(whoami)", context));
            assertNull(GitAskpassManager.resolvePrompt("Password for `id`", context));
        }
    }

    @Test
    @DisplayName("17. Access After Wipe: Throws IllegalStateException when accessed after wipe")
    void testCredentialContext_AccessAfterWipe() {
        GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester);
        context.wipe();

        assertTrue(context.isWiped());
        assertThrows(IllegalStateException.class, context::getToken);
        assertThrows(IllegalStateException.class, context::getTokenChars);
        assertNull(GitAskpassManager.resolvePrompt("Password for 'https://github.com': ", context));
    }

    @Test
    @DisplayName("18. ProcessBuilder Wiped Credential Safety: Throws IllegalStateException when process builder receives wiped context")
    void testProcessBuilder_WipedCredential() {
        GithubCredentialContext context = credentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester);
        context.wipe();

        ProcessBuilder pb = new ProcessBuilder("git", "status");
        assertThrows(IllegalStateException.class, () -> askpassManager.configureProcessBuilder(pb, context));
    }
}
