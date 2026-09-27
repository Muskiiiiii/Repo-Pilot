export type ReviewMode = 'CODE_CHANGE_REVIEW' | 'SECURITY_REVIEW' | 'TASK_IMPLEMENTATION_REVIEW';
export type ReviewStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type RiskScore = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
export type FindingSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
export type FindingConfidence = 'HIGH' | 'MEDIUM' | 'LOW';
export type FindingSide = 'ORIGINAL' | 'PROPOSED';
export type FindingType = 'DETERMINISTIC_FINDING' | 'AI_FINDING';

export interface ValidatedFindingCandidate {
  findingType?: FindingType;
  category?: string;
  cweId?: string;
  severity?: FindingSeverity;
  confidence?: FindingConfidence;
  title?: string;
  description?: string;
  filePath?: string;
  startLine?: number | null;
  endLine?: number | null;
  side?: FindingSide;
  evidence?: string;
  remediation?: string;
  contentHash?: string;
  fingerprint?: string;
  isUnverifiedSecurityClaim?: boolean;
}

export interface CodeReviewResultDto {
  reviewId: string;
  projectId: string;
  repositoryId: string;
  taskId: string;
  codeChangeId?: string | null;
  reviewMode: ReviewMode;
  status: ReviewStatus;
  summary: string;
  overallRiskScore: RiskScore;
  reviewContextHash?: string;
  modelIdentifier?: string;
  executionTimeMs?: number;
  createdAt: string;
  completedAt?: string | null;
  totalAiFindings: number;
  acceptedFindingsCount: number;
  rejectedFindingsCount: number;
  findings: ValidatedFindingCandidate[];
}

export interface CodeReviewCreateRequest {
  reviewMode?: ReviewMode;
  idempotencyKey?: string;
}
