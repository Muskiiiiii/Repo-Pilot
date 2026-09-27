package com.repopilot.organization.repository;

import com.repopilot.organization.entity.OrganizationUsageCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationUsageCounterRepository extends JpaRepository<OrganizationUsageCounter, UUID> {

    Optional<OrganizationUsageCounter> findByOrganizationId(UUID organizationId);

    @Query(value = "SELECT * FROM organization_usage_counters WHERE organization_id = :organizationId FOR UPDATE", nativeQuery = true)
    Optional<OrganizationUsageCounter> findByOrganizationIdWithLock(@Param("organizationId") UUID organizationId);
}
