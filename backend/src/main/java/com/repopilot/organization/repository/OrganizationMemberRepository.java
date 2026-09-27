package com.repopilot.organization.repository;

import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {

    Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<OrganizationMember> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    List<OrganizationMember> findByOrganizationId(UUID organizationId);

    @Query("SELECT om FROM OrganizationMember om JOIN FETCH om.organization WHERE om.user.id = :userId")
    List<OrganizationMember> findAllByUserIdWithOrganization(@Param("userId") UUID userId);

    long countByOrganizationIdAndRole(UUID organizationId, OrganizationRole role);
}
