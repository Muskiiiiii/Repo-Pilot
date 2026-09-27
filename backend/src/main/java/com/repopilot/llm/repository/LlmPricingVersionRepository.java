package com.repopilot.llm.repository;

import com.repopilot.llm.entity.LlmPricingVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmPricingVersionRepository extends JpaRepository<LlmPricingVersion, UUID> {
    Optional<LlmPricingVersion> findByProviderIdAndModelIdAndPricingVersion(
            String providerId, String modelId, Integer pricingVersion);
}
