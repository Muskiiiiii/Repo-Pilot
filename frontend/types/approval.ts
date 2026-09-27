export type ApprovalStatus = 'APPROVED' | 'REVOKED' | 'SUPERSEDED' | 'STALE';

export type PullRequestPublicationStatus =
  | 'PENDING'
  | 'VERIFYING'
  | 'PUSHING'
  | 'PUSHED'
  | 'RECONCILED'
  | 'PR_CREATING'
  | 'PR_CREATED'
  | 'PR_RECONCILED'
  | 'FAILED_STALE'
  | 'FAILED_CONFLICT'
  | 'FAILED_PUSH'
  | 'FAILED_VERIFICATION'
  | 'FAILED_PR_CREATION'
  | 'FAILED_PR_CONFLICT'
  | 'FAILED_PR_VERIFICATION';

export interface HumanApprovalResponseDto {
  approvalId: string;
  projectId: string;
  repositoryId: string;
  taskId: string;
  codeChangeId: string;
  reviewId?: string | null;
  approvalVersion: number;
  status: ApprovalStatus;
  repositoryBaseSha: string;
  codeChangeOriginalHash: string;
  codeChangeProposedHash: string;
  codeChangeDiffHash: string;
  reviewContextHash: string;
  approvedFiles: string[];
  approvedChangeCount: number;
  approvalRecordHash: string;
  approvedByUserId: string;
  approvedByUserEmail: string;
  approvedByUserRole: string;
  approvedAt: string;
  staleReason?: string | null;
  idempotencyKey: string;
  createdAt: string;
}

export interface GitPullRequestResult {
  operationId: string;
  approvalId: string;
  projectId: string;
  repositoryId: string;
  taskId: string;
  codeChangeId: string;
  githubPrNumber?: number | null;
  prUrl?: string | null;
  branchName?: string | null;
  baseBranch?: string | null;
  commitSha?: string | null;
  title?: string | null;
  status: PullRequestPublicationStatus;
  failureCategory?: string | null;
  failureReason?: string | null;
  createdAt: string;
}

export interface HumanApprovalCreateRequest {
  idempotencyKey?: string;
}
