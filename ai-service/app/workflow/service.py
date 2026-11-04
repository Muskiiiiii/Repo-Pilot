import logging
import time
from typing import Dict, Any, Optional
from datetime import datetime

from app.workflow.state import (
    EngineeringWorkflowState,
    WorkflowRunRequest,
    WorkflowResumeRequest,
    WorkflowResponse
)
from app.workflow.graph import engineering_graph, memory_checkpointer

logger = logging.getLogger("repopilot.ai.workflow.service")

# In-memory fast cache for completed / pending workflow states
_WORKFLOW_CACHE: Dict[str, EngineeringWorkflowState] = {}

class WorkflowService:
    """Orchestrates LangGraph execution, checkpoint state management, and resume actions."""

    @staticmethod
    async def run(request: WorkflowRunRequest) -> WorkflowResponse:
        task_id = request.task_id
        logger.info("Initializing LangGraph workflow for task: %s (repo: %s)", task_id, request.repository_id)

        initial_state: EngineeringWorkflowState = {
            "task_id": task_id,
            "repository_id": request.repository_id,
            "project_id": request.project_id,
            "issue_title": request.issue_title,
            "issue_description": request.issue_description,
            "issue_analysis": None,
            "retrieved_context": request.evidence_chunks or [],
            "search_queries": [],
            "implementation_plan": None,
            "code_changes": [],
            "file_contents": request.file_contents or {},
            "validation_results": {},
            "test_results": {},
            "test_runner_config": {
                **(request.test_runner_config or {}),
                "auto_approve": request.auto_approve or False
            },
            "diagnosis": None,
            "repair_attempts": 0,
            "max_repair_attempts": request.max_repair_attempts or 3,
            "repair_history": [],
            "review_results": None,
            "approval_status": "APPROVED" if request.auto_approve else "NOT_REQUESTED",
            "approval_metadata": None,
            "is_published": False,
            "workflow_status": "INITIALIZED",
            "error_message": None,
            "execution_metadata": {
                "started_at": datetime.utcnow().isoformat(),
                "engine": "LangGraph",
                "orchestration_version": "1.0.0"
            }
        }

        config = {"configurable": {"thread_id": task_id}}

        try:
            # Execute graph
            final_state = await engineering_graph.ainvoke(initial_state, config=config)
            _WORKFLOW_CACHE[task_id] = final_state
            return WorkflowService._to_response(final_state)
        except Exception as e:
            logger.error("Error executing LangGraph workflow for task %s: %s", task_id, str(e), exc_info=True)
            initial_state["workflow_status"] = "FAILED_EXECUTION_ERROR"
            initial_state["error_message"] = str(e)
            _WORKFLOW_CACHE[task_id] = initial_state
            return WorkflowService._to_response(initial_state)

    @staticmethod
    async def resume(request: WorkflowResumeRequest) -> WorkflowResponse:
        task_id = request.task_id
        logger.info("Resuming LangGraph workflow for task: %s with approval: %s", task_id, request.approved)

        cached_state = _WORKFLOW_CACHE.get(task_id)
        if not cached_state:
            raise ValueError(f"No checkpoint found for task {task_id}")

        if cached_state.get("workflow_status") != "AWAITING_APPROVAL":
            logger.warning("Task %s is in state '%s', not 'AWAITING_APPROVAL'. Current state will be updated.",
                           task_id, cached_state.get("workflow_status"))

        approval_status = "APPROVED" if request.approved else "REJECTED"
        cached_state["approval_status"] = approval_status
        cached_state["approval_metadata"] = {
            "approver": request.approver,
            "reason": request.reason,
            "timestamp": datetime.utcnow().isoformat()
        }

        # If rejected, immediately set REJECTED status and do not publish
        if not request.approved:
            cached_state["workflow_status"] = "REJECTED"
            cached_state["is_published"] = False
            _WORKFLOW_CACHE[task_id] = cached_state
            return WorkflowService._to_response(cached_state)

        # If approved, mark completed and ready for publication by the Java backend
        cached_state["workflow_status"] = "COMPLETED"
        cached_state["is_published"] = False  # Publication is handled by Java backend after approval
        _WORKFLOW_CACHE[task_id] = cached_state
        return WorkflowService._to_response(cached_state)

    @staticmethod
    def get_state(task_id: str) -> Optional[WorkflowResponse]:
        state = _WORKFLOW_CACHE.get(task_id)
        if not state:
            return None
        return WorkflowService._to_response(state)

    @staticmethod
    def _to_response(state: EngineeringWorkflowState) -> WorkflowResponse:
        plan = state.get("implementation_plan")
        summary = plan.get("summary") if plan else state.get("issue_title")

        return WorkflowResponse(
            task_id=state.get("task_id", ""),
            repository_id=state.get("repository_id", ""),
            workflow_status=state.get("workflow_status", "UNKNOWN"),
            approval_status=state.get("approval_status", "NOT_REQUESTED"),
            repair_attempts=state.get("repair_attempts", 0),
            is_published=state.get("is_published", False),
            summary=summary,
            plan=plan,
            code_changes=state.get("code_changes") or [],
            validation_results=state.get("validation_results"),
            test_results=state.get("test_results"),
            review_results=state.get("review_results"),
            error_message=state.get("error_message"),
            execution_metadata=state.get("execution_metadata") or {}
        )
