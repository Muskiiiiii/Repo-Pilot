package com.repopilot.testing.repository;

import com.repopilot.testing.entity.AIDiagnosis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AIDiagnosisRepository extends JpaRepository<AIDiagnosis, UUID> {
    Optional<AIDiagnosis> findByRepairIterationId(UUID repairIterationId);
}
