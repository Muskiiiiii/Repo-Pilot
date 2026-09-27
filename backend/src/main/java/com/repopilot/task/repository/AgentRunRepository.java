package com.repopilot.task.repository;

import com.repopilot.task.entity.AgentRun;
import com.repopilot.task.entity.TaskExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    List<AgentRun> findByTaskExecution(TaskExecution taskExecution);

    List<AgentRun> findByTaskExecutionId(UUID taskExecutionId);
}
