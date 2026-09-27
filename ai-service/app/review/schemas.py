from enum import Enum
from typing import List, Optional
from pydantic import BaseModel, Field, field_validator
import re

class ReviewMode(str, Enum):
    CODE_CHANGE_REVIEW = "CODE_CHANGE_REVIEW"
    TASK_IMPLEMENTATION_REVIEW = "TASK_IMPLEMENTATION_REVIEW"
    SECURITY_REVIEW = "SECURITY_REVIEW"

class FindingCategory(str, Enum):
    SECURITY_VULNERABILITY = "SECURITY_VULNERABILITY"
    BUG_RISK = "BUG_RISK"
    CODE_SMELL = "CODE_SMELL"
    PERFORMANCE_ISSUE = "PERFORMANCE_ISSUE"
    DATA_EXPOSURE = "DATA_EXPOSURE"
    INPUT_VALIDATION = "INPUT_VALIDATION"
    AUTHENTICATION_AUTHORIZATION = "AUTHENTICATION_AUTHORIZATION"
    CONCURRENCY_RACE = "CONCURRENCY_RACE"
    ERROR_HANDLING = "ERROR_HANDLING"

class FindingSeverity(str, Enum):
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    INFO = "INFO"

class FindingConfidence(str, Enum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"

class OverallRiskScore(str, Enum):
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"

UUID_PATTERN = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
HASH_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")

class EvidenceRefInput(BaseModel):
    sourceType: str = Field(..., description="Source type tag e.g. CODE_CHANGE, RAG_CHUNK")
    projectId: str = Field(..., description="Project UUID")
    repositoryId: str = Field(..., description="Repository UUID")
    taskId: Optional[str] = Field(default=None, description="Task UUID")
    codeChangeId: Optional[str] = Field(default=None, description="CodeChange UUID")
    filePath: Optional[str] = Field(default=None, description="File path")
    originalOrProposed: Optional[str] = Field(default="N/A", description="ORIGINAL, PROPOSED, or N/A")
    startLine: Optional[int] = Field(default=None, ge=1)
    endLine: Optional[int] = Field(default=None, ge=1)
    contentHash: Optional[str] = Field(default=None)
    sourceSnapshotHash: Optional[str] = Field(default=None)
    chunkId: Optional[str] = Field(default=None)
    authorizationScope: Optional[str] = Field(default=None)

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class ReviewContextInput(BaseModel):
    projectId: str = Field(..., description="Project UUID")
    repositoryId: str = Field(..., description="Repository UUID")
    taskId: str = Field(..., description="Task UUID")
    codeChangeId: Optional[str] = Field(default=None, description="CodeChange UUID")
    reviewMode: ReviewMode = Field(..., description="Review mode enum")
    filePath: Optional[str] = Field(default=None, description="Target file path")
    operation: Optional[str] = Field(default="MODIFY", description="File operation e.g. MODIFY, CREATE, DELETE")
    originalContent: Optional[str] = Field(default="", description="Original file content")
    proposedContent: Optional[str] = Field(default="", description="Proposed file content")
    unifiedDiff: Optional[str] = Field(default="", description="Unified diff snippet")
    originalHash: Optional[str] = Field(default="", description="SHA-256 hash of original content")
    proposedHash: Optional[str] = Field(default="", description="SHA-256 hash of proposed content")
    reviewContextHash: str = Field(..., description="64-character SHA-256 context hash")
    contextVersion: str = Field(default="v1.0", description="Context version e.g. v1.0")
    evidenceReferences: List[EvidenceRefInput] = Field(default_factory=list, description="Evidence provenance list")

    @field_validator("projectId", "repositoryId", "taskId")
    @classmethod
    def validate_uuid_fields(cls, value: str, info) -> str:
        if not value or not UUID_PATTERN.match(value):
            raise ValueError(f"Field '{info.field_name}' must be a valid UUID string: got '{value}'")
        return value

    @field_validator("codeChangeId")
    @classmethod
    def validate_optional_uuid(cls, value: Optional[str], info) -> Optional[str]:
        if value is not None and not UUID_PATTERN.match(value):
            raise ValueError(f"Field '{info.field_name}' must be a valid UUID string: got '{value}'")
        return value

    @field_validator("reviewContextHash")
    @classmethod
    def validate_context_hash(cls, value: str) -> str:
        if not value or not HASH_PATTERN.match(value):
            raise ValueError(f"reviewContextHash must be a 64-character hex string: got '{value}'")
        return value.lower()

    @field_validator("contextVersion")
    @classmethod
    def validate_version(cls, value: str) -> str:
        if value != "v1.0":
            raise ValueError(f"Unsupported contextVersion: '{value}'. Expected 'v1.0'")
        return value

    @field_validator("filePath")
    @classmethod
    def validate_file_path(cls, value: Optional[str]) -> Optional[str]:
        if value:
            if "\0" in value or "\r" in value or "\n" in value:
                raise ValueError("filePath contains control characters or null bytes")
            if value.startswith("/") or value.startswith("\\") or re.match(r"^[a-zA-Z]:", value):
                raise ValueError("filePath cannot be an absolute path")
            if ".." in value or "./.." in value or ".\\.." in value:
                raise ValueError("filePath contains path traversal sequence")
        return value

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class ReviewFindingOutput(BaseModel):
    category: FindingCategory = Field(..., description="Finding category enum")
    cweId: Optional[str] = Field(default=None, description="Optional CWE ID claim e.g. CWE-89 (AI_CLAIM / UNVERIFIED)")
    severity: FindingSeverity = Field(..., description="Severity enum")
    confidence: FindingConfidence = Field(..., description="Confidence enum")
    title: str = Field(..., min_length=1, description="Short title of finding")
    description: str = Field(..., min_length=1, description="Detailed description of problem")
    filePath: str = Field(..., min_length=1, description="Target relative file path")
    startLine: int = Field(..., ge=1, description="1-based start line")
    endLine: int = Field(..., ge=1, description="1-based end line")
    evidence: str = Field(..., description="Code snippet or evidence description")
    remediation: str = Field(..., description="Textual remediation guidance")

    @field_validator("title")
    @classmethod
    def validate_title_length(cls, title: str) -> str:
        if title and len(title) > 255:
            return title[:255]
        return title

    @field_validator("endLine")
    @classmethod
    def validate_line_range(cls, end_line: int, info) -> int:
        start_line = info.data.get("startLine")
        if start_line is not None and end_line < start_line:
            return start_line
        return end_line

    @field_validator("filePath")
    @classmethod
    def validate_output_file_path(cls, value: str) -> str:
        if "\0" in value or "\r" in value or "\n" in value:
            raise ValueError("Output filePath contains control characters")
        if value.startswith("/") or value.startswith("\\") or re.match(r"^[a-zA-Z]:", value):
            raise ValueError("Output filePath cannot be an absolute path")
        if ".." in value:
            raise ValueError("Output filePath contains path traversal")
        return value

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }

class ReviewResponse(BaseModel):
    summary: str = Field(..., description="Overall code review summary")
    overallRiskScore: OverallRiskScore = Field(default=OverallRiskScore.LOW, description="Overall risk score")
    reviewContextHash: str = Field(..., description="Echoed 64-char context hash")
    findings: List[ReviewFindingOutput] = Field(default_factory=list, description="Validated findings list")

    model_config = {
        "populate_by_name": True,
        "extra": "ignore"
    }
