package com.repopilot.llm.repository;

import com.repopilot.llm.entity.LlmActiveExceptionalFallbackPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmActiveExceptionalFallbackPolicyRepository extends JpaRepository<LlmActiveExceptionalFallbackPolicy, UUID> {

    @Query("SELECT a FROM LlmActiveExceptionalFallbackPolicy a WHERE a.organization.id = :organizationId AND a.sourceAuthorityType = :sourceAuthorityType AND a.originalProviderId = :originalProviderId AND a.targetProviderId = :targetProviderId")
    Optional<LlmActiveExceptionalFallbackPolicy> findByOrganizationIdAndSourceAuthorityTypeAndOriginalProviderIdAndTargetProviderId(
            @Param("organizationId") UUID organizationId,
            @Param("sourceAuthorityType") String sourceAuthorityType,
            @Param("originalProviderId") String originalProviderId,
            @Param("targetProviderId") String targetProviderId
    );

    @Query("SELECT a FROM LlmActiveExceptionalFallbackPolicy a WHERE a.organization.id = :organizationId")
    List<LlmActiveExceptionalFallbackPolicy> findByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT COUNT(a) FROM LlmActiveExceptionalFallbackPolicy a WHERE a.organization.id = :organizationId")
    long countByOrganizationId(@Param("organizationId") UUID organizationId);
}
