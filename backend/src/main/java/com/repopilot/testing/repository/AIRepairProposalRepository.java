package com.repopilot.testing.repository;

import com.repopilot.testing.entity.AIRepairProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AIRepairProposalRepository extends JpaRepository<AIRepairProposal, UUID> {
    Optional<AIRepairProposal> findByRepairIterationId(UUID repairIterationId);
}
