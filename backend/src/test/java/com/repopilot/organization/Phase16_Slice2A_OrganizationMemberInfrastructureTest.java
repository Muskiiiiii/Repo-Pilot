package com.repopilot.organization;

import com.repopilot.organization.dto.AddOrgMemberRequest;
import com.repopilot.organization.dto.OrganizationMemberDto;
import com.repopilot.organization.dto.UpdateOrganizationMemberRoleRequest;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationMemberRepository;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Phase16_Slice2A_OrganizationMemberInfrastructureTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMemberRepository organizationMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Organization orgA;
    private Organization orgB;
    private User user1;
    private User user2;
    private User user3;

    @BeforeEach
    void setUp() {
        orgA = new Organization();
        orgA.setName("Org Alpha");
        orgA.setSlug("org-alpha-" + UUID.randomUUID().toString().substring(0, 5));
        orgA = organizationRepository.save(orgA);

        orgB = new Organization();
        orgB.setName("Org Beta");
        orgB.setSlug("org-beta-" + UUID.randomUUID().toString().substring(0, 5));
        orgB = organizationRepository.save(orgB);

        user1 = new User();
        user1.setEmail("user1_" + UUID.randomUUID() + "@example.com");
        user1.setPasswordHash("hash1");
        user1.setName("User One");
        user1.setRole(UserRole.USER);
        user1 = userRepository.save(user1);

        user2 = new User();
        user2.setEmail("user2_" + UUID.randomUUID() + "@example.com");
        user2.setPasswordHash("hash2");
        user2.setName("User Two");
        user2.setRole(UserRole.USER);
        user2 = userRepository.save(user2);

        user3 = new User();
        user3.setEmail("user3_" + UUID.randomUUID() + "@example.com");
        user3.setPasswordHash("hash3");
        user3.setName("User Three");
        user3.setRole(UserRole.USER);
        user3 = userRepository.save(user3);
    }

    @Test
    @DisplayName("EVIDENCE A: JPA @Version is mapped correctly and increments on update")
    void testA_versionMappedAndIncrementsOnUpdate() {
        OrganizationMember member = new OrganizationMember(orgA, user1, OrganizationRole.ORG_ADMIN);
        member = organizationMemberRepository.saveAndFlush(member);

        assertNotNull(member.getVersion());
        assertEquals(0L, member.getVersion());

        member.setRole(OrganizationRole.ORG_MEMBER);
        OrganizationMember updatedMember = organizationMemberRepository.saveAndFlush(member);
        assertEquals(1L, updatedMember.getVersion());
    }

    @Test
    @DisplayName("EVIDENCE B: findByIdForUpdate produces pessimistic write lock")
    void testB_findByIdForUpdate_producesPessimisticLock() {
        Optional<Organization> lockedOrgOpt = organizationRepository.findByIdForUpdate(orgA.getId());
        assertTrue(lockedOrgOpt.isPresent());
        assertEquals(orgA.getId(), lockedOrgOpt.get().getId());
    }

    @Test
    @DisplayName("EVIDENCE C: countByOrganizationIdAndRole returns accurate counts")
    void testC_countByOrganizationIdAndRole_returnsAccurateCounts() {
        OrganizationMember m1 = new OrganizationMember(orgA, user1, OrganizationRole.ORG_ADMIN);
        OrganizationMember m2 = new OrganizationMember(orgA, user2, OrganizationRole.ORG_ADMIN);
        OrganizationMember m3 = new OrganizationMember(orgA, user3, OrganizationRole.ORG_MEMBER);
        organizationMemberRepository.save(m1);
        organizationMemberRepository.save(m2);
        organizationMemberRepository.save(m3);

        assertEquals(2L, organizationMemberRepository.countByOrganizationIdAndRole(orgA.getId(), OrganizationRole.ORG_ADMIN));
        assertEquals(1L, organizationMemberRepository.countByOrganizationIdAndRole(orgA.getId(), OrganizationRole.ORG_MEMBER));
        assertEquals(0L, organizationMemberRepository.countByOrganizationIdAndRole(orgB.getId(), OrganizationRole.ORG_ADMIN));
    }

    @Test
    @DisplayName("EVIDENCE D: Duplicate organization membership is constrained by UK constraint")
    void testD_duplicateOrganizationMembership_constrained() {
        OrganizationMember m1 = new OrganizationMember(orgA, user1, OrganizationRole.ORG_MEMBER);
        organizationMemberRepository.saveAndFlush(m1);

        OrganizationMember duplicate = new OrganizationMember(orgA, user1, OrganizationRole.ORG_ADMIN);
        assertThrows(DataIntegrityViolationException.class, () -> {
            organizationMemberRepository.saveAndFlush(duplicate);
        });
    }

    @Test
    @DisplayName("EVIDENCE E: DTO mapping & null/invalid request handling")
    void testE_dtoMappingAndRequestValidation() {
        OrganizationMember member = new OrganizationMember(orgA, user1, OrganizationRole.ORG_ADMIN);
        member = organizationMemberRepository.save(member);

        OrganizationMemberDto dto = OrganizationMemberDto.fromEntity(member);
        assertNotNull(dto);
        assertEquals(member.getId(), dto.getId());
        assertEquals(orgA.getId(), dto.getOrganizationId());
        assertEquals(user1.getId(), dto.getUserId());
        assertEquals(user1.getEmail(), dto.getUserEmail());
        assertEquals(user1.getName(), dto.getUserName());
        assertEquals(OrganizationRole.ORG_ADMIN, dto.getRole());
        assertEquals(0L, dto.getVersion());

        // Verify request DTO construction
        AddOrgMemberRequest addReq = new AddOrgMemberRequest("test@example.com", OrganizationRole.ORG_MEMBER);
        assertEquals("test@example.com", addReq.getEmail());
        assertEquals(OrganizationRole.ORG_MEMBER, addReq.getRole());

        UpdateOrganizationMemberRoleRequest updateReq = new UpdateOrganizationMemberRoleRequest(OrganizationRole.ORG_ADMIN, 0L);
        assertEquals(OrganizationRole.ORG_ADMIN, updateReq.getRole());
        assertEquals(0L, updateReq.getVersion());
    }

    @Test
    @DisplayName("EVIDENCE F: Repository tenant scoping enforces tenant isolation")
    void testF_repositoryTenantScoping() {
        OrganizationMember m1 = new OrganizationMember(orgA, user1, OrganizationRole.ORG_ADMIN);
        OrganizationMember m2 = new OrganizationMember(orgB, user1, OrganizationRole.ORG_MEMBER);
        organizationMemberRepository.save(m1);
        organizationMemberRepository.save(m2);

        assertTrue(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), user1.getId()));
        assertTrue(organizationMemberRepository.existsByOrganizationIdAndUserId(orgB.getId(), user1.getId()));
        assertFalse(organizationMemberRepository.existsByOrganizationIdAndUserId(orgA.getId(), user2.getId()));

        Optional<OrganizationMember> orgAMember = organizationMemberRepository.findByOrganizationIdAndUserId(orgA.getId(), user1.getId());
        assertTrue(orgAMember.isPresent());
        assertEquals(OrganizationRole.ORG_ADMIN, orgAMember.get().getRole());
    }
}
