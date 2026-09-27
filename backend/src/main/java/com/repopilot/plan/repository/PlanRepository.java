package com.repopilot.plan.repository;

import com.repopilot.plan.entity.Plan;
import com.repopilot.task.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PlanRepository extends JpaRepository<Plan, UUID> {

    List<Plan> findByTask(Task task);

    List<Plan> findByTaskId(UUID taskId);
}
