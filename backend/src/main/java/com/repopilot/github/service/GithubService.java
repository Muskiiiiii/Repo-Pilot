package com.repopilot.github.service;

import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.dto.*;
import com.repopilot.github.security.TokenEncryptionService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryBranch;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryBranchRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class GithubService {

    private static final Logger logger = LoggerFactory.getLogger(GithubService.class);
    private static final long OAUTH_STATE_VALIDITY_MS = 15 * 60 * 1000; // 15 minutes

    private final GithubApiService githubApiService;
    private final TokenEncryptionService tokenEncryptionService;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final RepositoryRepository repositoryRepository;
    private final RepositoryBranchRepository repositoryBranchRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final AuditLogService auditLogService;

    @Value("${repopilot.github.client-id:placeholder}")
    private String clientId;

    @Value("${repopilot.github.redirect-uri:http://localhost:3000/api/auth/callback/github}")
    private String redirectUri;

    @Value("${repopilot.jwt.secret:super_secret_jwt_key_repopilot}")
    private String hmacSecret;

    public GithubService(
            GithubApiService githubApiService,
            TokenEncryptionService tokenEncryptionService,
            UserRepository userRepository,
            ProjectRepository projectRepository,
            RepositoryRepository repositoryRepository,
            RepositoryBranchRepository repositoryBranchRepository,
            ProjectAuthorizationService projectAuthorizationService,
            AuditLogService auditLogService
    ) {
        this.githubApiService = githubApiService;
        this.tokenEncryptionService = tokenEncryptionService;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.repositoryRepository = repositoryRepository;
        this.repositoryBranchRepository = repositoryBranchRepository;
        this.projectAuthorizationService = projectAuthorizationService;
        this.auditLogService = auditLogService;
    }

    public GithubAuthorizeResponse getAuthorizationUrl(User user) {
        String state = generateState(user.getId());
        String url = String.format(
                "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=repo,read:user&state=%s",
                clientId, redirectUri, state
        );
        return new GithubAuthorizeResponse(url, state);
    }

    @Transactional
    public GithubStatusDto handleOAuthCallback(User user, String code, String state) {
        validateState(state, user.getId());

        String rawAccessToken = githubApiService.exchangeCodeForAccessToken(code);
        Map<String, String> userDetails = githubApiService.fetchAuthenticatedUser(rawAccessToken);

        String encryptedToken = tokenEncryptionService.encrypt(rawAccessToken);

        user.setGithubUserId(userDetails.get("id"));
        user.setGithubUsername(userDetails.get("login"));
        user.setGithubAccessToken(encryptedToken);
        user.setGithubConnectedAt(Instant.now());

        userRepository.save(user);

        auditLogService.logAction(
                user, null, null,
                "GITHUB_CONNECTED", "USER", user.getId(),
                "{\"githubUsername\":\"" + userDetails.get("login") + "\"}"
        );

        return new GithubStatusDto(true, user.getGithubUsername(), user.getGithubConnectedAt());
    }

    public GithubStatusDto getGithubStatus(User user) {
        boolean connected = user.getGithubAccessToken() != null && !user.getGithubAccessToken().isEmpty();
        return new GithubStatusDto(connected, user.getGithubUsername(), user.getGithubConnectedAt());
    }

    @Transactional
    public void disconnectGithub(User user) {
        String oldUsername = user.getGithubUsername();

        user.setGithubUserId(null);
        user.setGithubUsername(null);
        user.setGithubAccessToken(null);
        user.setGithubConnectedAt(null);

        userRepository.save(user);

        auditLogService.logAction(
                user, null, null,
                "GITHUB_DISCONNECTED", "USER", user.getId(),
                "{\"githubUsername\":\"" + (oldUsername != null ? oldUsername : "") + "\"}"
        );
    }

    public List<GithubRepoDto> getUserRepositories(User user) {
        String accessToken = getDecryptedAccessTokenOrThrow(user);
        try {
            return githubApiService.listUserRepositories(accessToken);
        } catch (ApiException e) {
            auditLogService.logAction(
                    user, null, null,
                    "REPOSITORY_ACCESS_FAILED", "GITHUB_USER", user.getId(),
                    "{\"reason\":\"" + e.getMessage().replace("\"", "'") + "\"}"
            );
            throw e;
        }
    }

    @Transactional
    public ProjectRepositoryDto importRepositoryToProject(User user, UUID projectId, ImportRepositoryRequest request) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, user);
        projectAuthorizationService.requireMinimumRole(project, user, ProjectRole.MEMBER);

        Optional<Repository> existing = repositoryRepository.findByProjectIdAndRepoName(projectId, request.getRepoName());
        if (existing.isPresent()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Repository '" + request.getRepoName() + "' is already imported in this project");
        }

        String accessToken = getDecryptedAccessTokenOrThrow(user);

        GithubRepoDto githubMeta;
        try {
            githubMeta = githubApiService.fetchRepositoryMetadata(accessToken, request.getOwnerName(), request.getRepoName());
        } catch (Exception e) {
            githubMeta = new GithubRepoDto(
                    request.getGithubRepoId(),
                    request.getOwnerName(),
                    request.getRepoName(),
                    request.getOwnerName() + "/" + request.getRepoName(),
                    request.getDefaultBranch() != null ? request.getDefaultBranch() : "main",
                    request.getCloneUrl() != null ? request.getCloneUrl() : "",
                    request.getWebUrl() != null ? request.getWebUrl() : "",
                    "PRIVATE".equalsIgnoreCase(request.getVisibility()),
                    ""
            );
        }

        Repository repository = new Repository();
        repository.setProject(project);
        repository.setGithubRepoId(githubMeta.getId() != null && !githubMeta.getId().isEmpty() ? githubMeta.getId() : request.getGithubRepoId());
        repository.setOwnerName(githubMeta.getOwner());
        repository.setRepoName(githubMeta.getName());
        repository.setDefaultBranch(githubMeta.getDefaultBranch());
        repository.setCloneUrl(githubMeta.getCloneUrl() != null && !githubMeta.getCloneUrl().isEmpty() ? githubMeta.getCloneUrl() : request.getCloneUrl());
        repository.setWebUrl(githubMeta.getHtmlUrl() != null && !githubMeta.getHtmlUrl().isEmpty() ? githubMeta.getHtmlUrl() : request.getWebUrl());
        repository.setVisibility(githubMeta.isPrivate() ? RepositoryVisibility.PRIVATE : RepositoryVisibility.PUBLIC);

        Repository savedRepo = repositoryRepository.save(repository);

        // Save default branch
        RepositoryBranch defaultBranch = new RepositoryBranch();
        defaultBranch.setRepository(savedRepo);
        defaultBranch.setName(githubMeta.getDefaultBranch());
        defaultBranch.setCommitSha("head");
        defaultBranch.setIsDefault(true);
        repositoryBranchRepository.save(defaultBranch);

        auditLogService.logAction(
                user, project, null,
                "REPOSITORY_IMPORTED", "REPOSITORY", savedRepo.getId(),
                "{\"repoFullName\":\"" + githubMeta.getFullName() + "\"}"
        );

        return ProjectRepositoryDto.fromEntity(savedRepo);
    }

    public List<ProjectRepositoryDto> getProjectRepositories(User user, UUID projectId) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, user);
        projectAuthorizationService.requireMinimumRole(project, user, ProjectRole.VIEWER);
        List<Repository> repos = repositoryRepository.findByProjectId(projectId);
        List<ProjectRepositoryDto> dtos = new ArrayList<>();
        for (Repository r : repos) {
            dtos.add(ProjectRepositoryDto.fromEntity(r));
        }
        return dtos;
    }

    public ProjectRepositoryDto getProjectRepositoryDetails(User user, UUID projectId, UUID repositoryId) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, user);
        projectAuthorizationService.requireMinimumRole(project, user, ProjectRole.VIEWER);
        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Repository does not belong to the specified project");
        }

        return ProjectRepositoryDto.fromEntity(repository);
    }

    public List<BranchDto> getRepositoryBranches(User user, UUID projectId, UUID repositoryId) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, user);
        projectAuthorizationService.requireMinimumRole(project, user, ProjectRole.VIEWER);
        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Repository does not belong to the specified project");
        }

        String accessToken = getDecryptedAccessTokenOrNull(user);
        if (accessToken != null) {
            try {
                return githubApiService.listRepositoryBranches(accessToken, repository.getOwnerName(), repository.getRepoName());
            } catch (Exception e) {
                logger.warn("Failed to fetch branches live from GitHub, falling back to database: {}", e.getMessage());
            }
        }

        List<RepositoryBranch> dbBranches = repositoryBranchRepository.findByRepositoryId(repositoryId);
        List<BranchDto> dtos = new ArrayList<>();
        for (RepositoryBranch b : dbBranches) {
            dtos.add(BranchDto.fromEntity(b));
        }
        return dtos;
    }

    public List<FileTreeItemDto> getRepositoryFileTree(User user, UUID projectId, UUID repositoryId, String branch) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, user);
        projectAuthorizationService.requireMinimumRole(project, user, ProjectRole.VIEWER);
        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Repository does not belong to the specified project");
        }

        String accessToken = getDecryptedAccessTokenOrThrow(user);
        String targetBranch = (branch != null && !branch.trim().isEmpty()) ? branch : repository.getDefaultBranch();

        try {
            return githubApiService.fetchRepositoryFileTree(accessToken, repository.getOwnerName(), repository.getRepoName(), targetBranch);
        } catch (ApiException e) {
            auditLogService.logAction(
                    user, repository.getProject(), null,
                    "REPOSITORY_ACCESS_FAILED", "REPOSITORY", repositoryId,
                    "{\"reason\":\"" + e.getMessage().replace("\"", "'") + "\"}"
            );
            throw e;
        }
    }

    public FileContentDto getRepositoryFileContent(User user, UUID projectId, UUID repositoryId, String branch, String path) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, user);
        projectAuthorizationService.requireMinimumRole(project, user, ProjectRole.VIEWER);
        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Repository does not belong to the specified project");
        }

        if (path == null || path.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File path is required");
        }

        String accessToken = getDecryptedAccessTokenOrThrow(user);
        String targetBranch = (branch != null && !branch.trim().isEmpty()) ? branch : repository.getDefaultBranch();

        try {
            return githubApiService.fetchFileContent(accessToken, repository.getOwnerName(), repository.getRepoName(), targetBranch, path);
        } catch (ApiException e) {
            auditLogService.logAction(
                    user, repository.getProject(), null,
                    "REPOSITORY_ACCESS_FAILED", "REPOSITORY", repositoryId,
                    "{\"reason\":\"" + e.getMessage().replace("\"", "'") + "\"}"
            );
            throw e;
        }
    }

    private String getDecryptedAccessTokenOrThrow(User user) {
        String encrypted = user.getGithubAccessToken();
        if (encrypted == null || encrypted.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "GitHub account is not connected. Please connect your GitHub account in Settings.");
        }
        String decrypted = tokenEncryptionService.decrypt(encrypted);
        if (decrypted == null || decrypted.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Failed to decrypt GitHub access token");
        }
        return decrypted;
    }

    private String getDecryptedAccessTokenOrNull(User user) {
        String encrypted = user.getGithubAccessToken();
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        try {
            return tokenEncryptionService.decrypt(encrypted);
        } catch (Exception e) {
            return null;
        }
    }

    private String generateState(UUID userId) {
        long timestamp = System.currentTimeMillis();
        String payload = userId.toString() + ":" + timestamp;
        String signature = computeHmac(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((payload + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    private void validateState(String state, UUID expectedUserId) {
        if (state == null || state.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing OAuth state parameter");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":");
            if (parts.length != 3) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid OAuth state format");
            }
            String userIdStr = parts[0];
            long timestamp = Long.parseLong(parts[1]);
            String signature = parts[2];

            if (!userIdStr.equals(expectedUserId.toString())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "State parameter user mismatch");
            }

            if (System.currentTimeMillis() - timestamp > OAUTH_STATE_VALIDITY_MS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "OAuth state has expired. Please try connecting again.");
            }

            String expectedPayload = userIdStr + ":" + timestamp;
            String expectedSignature = computeHmac(expectedPayload);
            if (!expectedSignature.equals(signature)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "OAuth state signature validation failed");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to parse or validate OAuth state");
        }
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("Error computing HMAC signature for OAuth state", e);
        }
    }
}
