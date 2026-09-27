package com.repopilot.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.dto.LoginRequest;
import com.repopilot.auth.dto.RefreshTokenRequest;
import com.repopilot.auth.dto.RegisterRequest;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.repopilot.organization.repository.OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private com.repopilot.project.repository.ProjectMemberRepository projectMemberRepository;

    @Autowired
    private com.repopilot.project.repository.ProjectRepository projectRepository;

    @Autowired
    private com.repopilot.organization.repository.OrganizationRepository organizationRepository;

    @Autowired
    private com.repopilot.auth.repository.RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        projectMemberRepository.deleteAll();
        organizationMemberRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void register_ShouldCreateUserAndReturnTokens() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest("newuser@repopilot.ai", "password123", "New User");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("newuser@repopilot.ai"))
                .andExpect(jsonPath("$.user.name").value("New User"));
    }

    @Test
    void login_ShouldReturnTokensForValidCredentials() throws Exception {
        User user = new User();
        user.setEmail("existing@repopilot.ai");
        user.setPasswordHash(passwordEncoder.encode("secretpass"));
        user.setName("Existing User");
        user.setRole(UserRole.USER);
        userRepository.save(user);

        LoginRequest loginRequest = new LoginRequest("existing@repopilot.ai", "secretpass");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("existing@repopilot.ai"));
    }

    @Test
    void login_ShouldReturn401ForInvalidPassword() throws Exception {
        User user = new User();
        user.setEmail("existing@repopilot.ai");
        user.setPasswordHash(passwordEncoder.encode("secretpass"));
        user.setName("Existing User");
        user.setRole(UserRole.USER);
        userRepository.save(user);

        LoginRequest loginRequest = new LoginRequest("existing@repopilot.ai", "wrongpass");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMe_WithValidToken_ShouldReturnUser() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest("me@repopilot.ai", "password123", "Me User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseStr = result.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(responseStr).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@repopilot.ai"))
                .andExpect(jsonPath("$.name").value("Me User"));
    }

    @Test
    void getMe_WithoutToken_ShouldReturn401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshToken_ShouldReturnNewTokens() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest("refresh@repopilot.ai", "password123", "Refresh User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseStr = result.getResponse().getContentAsString();
        String refreshToken = objectMapper.readTree(responseStr).get("refreshToken").asText();

        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }
}
