package com.repopilot.repository.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.project.entity.Project;
import com.repopilot.repository.chunker.ChunkResult;
import com.repopilot.repository.entity.*;
import com.repopilot.repository.repository.*;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexingTransactionHelperTest {

    @Mock private RepositoryRepository repositoryRepository;
    @Mock private RepositoryBranchRepository repositoryBranchRepository;
    @Mock private RepositoryFileRepository repositoryFileRepository;
    @Mock private CodeChunkRepository codeChunkRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private AuditLogService auditLogService;

    private IndexingTransactionHelper helper;
    private User testUser;
    private Project testProject;
    private Repository testRepo;

    @BeforeEach
    void setUp() {
        helper = new IndexingTransactionHelper(
                repositoryRepository, repositoryBranchRepository, repositoryFileRepository,
                codeChunkRepository, embeddingRepository, auditLogService, new ObjectMapper()
        );

        testUser = new User();
        testUser.setId(UUID.randomUUID());

        testProject = new Project();
        testProject.setId(UUID.randomUUID());

        testRepo = new Repository();
        testRepo.setId(UUID.randomUUID());
        testRepo.setProject(testProject);
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXING);
    }

    @Test
    void removeDeletedFile_deletesChunksEmbeddingsAndFile() {
        RepositoryFile file = new RepositoryFile();
        file.setId(UUID.randomUUID());
        file.setPath("src/Old.java");

        CodeChunk chunk = new CodeChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setFile(file);

        when(codeChunkRepository.findByFileIdWithFile(eq(file.getId()))).thenReturn(List.of(chunk));

        helper.removeDeletedFile(file);

        verify(embeddingRepository).deleteByChunkIn(eq(List.of(chunk)));
        verify(codeChunkRepository).deleteByFileIn(eq(List.of(file)));
        verify(repositoryFileRepository).delete(eq(file));
    }

    @Test
    void saveFileAndChunks_atomicReplacement_cleansOldChunksAndSavesNew() {
        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());

        RepositoryFile existingFile = new RepositoryFile();
        existingFile.setId(UUID.randomUUID());
        existingFile.setPath("src/App.java");

        CodeChunk oldChunk = new CodeChunk();
        oldChunk.setId(UUID.randomUUID());
        oldChunk.setFile(existingFile);

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codeChunkRepository.findByFileIdWithFile(eq(existingFile.getId()))).thenReturn(List.of(oldChunk));
        when(codeChunkRepository.save(any(CodeChunk.class))).thenAnswer(inv -> {
            CodeChunk c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });

        ChunkResult newChunk = new ChunkResult(0, "class App {}", "App", "CLASS", 1, 5, "hashNew", false);

        SavedFileAndChunks result = helper.saveFileAndChunks(
                testRepo, branch, "src/App.java", "JAVA", 100L,
                "hashNew", "shaNew", false, existingFile, List.of(newChunk)
        );

        verify(embeddingRepository).deleteByChunkIn(eq(List.of(oldChunk)));
        verify(codeChunkRepository).deleteByFileIn(eq(List.of(existingFile)));
        verify(codeChunkRepository).save(any(CodeChunk.class));

        assertNotNull(result.getFile());
        assertEquals(1, result.getChunks().size());
    }

    @Test
    void saveFileAndChunks_binaryFile_savesFileMetadataOnly() {
        RepositoryBranch branch = new RepositoryBranch();
        branch.setId(UUID.randomUUID());

        when(repositoryFileRepository.save(any(RepositoryFile.class))).thenAnswer(inv -> inv.getArgument(0));

        SavedFileAndChunks result = helper.saveFileAndChunks(
                testRepo, branch, "solutions/cpp/airline_system", "C++", 150000L,
                "binaryHash", "shaBinary", true, null, null
        );

        verify(codeChunkRepository, never()).save(any());
        verify(embeddingRepository, never()).save(any());
        assertTrue(result.getFile().getIsBinary());
        assertTrue(result.getChunks().isEmpty());
    }

    @Test
    void getExistingChunksForFile_usesFindByFileIdWithFileAndInitializesFile() {
        UUID fileId = UUID.randomUUID();
        RepositoryFile file = new RepositoryFile();
        file.setId(fileId);
        file.setPath("src/main/java/App.java");

        CodeChunk chunk = new CodeChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setFile(file);

        when(codeChunkRepository.findByFileIdWithFile(eq(fileId))).thenReturn(List.of(chunk));

        List<CodeChunk> result = helper.getExistingChunksForFile(fileId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("src/main/java/App.java", result.get(0).getFile().getPath());
        verify(codeChunkRepository).findByFileIdWithFile(eq(fileId));
    }

    @Test
    void getExistingChunksGroupedByFile_groupsByFileId() {
        UUID repoId = UUID.randomUUID();
        RepositoryFile file1 = new RepositoryFile();
        file1.setId(UUID.randomUUID());
        file1.setPath("src/A.java");

        RepositoryFile file2 = new RepositoryFile();
        file2.setId(UUID.randomUUID());
        file2.setPath("src/B.java");

        CodeChunk c1 = new CodeChunk();
        c1.setId(UUID.randomUUID());
        c1.setFile(file1);

        CodeChunk c2 = new CodeChunk();
        c2.setId(UUID.randomUUID());
        c2.setFile(file2);

        when(codeChunkRepository.findByRepositoryIdWithFile(eq(repoId))).thenReturn(List.of(c1, c2));

        Map<UUID, List<CodeChunk>> map = helper.getExistingChunksGroupedByFile(repoId);

        assertEquals(2, map.size());
        assertTrue(map.containsKey(file1.getId()));
        assertTrue(map.containsKey(file2.getId()));
        assertEquals(1, map.get(file1.getId()).size());
        assertEquals(1, map.get(file2.getId()).size());
    }

    @Test
    void saveEmbeddingsBatch_savesEmbeddingsForBatch() {
        CodeChunk chunk = new CodeChunk();
        chunk.setId(UUID.randomUUID());

        helper.saveEmbeddingsBatch(List.of(chunk), List.of("[0.1, 0.2]"), "voyage-code-4", 1024);

        verify(embeddingRepository).save(argThat(e ->
                e.getChunk().equals(chunk) &&
                "[0.1, 0.2]".equals(e.getEmbedding()) &&
                "voyage-code-4".equals(e.getModelName()) &&
                e.getDimensions() == 1024
        ));
    }

    @Test
    void markIndexingSuccess_updatesStatusAndLogsAudit_whenAllChunksEmbedded() {
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));
        when(codeChunkRepository.countByRepositoryId(eq(testRepo.getId()))).thenReturn(5L);
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID c3 = UUID.randomUUID();
        UUID c4 = UUID.randomUUID();
        UUID c5 = UUID.randomUUID();
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId())))
                .thenReturn(Set.of(c1, c2, c3, c4, c5));

        helper.markIndexingSuccess(testRepo, 10, 25, testUser);

        assertEquals(RepositoryIndexStatus.INDEXED, testRepo.getIndexStatus());
        assertNotNull(testRepo.getLastIndexedAt());
        verify(repositoryRepository).save(eq(testRepo));
        verify(auditLogService).logAction(eq(testUser), eq(testProject), any(), eq("REPOSITORY_INDEXED"), eq("REPOSITORY"), eq(testRepo.getId()), contains("\"indexedFiles\":10"));
    }

    @Test
    void markIndexingSuccess_throwsIllegalStateException_whenChunksMissingEmbeddings() {
        when(codeChunkRepository.countByRepositoryId(eq(testRepo.getId()))).thenReturn(5L);
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        // Only 2 out of 5 chunks have embeddings
        when(embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(eq(testRepo.getId())))
                .thenReturn(Set.of(c1, c2));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                helper.markIndexingSuccess(testRepo, 10, 2, testUser)
        );

        assertTrue(ex.getMessage().contains("Cannot mark repository as INDEXED"));
        assertTrue(ex.getMessage().contains("3 of 5 code chunks are missing embeddings"));
        verify(repositoryRepository, never()).save(any());
    }

    @Test
    void markIndexingFailed_updatesStatusAndLogsAudit() {
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));

        helper.markIndexingFailed(testRepo, "Something went wrong", testUser);

        assertEquals(RepositoryIndexStatus.FAILED, testRepo.getIndexStatus());
        verify(repositoryRepository).save(eq(testRepo));
        verify(auditLogService).logAction(eq(testUser), eq(testProject), any(), eq("REPOSITORY_INDEX_FAILED"), eq("REPOSITORY"), eq(testRepo.getId()), contains("Something went wrong"));
    }
}
