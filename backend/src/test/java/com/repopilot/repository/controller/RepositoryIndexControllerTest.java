package com.repopilot.repository.controller;

import com.repopilot.auth.security.JwtAuthenticationEntryPoint;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.repository.dto.RepositoryIndexProgressDto;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.service.RepositoryIndexingService;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RepositoryIndexController.class)
@AutoConfigureMockMvc(addFilters = false)
class RepositoryIndexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RepositoryIndexingService repositoryIndexingService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @MockBean
    private com.repopilot.auth.security.CustomUserDetailsService customUserDetailsService;

    private User testUser;
    private UserPrincipal userPrincipal;
    private UUID projectId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("user@example.com");

        userPrincipal = new UserPrincipal(testUser);
        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userPrincipal, null, userPrincipal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void triggerIndexing_returnsOk() throws Exception {
        RepositoryIndexProgressDto progress = new RepositoryIndexProgressDto(
                repositoryId, RepositoryIndexStatus.INDEXING, 10, 0, 0, 0, 0.0, null
        );

        when(repositoryIndexingService.triggerIndexing(eq(projectId), eq(repositoryId), any(), any()))
                .thenReturn(progress);

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/repositories/" + repositoryId + "/index")
                        .param("branch", "main")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value(repositoryId.toString()))
                .andExpect(jsonPath("$.status").value("INDEXING"));
    }

    @Test
    void getIndexingStatus_returnsStatus() throws Exception {
        RepositoryIndexProgressDto progress = new RepositoryIndexProgressDto(
                repositoryId, RepositoryIndexStatus.INDEXED, 10, 10, 50, 50, 100.0, null
        );

        when(repositoryIndexingService.getIndexingStatus(eq(projectId), eq(repositoryId), any()))
                .thenReturn(progress);

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/repositories/" + repositoryId + "/index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDEXED"))
                .andExpect(jsonPath("$.progressPercentage").value(100.0));
    }

    @Test
    void getIndexingStatus_unauthorizedProject_returnsForbidden() throws Exception {
        when(repositoryIndexingService.getIndexingStatus(eq(projectId), eq(repositoryId), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied"));

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/repositories/" + repositoryId + "/index/status"))
                .andExpect(status().isForbidden());
    }

    @Test
    void stopIndexing_returnsOk() throws Exception {
        RepositoryIndexProgressDto progress = new RepositoryIndexProgressDto(
                repositoryId, RepositoryIndexStatus.CANCELLED, 10, 10, 50, 20, 40.0, null
        );

        when(repositoryIndexingService.stopIndexing(eq(projectId), eq(repositoryId), any()))
                .thenReturn(progress);

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/repositories/" + repositoryId + "/index/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.canResume").value(true));
    }

    @Test
    void resumeIndexing_returnsOk() throws Exception {
        RepositoryIndexProgressDto progress = new RepositoryIndexProgressDto(
                repositoryId, RepositoryIndexStatus.INDEXING, 10, 10, 50, 20, 40.0, null
        );

        when(repositoryIndexingService.resumeIndexing(eq(projectId), eq(repositoryId), any()))
                .thenReturn(progress);

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/repositories/" + repositoryId + "/index/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDEXING"))
                .andExpect(jsonPath("$.canStop").value(true));
    }
}
