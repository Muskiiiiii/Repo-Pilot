package com.repopilot.repository.service;

import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.dto.SearchRequestDto;
import com.repopilot.repository.dto.SearchResponseDto;
import com.repopilot.repository.dto.SearchResultItemDto;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.repository.EmbeddingRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SemanticSearchServiceImpl implements SemanticSearchService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticSearchServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final EmbeddingRepository embeddingRepository;
    private final AiServiceClient aiServiceClient;

    public SemanticSearchServiceImpl(ProjectAuthorizationService projectAuthorizationService,
                                     RepositoryRepository repositoryRepository,
                                     EmbeddingRepository embeddingRepository,
                                     AiServiceClient aiServiceClient) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.embeddingRepository = embeddingRepository;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    @Transactional(readOnly = true)
    public SearchResponseDto searchRepository(UUID projectId, UUID repositoryId, SearchRequestDto request, User currentUser) {
        // 1. Authorize user for project
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, currentUser);

        // 2. Fetch repository & verify ownership by project
        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository not found"));

        if (!repository.getProject().getId().equals(project.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPOSITORY_NOT_IN_PROJECT", "Repository does not belong to the specified project");
        }

        // 3. Verify repository index status
        if (repository.getIndexStatus() != RepositoryIndexStatus.INDEXED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPOSITORY_NOT_INDEXED",
                    "Repository is not indexed yet. Please index the repository before searching.");
        }

        // 4. Validate query & parameters
        String rawQuery = request != null ? request.getQuery() : null;
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "Search query cannot be blank");
        }

        String query = rawQuery.trim();
        if (query.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "Search query cannot exceed 1000 characters");
        }

        int topK = request != null && request.getTopK() != null ? Math.min(Math.max(1, request.getTopK()), 100) : 10;

        // 5. Generate query embedding (with input_type="query")
        String queryVector = aiServiceClient.generateQueryEmbedding(query);

        // 6. Perform pgvector cosine distance search (<=>)
        List<Object[]> rawResults = embeddingRepository.searchSemanticChunksNative(repositoryId, queryVector, topK);

        List<SearchResultItemDto> results = new ArrayList<>();
        if (rawResults != null) {
            for (Object[] row : rawResults) {
                UUID chunkId = row[0] instanceof UUID ? (UUID) row[0] : UUID.fromString(row[0].toString());
                int chunkIndex = row[1] instanceof Number ? ((Number) row[1]).intValue() : 0;
                String content = row[2] != null ? row[2].toString() : "";
                String symbolName = row[3] != null ? row[3].toString() : "";
                String chunkType = row[4] != null ? row[4].toString() : "";
                int startLine = row[5] instanceof Number ? ((Number) row[5]).intValue() : 0;
                int endLine = row[6] instanceof Number ? ((Number) row[6]).intValue() : 0;
                String filePath = row[7] != null ? row[7].toString() : "";
                String fileLanguage = row[8] != null ? row[8].toString() : "";
                double distance = row[9] instanceof Number ? ((Number) row[9]).doubleValue() : 0.0;

                // Cosine similarity = 1.0 - distance (bounded 0.0 to 1.0)
                double rawSimilarity = Math.max(0.0, Math.min(1.0, 1.0 - distance));
                double similarityScore = BigDecimal.valueOf(rawSimilarity)
                        .setScale(4, RoundingMode.HALF_UP)
                        .doubleValue();
                double roundedDistance = BigDecimal.valueOf(distance)
                        .setScale(4, RoundingMode.HALF_UP)
                        .doubleValue();

                results.add(new SearchResultItemDto(
                        chunkId, filePath, fileLanguage, symbolName, chunkType,
                        startLine, endLine, content, roundedDistance, similarityScore
                ));
            }
        }

        return new SearchResponseDto(query, topK, results.size(), results);
    }
}
