from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any

class IssueUnderstandingRequest(BaseModel):
    title: str = Field(..., description="Issue title")
    description: str = Field(..., description="Issue description")

class IssueUnderstandingResponse(BaseModel):
    problem_statement: str = Field(default="", validation_alias="problemStatement", description="Clear summary of the problem")
    requested_behavior: List[str] = Field(default_factory=list, validation_alias="requestedBehavior", description="List of expected behaviors")
    acceptance_criteria: List[str] = Field(default_factory=list, validation_alias="acceptanceCriteria", description="Acceptance criteria")
    affected_areas: List[str] = Field(default_factory=list, validation_alias="affectedAreas", description="Likely affected modules/areas")
    constraints: List[str] = Field(default_factory=list, description="Technical constraints")
    ambiguities: List[str] = Field(default_factory=list, description="Unclear or missing aspects")
    assumptions: List[str] = Field(default_factory=list, description="Assumptions made")
    search_queries: List[str] = Field(default_factory=list, validation_alias="searchQueries", description="2-4 semantic search queries for vector retrieval")

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class CodeEvidenceChunk(BaseModel):
    chunk_id: Optional[str] = Field(default="", validation_alias="chunkId")
    file_path: Optional[str] = Field(default="", validation_alias="filePath")
    file_language: Optional[str] = Field(default="", validation_alias="fileLanguage")
    symbol_name: Optional[str] = Field(default="", validation_alias="symbolName")
    chunk_type: Optional[str] = Field(default="", validation_alias="chunkType")
    start_line: Optional[int] = Field(default=0, validation_alias="startLine")
    end_line: Optional[int] = Field(default=0, validation_alias="endLine")
    content: Optional[str] = Field(default="", validation_alias="contentSnippet")
    similarity_score: Optional[float] = Field(default=0.0, validation_alias="similarityScore")
    distance: Optional[float] = Field(default=0.0)

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class ImplementationPlanRequest(BaseModel):
    title: str
    description: str
    issue_understanding: IssueUnderstandingResponse = Field(default_factory=IssueUnderstandingResponse, validation_alias="issueUnderstanding")
    evidence_chunks: List[CodeEvidenceChunk] = Field(default_factory=list, validation_alias="evidenceChunks")

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class ProposedChangeItem(BaseModel):
    file_path: Optional[str] = Field(default="", validation_alias="filePath")
    symbol_name: Optional[str] = Field(default="", validation_alias="symbolName")
    start_line: Optional[int] = Field(default=None, validation_alias="startLine")
    end_line: Optional[int] = Field(default=None, validation_alias="endLine")
    reason: Optional[str] = Field(default="")
    change_description: Optional[str] = Field(default="", validation_alias="changeDescription")

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class ImplementationPlanResponse(BaseModel):
    summary: str
    problem_analysis: str
    relevant_files: List[str] = Field(default_factory=list)
    relevant_symbols: List[str] = Field(default_factory=list)
    current_behavior: str
    proposed_changes: List[ProposedChangeItem] = Field(default_factory=list)
    step_by_step_plan: List[str] = Field(default_factory=list)
    data_api_changes: str
    testing_strategy: List[str] = Field(default_factory=list)
    risks: List[str] = Field(default_factory=list)
    assumptions: List[str] = Field(default_factory=list)
    open_questions: List[str] = Field(default_factory=list)
    confidence_level: str = "MEDIUM"  # HIGH, MEDIUM, LOW
    confidence_score: float = 0.70
    is_ambiguous: bool = False
    ambiguity_reason: Optional[str] = None
    evidence_chunk_ids: List[str] = Field(default_factory=list)

# Phase 9: Code Generation Schemas

class CodeGenerationRequest(BaseModel):
    task_title: Optional[str] = Field(default="", validation_alias="taskTitle")
    task_description: Optional[str] = Field(default="", validation_alias="taskDescription")
    issue_understanding: Optional[Dict[str, Any]] = Field(default_factory=dict, validation_alias="issueUnderstanding")
    plan_summary: Optional[str] = Field(default="", validation_alias="planSummary")
    proposed_changes: List[ProposedChangeItem] = Field(default_factory=list, validation_alias="proposedChanges")
    evidence_chunks: List[CodeEvidenceChunk] = Field(default_factory=list, validation_alias="evidenceChunks")
    file_contents: Dict[str, str] = Field(default_factory=dict, validation_alias="fileContents")

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class GeneratedCodeChangeItem(BaseModel):
    file_path: Optional[str] = Field(default="", validation_alias="filePath")
    change_type: Optional[str] = Field(default="MODIFY", validation_alias="changeType")  # CREATE, MODIFY, DELETE
    reason: Optional[str] = ""
    original_content: Optional[str] = Field(default="", validation_alias="originalContent")
    proposed_content: Optional[str] = Field(default="", validation_alias="proposedContent")
    affected_symbols: List[str] = Field(default_factory=list, validation_alias="affectedSymbols")
    start_line: Optional[int] = Field(default=1, validation_alias="startLine")
    end_line: Optional[int] = Field(default=1, validation_alias="endLine")
    evidence_chunk_ids: List[str] = Field(default_factory=list, validation_alias="evidenceChunkIds")
    confidence: Optional[str] = "HIGH"  # HIGH, MEDIUM, LOW

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class CodeGenerationResponse(BaseModel):
    status: str = "SUCCESS"  # SUCCESS, INSUFFICIENT_CONTEXT, REJECTED_SECURITY
    message: str = "Code changes generated successfully."
    changes: List[GeneratedCodeChangeItem] = Field(default_factory=list)
