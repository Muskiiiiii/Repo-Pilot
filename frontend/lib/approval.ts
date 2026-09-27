import { apiFetch } from './api';
import {
  HumanApprovalResponseDto,
  HumanApprovalCreateRequest,
  GitPullRequestResult,
} from '@/types/approval';

/**
 * Creates a human approval record for a specific code change.
 */
export async function createHumanApproval(
  projectId: string,
  repositoryId: string,
  taskId: string,
  codeChangeId: string,
  request: HumanApprovalCreateRequest = {}
): Promise<HumanApprovalResponseDto> {
  return apiFetch<HumanApprovalResponseDto>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/code-changes/${encodeURIComponent(codeChangeId)}/approvals`,
    {
      method: 'POST',
      body: JSON.stringify(request),
    }
  );
}

/**
 * Fetches a specific human approval record by approval ID.
 */
export async function fetchApprovalById(
  projectId: string,
  repositoryId: string,
  taskId: string,
  approvalId: string
): Promise<HumanApprovalResponseDto> {
  return apiFetch<HumanApprovalResponseDto>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/approvals/${encodeURIComponent(approvalId)}`
  );
}

/**
 * Fetches all human approval records for a specific code change.
 */
export async function fetchApprovalsForCodeChange(
  projectId: string,
  repositoryId: string,
  taskId: string,
  codeChangeId: string
): Promise<HumanApprovalResponseDto[]> {
  return apiFetch<HumanApprovalResponseDto[]>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/code-changes/${encodeURIComponent(codeChangeId)}/approvals`
  );
}

/**
 * Revokes an existing human approval record.
 */
export async function revokeApproval(
  projectId: string,
  repositoryId: string,
  taskId: string,
  approvalId: string,
  reason: string
): Promise<HumanApprovalResponseDto> {
  return apiFetch<HumanApprovalResponseDto>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/approvals/${encodeURIComponent(approvalId)}/revoke`,
    {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }
  );
}

/**
 * Triggers or recovers GitHub remote publication (git push + pull request creation).
 */
export async function publishOrRecoverApproval(
  projectId: string,
  repositoryId: string,
  taskId: string,
  codeChangeId: string,
  approvalId: string
): Promise<GitPullRequestResult> {
  return apiFetch<GitPullRequestResult>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/code-changes/${encodeURIComponent(codeChangeId)}/approvals/${encodeURIComponent(approvalId)}/publish`,
    {
      method: 'POST',
    }
  );
}
