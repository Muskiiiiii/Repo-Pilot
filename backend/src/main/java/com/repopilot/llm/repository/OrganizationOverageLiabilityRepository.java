package com.repopilot.llm.repository;

import com.repopilot.llm.entity.OrganizationOverageLiability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrganizationOverageLiabilityRepository extends JpaRepository<OrganizationOverageLiability, UUID> {
    List<OrganizationOverageLiability> findByOrganizationId(UUID organizationId);
}
