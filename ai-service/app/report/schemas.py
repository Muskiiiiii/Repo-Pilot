import re
from typing import List, Optional
from pydantic import BaseModel, Field, field_validator

MAX_INPUT_BYTES = 500_000
MAX_OUTPUT_BYTES = 100_000

class ReportSynthesisInput(BaseModel):
    implementationReportId: str = Field(..., description="ImplementationReport UUID")
    taskExecutionId: str = Field(..., description="TaskExecution UUID")
    projectId: str = Field(..., description="Project UUID")
    repositoryId: Optional[str] = Field(None, description="Repository UUID")
    taskTitle: Optional[str] = Field(None, description="Task title")
    executionOutcome: str = Field(..., description="Execution outcome string (COMPLETED/FAILED)")
    sourceContextHash: str = Field(..., description="64-character SHA-256 hash")
    changedFilesSummaryJson: Optional[str] = Field(None, description="JSON string of changed files summary")
    testSummaryJson: Optional[str] = Field(None, description="JSON string of test summary")
    securityReviewSummaryJson: Optional[str] = Field(None, description="JSON string of security review summary")
    confidenceScore: Optional[float] = Field(None, description="Confidence score")
    failureReason: Optional[str] = Field(None, description="Redacted failure reason string")
    synthesizerVersion: str = Field("v1.0", description="Synthesizer version")
    promptVersion: str = Field("v1.0", description="Prompt version")
    validatorVersion: str = Field("v1.0", description="Validator version")

    @field_validator("sourceContextHash")
    def validate_hash_format(cls, v: str) -> str:
        if not re.match(r"^[a-fA-F0-9]{64}$", v):
            raise ValueError("sourceContextHash must be a 64-character hexadecimal SHA-256 string")
        return v

class ReportSynthesisOutput(BaseModel):
    executiveSummary: str = Field(..., max_length=2000, description="High-level executive summary of execution outcome")
    implementationExplanation: str = Field(..., max_length=5000, description="Detailed explanation of code changes made")
    testingExplanation: str = Field(..., max_length=5000, description="Explanation of test execution coverage and outcomes")
    securityExplanation: str = Field(..., max_length=5000, description="Explanation of security analysis and finding severities")
    limitations: List[str] = Field(default_factory=list, max_items=10, description="List of known limitations or edge cases")
    developerNotes: List[str] = Field(default_factory=list, max_items=10, description="Developer maintenance notes and guidelines")
