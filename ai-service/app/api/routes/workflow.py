import logging
from fastapi import APIRouter, HTTPException, status
from app.workflow.state import (
    WorkflowRunRequest,
    WorkflowResumeRequest,
    WorkflowResponse
)
from app.workflow.service import WorkflowService

logger = logging.getLogger("repopilot.ai.api.workflow")

router = APIRouter(prefix="/api/v1/workflow", tags=["workflow"])

@router.post("/run", response_model=WorkflowResponse)
async def handle_run_workflow(request: WorkflowRunRequest):
    """
    Execute the complete RepoPilot LangGraph agentic engineering workflow.
    Orchestrates issue understanding, context retrieval, planning, code generation,
    validation, test execution, bounded repair loop, AI review, and approval boundary.
    """
    if not request.task_id or not request.task_id.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="task_id cannot be blank"
        )
    if not request.issue_title or not request.issue_title.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="issue_title cannot be blank"
        )

    try:
        logger.info("Starting LangGraph workflow for task: '%s'", request.task_id)
        response = await WorkflowService.run(request)
        return response
    except Exception as e:
        logger.error("Unhandled error in workflow run endpoint: %s", str(e), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to execute workflow: {str(e)}"
        )

@router.post("/resume", response_model=WorkflowResponse)
async def handle_resume_workflow(request: WorkflowResumeRequest):
    """
    Resume a paused LangGraph workflow after human approval or rejection.
    Enforces human-in-the-loop governance before any publication decision.
    """
    if not request.task_id or not request.task_id.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="task_id cannot be blank"
        )

    try:
        logger.info("Resuming LangGraph workflow for task: '%s' (Approved: %s)",
                    request.task_id, request.approved)
        response = await WorkflowService.resume(request)
        return response
    except ValueError as ve:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(ve)
        )
    except Exception as e:
        logger.error("Unhandled error in workflow resume endpoint: %s", str(e), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to resume workflow: {str(e)}"
        )

@router.get("/status/{task_id}", response_model=WorkflowResponse)
async def handle_get_workflow_status(task_id: str):
    """Retrieve current LangGraph state and checkpoint history for a given task."""
    if not task_id or not task_id.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="task_id cannot be blank"
        )

    state = WorkflowService.get_state(task_id)
    if not state:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No workflow execution found for task: {task_id}"
        )

    return state
