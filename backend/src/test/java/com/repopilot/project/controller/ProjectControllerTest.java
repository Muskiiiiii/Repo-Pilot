package com.repopilot.project.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.organization.config.DefaultOrganizationHolder;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.project.dto.AddMemberRequest;
import com.repopilot.project.dto.CreateProjectRequest;
import com.repopilot.project.dto.UpdateProjectRequest;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private OrganizationMemberRepository orgMemberRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User userOwner;
    private User userMember;
    private User userStranger;
    private String tokenOwner;
    private String tokenMember;
    private String tokenStranger;
    private Project testProject;

    @BeforeEach
    void setUp() {
        projectMemberRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Create Owner User
        userOwner = new User();
        userOwner.setEmail("owner@repopilot.ai");
        userOwner.setPasswordHash(passwordEncoder.encode("password123"));
        userOwner.setName("Owner User");
        userOwner.setRole(UserRole.USER);
        userOwner = userRepository.save(userOwner);
        orgMemberRepository.save(new OrganizationMember(DefaultOrganizationHolder.getDefaultOrganization(), userOwner, OrganizationRole.ORG_ADMIN));
        tokenOwner = tokenProvider.generateAccessToken(userOwner);

        // 2. Create Member User
        userMember = new User();
        userMember.setEmail("member@repopilot.ai");
        userMember.setPasswordHash(passwordEncoder.encode("password123"));
        userMember.setName("Member User");
        userMember.setRole(UserRole.USER);
        userMember = userRepository.save(userMember);
        tokenMember = tokenProvider.generateAccessToken(userMember);

        // 3. Create Stranger User (No access)
        userStranger = new User();
        userStranger.setEmail("stranger@repopilot.ai");
        userStranger.setPasswordHash(passwordEncoder.encode("password123"));
        userStranger.setName("Stranger User");
        userStranger.setRole(UserRole.USER);
        userStranger = userRepository.save(userStranger);
        tokenStranger = tokenProvider.generateAccessToken(userStranger);

        // 4. Create Test Project owned by userOwner
        testProject = new Project();
        testProject.setName("E-Commerce Backend");
        testProject.setDescription("Main backend repository project");
        testProject.setOwner(userOwner);
        testProject.setOrganization(DefaultOrganizationHolder.getDefaultOrganization());
        testProject.setStatus(ProjectStatus.ACTIVE);
        testProject = projectRepository.save(testProject);

        // Save Owner in ProjectMembers
        ProjectMember ownerMembership = new ProjectMember(testProject, userOwner, ProjectRole.OWNER);
        projectMemberRepository.save(ownerMembership);

        // Save Member in ProjectMembers as MEMBER
        ProjectMember memberMembership = new ProjectMember(testProject, userMember, ProjectRole.MEMBER);
        projectMemberRepository.save(memberMembership);
    }

    @Test
    void createProject_ShouldReturn201Created() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("New AI Project", "AI Workspace Description");

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("New AI Project"))
                .andExpect(jsonPath("$.userRole").value("OWNER"));
    }

    @Test
    void createProject_InvalidName_ShouldReturn400BadRequest() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("", "Description");

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUserProjects_ShouldReturnAccessibleProjectsOnly() throws Exception {
        // Owner lists projects -> sees testProject
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenOwner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("E-Commerce Backend"));

        // Member lists projects -> sees testProject (member)
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenMember))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userRole").value("MEMBER"));

        // Stranger lists projects -> sees empty array
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenStranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getProjectById_AuthorizedUser_ShouldReturnProject() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + testProject.getId())
                        .header("Authorization", "Bearer " + tokenOwner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("E-Commerce Backend"));
    }

    @Test
    void getProjectById_UnauthorizedUser_ShouldReturn403Forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + testProject.getId())
                        .header("Authorization", "Bearer " + tokenStranger))
                .andExpect(status().isForbidden());
    }

    @Test
    void getProjectById_Unauthenticated_ShouldReturn401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + testProject.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProject_Owner_ShouldSucceed() throws Exception {
        UpdateProjectRequest request = new UpdateProjectRequest("Updated Name", "Updated Desc", ProjectStatus.ACTIVE);

        mockMvc.perform(put("/api/v1/projects/" + testProject.getId())
                        .header("Authorization", "Bearer " + tokenOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.description").value("Updated Desc"));
    }

    @Test
    void updateProject_NonAdminMember_ShouldReturn403Forbidden() throws Exception {
        UpdateProjectRequest request = new UpdateProjectRequest("Hacked Name", "Hacked Desc", ProjectStatus.ACTIVE);

        mockMvc.perform(put("/api/v1/projects/" + testProject.getId())
                        .header("Authorization", "Bearer " + tokenMember)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteProject_Owner_ShouldSucceed() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + testProject.getId())
                        .header("Authorization", "Bearer " + tokenOwner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Project deleted successfully"));

        Project deletedProject = projectRepository.findById(testProject.getId()).orElseThrow();
        assertEquals(ProjectStatus.DELETED, deletedProject.getStatus());
    }

    @Test
    void deleteProject_NonOwnerMember_ShouldReturn403Forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/" + testProject.getId())
                        .header("Authorization", "Bearer " + tokenMember))
                .andExpect(status().isForbidden());
    }

    @Test
    void addProjectMember_Owner_ShouldSucceed() throws Exception {
        User newUser = new User();
        newUser.setEmail("newdev@repopilot.ai");
        newUser.setPasswordHash(passwordEncoder.encode("pass123"));
        newUser.setName("New Dev");
        newUser.setRole(UserRole.USER);
        userRepository.save(newUser);

        AddMemberRequest request = new AddMemberRequest("newdev@repopilot.ai", ProjectRole.MEMBER);

        mockMvc.perform(post("/api/v1/projects/" + testProject.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("newdev@repopilot.ai"))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    void removeProjectMember_Owner_ShouldSucceed() throws Exception {
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(testProject.getId(), userMember.getId()).orElseThrow();

        mockMvc.perform(delete("/api/v1/projects/" + testProject.getId() + "/members/" + member.getId())
                        .header("Authorization", "Bearer " + tokenOwner))
                .andExpect(status().isOk());

        assertFalse(projectMemberRepository.existsById(member.getId()));
    }
}
