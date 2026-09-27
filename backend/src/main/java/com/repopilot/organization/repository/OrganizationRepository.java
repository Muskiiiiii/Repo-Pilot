package com.repopilot.organization.repository;

import com.repopilot.organization.entity.Organization;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query(value = "SELECT * FROM organizations WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Organization> findByIdForUpdate(@Param("id") UUID id);
}
