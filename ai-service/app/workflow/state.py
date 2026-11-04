from typing import TypedDict, List, Dict, Any, Optional
from pydantic import BaseModel, Field

class EngineeringWorkflowState(TypedDict, total=False):
    """
    Typed shared state for the RepoPilot LangGraph engineering workflow.
    Preserves task and repository correlation without storing credentials or secrets.
    """
    # Core Task & Repository Identifiers
    task_id: str
    repository_id: str
    project_id: Optional[str]

    # Issue Understanding Phase
    issue_title: str
    issue_description: str
    issue_analysis: Optional[Dict[str, Any]]

    # Retrieval & RAG Phase
    retrieved_context: List[Dict[str, Any]]
    search_queries: List[str]

    # Implementation Planning Phase
    implementation_plan: Optional[Dict[str, Any]]

    # Code Generation & Modification Phase
    code_changes: List[Dict[str, Any]]
    file_contents: Dict[str, str]

    # Validation Phase
    validation_results: Dict[str, Any]

    # Test Execution Phase
    test_results: Dict[str, Any]
    test_runner_config: Dict[str, Any]

    # Diagnosis & Repair Loop
    diagnosis: Optional[Dict[str, Any]]
    repair_attempts: int
    max_repair_attempts: int
    repair_history: List[Dict[str, Any]]

    # AI Code Review Phase
    review_results: Optional[Dict[str, Any]]

    # Human-in-the-Loop & Publication Boundary
    approval_status: str  # "NOT_REQUESTED", "PENDING", "APPROVED", "REJECTED"
    approval_metadata: Optional[Dict[str, Any]]
    is_published: bool

    # Workflow Status & Telemetry
    workflow_status: str  # e.g., "INITIALIZED", "ANALYZING", "PLANNING", "GENERATING_CODE", "VALIDATING", "TESTING", "REPAIRING", "REVIEWING", "AWAITING_APPROVAL", "COMPLETED", "FAILED"
    error_message: Optional[str]
    execution_metadata: Dict[str, Any]

# Pydantic schemas for API request / response

class WorkflowRunRequest(BaseModel):
    task_id: str = Field(..., description="Unique task identifier")
    repository_id: str = Field(..., description="Repository UUID or identifier")
    project_id: Optional[str] = Field(default=None, description="Optional project UUID")
    issue_title: str = Field(..., description="Issue title", min_length=1)
    issue_description: str = Field(default="", description="Detailed issue description")
    file_contents: Optional[Dict[str, str]] = Field(default_factory=dict, description="Preloaded file contents")
    evidence_chunks: Optional[List[Dict[str, Any]]] = Field(default_factory=list, description="Optional pre-retrieved context chunks")
    max_repair_attempts: Optional[int] = Field(default=3, description="Maximum automated repair attempts", ge=0, le=10)
    test_runner_config: Optional[Dict[str, Any]] = Field(default_factory=dict, description="Test execution configuration or mock options")
    auto_approve: Optional[bool] = Field(default=False, description="Require human approval before finalizing publication readiness")

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class WorkflowResumeRequest(BaseModel):
    task_id: str = Field(..., description="Task ID to resume from checkpoint")
    approved: bool = Field(..., description="Human approval decision (True to proceed, False to reject)")
    approver: Optional[str] = Field(default="human_reviewer", description="Identifier of the human reviewer")
    reason: Optional[str] = Field(default="", description="Optional feedback or rejection reason")

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class WorkflowResponse(BaseModel):
    task_id: str
    repository_id: str
    workflow_status: str
    approval_status: str
    repair_attempts: int
    is_published: bool
    summary: Optional[str] = None
    plan: Optional[Dict[str, Any]] = None
    code_changes: List[Dict[str, Any]] = Field(default_factory=list)
    validation_results: Optional[Dict[str, Any]] = None
    test_results: Optional[Dict[str, Any]] = None
    review_results: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None
    execution_metadata: Dict[str, Any] = Field(default_factory=dict)
