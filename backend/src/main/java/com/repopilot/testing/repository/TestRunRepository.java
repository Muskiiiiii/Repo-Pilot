package com.repopilot.testing.repository;

import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.testing.entity.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    List<TestRun> findByTask(Task task);

    List<TestRun> findByTaskId(UUID taskId);

    List<TestRun> findByTaskExecution(TaskExecution taskExecution);

    List<TestRun> findByTaskExecutionId(UUID taskExecutionId);
}
