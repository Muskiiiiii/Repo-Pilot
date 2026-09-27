package com.repopilot.repository.service;

import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.dto.SearchRequestDto;
import com.repopilot.repository.dto.SearchResponseDto;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.repository.EmbeddingRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticSearchServiceImplTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private EmbeddingRepository embeddingRepository;

    @Mock
    private ProjectAuthorizationService authorizationService;

    @Mock
    private AiServiceClient aiServiceClient;

    private SemanticSearchServiceImpl semanticSearchService;

    private User testUser;
    private Project testProject;
    private Repository testRepo;
    private UUID projectId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        semanticSearchService = new SemanticSearchServiceImpl(
                authorizationService,
                repositoryRepository,
                embeddingRepository,
                aiServiceClient
        );

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("user@repopilot.ai");

        testProject = new Project();
        testProject.setId(projectId);
        testProject.setName("Test Project");

        testRepo = new Repository();
        testRepo.setId(repositoryId);
        testRepo.setProject(testProject);
        testRepo.setRepoName("test-repo");
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);
    }

    @Test
    void search_unauthorizedUser_throwsApiException403() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied"))
                .when(authorizationService).getAuthorizedProject(projectId, testUser);

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("test query");

        ApiException ex = assertThrows(ApiException.class, () ->
                semanticSearchService.searchRepository(projectId, repositoryId, request, testUser)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void search_repositoryNotFound_throwsApiException404() {
        when(authorizationService.getAuthorizedProject(projectId, testUser))
                .thenReturn(testProject);
        when(repositoryRepository.findById(repositoryId))
                .thenReturn(Optional.empty());

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("test query");

        ApiException ex = assertThrows(ApiException.class, () ->
                semanticSearchService.searchRepository(projectId, repositoryId, request, testUser)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void search_repositoryNotIndexed_throwsApiException400() {
        testRepo.setIndexStatus(RepositoryIndexStatus.NOT_INDEXED);
        when(authorizationService.getAuthorizedProject(projectId, testUser))
                .thenReturn(testProject);
        when(repositoryRepository.findById(repositoryId))
                .thenReturn(Optional.of(testRepo));

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("test query");

        ApiException ex = assertThrows(ApiException.class, () ->
                semanticSearchService.searchRepository(projectId, repositoryId, request, testUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("REPOSITORY_NOT_INDEXED", ex.getCode());
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void search_blankQuery_throwsApiException400() {
        when(authorizationService.getAuthorizedProject(projectId, testUser))
                .thenReturn(testProject);
        when(repositoryRepository.findById(repositoryId))
                .thenReturn(Optional.of(testRepo));

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("   ");

        ApiException ex = assertThrows(ApiException.class, () ->
                semanticSearchService.searchRepository(projectId, repositoryId, request, testUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_QUERY", ex.getCode());
    }

    @Test
    void search_oversizedQuery_throwsApiException400() {
        when(authorizationService.getAuthorizedProject(projectId, testUser))
                .thenReturn(testProject);
        when(repositoryRepository.findById(repositoryId))
                .thenReturn(Optional.of(testRepo));

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("a".repeat(1001));

        ApiException ex = assertThrows(ApiException.class, () ->
                semanticSearchService.searchRepository(projectId, repositoryId, request, testUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_QUERY", ex.getCode());
    }

    @Test
    void search_validQuery_returnsRankedResults() {
        when(authorizationService.getAuthorizedProject(projectId, testUser))
                .thenReturn(testProject);
        when(repositoryRepository.findById(repositoryId))
                .thenReturn(Optional.of(testRepo));

        // Mock AI service query vector generation
        String queryVectorStr = "[0.1, 0.2, 0.3]";
        when(aiServiceClient.generateQueryEmbedding("how does auth work?"))
                .thenReturn(queryVectorStr);

        // Mock native DB query result row
        UUID chunkId = UUID.randomUUID();
        Object[] row = new Object[]{
                chunkId,                  // 0: chunkId
                0,                        // 1: chunkIndex
                "public void login() {}", // 2: content
                "login",                  // 3: symbolName
                "METHOD",                 // 4: chunkType
                1,                        // 5: startLine
                15,                       // 6: endLine
                "src/Auth.java",          // 7: filePath
                "Java",                   // 8: fileLanguage
                0.1500                    // 9: distance
        };

        when(embeddingRepository.searchSemanticChunksNative(eq(repositoryId), eq(queryVectorStr), eq(10)))
                .thenReturn(List.<Object[]>of(row));

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("how does auth work?");
        request.setTopK(10);

        SearchResponseDto response = semanticSearchService.searchRepository(projectId, repositoryId, request, testUser);

        assertNotNull(response);
        assertEquals("how does auth work?", response.getQuery());
        assertEquals(1, response.getResults().size());

        var result = response.getResults().get(0);
        assertEquals(chunkId, result.getChunkId());
        assertEquals("src/Auth.java", result.getFilePath());
        assertEquals("login", result.getSymbolName());
        assertEquals("METHOD", result.getChunkType());
        assertEquals(1, result.getStartLine());
        assertEquals(15, result.getEndLine());
        assertEquals(0.15, result.getDistance());
        assertEquals(0.85, result.getSimilarityScore(), 0.0001);

        verify(authorizationService).getAuthorizedProject(projectId, testUser);
        verify(aiServiceClient).generateQueryEmbedding("how does auth work?");
    }

    @Test
    void search_topKOutOfBounds_clampedToValidRange() {
        when(authorizationService.getAuthorizedProject(projectId, testUser))
                .thenReturn(testProject);
        when(repositoryRepository.findById(repositoryId))
                .thenReturn(Optional.of(testRepo));
        when(aiServiceClient.generateQueryEmbedding("test")).thenReturn("[0.1]");
        when(embeddingRepository.searchSemanticChunksNative(eq(repositoryId), anyString(), eq(100)))
                .thenReturn(List.<Object[]>of());

        SearchRequestDto request = new SearchRequestDto();
        request.setQuery("test");
        request.setTopK(500); // Exceeds max 100

        SearchResponseDto response = semanticSearchService.searchRepository(projectId, repositoryId, request, testUser);

        assertNotNull(response);
        verify(embeddingRepository).searchSemanticChunksNative(eq(repositoryId), anyString(), eq(100));
    }
}
