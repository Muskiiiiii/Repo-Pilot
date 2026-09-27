package com.repopilot.repository;

import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.*;
import com.repopilot.repository.repository.*;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PgVectorIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryBranchRepository repositoryBranchRepository;

    @Autowired
    private RepositoryFileRepository repositoryFileRepository;

    @Autowired
    private CodeChunkRepository codeChunkRepository;

    @Autowired
    private EmbeddingRepository embeddingRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    void testPgVectorInsertAndRead_1024Dimensions() {
        // 1. Setup User
        User user = new User();
        user.setEmail("vector_test_" + UUID.randomUUID() + "@repopilot.ai");
        user.setPasswordHash("hash123");
        user.setName("Vector Test User");
        user.setRole(UserRole.USER);
        User savedUser = userRepository.save(user);

        // 2. Setup Project
        Project project = new Project();
        project.setName("Vector Test Project");
        project.setDescription("Testing PostgreSQL 1024d pgvector insertion");
        project.setOwner(savedUser);
        project.setStatus(ProjectStatus.ACTIVE);
        Project savedProject = projectRepository.save(project);

        // 3. Setup Repository
        Repository repo = new Repository();
        repo.setProject(savedProject);
        repo.setOwnerName("repopilot");
        repo.setRepoName("vector-test-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repo.setDefaultBranch("main");
        repo.setVisibility(RepositoryVisibility.PUBLIC);
        repo.setIndexStatus(RepositoryIndexStatus.INDEXING);
        Repository savedRepo = repositoryRepository.save(repo);

        // 4. Setup Branch & File & Chunk
        RepositoryBranch branch = new RepositoryBranch();
        branch.setRepository(savedRepo);
        branch.setName("main");
        branch.setCommitSha("1234567890abcdef");
        branch.setIsDefault(true);
        RepositoryBranch savedBranch = repositoryBranchRepository.save(branch);

        RepositoryFile file = new RepositoryFile();
        file.setRepository(savedRepo);
        file.setBranch(savedBranch);
        file.setPath("src/VectorService.java");
        file.setLanguage("Java");
        file.setFileSize(512L);
        RepositoryFile savedFile = repositoryFileRepository.save(file);

        CodeChunk chunk = new CodeChunk();
        chunk.setFile(savedFile);
        chunk.setChunkIndex(0);
        chunk.setContent("public class VectorService {}");
        chunk.setStartLine(1);
        chunk.setEndLine(5);
        CodeChunk savedChunk = codeChunkRepository.save(chunk);

        // 5. Construct 1024-dimensional float vector string: [0.001, 0.002, ..., 1.024]
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= 1024; i++) {
            sb.append(String.format("%.4f", i * 0.001));
            if (i < 1024) {
                sb.append(", ");
            }
        }
        sb.append("]");
        String vectorString = sb.toString();

        // Verify construct string has 1024 elements
        String[] elements = vectorString.substring(1, vectorString.length() - 1).split(",");
        assertEquals(1024, elements.length, "Constructed vector string must have 1024 elements");

        // 6. Insert 1024d Embedding into PostgreSQL
        Embedding embedding = new Embedding();
        embedding.setChunk(savedChunk);
        embedding.setModelName("voyage-code-4");
        embedding.setDimensions(1024);
        embedding.setEmbedding(vectorString);

        Embedding savedEmbedding = embeddingRepository.save(embedding);
        assertNotNull(savedEmbedding.getId(), "Saved embedding ID must not be null");

        // Flush & clear session to force database fetch from PostgreSQL
        entityManager.flush();
        entityManager.clear();

        // 7. Read Embedding from PostgreSQL
        Optional<Embedding> retrievedOpt = embeddingRepository.findById(savedEmbedding.getId());
        assertTrue(retrievedOpt.isPresent(), "Retrieved embedding must be present");

        Embedding retrieved = retrievedOpt.get();
        assertEquals("voyage-code-4", retrieved.getModelName());
        assertEquals(1024, retrieved.getDimensions());
        assertNotNull(retrieved.getEmbedding(), "Retrieved embedding vector string must not be null");

        // Verify retrieved PostgreSQL vector format and dimension count
        String retrievedVector = retrieved.getEmbedding();
        assertTrue(retrievedVector.startsWith("[") && retrievedVector.endsWith("]"), "Retrieved vector must be enclosed in brackets");
        String[] retrievedElements = retrievedVector.substring(1, retrievedVector.length() - 1).split(",");
        assertEquals(1024, retrievedElements.length, "Retrieved PostgreSQL vector must contain exactly 1024 dimensions");

        // Verify first and last element values match roughly
        double firstVal = Double.parseDouble(retrievedElements[0].trim());
        assertEquals(0.001, firstVal, 0.0001);
    }

    @Test
    void testPgVectorSemanticSearch_CosineDistanceRanking() throws Exception {
        // Skip test if running on H2 in-memory DB as H2 does not support pgvector <=> operator
        try (var conn = dataSource.getConnection()) {
            if (conn.getMetaData().getDatabaseProductName().toLowerCase().contains("h2")) {
                return;
            }
        }

        // 1. Setup User & Project & Repository
        User user = new User();
        user.setEmail("search_test_" + UUID.randomUUID() + "@repopilot.ai");
        user.setPasswordHash("hash123");
        user.setName("Search Test User");
        user.setRole(UserRole.USER);
        User savedUser = userRepository.save(user);

        Project project = new Project();
        project.setName("Search Test Project");
        project.setOwner(savedUser);
        project.setStatus(ProjectStatus.ACTIVE);
        Project savedProject = projectRepository.save(project);

        Repository repo = new Repository();
        repo.setProject(savedProject);
        repo.setOwnerName("repopilot");
        repo.setRepoName("search-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repo.setDefaultBranch("main");
        repo.setVisibility(RepositoryVisibility.PUBLIC);
        repo.setIndexStatus(RepositoryIndexStatus.INDEXED);
        Repository savedRepo = repositoryRepository.save(repo);

        RepositoryBranch branch = new RepositoryBranch();
        branch.setRepository(savedRepo);
        branch.setName("main");
        branch.setCommitSha("abcdef1234567890");
        branch.setIsDefault(true);
        RepositoryBranch savedBranch = repositoryBranchRepository.save(branch);

        RepositoryFile file = new RepositoryFile();
        file.setRepository(savedRepo);
        file.setBranch(savedBranch);
        file.setPath("src/AuthService.java");
        file.setLanguage("Java");
        file.setFileSize(1024L);
        RepositoryFile savedFile = repositoryFileRepository.save(file);

        // Chunk 1: Auth method
        CodeChunk chunk1 = new CodeChunk();
        chunk1.setFile(savedFile);
        chunk1.setChunkIndex(0);
        chunk1.setSymbolName("authenticateUser");
        chunk1.setChunkType("METHOD");
        chunk1.setContent("public boolean authenticateUser(String username, String password) { return true; }");
        chunk1.setStartLine(10);
        chunk1.setEndLine(20);
        CodeChunk savedChunk1 = codeChunkRepository.save(chunk1);

        // Chunk 2: Other method
        CodeChunk chunk2 = new CodeChunk();
        chunk2.setFile(savedFile);
        chunk2.setChunkIndex(1);
        chunk2.setSymbolName("formatDate");
        chunk2.setChunkType("METHOD");
        chunk2.setContent("public String formatDate(Date date) { return date.toString(); }");
        chunk2.setStartLine(22);
        chunk2.setEndLine(30);
        CodeChunk savedChunk2 = codeChunkRepository.save(chunk2);

        // Vector 1: [1, 0, 0, ...] (dimension 1024)
        StringBuilder sb1 = new StringBuilder("[1.0");
        for (int i = 2; i <= 1024; i++) sb1.append(", 0.0");
        sb1.append("]");

        // Vector 2: [0, 1, 0, ...] (dimension 1024)
        StringBuilder sb2 = new StringBuilder("[0.0, 1.0");
        for (int i = 3; i <= 1024; i++) sb2.append(", 0.0");
        sb2.append("]");

        Embedding emb1 = new Embedding();
        emb1.setChunk(savedChunk1);
        emb1.setModelName("voyage-code-4");
        emb1.setDimensions(1024);
        emb1.setEmbedding(sb1.toString());
        embeddingRepository.save(emb1);

        Embedding emb2 = new Embedding();
        emb2.setChunk(savedChunk2);
        emb2.setModelName("voyage-code-4");
        emb2.setDimensions(1024);
        emb2.setEmbedding(sb2.toString());
        embeddingRepository.save(emb2);

        entityManager.flush();
        entityManager.clear();

        // Perform Query Vector [1, 0, 0, ...] -> Should match Chunk 1 with distance ~0.0
        String queryVector = sb1.toString();
        List<Object[]> searchResults = embeddingRepository.searchSemanticChunksNative(savedRepo.getId(), queryVector, 10);

        assertNotNull(searchResults);
        assertEquals(2, searchResults.size(), "Should return 2 chunk results");

        // Top match should be Chunk 1
        Object[] topResult = searchResults.get(0);
        UUID topChunkId = topResult[0] instanceof UUID ? (UUID) topResult[0] : UUID.fromString(topResult[0].toString());
        String topContent = (String) topResult[2];
        String topSymbolName = (String) topResult[3];
        String topChunkType = (String) topResult[4];
        int topStartLine = ((Number) topResult[5]).intValue();
        int topEndLine = ((Number) topResult[6]).intValue();
        String topFilePath = (String) topResult[7];
        double topDistance = ((Number) topResult[9]).doubleValue();

        assertEquals(savedChunk1.getId(), topChunkId);
        assertEquals("src/AuthService.java", topFilePath);
        assertEquals("authenticateUser", topSymbolName);
        assertEquals("METHOD", topChunkType);
        assertEquals(10, topStartLine);
        assertEquals(20, topEndLine);
        assertTrue(topDistance < 0.0001, "Distance to identical vector should be ~0.0");

        // Second match should be Chunk 2 with orthogonal distance ~1.0
        Object[] secondResult = searchResults.get(1);
        UUID secondChunkId = secondResult[0] instanceof UUID ? (UUID) secondResult[0] : UUID.fromString(secondResult[0].toString());
        double secondDistance = ((Number) secondResult[9]).doubleValue();

        assertEquals(savedChunk2.getId(), secondChunkId);
        assertTrue(Math.abs(secondDistance - 1.0) < 0.0001, "Orthogonal vector distance should be ~1.0");
    }
}
