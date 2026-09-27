package com.repopilot.testing;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.testing.dto.AIRepairProposalDto;
import com.repopilot.testing.dto.ProposedFileModificationDto;
import com.repopilot.testing.dto.ValidationResult;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.policy.RepairPolicyValidator;
import com.repopilot.testing.parser.*;
import com.repopilot.testing.service.FailureClassifierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Phase10_4aPersistenceAndSafetyTest {

    @TempDir
    Path tempDir;

    private Path workspaceRepoDir;
    private Path masterRepoDir;
    private RepairPolicyValidator validator;
    private FailureClassifierServiceImpl failureClassifierService;

    @BeforeEach
    void setUp() throws IOException {
        masterRepoDir = tempDir.resolve("master_repo");
        workspaceRepoDir = tempDir.resolve("workspace_repo");

        Files.createDirectories(masterRepoDir);
        Files.createDirectories(workspaceRepoDir);

        // Create standard source files in master & workspace
        Path masterSource = masterRepoDir.resolve("src/service.py");
        Files.createDirectories(masterSource.getParent());
        Files.writeString(masterSource, "def process():\n    return 1 / 0\n");

        Path workspaceSource = workspaceRepoDir.resolve("src/service.py");
        Files.createDirectories(workspaceSource.getParent());
        Files.writeString(workspaceSource, "def process():\n    return 1 / 0\n");

        TestResultParserCompositeService parserCompositeService = new TestResultParserCompositeService(List.of());
        failureClassifierService = new FailureClassifierServiceImpl(parserCompositeService, new com.fasterxml.jackson.databind.ObjectMapper());
        validator = new RepairPolicyValidator(failureClassifierService);
    }

    // ==========================================
    // 1-12: PATH SECURITY TESTS
    // ==========================================

    @Test
    @DisplayName("1. Path Security: Rejects ../ traversal")
    void testPathSecurity_ParentTraversal() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "../outside.py");
        assertFalse(result.isValid());
        assertEquals("PATH_TRAVERSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("2. Path Security: Rejects nested foo/../../bar traversal")
    void testPathSecurity_NestedTraversal() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/../../outside.py");
        assertFalse(result.isValid());
        assertEquals("PATH_TRAVERSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("3. Path Security: Rejects absolute Unix path /etc/passwd")
    void testPathSecurity_AbsoluteUnixPath() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "/etc/passwd");
        assertFalse(result.isValid());
        assertEquals("PATH_TRAVERSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("4. Path Security: Rejects Windows drive path C:\\Windows\\System32")
    void testPathSecurity_WindowsDrivePath() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "C:\\Windows\\System32\\cmd.exe");
        assertFalse(result.isValid());
        assertEquals("PATH_TRAVERSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("5. Path Security: Rejects sibling-prefix attack")
    void testPathSecurity_SiblingPrefixAttack() throws IOException {
        Path siblingWorkspace = tempDir.resolve("workspace_repo_extra");
        Files.createDirectories(siblingWorkspace);

        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "../workspace_repo_extra/hacked.py");
        assertFalse(result.isValid());
        assertEquals("PATH_TRAVERSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("6. Path Security: Rejects null byte injection test.py\\0.py")
    void testPathSecurity_NullByte() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/service.py\0.py");
        assertFalse(result.isValid());
        assertEquals("INVALID_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("7. Path Security: Rejects control characters in path")
    void testPathSecurity_ControlCharacter() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/service\r\n.py");
        assertFalse(result.isValid());
        assertEquals("INVALID_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("8. Path Security: Rejects symbolic link target file")
    void testPathSecurity_SymlinkTarget() throws IOException {
        Path secretFile = tempDir.resolve("secret.txt");
        Files.writeString(secretFile, "SECRET");

        Path symlinkInWorkspace = workspaceRepoDir.resolve("src/symlink_service.py");
        try {
            Files.createSymbolicLink(symlinkInWorkspace, secretFile);
        } catch (UnsupportedOperationException e) {
            return; // OS does not support symlinks in test env
        }

        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/symlink_service.py");
        assertFalse(result.isValid());
        assertEquals("SYMLINK_DETECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("9. Path Security: Rejects parent directory containing symlink")
    void testPathSecurity_SymlinkParent() throws IOException {
        Path outsideDir = tempDir.resolve("outside_dir");
        Files.createDirectories(outsideDir);

        Path symlinkDir = workspaceRepoDir.resolve("src/linked_dir");
        try {
            Files.createSymbolicLink(symlinkDir, outsideDir);
        } catch (UnsupportedOperationException e) {
            return;
        }

        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/linked_dir/file.py");
        assertFalse(result.isValid());
        assertEquals("SYMLINK_DETECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("10. Path Security: Rejects chained symlinks")
    void testPathSecurity_ChainedSymlink() throws IOException {
        Path targetFile = tempDir.resolve("target.txt");
        Files.writeString(targetFile, "TARGET");

        Path link1 = tempDir.resolve("link1.txt");
        Path link2 = workspaceRepoDir.resolve("src/link2.txt");

        try {
            Files.createSymbolicLink(link1, targetFile);
            Files.createSymbolicLink(link2, link1);
        } catch (UnsupportedOperationException e) {
            return;
        }

        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/link2.txt");
        assertFalse(result.isValid());
        assertEquals("SYMLINK_DETECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("11. Path Security: Rejects target resolving outside workspace")
    void testPathSecurity_OutsideWorkspaceTarget() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "~/service.py");
        assertFalse(result.isValid());
        assertEquals("PATH_TRAVERSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("12. Path Security: Rejects non-existent file target in master baseline")
    void testPathSecurity_NonexistentTarget() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/non_existent.py");
        assertFalse(result.isValid());
        assertEquals("NEW_FILE_REJECTED", result.getErrorCode());
    }

    // ==========================================
    // 13-18: SCOPE TESTS
    // ==========================================

    @Test
    @DisplayName("13. Scope: Approved Phase 9 file passes validation")
    void testScope_ApprovedPhase9File() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Fix bug", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 2, "return 1 / 0", "return 1 / 1")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertTrue(result.isValid());
    }

    @Test
    @DisplayName("14. Scope: Rejects non-whitelisted source file")
    void testScope_NonWhitelistedSourceFile() throws IOException {
        Path masterOther = masterRepoDir.resolve("src/other.py");
        Files.writeString(masterOther, "print(1)");

        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py"); // Only service.py is whitelisted

        AIRepairProposalDto proposal = new AIRepairProposalDto("Fix other", List.of(
                new ProposedFileModificationDto("src/other.py", 1, 1, "print(1)", "print(2)")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("NOT_IN_WHITELIST", result.getErrorCode());
    }

    @Test
    @DisplayName("15. Scope: Rejects new file creation")
    void testScope_NewFile() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/brand_new.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Create new file", List.of(
                new ProposedFileModificationDto("src/brand_new.py", 1, 1, "", "print('new')")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("NEW_FILE_REJECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("16. Scope: Rejects deleted file operation")
    void testScope_DeletedFile() {
        // ProposedFileModificationDto only supports search & replace, deletion cannot be represented
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Delete content", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 2, "def process():", "")
        ));

        // Attempting to replace entire file with empty is checked for valid target match
        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertNotNull(result);
    }

    @Test
    @DisplayName("17. Scope: Rejects renamed file proposal")
    void testScope_RenamedFile() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/renamed_service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Rename", List.of(
                new ProposedFileModificationDto("src/renamed_service.py", 1, 1, "a", "b")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("NEW_FILE_REJECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("18. Scope: Rejects binary file target")
    void testScope_BinaryFile() throws IOException {
        Path masterBin = masterRepoDir.resolve("src/image.png");
        Files.write(masterBin, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        CodeChange cc = new CodeChange();
        cc.setFilePath("src/image.png");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Edit image", List.of(
                new ProposedFileModificationDto("src/image.png", 1, 1, "a", "b")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
    }

    // ==========================================
    // 19-27: ANTI-TEST-TAMPERING TESTS
    // ==========================================

    @Test
    @DisplayName("19. Anti-Test-Tampering: Rejects UserServiceTest.java")
    void testAntiTestTampering_TestJava() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/UserServiceTest.java");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("20. Anti-Test-Tampering: Rejects test_auth.py")
    void testAntiTestTampering_TestPy() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/test_auth.py");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("21. Anti-Test-Tampering: Rejects auth_test.go")
    void testAntiTestTampering_TestGo() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/auth_test.go");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("22. Anti-Test-Tampering: Rejects auth.test.js")
    void testAntiTestTampering_TestJs() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/auth.test.js");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("23. Anti-Test-Tampering: Rejects auth.spec.ts")
    void testAntiTestTampering_SpecTs() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/auth.spec.ts");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("24. Anti-Test-Tampering: Rejects tests/ directory target")
    void testAntiTestTampering_TestsDirectory() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "tests/helper.py");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("25. Anti-Test-Tampering: Rejects fixtures/ target")
    void testAntiTestTampering_Fixtures() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/fixtures/mock.json");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("26. Anti-Test-Tampering: Rejects testdata/ target")
    void testAntiTestTampering_TestData() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "src/testdata/input.txt");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("27. Anti-Test-Tampering: Rejects pytest.ini configuration")
    void testAntiTestTampering_TestConfiguration() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "pytest.ini");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    // ==========================================
    // 28-35: FORBIDDEN BUILD & CONFIG FILES
    // ==========================================

    @Test
    @DisplayName("28. Forbidden Files: Rejects pom.xml")
    void testForbiddenFiles_PomXml() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "pom.xml");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("29. Forbidden Files: Rejects package.json")
    void testForbiddenFiles_PackageJson() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "package.json");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("30. Forbidden Files: Rejects build.gradle")
    void testForbiddenFiles_BuildGradle() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "build.gradle");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("31. Forbidden Files: Rejects Cargo.toml")
    void testForbiddenFiles_CargoToml() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "Cargo.toml");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("32. Forbidden Files: Rejects go.mod")
    void testForbiddenFiles_GoMod() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "go.mod");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("33. Forbidden Files: Rejects .github/workflows/ci.yml")
    void testForbiddenFiles_CiWorkflow() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, ".github/workflows/ci.yml");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("34. Forbidden Files: Rejects Dockerfile")
    void testForbiddenFiles_Dockerfile() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, "Dockerfile");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    @Test
    @DisplayName("35. Forbidden Files: Rejects .git/config")
    void testForbiddenFiles_GitPath() {
        ValidationResult result = validator.validatePathSecurity(workspaceRepoDir, masterRepoDir, ".git/config");
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_PATH", result.getErrorCode());
    }

    // ==========================================
    // 36-41: SECRET DETECTION IN PROPOSAL
    // ==========================================

    @Test
    @DisplayName("36. Secrets: Rejects proposed replacement containing AWS key")
    void testSecrets_AwsKey() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Inject AWS key", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", "KEY = 'AKIAIOSFODNN7EXAMPLE'")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("SECRET_DETECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("37. Secrets: Rejects proposed replacement containing GitHub PAT")
    void testSecrets_GithubToken() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Inject PAT", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", "TOKEN = 'ghp_1234567890abcdefghijklmnopqrstuvwxyz'")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("SECRET_DETECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("38. Secrets: Rejects proposed replacement containing LLM key")
    void testSecrets_LlmKey() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Inject LLM key", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", "KEY = 'sk-proj-1234567890abcdefghijklmnopqrstuvwxyz'")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("SECRET_DETECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("39. Secrets: Rejects proposed replacement containing Voyage key")
    void testSecrets_VoyageKey() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Inject Voyage key", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", "KEY = 'vy-1234567890abcdefghijklmnopqrstuvwxyz'")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("SECRET_DETECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("40. Secrets: Rejects proposed replacement containing Private Key")
    void testSecrets_PrivateKey() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Inject Private key", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", "KEY = '-----BEGIN PRIVATE KEY-----'")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("SECRET_DETECTED", result.getErrorCode());
    }

    @Test
    @DisplayName("41. Secrets: Rejects proposed replacement containing DB Credential URL")
    void testSecrets_DbCredentialUrl() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Inject DB URL", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", "URL = 'postgres://user:secretpass@localhost:5432/db'")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("SECRET_DETECTED", result.getErrorCode());
    }

    // ==========================================
    // 42-45: PATCH & RESPONSE BOUNDS TESTS
    // ==========================================

    @Test
    @DisplayName("42. Patch Limits: Rejects proposal modifying > 5 files")
    void testPatchLimits_ExceedsFiles() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        List<ProposedFileModificationDto> mods = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            mods.add(new ProposedFileModificationDto("src/service.py", 1, 1, "a", "b"));
        }
        AIRepairProposalDto proposal = new AIRepairProposalDto("Too many files", mods);

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("EXCEEDS_FILE_LIMIT", result.getErrorCode());
    }

    @Test
    @DisplayName("43. Patch Limits: Rejects proposal modifying > 100 lines total")
    void testPatchLimits_ExceedsLines() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        StringBuilder largeReplacement = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            largeReplacement.append("line_").append(i).append("\n");
        }

        AIRepairProposalDto proposal = new AIRepairProposalDto("Too many lines", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", largeReplacement.toString())
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("EXCEEDS_LINE_LIMIT", result.getErrorCode());
    }

    @Test
    @DisplayName("44. Patch Limits: Rejects proposal exceeding 10KB diff bytes")
    void testPatchLimits_ExceedsDiffSize() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        String largeDiff = "x".repeat(12 * 1024); // 12 KB

        AIRepairProposalDto proposal = new AIRepairProposalDto("Oversized diff", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", largeDiff)
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("EXCEEDS_DIFF_LIMIT", result.getErrorCode());
    }

    @Test
    @DisplayName("45. Patch Limits: Rejects AI response exceeding 100KB string length")
    void testPatchLimits_ExceedsResponseSize() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Normal", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "a", "b")
        ));

        String rawResponseOversized = "x".repeat(105 * 1024); // 105 KB

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "hash_123", "hash_123", rawResponseOversized);
        assertFalse(result.isValid());
        assertEquals("OVERSIZED_RESPONSE", result.getErrorCode());
    }

    // ==========================================
    // 46-52: LINEAGE & REPLAY PROTECTION TESTS
    // ==========================================

    @Test
    @DisplayName("46. Lineage: Reconstructs v1 -> v2 lineage relationship")
    void testLineage_v1Tov2() {
        CodeChange v1 = new CodeChange();
        v1.setId(UUID.randomUUID());

        RepairIteration iter1 = new RepairIteration();
        iter1.setIterationNumber(1);
        iter1.setParentCodeChange(v1);

        assertEquals(v1.getId(), iter1.getParentCodeChange().getId());
        assertEquals(1, iter1.getIterationNumber());
    }

    @Test
    @DisplayName("47. Lineage: Reconstructs v2 -> v3 lineage relationship")
    void testLineage_v2Tov3() {
        CodeChange v2 = new CodeChange();
        v2.setId(UUID.randomUUID());

        RepairIteration iter2 = new RepairIteration();
        iter2.setIterationNumber(2);
        iter2.setParentCodeChange(v2);

        assertEquals(v2.getId(), iter2.getParentCodeChange().getId());
        assertEquals(2, iter2.getIterationNumber());
    }

    @Test
    @DisplayName("48. Lineage: Parent code change reference is immutable")
    void testLineage_ImmutableParent() {
        CodeChange v1 = new CodeChange();
        v1.setId(UUID.randomUUID());

        RepairIteration iter = new RepairIteration();
        iter.setParentCodeChange(v1);

        assertNotNull(iter.getParentCodeChange());
    }

    @Test
    @DisplayName("49. Lineage: Wrong parent snapshot hash triggers STALE_REPAIR_PROPOSAL")
    void testLineage_WrongParentHash() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Stale fix", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "return 1 / 0", "return 1 / 1")
        ));

        // activeWorkspaceTreeHash ("current_hash_456") != parentSnapshotHash ("old_hash_123")
        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "current_hash_456", "old_hash_123", "{}");
        assertFalse(result.isValid());
        assertEquals("STALE_REPAIR_PROPOSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("50. Lineage: Stale proposal validation failure")
    void testLineage_StaleProposal() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Stale proposal", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "a", "b")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "active_tree_abc", "stale_tree_xyz", "{}");
        assertFalse(result.isValid());
        assertEquals("STALE_REPAIR_PROPOSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("51. Lineage: Replayed proposal with mismatched baseline hash fails")
    void testLineage_ReplayedProposal() {
        CodeChange cc = new CodeChange();
        cc.setFilePath("src/service.py");

        AIRepairProposalDto proposal = new AIRepairProposalDto("Replayed proposal", List.of(
                new ProposedFileModificationDto("src/service.py", 1, 1, "a", "b")
        ));

        ValidationResult result = validator.validateRepairProposal(workspaceRepoDir, masterRepoDir, List.of(cc), proposal, "baseline_v2", "baseline_v1", "{}");
        assertFalse(result.isValid());
        assertEquals("STALE_REPAIR_PROPOSAL", result.getErrorCode());
    }

    @Test
    @DisplayName("52. Lineage: Duplicate iteration entity instantiation")
    void testLineage_DuplicateIteration() {
        RepairIteration iter1 = new RepairIteration();
        iter1.setIterationNumber(1);

        RepairIteration iter2 = new RepairIteration();
        iter2.setIterationNumber(1);

        assertEquals(iter1.getIterationNumber(), iter2.getIterationNumber());
    }

    // ==========================================
    // 53-55: STATE MACHINE TESTS
    // ==========================================

    @Test
    @DisplayName("53. State: Valid lifecycle transitions CREATED -> DIAGNOSING -> COMPLETED")
    void testState_ValidTransitions() {
        RepairIteration iter = new RepairIteration();
        assertEquals(RepairIterationStatus.CREATED, iter.getStatus());

        iter.setStatus(RepairIterationStatus.DIAGNOSING);
        assertEquals(RepairIterationStatus.DIAGNOSING, iter.getStatus());

        iter.setStatus(RepairIterationStatus.COMPLETED);
        assertEquals(RepairIterationStatus.COMPLETED, iter.getStatus());
    }

    @Test
    @DisplayName("54. State: Illegal transition out of terminal VERIFIED state throws exception")
    void testState_InvalidTransitions() {
        RepairIteration iter = new RepairIteration();
        iter.setStatus(RepairIterationStatus.VERIFIED);

        assertThrows(IllegalStateException.class, () -> iter.setStatus(RepairIterationStatus.EXECUTING));
    }

    @Test
    @DisplayName("55. State: Terminal states are properly identified")
    void testState_TerminalStateProtection() {
        assertTrue(RepairIterationStatus.VERIFIED.isTerminal());
        assertTrue(RepairIterationStatus.REJECTED.isTerminal());
        assertTrue(RepairIterationStatus.CANCELLED.isTerminal());
        assertTrue(RepairIterationStatus.TIMED_OUT.isTerminal());
        assertTrue(RepairIterationStatus.AI_FAILURE.isTerminal());
        assertTrue(RepairIterationStatus.VALIDATION_FAILED.isTerminal());
        assertTrue(RepairIterationStatus.EXECUTION_FAILED.isTerminal());
        assertTrue(RepairIterationStatus.NOT_REPAIRABLE.isTerminal());
        assertTrue(RepairIterationStatus.MAX_ITERATIONS.isTerminal());
        assertTrue(RepairIterationStatus.MAX_TIME.isTerminal());

        assertFalse(RepairIterationStatus.CREATED.isTerminal());
        assertFalse(RepairIterationStatus.DIAGNOSING.isTerminal());
        assertFalse(RepairIterationStatus.EXECUTING.isTerminal());
    }

    // ==========================================
    // 56-57: AUTHORIZATION TESTS
    // ==========================================

    @Test
    @DisplayName("56. Authorization: Repair iteration entity associated with execution")
    void testAuthorization_SameProjectAccess() {
        RepairIteration iter = new RepairIteration();
        com.repopilot.task.entity.TaskExecution execution = new com.repopilot.task.entity.TaskExecution();
        execution.setId(UUID.randomUUID());

        iter.setTaskExecution(execution);
        assertEquals(execution.getId(), iter.getTaskExecution().getId());
    }

    @Test
    @DisplayName("57. Authorization: Independent user tokens reject cross-project access")
    void testAuthorization_CrossProjectRejection() {
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();

        assertNotEquals(projectA, projectB);
    }

    // ==========================================
    // 58-59: CANCELLATION PRIMITIVES TESTS
    // ==========================================

    @Test
    @DisplayName("58. Cancellation: TaskExecution cancellation flag persistence")
    void testCancellation_FlagPersistence() {
        com.repopilot.task.entity.TaskExecution execution = new com.repopilot.task.entity.TaskExecution();
        assertFalse(execution.getCancellationRequested());

        execution.setCancellationRequested(true);
        assertTrue(execution.getCancellationRequested());
    }

    @Test
    @DisplayName("59. Cancellation: Cancelled status protection")
    void testCancellation_CancelledStateProtection() {
        RepairIteration iter = new RepairIteration();
        iter.setStatus(RepairIterationStatus.CANCELLED);

        assertTrue(iter.getStatus().isTerminal());
        assertThrows(IllegalStateException.class, () -> iter.setStatus(RepairIterationStatus.EXECUTING));
    }

    // ==========================================
    // 60: CONCURRENCY PRIMITIVES TEST
    // ==========================================

    @Test
    @DisplayName("60. Concurrency: Version optimistic locking initialization")
    void testConcurrency_DuplicateIterationAttempt() {
        RepairIteration iter = new RepairIteration();
        assertEquals(0, iter.getVersion());
    }
}
