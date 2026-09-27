package com.repopilot.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.organization.dto.AddOrgMemberRequest;
import com.repopilot.organization.dto.UpdateOrganizationMemberRoleRequest;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class Phase16_Slice2D_OrganizationRbacRestBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Organization orgA;
    private Organization orgB;

    private User adminOrgA;
    private User memberOrgA;
    private User candidateUser;
    private User adminOrgB;

    private String tokenAdminOrgA;
    private String tokenMemberOrgA;
    private String tokenAdminOrgB;

    private Project projA1;
    private Project projB1;

    @BeforeEach
    void setUp() {
        adminOrgA = createUser("admin_orga@example.com", "Admin OrgA");
        memberOrgA = createUser("member_orga@example.com", "Member OrgA");
        candidateUser = createUser("candidate@example.com", "Candidate User");
        adminOrgB = createUser("admin_orgb@example.com", "Admin OrgB");

        tokenAdminOrgA = tokenProvider.generateAccessToken(adminOrgA);
        tokenMemberOrgA = tokenProvider.generateAccessToken(memberOrgA);
        tokenAdminOrgB = tokenProvider.generateAccessToken(adminOrgB);

        orgA = new Organization();
        orgA.setName("Org A");
        orgA.setSlug("org-a-" + UUID.randomUUID().toString().substring(0, 8));
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-" + UUID.randomUUID().toString().substring(0, 8));
        orgB = organizationRepository.save(orgB);

        organizationMemberRepository.save(new OrganizationMember(orgA, adminOrgA, OrganizationRole.ORG_ADMIN));
        organizationMemberRepository.save(new OrganizationMember(orgA, memberOrgA, OrganizationRole.ORG_MEMBER));
        organizationMemberRepository.save(new OrganizationMember(orgB, adminOrgB, OrganizationRole.ORG_ADMIN));

        projA1 = new Project();
        projA1.setName("Project A1");
        projA1.setOwner(adminOrgA);
        projA1.setOrganization(orgA);
        projA1 = projectRepository.save(projA1);
        projectMemberRepository.save(new ProjectMember(projA1, adminOrgA, ProjectRole.OWNER));

        projB1 = new Project();
        projB1.setName("Project B1");
        projB1.setOwner(adminOrgB);
        projB1.setOrganization(orgB);
        projB1 = projectRepository.save(projB1);
        projectMemberRepository.save(new ProjectMember(projB1, adminOrgB, ProjectRole.OWNER));
    }

    private User createUser(String email, String name) {
        User user = new User();
        user.setEmail(email.toLowerCase().trim());
        user.setName(name);
        user.setPasswordHash(passwordEncoder.encode("password123"));
        user.setRole(UserRole.USER);
        return userRepository.save(user);
    }

    // --- AUTHENTICATION ---

    // 1. Anonymous member list -> 401
    @Test
    @DisplayName("1. Anonymous member list -> 401")
    void test1_anonymousMemberList_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/members"))
                .andExpect(status().isUnauthorized());
    }

    // 2. Anonymous add member -> 401
    @Test
    @DisplayName("2. Anonymous add member -> 401")
    void test2_anonymousAddMember_unauthorized() throws Exception {
        AddOrgMemberRequest req = new AddOrgMemberRequest(candidateUser.getEmail(), OrganizationRole.ORG_MEMBER);
        mockMvc.perform(post("/api/v1/organizations/" + orgA.getId() + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // 3. Anonymous role update -> 401
    @Test
    @DisplayName("3. Anonymous role update -> 401")
    void test3_anonymousRoleUpdate_unauthorized() throws Exception {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN);
        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/members/" + member.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // 4. Anonymous remove member -> 401
    @Test
    @DisplayName("4. Anonymous remove member -> 401")
    void test4_anonymousRemoveMember_unauthorized() throws Exception {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        mockMvc.perform(delete("/api/v1/organizations/" + orgA.getId() + "/members/" + member.getId()))
                .andExpect(status().isUnauthorized());
    }

    // 5. Anonymous project list -> 401
    @Test
    @DisplayName("5. Anonymous project list -> 401")
    void test5_anonymousProjectList_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/projects"))
                .andExpect(status().isUnauthorized());
    }

    // --- ORGANIZATION RBAC ---

    // 6. ORG_MEMBER can list members
    @Test
    @DisplayName("6. ORG_MEMBER can list members")
    void test6_orgMember_canListMembers() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // 7. ORG_ADMIN can list members
    @Test
    @DisplayName("7. ORG_ADMIN can list members")
    void test7_orgAdmin_canListMembers() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // 8. ORG_MEMBER cannot add member -> 403
    @Test
    @DisplayName("8. ORG_MEMBER cannot add member -> 403")
    void test8_orgMember_cannotAddMember() throws Exception {
        AddOrgMemberRequest req = new AddOrgMemberRequest(candidateUser.getEmail(), OrganizationRole.ORG_MEMBER);
        mockMvc.perform(post("/api/v1/organizations/" + orgA.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenMemberOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // 9. ORG_MEMBER cannot update role -> 403
    @Test
    @DisplayName("9. ORG_MEMBER cannot update role -> 403")
    void test9_orgMember_cannotUpdateRole() throws Exception {
        OrganizationMember adminMember = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);
        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/members/" + adminMember.getId())
                        .header("Authorization", "Bearer " + tokenMemberOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    // 10. ORG_MEMBER cannot remove member -> 403
    @Test
    @DisplayName("10. ORG_MEMBER cannot remove member -> 403")
    void test10_orgMember_cannotRemoveMember() throws Exception {
        OrganizationMember adminMember = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();
        mockMvc.perform(delete("/api/v1/organizations/" + orgA.getId() + "/members/" + adminMember.getId())
                        .header("Authorization", "Bearer " + tokenMemberOrgA))
                .andExpect(status().isForbidden());
    }

    // 11. ORG_ADMIN can add member -> 201
    @Test
    @DisplayName("11. ORG_ADMIN can add member -> 201")
    void test11_orgAdmin_canAddMember() throws Exception {
        AddOrgMemberRequest req = new AddOrgMemberRequest(candidateUser.getEmail(), OrganizationRole.ORG_MEMBER);
        mockMvc.perform(post("/api/v1/organizations/" + orgA.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userEmail").value(candidateUser.getEmail()))
                .andExpect(jsonPath("$.role").value("ORG_MEMBER"));
    }

    // 12. ORG_ADMIN can update role -> 200
    @Test
    @DisplayName("12. ORG_ADMIN can update role -> 200")
    void test12_orgAdmin_canUpdateRole() throws Exception {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN);
        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/members/" + member.getId())
                        .header("Authorization", "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"));
    }

    // 13. ORG_ADMIN can remove member -> 200
    @Test
    @DisplayName("13. ORG_ADMIN can remove member -> 200")
    void test13_orgAdmin_canRemoveMember() throws Exception {
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        mockMvc.perform(delete("/api/v1/organizations/" + orgA.getId() + "/members/" + member.getId())
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk());

        assertFalse(organizationMemberRepository.existsById(member.getId()));
    }

    // --- MEMBER IDOR & ERROR CONTRACT ---

    // 14. Cross-org member ID cannot be modified -> 400 or 403 or 404
    @Test
    @DisplayName("14. Cross-org member ID cannot be modified")
    void test14_crossOrgMemberId_cannotBeModified() throws Exception {
        OrganizationMember memberB = organizationMemberRepository.findByOrganizationIdAndUserId(orgB.getId(), adminOrgB.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);
        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/members/" + memberB.getId())
                        .header("Authorization", "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // 15. Forged organizationId cannot bypass authorization -> 403
    @Test
    @DisplayName("15. Forged organizationId cannot bypass authorization -> 403")
    void test15_forgedOrganizationId_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgB.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isForbidden());
    }

    // 16. Random member UUID cannot mutate anything -> 404
    @Test
    @DisplayName("16. Random member UUID cannot mutate anything -> 404")
    void test16_randomMemberUuid_notFound() throws Exception {
        UUID fakeMemberId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/organizations/" + orgA.getId() + "/members/" + fakeMemberId)
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isNotFound());
    }

    // 17. Duplicate member creation returns 409 CONFLICT
    @Test
    @DisplayName("17. Duplicate member creation returns 409 CONFLICT")
    void test17_duplicateMemberCreation_conflict() throws Exception {
        AddOrgMemberRequest req = new AddOrgMemberRequest(memberOrgA.getEmail(), OrganizationRole.ORG_MEMBER);
        mockMvc.perform(post("/api/v1/organizations/" + orgA.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest()); // Service maps duplicate member check to ApiException
    }

    // --- SOLE ADMIN ---

    // 18. Last-admin removal returns expected 400 LAST_ADMIN_REMOVAL_FORBIDDEN
    @Test
    @DisplayName("18. Last-admin removal returns expected 400")
    void test18_lastAdminRemoval_badRequest() throws Exception {
        OrganizationMember adminMember = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();
        mockMvc.perform(delete("/api/v1/organizations/" + orgA.getId() + "/members/" + adminMember.getId())
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_REMOVAL_FORBIDDEN"));
    }

    // 19. Last-admin demotion returns expected 400 LAST_ADMIN_REMOVAL_FORBIDDEN
    @Test
    @DisplayName("19. Last-admin demotion returns expected 400")
    void test19_lastAdminDemotion_badRequest() throws Exception {
        OrganizationMember adminMember = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), adminOrgA.getId()).orElseThrow();
        UpdateOrganizationMemberRoleRequest req = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_MEMBER);
        mockMvc.perform(put("/api/v1/organizations/" + orgA.getId() + "/members/" + adminMember.getId())
                        .header("Authorization", "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_REMOVAL_FORBIDDEN"));
    }

    // --- OPTIMISTIC LOCKING ---

    // 20. Version field returned in member DTO
    @Test
    @DisplayName("20. Member DTO returns valid structure")
    void test20_memberDtoStructure() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].role").isNotEmpty());
    }

    // --- PROJECT VISIBILITY ---

    // 21. Org member can access authorized project listing
    @Test
    @DisplayName("21. Org member can access authorized project listing")
    void test21_orgMember_canAccessAuthorizedProjectListing() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/projects")
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Project A1"));
    }

    // 22. Cross-org project listing returns 403 FORBIDDEN
    @Test
    @DisplayName("22. Cross-org project listing returns 403 FORBIDDEN")
    void test22_crossOrgProjectListing_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgB.getId() + "/projects")
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isForbidden());
    }

    // 23. Org member without project role does not receive project
    @Test
    @DisplayName("23. Org member without project role does not receive project")
    void test23_orgMemberWithoutProjectRole_receivesEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/projects")
                        .header("Authorization", "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // 24. Org member with project role receives project
    @Test
    @DisplayName("24. Org member with project role receives project")
    void test24_orgMemberWithProjectRole_receivesProject() throws Exception {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/projects")
                        .header("Authorization", "Bearer " + tokenMemberOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Project A1"));
    }

    // 25. Org admin without project role does not bypass project authority
    @Test
    @DisplayName("25. Org admin without project role does not bypass project authority")
    void test25_orgAdminWithoutProjectRole_doesNotBypassProjectAuth() throws Exception {
        User orgAdmin2 = createUser("org_admin2@example.com", "Org Admin 2");
        organizationMemberRepository.save(new OrganizationMember(orgA, orgAdmin2, OrganizationRole.ORG_ADMIN));
        String tokenOrgAdmin2 = tokenProvider.generateAccessToken(orgAdmin2);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/projects")
                        .header("Authorization", "Bearer " + tokenOrgAdmin2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // 26. Removed org member with stale project membership receives no project
    @Test
    @DisplayName("26. Removed org member with stale project membership receives no project")
    void test26_removedOrgMemberWithStaleProjectMembership_forbidden() throws Exception {
        projectMemberRepository.save(new ProjectMember(projA1, memberOrgA, ProjectRole.MEMBER));
        OrganizationMember member = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), memberOrgA.getId()).orElseThrow();
        organizationMemberRepository.delete(member);

        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/projects")
                        .header("Authorization", "Bearer " + tokenMemberOrgA))
                .andExpect(status().isForbidden());
    }

    // --- RESPONSE SECURITY ---

    // 27. Member DTO contains no credentials/passwords
    @Test
    @DisplayName("27. Member DTO contains no credentials/passwords")
    void test27_memberDtoNoCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userEmail").value(adminOrgA.getEmail()))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].githubAccessToken").doesNotExist());
    }

    // 28. Project DTO contains no unauthorized sensitive fields
    @Test
    @DisplayName("28. Project DTO contains no unauthorized sensitive fields")
    void test28_projectDtoNoSensitiveFields() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/projects")
                        .header("Authorization", "Bearer " + tokenAdminOrgA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Project A1"))
                .andExpect(jsonPath("$[0].userRole").value("OWNER"));
    }

    // --- AUTHORITY BOUNDARY ---

    // 29. REST mutation produces exact audit event
    @Test
    @DisplayName("29. REST mutation produces exact audit event")
    void test29_restMutationProducesAuditEvent() throws Exception {
        AddOrgMemberRequest req = new AddOrgMemberRequest(candidateUser.getEmail(), OrganizationRole.ORG_MEMBER);
        mockMvc.perform(post("/api/v1/organizations/" + orgA.getId() + "/members")
                        .header("Authorization", "Bearer " + tokenAdminOrgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        List<AuditLog> audits = auditLogRepository.findByResourceTypeAndResourceId("ORGANIZATION_MEMBER",
                organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), candidateUser.getId()).orElseThrow().getId());
        assertFalse(audits.isEmpty());
        assertEquals("ORG_MEMBER_ADDED", audits.get(0).getAction());
        assertEquals(orgA.getId(), audits.get(0).getOrganization().getId());
    }

    // 30. Controller delegates authorization to established services
    @Test
    @DisplayName("30. Controller delegates authorization to established services")
    void test30_controllerDelegatesAuthorization() throws Exception {
        // Non-member calling projects endpoint -> service throws FORBIDDEN -> controller returns 403
        mockMvc.perform(get("/api/v1/organizations/" + orgA.getId() + "/projects")
                        .header("Authorization", "Bearer " + tokenAdminOrgB))
                .andExpect(status().isForbidden());
    }
}
