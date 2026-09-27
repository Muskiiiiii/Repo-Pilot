package com.repopilot.llm.repository;

import com.repopilot.llm.entity.LlmExceptionalFallbackPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmExceptionalFallbackPolicyRepository extends JpaRepository<LlmExceptionalFallbackPolicy, UUID> {

    Optional<LlmExceptionalFallbackPolicy> findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderIdAndPolicyVersion(
            UUID organizationId,
            String sourceAuthorityType,
            String originalProviderId,
            String targetProviderId,
            Integer policyVersion
    );

    List<LlmExceptionalFallbackPolicy> findByOrganizationId(UUID organizationId);

    Page<LlmExceptionalFallbackPolicy> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    @Query("SELECT COALESCE(MAX(p.policyVersion), 0) FROM LlmExceptionalFallbackPolicy p WHERE p.organization.id = :organizationId AND p.sourceAuthorityType = :sourceAuthorityType AND p.originalProviderId = :originalProviderId AND p.targetProviderId = :targetProviderId")
    Integer findMaxPolicyVersion(
            @Param("organizationId") UUID organizationId,
            @Param("sourceAuthorityType") String sourceAuthorityType,
            @Param("originalProviderId") String originalProviderId,
            @Param("targetProviderId") String targetProviderId
    );
}
