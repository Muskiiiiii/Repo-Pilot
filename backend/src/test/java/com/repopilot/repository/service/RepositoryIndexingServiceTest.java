package com.repopilot.repository.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.dto.FileContentDto;
import com.repopilot.github.dto.FileTreeItemDto;
import com.repopilot.github.security.TokenEncryptionService;
import com.repopilot.github.service.GithubApiService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.chunker.SlidingWindowCodeChunker;
import com.repopilot.repository.dto.RepositoryIndexProgressDto;
import com.repopilot.repository.entity.*;
import com.repopilot.repository.repository.*;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepositoryIndexingServiceTest {

    @Mock private RepositoryRepository repositoryRepository;
    @Mock private RepositoryBranchRepository repositoryBranchRepository;
    @Mock private RepositoryFileRepository repositoryFileRepository;
    @Mock private CodeChunkRepository codeChunkRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private ProjectAuthorizationService authorizationService;
    @Mock private GithubApiService githubApiService;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private AiServiceClient aiServiceClient;
    @Mock private AuditLogService auditLogService;

    private FileFilterService fileFilterService;
    private CodeChunkerService codeChunkerService;
    private RepositoryIndexingService repositoryIndexingService;

    private User testUser;
    private Project testProject;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        SecretDetectionService secretDetectionService = new SecretDetectionService();
        fileFilterService = new FileFilterService(secretDetectionService);
        SlidingWindowCodeChunker slidingWindowChunker = new SlidingWindowCodeChunker();
        codeChunkerService = new CodeChunkerService(List.of(slidingWindowChunker), slidingWindowChunker, secretDetectionService);

        repositoryIndexingService = new RepositoryIndexingServiceImpl(
                repositoryRepository, repositoryBranchRepository, repositoryFileRepository,
                codeChunkRepository, embeddingRepository, authorizationService,
                githubApiService, tokenEncryptionService, fileFilterService,
                codeChunkerService, aiServiceClient, auditLogService, new ObjectMapper()
        );

        ReflectionTestUtils.setField(repositoryIndexingService, "maxFilesPerRepo", 1000);
        ReflectionTestUtils.setField(repositoryIndexingService, "maxSizeBytes", 1048576L);
        ReflectionTestUtils.setField(repositoryIndexingService, "batchSize", 32);

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setGithubAccessToken("encrypted_token");

        testProject = new Project();
        testProject.setId(UUID.randomUUID());

        testRepo = new Repository();
        testRepo.setId(UUID.randomUUID());
        testRepo.setProject(testProject);
        testRepo.setOwnerName("octocat");
        testRepo.setRepoName("Hello-World");
        testRepo.setDefaultBranch("main");
        testRepo.setIndexStatus(RepositoryIndexStatus.NOT_INDEXED);
    }

    @Test
    void triggerIndexing_unauthorized_throwsForbidden() {
        UUID otherProjectId = UUID.randomUUID();
        when(authorizationService.getAuthorizedProject(eq(otherProjectId), eq(testUser)))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Access denied"));

        assertThrows(ApiException.class, () ->
                repositoryIndexingService.triggerIndexing(otherProjectId, testRepo.getId(), testUser, "main"));
    }

    @Test
    void executeIndexingProcess_exceedsFileLimit_rejectsAndMarksFailed() {
        ReflectionTestUtils.setField(repositoryIndexingService, "maxFilesPerRepo", 2);

        lenient().when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        lenient().when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));
        lenient().when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        List<FileTreeItemDto> items = List.of(
                new FileTreeItemDto("src/A.java", "blob", 100L, "sha1"),
                new FileTreeItemDto("src/B.java", "blob", 100L, "sha2"),
                new FileTreeItemDto("src/C.java", "blob", 100L, "sha3")
        );

        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        assertThrows(ApiException.class, () ->
                repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key"));

        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
        verify(repositoryRepository, atLeastOnce()).save(testRepo);
    }

    @Test
    void executeIndexingProcess_incrementalIndexing_cleansDeletedFiles() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        // DB currently has FileA and DeletedFileB
        RepositoryFile fileA = new RepositoryFile();
        fileA.setId(UUID.randomUUID());
        fileA.setPath("src/App.java");
        fileA.setContentHash("hashA");
        fileA.setIsBinary(false);

        RepositoryFile deletedFileB = new RepositoryFile();
        deletedFileB.setId(UUID.randomUUID());
        deletedFileB.setPath("src/Old.java");
        deletedFileB.setContentHash("hashB");
        deletedFileB.setIsBinary(false);

        CodeChunk chunkA = new CodeChunk();
        chunkA.setId(UUID.randomUUID());
        chunkA.setFile(fileA);

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(fileA, deletedFileB));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(List.of(chunkA));
        when(codeChunkRepository.findByFileIdWithFile(eq(deletedFileB.getId()))).thenReturn(Collections.emptyList());
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(Set.of(chunkA.getId()));

        // GitHub tree only contains App.java (Old.java was deleted)
        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/App.java", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto();
        fileContent.setContent("class App {}");
        lenient().when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/App.java"))).thenReturn(fileContent);

        try (var mockedStatic = mockStatic(CodeChunkerService.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> CodeChunkerService.computeSha256("class App {}")).thenReturn("hashA");

            repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");
        }

        // DeletedFileB should be deleted from DB
        verify(repositoryFileRepository).delete(eq(deletedFileB));
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_extensionlessBinaryFile_markedBinaryWithoutChunks() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(
                new FileTreeItemDto("solutions/cpp/airlinemanagementsystem/airline_system", "blob", 155456L, "sha1")
        );
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto();
        fileContent.setPath("solutions/cpp/airlinemanagementsystem/airline_system");
        fileContent.setContent("ELF\u0000\u0002\u0001\u0001");
        fileContent.setIsBinary(true);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(fileContent);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> {
            RepositoryFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        verify(repositoryFileRepository).save(argThat(RepositoryFile::getIsBinary));
        verify(codeChunkRepository, never()).save(any());
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_githubBinaryFlag_skipsChunking() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("bin/app", "blob", 500L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto();
        fileContent.setPath("bin/app");
        fileContent.setContent("data");
        fileContent.setIsBinary(true);
        lenient().when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(fileContent);

        lenient().when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> {
            RepositoryFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        verify(codeChunkRepository, never()).save(any());
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_nulContentDefense_marksBinaryAndSkipsChunking() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/bad_file.txt", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto();
        fileContent.setPath("src/bad_file.txt");
        fileContent.setContent("Text with NUL \u0000 byte");
        fileContent.setIsBinary(false); // Flag false, but content contains NUL
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(fileContent);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> {
            RepositoryFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        verify(repositoryFileRepository).save(argThat(RepositoryFile::getIsBinary));
        verify(codeChunkRepository, never()).save(any());
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_mixedRepository_indexesNormalFilesAndSkipsBinary() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(
                new FileTreeItemDto("src/FileA.java", "blob", 100L, "sha1"),
                new FileTreeItemDto("solutions/cpp/airline_system", "blob", 150000L, "sha2"),
                new FileTreeItemDto("src/FileB.java", "blob", 100L, "sha3")
        );
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto contentA = new FileContentDto();
        contentA.setPath("src/FileA.java");
        contentA.setContent("public class FileA {}");
        contentA.setIsBinary(false);

        FileContentDto contentBinary = new FileContentDto();
        contentBinary.setPath("solutions/cpp/airline_system");
        contentBinary.setContent("\u0000binary_data");
        contentBinary.setIsBinary(true);

        FileContentDto contentB = new FileContentDto();
        contentB.setPath("src/FileB.java");
        contentB.setContent("public class FileB {}");
        contentB.setIsBinary(false);

        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/FileA.java"))).thenReturn(contentA);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("solutions/cpp/airline_system"))).thenReturn(contentBinary);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/FileB.java"))).thenReturn(contentB);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> {
            RepositoryFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });

        when(codeChunkRepository.save(any(CodeChunk.class))).thenAnswer(inv -> {
            CodeChunk c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });

        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1, 0.2]", "[0.3, 0.4]"));

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
        verify(codeChunkRepository, atLeast(2)).save(any(CodeChunk.class));
        verify(embeddingRepository, atLeast(2)).save(any(Embedding.class));
    }

    @Test
    void executeIndexingProcess_auditErrorHandling_handlesMultilineException() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/FileA.java", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto contentA = new FileContentDto();
        contentA.setPath("src/FileA.java");
        contentA.setContent("public class FileA {}");
        contentA.setIsBinary(false);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/FileA.java"))).thenReturn(contentA);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> {
            RepositoryFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });

        // Simulate database exception during chunk save with multiline error
        when(codeChunkRepository.save(any(CodeChunk.class))).thenThrow(
                new RuntimeException("ERROR: invalid byte sequence for encoding \"UTF8\": 0x00\n  Where: unnamed portal parameter $3")
        );

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
        verify(auditLogService).logAction(eq(testUser), eq(testProject), any(), eq("REPOSITORY_INDEX_FAILED"), eq("REPOSITORY"), eq(testRepo.getId()), contains("invalid byte sequence"));
    }

    @Test
    void executeIndexingProcess_retryIncompleteFile_replacesIncompleteChunks() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        // FileA exists in DB but has 0 chunks (incomplete from failed prior run)
        RepositoryFile incompleteFileA = new RepositoryFile();
        incompleteFileA.setId(UUID.randomUUID());
        incompleteFileA.setPath("src/App.java");
        incompleteFileA.setContentHash("hashA");
        incompleteFileA.setIsBinary(false);

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(incompleteFileA));
        when(codeChunkRepository.findByFileIdWithFile(eq(incompleteFileA.getId()))).thenReturn(Collections.emptyList());

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/App.java", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto();
        fileContent.setPath("src/App.java");
        fileContent.setContent("class App {}");
        fileContent.setIsBinary(false);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/App.java"))).thenReturn(fileContent);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codeChunkRepository.save(any(CodeChunk.class))).thenAnswer(inv -> {
            CodeChunk c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1, 0.2]"));

        try (var mockedStatic = mockStatic(CodeChunkerService.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> CodeChunkerService.computeSha256("class App {}")).thenReturn("hashA");

            repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");
        }

        verify(codeChunkRepository).save(any(CodeChunk.class));
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_specialCharacterPaths_handledSafely() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("solutions/csharp/c#.csproj", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto();
        fileContent.setPath("solutions/csharp/c#.csproj");
        fileContent.setContent("<Project Sdk=\"Microsoft.NET.Sdk\"></Project>");
        fileContent.setIsBinary(false);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("solutions/csharp/c#.csproj"))).thenReturn(fileContent);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codeChunkRepository.save(any(CodeChunk.class))).thenAnswer(inv -> {
            CodeChunk c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1, 0.2]"));

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
        verify(codeChunkRepository).save(any(CodeChunk.class));
    }

    @Test
    void executeIndexingProcess_fileLevel404_skipsFileAndCompletesIndexing() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(
                new FileTreeItemDto("src/FileA.java", "blob", 100L, "sha1"),
                new FileTreeItemDto("src/MissingFile.java", "blob", 100L, "sha2"),
                new FileTreeItemDto("src/FileB.java", "blob", 100L, "sha3")
        );
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto contentA = new FileContentDto("src/FileA.java", "main", "public class FileA {}", "utf-8", 100L, false);
        FileContentDto contentB = new FileContentDto("src/FileB.java", "main", "public class FileB {}", "utf-8", 100L, false);

        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/FileA.java"))).thenReturn(contentA);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/MissingFile.java")))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "GitHub repository or file path not found."));
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/FileB.java"))).thenReturn(contentB);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codeChunkRepository.save(any(CodeChunk.class))).thenAnswer(inv -> {
            CodeChunk c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1, 0.2]", "[0.3, 0.4]"));

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
        verify(codeChunkRepository, atLeast(2)).save(any(CodeChunk.class));
    }

    @Test
    void executeIndexingProcess_repositoryLevel401_failsIndexing() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "GitHub token is invalid or expired."));

        assertThrows(ApiException.class, () ->
                repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key")
        );

        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_unchangedFileMissingEmbeddings_queuesExistingChunksForEmbedding() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        RepositoryFile fileA = new RepositoryFile();
        fileA.setId(UUID.randomUUID());
        fileA.setPath("src/App.java");
        fileA.setContentHash("hashA");
        fileA.setIsBinary(false);

        CodeChunk existingChunk = new CodeChunk();
        existingChunk.setId(UUID.randomUUID());
        existingChunk.setFile(fileA);
        existingChunk.setSymbolName("App");
        existingChunk.setContent("class App {}");

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(fileA));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(List.of(existingChunk));
        Set<UUID> embeddedIds = new HashSet<>();
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(embeddedIds);
        lenient().when(embeddingRepository.save(any(Embedding.class))).thenAnswer(inv -> {
            Embedding emb = inv.getArgument(0);
            if (emb.getChunk() != null && emb.getChunk().getId() != null) {
                embeddedIds.add(emb.getChunk().getId());
            }
            return emb;
        });

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/App.java", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto();
        fileContent.setPath("src/App.java");
        fileContent.setContent("class App {}");
        fileContent.setIsBinary(false);
        lenient().when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/App.java"))).thenReturn(fileContent);

        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1, 0.2]"));

        try (var mockedStatic = mockStatic(CodeChunkerService.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> CodeChunkerService.computeSha256("class App {}")).thenReturn("hashA");

            repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");
        }

        // Chunks were reused (not saved again)
        verify(codeChunkRepository, never()).save(any());
        // Embeddings were generated for the existing chunk
        verify(embeddingRepository).save(argThat(e -> e.getChunk().getId().equals(existingChunk.getId())));
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_unchangedFileFullyEmbedded_skipsFile() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        RepositoryFile fileA = new RepositoryFile();
        fileA.setId(UUID.randomUUID());
        fileA.setPath("src/App.java");
        fileA.setContentHash("hashA");
        fileA.setIsBinary(false);

        CodeChunk existingChunk = new CodeChunk();
        existingChunk.setId(UUID.randomUUID());
        existingChunk.setFile(fileA);

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(fileA));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(List.of(existingChunk));
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(Set.of(existingChunk.getId()));

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/App.java", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto();
        fileContent.setPath("src/App.java");
        fileContent.setContent("class App {}");
        fileContent.setIsBinary(false);
        lenient().when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/App.java"))).thenReturn(fileContent);

        try (var mockedStatic = mockStatic(CodeChunkerService.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> CodeChunkerService.computeSha256("class App {}")).thenReturn("hashA");

            repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");
        }

        verify(aiServiceClient, never()).generateEmbeddings(anyList());
        verify(embeddingRepository, never()).save(any());
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_embeddingApiFailure_repositoryReachesFailedStatus() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/App.java", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        FileContentDto fileContent = new FileContentDto("src/App.java", "main", "class App {}", "utf-8", 100L, false);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/App.java"))).thenReturn(fileContent);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codeChunkRepository.save(any(CodeChunk.class))).thenAnswer(inv -> {
            CodeChunk c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });

        // Simulate 401 failure during AI embedding generation
        when(aiServiceClient.generateEmbeddings(anyList())).thenThrow(
                new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate embeddings: 401 Unauthorized")
        );

        assertThrows(ApiException.class, () ->
                repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key")
        );

        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_primaryRateLimit_failsWithResetMessageAndPreservesState() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        List<FileTreeItemDto> items = List.of(
                new FileTreeItemDto("src/File1.java", "blob", 100L, "sha1"),
                new FileTreeItemDto("src/File2.java", "blob", 100L, "sha2")
        );
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        // First file fetches cleanly, second triggers primary rate limit exception
        FileContentDto file1Content = new FileContentDto("src/File1.java", "main", "class File1 {}", "utf-8", 100L, false);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/File1.java"))).thenReturn(file1Content);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/File2.java")))
                .thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "GITHUB_RATE_LIMIT_EXHAUSTED", "GitHub rate limit exhausted (0/5000 remaining). Resets at 2026-09-13T04:00:00Z (in 45 minutes). Please retry after reset time."));

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> {
            RepositoryFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(UUID.randomUUID());
            return f;
        });

        ApiException ex = assertThrows(ApiException.class, () ->
                repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key")
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertTrue(ex.getMessage().contains("rate limit exhausted"));
        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_resumeAfterRateLimit_processesRemainingFilesOnly() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        // File1 was already saved in DB during prior run before rate limit
        RepositoryFile existingFile1 = new RepositoryFile();
        existingFile1.setId(UUID.randomUUID());
        existingFile1.setPath("src/File1.java");
        existingFile1.setContentHash("sha1");
        existingFile1.setIsBinary(false);

        CodeChunk existingChunk1 = new CodeChunk();
        existingChunk1.setId(UUID.randomUUID());
        existingChunk1.setFile(existingFile1);
        existingChunk1.setContent("class File1 {}");

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(existingFile1));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(List.of(existingChunk1));
        Set<UUID> embeddedIds = new HashSet<>();
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(embeddedIds);
        lenient().when(embeddingRepository.save(any(Embedding.class))).thenAnswer(inv -> {
            Embedding emb = inv.getArgument(0);
            if (emb.getChunk() != null && emb.getChunk().getId() != null) {
                embeddedIds.add(emb.getChunk().getId());
            }
            return emb;
        });

        // GitHub tree contains File1 (sha1) and File2 (sha2)
        List<FileTreeItemDto> items = List.of(
                new FileTreeItemDto("src/File1.java", "blob", 100L, "sha1"),
                new FileTreeItemDto("src/File2.java", "blob", 100L, "sha2")
        );
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        // ONLY File2 needs fetching. File1 (matching sha1) is skipped!
        FileContentDto file2Content = new FileContentDto("src/File2.java", "main", "class File2 {}", "utf-8", 100L, false);
        when(githubApiService.fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/File2.java"))).thenReturn(file2Content);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codeChunkRepository.save(any(CodeChunk.class))).thenAnswer(inv -> {
            CodeChunk c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1, 0.2]", "[0.3, 0.4]"));

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        // Verify File1 content was NEVER fetched from GitHub
        verify(githubApiService, never()).fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/File1.java"));
        // Verify ONLY File2 content was fetched
        verify(githubApiService, times(1)).fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("src/File2.java"));
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_emptyFileWithZeroChunks_classifiedAsComplete() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        // Existing empty file (__init__.py) with 0 bytes and 0 chunks
        RepositoryFile emptyInitPy = new RepositoryFile();
        emptyInitPy.setId(UUID.randomUUID());
        emptyInitPy.setPath("python/pkg/__init__.py");
        emptyInitPy.setFileSize(0L);
        emptyInitPy.setContentHash("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
        emptyInitPy.setIsBinary(false);

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(emptyInitPy));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(Collections.emptyList());
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(Collections.emptySet());

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("python/pkg/__init__.py", "blob", 0L, "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        // Legitimate empty file (0 bytes, 0 chunks) was classified as COMPLETE and NEVER fetched over HTTP
        verify(githubApiService, never()).fetchFileContent(anyString(), anyString(), anyString(), anyString(), eq("python/pkg/__init__.py"));
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_embeddingAwareRetry_queuesOnlyMissingEmbeddingsAndPreventsDuplicates() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        RepositoryFile existingFile = new RepositoryFile();
        existingFile.setId(UUID.randomUUID());
        existingFile.setPath("src/App.java");
        existingFile.setGithubBlobSha("sha_app");
        existingFile.setContentHash("hash_app");
        existingFile.setIsBinary(false);

        CodeChunk chunk1 = new CodeChunk();
        chunk1.setId(UUID.randomUUID());
        chunk1.setFile(existingFile);
        chunk1.setSymbolName("method1");
        chunk1.setContent("void method1() {}");

        CodeChunk chunk2 = new CodeChunk();
        chunk2.setId(UUID.randomUUID());
        chunk2.setFile(existingFile);
        chunk2.setSymbolName("method2");
        chunk2.setContent("void method2() {}");

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(existingFile));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(List.of(chunk1, chunk2));

        Set<UUID> embeddedIds = new HashSet<>(Set.of(chunk1.getId()));
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(embeddedIds);
        lenient().when(embeddingRepository.save(any(Embedding.class))).thenAnswer(inv -> {
            Embedding emb = inv.getArgument(0);
            if (emb.getChunk() != null && emb.getChunk().getId() != null) {
                embeddedIds.add(emb.getChunk().getId());
            }
            return emb;
        });

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/App.java", "blob", 100L, "sha_app"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);
        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1, 0.2]"));

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        // Verify content fetch was skipped for unchanged file
        verify(githubApiService, never()).fetchFileContent(anyString(), anyString(), anyString(), anyString(), anyString());

        // Verify generateEmbeddings was called ONLY for chunk2 (1 text in list), preventing duplicate embeddings for chunk1
        verify(aiServiceClient, times(1)).generateEmbeddings(argThat(list -> list.size() == 1 && list.get(0).contains("method2")));
        verify(embeddingRepository, times(1)).save(argThat(emb -> emb.getChunk().getId().equals(chunk2.getId())));
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_partialProgressPreservation_successfulBatchesPersistedBeforeLaterBatchFailure() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        RepositoryFile existingFile = new RepositoryFile();
        existingFile.setId(UUID.randomUUID());
        existingFile.setPath("src/Large.java");
        existingFile.setGithubBlobSha("sha_large");
        existingFile.setContentHash("hash_large");
        existingFile.setIsBinary(false);

        // Create 2 chunks (with batchSize=1 to force 2 separate batches)
        ReflectionTestUtils.setField(repositoryIndexingService, "batchSize", 1);

        CodeChunk chunk1 = new CodeChunk();
        chunk1.setId(UUID.randomUUID());
        chunk1.setFile(existingFile);
        chunk1.setSymbolName("part1");
        chunk1.setContent("part1");

        CodeChunk chunk2 = new CodeChunk();
        chunk2.setId(UUID.randomUUID());
        chunk2.setFile(existingFile);
        chunk2.setSymbolName("part2");
        chunk2.setContent("part2");

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(existingFile));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(List.of(chunk1, chunk2));
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(Collections.emptySet());

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/Large.java", "blob", 200L, "sha_large"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        // Batch 1 returns vector, Batch 2 throws exception
        when(aiServiceClient.generateEmbeddings(anyList()))
                .thenReturn(List.of("[0.1, 0.2]"))
                .thenThrow(new RuntimeException("Voyage AI API HTTP error 429"));

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        // Verify Batch 1 embedding WAS saved before Batch 2 failed
        verify(embeddingRepository, times(1)).save(argThat(emb -> emb.getChunk().getId().equals(chunk1.getId())));
        // Verify repository status marked FAILED cleanly
        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
    }

    @Test
    void getIndexingStatus_returnsDatabaseStatusWhenNoRedisCache() {
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        var status = repositoryIndexingService.getIndexingStatus(testProject.getId(), testRepo.getId(), testUser);

        assertNotNull(status);
        assertEquals(RepositoryIndexStatus.NOT_INDEXED, status.getStatus());
        assertEquals(0.0, status.getProgressPercentage());
    }

    @Test
    void getIndexingStatus_reconcilesStaleDbStatusWhenLockMissing() {
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXING);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        var status = repositoryIndexingService.getIndexingStatus(testProject.getId(), testRepo.getId(), testUser);

        assertNotNull(status);
        assertEquals(RepositoryIndexStatus.FAILED, status.getStatus());
        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
        verify(repositoryRepository).save(eq(testRepo));
    }

    @Test
    void stopIndexing_whenCalled_returnsCancelledStatusAndHealthStopped() {
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXING);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        var status = repositoryIndexingService.stopIndexing(testProject.getId(), testRepo.getId(), testUser);

        assertNotNull(status);
        assertEquals(RepositoryIndexStatus.CANCELLED, status.getStatus());
        assertEquals("STOPPED", status.getHealth());
        assertFalse(status.isCanStop());
        assertTrue(status.isCanResume());
    }

    @Test
    void resumeIndexing_whenCalled_resumesIndexingProcess() {
        testRepo.setIndexStatus(RepositoryIndexStatus.CANCELLED);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        var status = repositoryIndexingService.resumeIndexing(testProject.getId(), testRepo.getId(), testUser);

        assertNotNull(status);
        assertEquals(RepositoryIndexStatus.INDEXING, status.getStatus());
        assertTrue(status.isCanStop());
        assertFalse(status.isCanResume());
    }

    @Test
    void testA_lockAcquisition_createsUniqueOwnerToken() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(org.springframework.data.redis.core.ValueOperations.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(mockRedis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        String token1 = UUID.randomUUID().toString();
        String token2 = UUID.randomUUID().toString();
        assertNotEquals(token1, token2);

        boolean acquired = service.acquireRedisLock("indexing:lock:123", token1, Duration.ofMinutes(15));
        assertTrue(acquired);
        verify(ops).setIfAbsent(eq("indexing:lock:123"), eq(token1), eq(Duration.ofMinutes(15)));
    }

    @Test
    void testB_secondWorker_cannotAcquireExistingLock() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(org.springframework.data.redis.core.ValueOperations.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(mockRedis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq("indexing:lock:123"), anyString(), any(Duration.class))).thenReturn(false);

        boolean acquired = service.acquireRedisLock("indexing:lock:123", "worker2", Duration.ofMinutes(15));
        assertFalse(acquired);
    }

    @Test
    void testC_heartbeat_extendsOwnerTtl() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(mockRedis.execute(any(), anyList(), anyString(), anyString())).thenReturn(1L);

        boolean renewed = service.renewRedisLock("indexing:lock:123", "worker1", Duration.ofMinutes(15));
        assertTrue(renewed);
    }

    @Test
    void testD_heartbeat_cannotExtendAnotherWorkersLock() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(mockRedis.execute(any(), anyList(), anyString(), anyString())).thenReturn(0L);

        boolean renewed = service.renewRedisLock("indexing:lock:123", "worker2", Duration.ofMinutes(15));
        assertFalse(renewed);
    }

    @Test
    void testE_oldWorker_cannotDeleteNewWorkersLock() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(mockRedis.execute(any(), anyList(), anyString())).thenReturn(0L);

        boolean released = service.releaseRedisLock("indexing:lock:123", "oldWorkerToken");
        assertFalse(released);
    }

    @Test
    void testF_heartbeat_stopsOnSuccessfulCompletion() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(org.springframework.data.redis.core.ValueOperations.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(mockRedis.opsForValue()).thenReturn(ops);
        lenient().when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        lenient().when(tokenEncryptionService.decrypt(anyString())).thenReturn("token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        lenient().when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(Collections.emptyList());

        service.executeIndexingProcess(testUser, testRepo, "main", "indexing:lock:" + testRepo.getId(), "worker1");

        @SuppressWarnings("unchecked")
        Map<UUID, java.util.concurrent.ScheduledFuture<?>> tasks = (Map<UUID, java.util.concurrent.ScheduledFuture<?>>) ReflectionTestUtils.getField(service, "heartbeatTasks");
        assertNotNull(tasks);
        assertFalse(tasks.containsKey(testRepo.getId()));
    }

    @Test
    void testG_heartbeat_stopsOnCancellation() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(org.springframework.data.redis.core.ValueOperations.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(mockRedis.opsForValue()).thenReturn(ops);
        lenient().when(mockRedis.hasKey("indexing:cancel:" + testRepo.getId())).thenReturn(true);
        lenient().when(tokenEncryptionService.decrypt(anyString())).thenReturn("token");

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/A.java", "blob", 100L, "sha1"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        service.executeIndexingProcess(testUser, testRepo, "main", "indexing:lock:" + testRepo.getId(), "worker1");

        @SuppressWarnings("unchecked")
        Map<UUID, java.util.concurrent.ScheduledFuture<?>> tasks = (Map<UUID, java.util.concurrent.ScheduledFuture<?>>) ReflectionTestUtils.getField(service, "heartbeatTasks");
        assertNotNull(tasks);
        assertFalse(tasks.containsKey(testRepo.getId()));
    }

    @Test
    void testH_heartbeat_stopsOnFatalFailure() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(org.springframework.data.redis.core.ValueOperations.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(mockRedis.opsForValue()).thenReturn(ops);
        when(githubApiService.fetchRepositoryFileTree(any(), any(), any(), any())).thenThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Fatal failure"));

        assertThrows(ApiException.class, () ->
                service.executeIndexingProcess(testUser, testRepo, "main", "indexing:lock:" + testRepo.getId(), "worker1"));

        @SuppressWarnings("unchecked")
        Map<UUID, java.util.concurrent.ScheduledFuture<?>> tasks = (Map<UUID, java.util.concurrent.ScheduledFuture<?>>) ReflectionTestUtils.getField(service, "heartbeatTasks");
        assertNotNull(tasks);
        assertFalse(tasks.containsKey(testRepo.getId()));
    }

    @Test
    void testI_noHeartbeatExecutorThreadLeak() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        @SuppressWarnings("unchecked")
        Map<UUID, java.util.concurrent.ScheduledFuture<?>> tasks = (Map<UUID, java.util.concurrent.ScheduledFuture<?>>) ReflectionTestUtils.getField(service, "heartbeatTasks");
        assertNotNull(tasks);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void testJ_temporaryRedisLockAbsence_doesNotImmediatelyMarkHealthyIndexingJobFailed() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXING);

        @SuppressWarnings("unchecked")
        Map<UUID, String> localWorkers = (Map<UUID, String>) ReflectionTestUtils.getField(service, "activeLocalWorkers");
        assertNotNull(localWorkers);
        localWorkers.put(testRepo.getId(), "worker1");

        when(mockRedis.hasKey("indexing:lock:" + testRepo.getId())).thenReturn(false);

        var status = service.getIndexingStatus(testProject.getId(), testRepo.getId(), testUser);

        assertNotNull(status);
        assertEquals(RepositoryIndexStatus.INDEXING, status.getStatus());
        assertNotEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());

        localWorkers.remove(testRepo.getId());
    }

    @Test
    void testK_confirmedStaleWorker_eventuallyReconciledToFailed() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(org.springframework.data.redis.core.ValueOperations.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXING);
        when(mockRedis.hasKey("indexing:lock:" + testRepo.getId())).thenReturn(false);
        when(mockRedis.opsForValue()).thenReturn(ops);
        when(ops.get("indexing:progress:" + testRepo.getId())).thenReturn(null);

        var status = service.getIndexingStatus(testProject.getId(), testRepo.getId(), testUser);

        assertNotNull(status);
        assertEquals(RepositoryIndexStatus.FAILED, status.getStatus());
        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
    }

    @Test
    void testL_longRunningJob_remainsIndexingBeyond15Minutes() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXING);
        when(mockRedis.hasKey("indexing:lock:" + testRepo.getId())).thenReturn(true);

        var status = service.getIndexingStatus(testProject.getId(), testRepo.getId(), testUser);

        assertNotNull(status);
        assertEquals(RepositoryIndexStatus.INDEXING, status.getStatus());
    }

    @Test
    void testM_progressRate_cannotProduceFalse264Spikes() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        var progress = service.getIndexingStatus(testProject.getId(), testRepo.getId(), testUser);
        assertNull(progress.getRatePerMinute());
    }

    @Test
    void testN_existingConcurrencyProtection_stillWorks() {
        RepositoryIndexingServiceImpl service = (RepositoryIndexingServiceImpl) repositoryIndexingService;
        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(org.springframework.data.redis.core.ValueOperations.class);
        ReflectionTestUtils.setField(service, "redisTemplate", mockRedis);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));
        when(mockRedis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThrows(ApiException.class, () ->
                service.triggerIndexing(testProject.getId(), testRepo.getId(), testUser, "main"));
    }

    @Test
    void resumeIndexing_6799Chunks_6796ExistingEmbeddings_3MissingOnUnchangedFiles_embedsOnly3MissingChunksAndCompletes() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        RepositoryFile fileA = new RepositoryFile();
        fileA.setId(UUID.randomUUID());
        fileA.setPath("design-patterns/Javascript/Behavioral Pattern/Chain of Responsibilites/supportRequest.js");
        fileA.setGithubBlobSha("shaA");
        fileA.setContentHash("hashA");
        fileA.setIsBinary(false);

        RepositoryFile fileB = new RepositoryFile();
        fileB.setId(UUID.randomUUID());
        fileB.setPath("design-patterns/Javascript/Behavioral Pattern/Chain of Responsibilites/main.js");
        fileB.setGithubBlobSha("shaB");
        fileB.setContentHash("hashB");
        fileB.setIsBinary(false);

        RepositoryFile fileC = new RepositoryFile();
        fileC.setId(UUID.randomUUID());
        fileC.setPath("src/EmbeddedFile.java");
        fileC.setGithubBlobSha("shaC");
        fileC.setContentHash("hashC");
        fileC.setIsBinary(false);

        CodeChunk missingChunk1 = new CodeChunk();
        missingChunk1.setId(UUID.randomUUID());
        missingChunk1.setFile(fileA);
        missingChunk1.setSymbolName("supportRequest:1-60");
        missingChunk1.setContent("content1");

        CodeChunk missingChunk2 = new CodeChunk();
        missingChunk2.setId(UUID.randomUUID());
        missingChunk2.setFile(fileA);
        missingChunk2.setSymbolName("supportRequest:46-91");
        missingChunk2.setContent("content2");

        CodeChunk missingChunk3 = new CodeChunk();
        missingChunk3.setId(UUID.randomUUID());
        missingChunk3.setFile(fileB);
        missingChunk3.setSymbolName("main");
        missingChunk3.setContent("content3");

        CodeChunk embeddedChunk = new CodeChunk();
        embeddedChunk.setId(UUID.randomUUID());
        embeddedChunk.setFile(fileC);
        embeddedChunk.setSymbolName("embedded");
        embeddedChunk.setContent("embeddedContent");

        List<CodeChunk> allChunks = List.of(missingChunk1, missingChunk2, missingChunk3, embeddedChunk);

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(fileA, fileB, fileC));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(allChunks);
        when(codeChunkRepository.countByRepositoryId(eq(testRepo.getId()))).thenReturn((long) allChunks.size());

        Set<UUID> embeddedIds = new HashSet<>();
        embeddedIds.add(embeddedChunk.getId());
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(embeddedIds);
        lenient().when(embeddingRepository.save(any(Embedding.class))).thenAnswer(inv -> {
            Embedding emb = inv.getArgument(0);
            if (emb.getChunk() != null && emb.getChunk().getId() != null) {
                embeddedIds.add(emb.getChunk().getId());
            }
            return emb;
        });

        List<FileTreeItemDto> items = List.of(
                new FileTreeItemDto("design-patterns/Javascript/Behavioral Pattern/Chain of Responsibilites/supportRequest.js", "blob", 100L, "shaA"),
                new FileTreeItemDto("design-patterns/Javascript/Behavioral Pattern/Chain of Responsibilites/main.js", "blob", 100L, "shaB"),
                new FileTreeItemDto("src/EmbeddedFile.java", "blob", 100L, "shaC")
        );
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1]", "[0.2]", "[0.3]"));

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        verify(githubApiService, never()).fetchFileContent(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(aiServiceClient, times(1)).generateEmbeddings(argThat(list -> list.size() == 3));
        verify(embeddingRepository, times(3)).save(any(Embedding.class));
        verify(embeddingRepository, never()).save(argThat(emb -> emb.getChunk().getId().equals(embeddedChunk.getId())));
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_binaryFileWithNoChunks_skipsFile() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        RepositoryFile binaryFile = new RepositoryFile();
        binaryFile.setId(UUID.randomUUID());
        binaryFile.setPath("assets/logo.png");
        binaryFile.setGithubBlobSha("sha_logo");
        binaryFile.setContentHash("hash_logo");
        binaryFile.setIsBinary(true);

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(binaryFile));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(Collections.emptyList());
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(Collections.emptySet());

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("assets/logo.png", "blob", 5000L, "sha_logo"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        verify(githubApiService, never()).fetchFileContent(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(aiServiceClient, never()).generateEmbeddings(anyList());
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void executeIndexingProcess_binaryFlagWithExistingChunks_queuesUnembeddedChunks() {
        when(tokenEncryptionService.decrypt(anyString())).thenReturn("raw_github_token");

        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());
        when(repositoryBranchRepository.findByRepositoryIdAndName(any(), any())).thenReturn(Optional.of(branch));

        RepositoryFile anomalousFile = new RepositoryFile();
        anomalousFile.setId(UUID.randomUUID());
        anomalousFile.setPath("src/App.js");
        anomalousFile.setGithubBlobSha("sha_app");
        anomalousFile.setContentHash("hash_app");
        anomalousFile.setIsBinary(true); // Flagged binary, but has chunks!

        CodeChunk chunk = new CodeChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setFile(anomalousFile);
        chunk.setSymbolName("App");
        chunk.setContent("function App() {}");

        when(repositoryFileRepository.findByRepositoryIdAndBranchId(eq(testRepo.getId()), eq(branch.getId())))
                .thenReturn(List.of(anomalousFile));
        when(codeChunkRepository.findByRepositoryIdWithFile(eq(testRepo.getId()))).thenReturn(List.of(chunk));
        when(codeChunkRepository.countByRepositoryId(eq(testRepo.getId()))).thenReturn(1L);

        Set<UUID> embeddedIds = new HashSet<>();
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId()))).thenReturn(embeddedIds);
        lenient().when(embeddingRepository.save(any(Embedding.class))).thenAnswer(inv -> {
            Embedding emb = inv.getArgument(0);
            if (emb.getChunk() != null && emb.getChunk().getId() != null) {
                embeddedIds.add(emb.getChunk().getId());
            }
            return emb;
        });

        List<FileTreeItemDto> items = List.of(new FileTreeItemDto("src/App.js", "blob", 100L, "sha_app"));
        when(githubApiService.fetchRepositoryFileTree(anyString(), anyString(), anyString(), anyString())).thenReturn(items);
        when(aiServiceClient.generateEmbeddings(anyList())).thenReturn(List.of("[0.1, 0.2]"));

        repositoryIndexingService.executeIndexingProcess(testUser, testRepo, "main", "lock:key");

        verify(githubApiService, never()).fetchFileContent(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(aiServiceClient, times(1)).generateEmbeddings(argThat(list -> list.size() == 1));
        verify(embeddingRepository, times(1)).save(any(Embedding.class));
        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
    }

    @Test
    void getIndexingStatus_indexedRepository_returnsCumulativeTotalsFromDb() {
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        when(repositoryFileRepository.countByRepositoryId(eq(testRepo.getId()))).thenReturn(3278L);
        when(codeChunkRepository.countByRepositoryId(eq(testRepo.getId()))).thenReturn(6799L);
        when(embeddingRepository.countByRepositoryId(eq(testRepo.getId()))).thenReturn(6799L);

        RepositoryIndexProgressDto dto = repositoryIndexingService.getIndexingStatus(testProject.getId(), testRepo.getId(), testUser);

        assertEquals(RepositoryIndexStatus.INDEXED, dto.getStatus());
        assertEquals(3278, dto.getTotalFiles());
        assertEquals(3278, dto.getProcessedFiles());
        assertEquals(6799, dto.getTotalChunks());
        assertEquals(6799, dto.getEmbeddedChunks());
        assertEquals(0, dto.getRemainingEmbeddings());
        assertEquals(100.0, dto.getProgressPercentage());
        assertFalse(dto.isCanResume());
        assertFalse(dto.isCanStop());
        assertNull(dto.getErrorMessage());
    }

    @Test
    void getIndexingStatus_indexingSession_preservesSessionMetrics() throws Exception {
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXING);
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        RepositoryIndexProgressDto indexingSessionProgress = new RepositoryIndexProgressDto(
                testRepo.getId(), RepositoryIndexStatus.INDEXING, 100, 50, 32, 16, 16, 50.0, null, null, java.time.Instant.now().toString(), "HEALTHY", true, false, null
        );

        org.springframework.data.redis.core.StringRedisTemplate mockRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops = mock(org.springframework.data.redis.core.ValueOperations.class);
        ReflectionTestUtils.setField(repositoryIndexingService, "redisTemplate", mockRedis);
        when(mockRedis.opsForValue()).thenReturn(ops);
        when(mockRedis.hasKey("indexing:lock:" + testRepo.getId())).thenReturn(true);
        String json = new ObjectMapper().writeValueAsString(indexingSessionProgress);
        when(ops.get("indexing:progress:" + testRepo.getId())).thenReturn(json);

        RepositoryIndexProgressDto dto = repositoryIndexingService.getIndexingStatus(testProject.getId(), testRepo.getId(), testUser);

        assertEquals(RepositoryIndexStatus.INDEXING, dto.getStatus());
        assertEquals(100, dto.getTotalFiles());
        assertEquals(50, dto.getProcessedFiles());
        assertEquals(32, dto.getTotalChunks());
        assertEquals(16, dto.getEmbeddedChunks());
        assertEquals(16, dto.getRemainingEmbeddings());
        assertEquals(50.0, dto.getProgressPercentage());
    }
}
