package com.repopilot.organization.repository;

import com.repopilot.organization.entity.OrganizationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationPolicyRepository extends JpaRepository<OrganizationPolicy, UUID> {

    Optional<OrganizationPolicy> findByOrganizationId(UUID organizationId);

    boolean existsByOrganizationId(UUID organizationId);
}
