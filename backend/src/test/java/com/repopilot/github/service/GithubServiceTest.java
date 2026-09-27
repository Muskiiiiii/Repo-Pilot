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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GithubServiceTest {

    @Mock
    private GithubApiService githubApiService;

    @Mock
    private TokenEncryptionService tokenEncryptionService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private RepositoryBranchRepository repositoryBranchRepository;

    @Mock
    private ProjectAuthorizationService projectAuthorizationService;

    @Mock
    private AuditLogService auditLogService;

    private GithubService githubService;

    private User testUser;
    private Project testProject;
    private UUID userId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        githubService = new GithubService(
                githubApiService,
                tokenEncryptionService,
                userRepository,
                projectRepository,
                repositoryRepository,
                repositoryBranchRepository,
                projectAuthorizationService,
                auditLogService
        );

        ReflectionTestUtils.setField(githubService, "clientId", "test_client_id");
        ReflectionTestUtils.setField(githubService, "redirectUri", "http://localhost:3000/callback");
        ReflectionTestUtils.setField(githubService, "hmacSecret", "test_hmac_secret_key");

        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        testUser = new User();
        testUser.setId(userId);
        testUser.setEmail("user@example.com");
        testUser.setName("Test User");

        testProject = new Project();
        testProject.setId(projectId);
        testProject.setName("Test Project");
    }

    @Test
    void testGetAuthorizationUrl() {
        GithubAuthorizeResponse response = githubService.getAuthorizationUrl(testUser);

        assertNotNull(response);
        assertNotNull(response.getAuthorizeUrl());
        assertNotNull(response.getState());
        assertTrue(response.getAuthorizeUrl().contains("client_id=test_client_id"));
        assertTrue(response.getAuthorizeUrl().contains("state=" + response.getState()));
    }

    @Test
    void testHandleOAuthCallback_Success() {
        GithubAuthorizeResponse authResp = githubService.getAuthorizationUrl(testUser);
        String state = authResp.getState();

        when(githubApiService.exchangeCodeForAccessToken("valid_code")).thenReturn("gho_raw_token");
        Map<String, String> userDetails = new HashMap<>();
        userDetails.put("id", "12345");
        userDetails.put("login", "octocat");
        when(githubApiService.fetchAuthenticatedUser("gho_raw_token")).thenReturn(userDetails);
        when(tokenEncryptionService.encrypt("gho_raw_token")).thenReturn("encrypted_token");

        GithubStatusDto status = githubService.handleOAuthCallback(testUser, "valid_code", state);

        assertTrue(status.isConnected());
        assertEquals("octocat", status.getGithubUsername());
        assertNotNull(status.getConnectedAt());

        assertEquals("12345", testUser.getGithubUserId());
        assertEquals("octocat", testUser.getGithubUsername());
        assertEquals("encrypted_token", testUser.getGithubAccessToken());

        verify(userRepository).save(testUser);
        verify(auditLogService).logAction(eq(testUser), isNull(), isNull(), eq("GITHUB_CONNECTED"), eq("USER"), eq(userId), anyString());
    }

    @Test
    void testHandleOAuthCallback_InvalidState() {
        ApiException ex = assertThrows(ApiException.class, () ->
                githubService.handleOAuthCallback(testUser, "valid_code", "invalid_state_string")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void testGetGithubStatus() {
        GithubStatusDto status1 = githubService.getGithubStatus(testUser);
        assertFalse(status1.isConnected());
        assertNull(status1.getGithubUsername());

        testUser.setGithubAccessToken("enc_token");
        testUser.setGithubUsername("octocat");
        testUser.setGithubConnectedAt(Instant.now());

        GithubStatusDto status2 = githubService.getGithubStatus(testUser);
        assertTrue(status2.isConnected());
        assertEquals("octocat", status2.getGithubUsername());
    }

    @Test
    void testDisconnectGithub() {
        testUser.setGithubAccessToken("enc_token");
        testUser.setGithubUsername("octocat");
        testUser.setGithubConnectedAt(Instant.now());

        githubService.disconnectGithub(testUser);

        assertNull(testUser.getGithubAccessToken());
        assertNull(testUser.getGithubUsername());
        assertNull(testUser.getGithubConnectedAt());

        verify(userRepository).save(testUser);
        verify(auditLogService).logAction(eq(testUser), isNull(), isNull(), eq("GITHUB_DISCONNECTED"), eq("USER"), eq(userId), anyString());
    }

    @Test
    void testGetUserRepositories_Success() {
        testUser.setGithubAccessToken("enc_token");
        when(tokenEncryptionService.decrypt("enc_token")).thenReturn("raw_token");

        GithubRepoDto repo1 = new GithubRepoDto("1", "owner", "repo1", "owner/repo1", "main", "clone1", "html1", false, "desc1");
        when(githubApiService.listUserRepositories("raw_token")).thenReturn(List.of(repo1));

        List<GithubRepoDto> result = githubService.getUserRepositories(testUser);

        assertEquals(1, result.size());
        assertEquals("repo1", result.get(0).getName());
    }

    @Test
    void testGetUserRepositories_NotConnected() {
        ApiException ex = assertThrows(ApiException.class, () -> githubService.getUserRepositories(testUser));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void testImportRepositoryToProject_Success() {
        testUser.setGithubAccessToken("enc_token");
        when(tokenEncryptionService.decrypt("enc_token")).thenReturn("raw_token");

        ImportRepositoryRequest req = new ImportRepositoryRequest("100", "octocat", "Hello-World", "main", "http://clone", "http://web", "PUBLIC");

        when(projectAuthorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(repositoryRepository.findByProjectIdAndRepoName(projectId, "Hello-World")).thenReturn(Optional.empty());

        GithubRepoDto meta = new GithubRepoDto("100", "octocat", "Hello-World", "octocat/Hello-World", "main", "http://clone", "http://web", false, "desc");
        when(githubApiService.fetchRepositoryMetadata("raw_token", "octocat", "Hello-World")).thenReturn(meta);

        Repository savedRepo = new Repository();
        savedRepo.setId(UUID.randomUUID());
        savedRepo.setProject(testProject);
        savedRepo.setOwnerName("octocat");
        savedRepo.setRepoName("Hello-World");
        savedRepo.setDefaultBranch("main");
        savedRepo.setVisibility(RepositoryVisibility.PUBLIC);

        when(repositoryRepository.save(any(Repository.class))).thenReturn(savedRepo);

        ProjectRepositoryDto dto = githubService.importRepositoryToProject(testUser, projectId, req);

        assertNotNull(dto);
        assertEquals("Hello-World", dto.getRepoName());
        assertEquals("octocat", dto.getOwnerName());

        verify(projectAuthorizationService).requireMinimumRole(testProject, testUser, ProjectRole.MEMBER);
        verify(repositoryBranchRepository).save(any(RepositoryBranch.class));
        verify(auditLogService).logAction(eq(testUser), eq(testProject), isNull(), eq("REPOSITORY_IMPORTED"), eq("REPOSITORY"), eq(savedRepo.getId()), anyString());
    }

    @Test
    void testImportRepositoryToProject_DuplicatePrevention() {
        ImportRepositoryRequest req = new ImportRepositoryRequest("100", "octocat", "Hello-World", "main", "http://clone", "http://web", "PUBLIC");

        when(projectAuthorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(repositoryRepository.findByProjectIdAndRepoName(projectId, "Hello-World")).thenReturn(Optional.of(new Repository()));

        ApiException ex = assertThrows(ApiException.class, () ->
                githubService.importRepositoryToProject(testUser, projectId, req)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("already imported"));
    }

    @Test
    void testGetProjectRepositoryDetails_CrossProjectAccessPrevention() {
        UUID repoId = UUID.randomUUID();
        Project otherProject = new Project();
        otherProject.setId(UUID.randomUUID());

        Repository repo = new Repository();
        repo.setId(repoId);
        repo.setProject(otherProject);

        when(repositoryRepository.findById(repoId)).thenReturn(Optional.of(repo));

        ApiException ex = assertThrows(ApiException.class, () ->
                githubService.getProjectRepositoryDetails(testUser, projectId, repoId)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void testGetRepositoryFileTree_Success() {
        UUID repoId = UUID.randomUUID();
        Repository repo = new Repository();
        repo.setId(repoId);
        repo.setProject(testProject);
        repo.setOwnerName("octocat");
        repo.setRepoName("Hello-World");
        repo.setDefaultBranch("main");

        testUser.setGithubAccessToken("enc_token");
        when(tokenEncryptionService.decrypt("enc_token")).thenReturn("raw_token");
        when(repositoryRepository.findById(repoId)).thenReturn(Optional.of(repo));

        FileTreeItemDto item = new FileTreeItemDto("src/index.js", "blob", 100L, "sha123");
        when(githubApiService.fetchRepositoryFileTree("raw_token", "octocat", "Hello-World", "main")).thenReturn(List.of(item));

        List<FileTreeItemDto> result = githubService.getRepositoryFileTree(testUser, projectId, repoId, "main");

        assertEquals(1, result.size());
        assertEquals("src/index.js", result.get(0).getPath());
    }

    @Test
    void testEncodePath_specialCharacters() {
        assertEquals("solutions/csharp/c%23.csproj", GithubApiServiceImpl.encodePath("solutions/csharp/c#.csproj"));
        assertEquals("hello%20world/file%231.java", GithubApiServiceImpl.encodePath("hello world/file#1.java"));
        assertEquals("docs/%E6%97%A5%E6%9C%AC%E8%AA%9E.md", GithubApiServiceImpl.encodePath("docs/日本語.md"));
        assertEquals("foo%26bar/baz%2B1.py", GithubApiServiceImpl.encodePath("foo&bar/baz+1.py"));
    }
}
