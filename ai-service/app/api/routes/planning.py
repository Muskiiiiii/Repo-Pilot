import logging
from fastapi import APIRouter, HTTPException, status
from app.planning.schemas import (
    IssueUnderstandingRequest,
    IssueUnderstandingResponse,
    ImplementationPlanRequest,
    ImplementationPlanResponse,
    CodeGenerationRequest,
    CodeGenerationResponse
)
from app.planning.understanding import analyze_issue
from app.planning.planner import generate_implementation_plan
from app.planning.generator import generate_code_changes

logger = logging.getLogger("repopilot.ai.api.routes.planning")

router = APIRouter(prefix="/api/v1/planning", tags=["planning"])

@router.post("/issue-understanding", response_model=IssueUnderstandingResponse)
async def handle_issue_understanding(request: IssueUnderstandingRequest):
    """Analyze a developer issue/task and return structured problem understanding + search queries."""
    if not request.title or not request.title.strip():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Issue title cannot be blank")

    try:
        logger.info("Analyzing issue title: '%s'", request.title)
        understanding = await analyze_issue(request)
        return understanding
    except Exception as e:
        logger.error("Error in issue understanding endpoint: %s", str(e), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to analyze issue: {str(e)}"
        )

@router.post("/generate-plan", response_model=ImplementationPlanResponse)
async def handle_generate_plan(request: ImplementationPlanRequest):
    """Generate an evidence-backed implementation plan for a developer issue."""
    if not request.title or not request.title.strip():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Task title cannot be blank")

    try:
        logger.info("Generating implementation plan for task: '%s' with %d evidence chunk(s)",
                    request.title, len(request.evidence_chunks or []))
        plan = await generate_implementation_plan(request)
        return plan
    except Exception as e:
        logger.error("Error in generate-plan endpoint: %s", str(e), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to generate implementation plan: {str(e)}"
        )

@router.post("/generate-code", response_model=CodeGenerationResponse)
async def handle_generate_code(request: CodeGenerationRequest):
    """Generate proposed code changes grounded in implementation plan and codebase evidence."""
    if not request.task_title or not request.task_title.strip():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Task title cannot be blank")

    try:
        logger.info("Generating proposed code changes for task: '%s' with %d target file(s)",
                    request.task_title, len(request.proposed_changes or []))
        response = await generate_code_changes(request)
        return response
    except Exception as e:
        logger.error("Error in generate-code endpoint: %s", str(e), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to generate code changes: {str(e)}"
        )
