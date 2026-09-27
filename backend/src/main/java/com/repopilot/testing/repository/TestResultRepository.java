package com.repopilot.testing.repository;

import com.repopilot.testing.entity.TestResult;
import com.repopilot.testing.entity.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TestResultRepository extends JpaRepository<TestResult, UUID> {

    List<TestResult> findByTestRun(TestRun testRun);

    List<TestResult> findByTestRunId(UUID testRunId);
}
