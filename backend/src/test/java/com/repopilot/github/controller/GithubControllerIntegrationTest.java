package com.repopilot.github.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.github.dto.ImportRepositoryRequest;
import com.repopilot.github.security.TokenEncryptionService;
import com.repopilot.github.service.GithubApiService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GithubControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenEncryptionService tokenEncryptionService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GithubApiService githubApiService;

    private User owner;
    private String ownerToken;
    private Project project;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setEmail("ghowner@example.com");
        owner.setPasswordHash(passwordEncoder.encode("Password123!"));
        owner.setName("GitHub Owner");
        owner.setRole(UserRole.USER);
        owner.setGithubUserId("12345");
        owner.setGithubUsername("octocat");
        owner.setGithubAccessToken(tokenEncryptionService.encrypt("raw_test_github_token"));
        owner.setGithubConnectedAt(Instant.now());
        owner = userRepository.save(owner);

        ownerToken = tokenProvider.generateAccessToken(owner);

        project = new Project();
        project.setName("GitHub Test Project");
        project.setOwner(owner);
        project = projectRepository.save(project);

        ProjectMember member = new ProjectMember(project, owner, ProjectRole.OWNER);
        projectMemberRepository.save(member);
    }

    @Test
    void testGetAuthorizeUrl_Authenticated() throws Exception {
        mockMvc.perform(get("/api/v1/github/authorize")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").exists())
                .andExpect(jsonPath("$.state").exists());
    }

    @Test
    void testGetAuthorizeUrl_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/github/authorize"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testGetGithubStatus() throws Exception {
        mockMvc.perform(get("/api/v1/github/status")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.githubUsername").value("octocat"));
    }

    @Test
    void testDisconnectGithub() throws Exception {
        mockMvc.perform(post("/api/v1/github/disconnect")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("GitHub account disconnected successfully"));

        mockMvc.perform(get("/api/v1/github/status")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void testImportRepositoryAndFetchProjectRepositories() throws Exception {
        com.repopilot.github.dto.GithubRepoDto meta = new com.repopilot.github.dto.GithubRepoDto(
                "999", "octocat", "DemoRepo", "octocat/DemoRepo", "main",
                "https://github.com/octocat/DemoRepo.git", "https://github.com/octocat/DemoRepo", false, "desc"
        );
        when(githubApiService.fetchRepositoryMetadata(anyString(), anyString(), anyString())).thenReturn(meta);

        ImportRepositoryRequest request = new ImportRepositoryRequest(
                "999", "octocat", "DemoRepo", "main",
                "https://github.com/octocat/DemoRepo.git",
                "https://github.com/octocat/DemoRepo",
                "PUBLIC"
        );

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/repositories/import")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repoName").value("DemoRepo"))
                .andExpect(jsonPath("$.ownerName").value("octocat"))
                .andExpect(jsonPath("$.defaultBranch").value("main"));

        // List repos for project
        mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/repositories")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].repoName").value("DemoRepo"));
    }

    @Test
    void testImportDuplicateRepository_ReturnsBadRequest() throws Exception {
        com.repopilot.github.dto.GithubRepoDto meta = new com.repopilot.github.dto.GithubRepoDto(
                "999", "octocat", "DemoRepo", "octocat/DemoRepo", "main",
                "https://github.com/octocat/DemoRepo.git", "https://github.com/octocat/DemoRepo", false, "desc"
        );
        when(githubApiService.fetchRepositoryMetadata(anyString(), anyString(), anyString())).thenReturn(meta);

        ImportRepositoryRequest request = new ImportRepositoryRequest(
                "999", "octocat", "DemoRepo", "main",
                "https://github.com/octocat/DemoRepo.git",
                "https://github.com/octocat/DemoRepo",
                "PUBLIC"
        );

        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/repositories/import")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Second import of same repo in same project
        mockMvc.perform(post("/api/v1/projects/" + project.getId() + "/repositories/import")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Repository 'DemoRepo' is already imported in this project"));
    }
}
