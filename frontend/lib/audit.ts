import { apiFetch } from './api';
import { PublicationAuditEventResponseDto } from '@/types/audit';

/**
 * Fetches the audit trail for a task.
 */
export async function fetchTaskAuditTrail(
  projectId: string,
  repositoryId: string,
  taskId: string
): Promise<PublicationAuditEventResponseDto[]> {
  return apiFetch<PublicationAuditEventResponseDto[]>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/audit`
  );
}

/**
 * Fetches the audit trail for a specific code change.
 */
export async function fetchCodeChangeAuditTrail(
  projectId: string,
  repositoryId: string,
  taskId: string,
  codeChangeId: string
): Promise<PublicationAuditEventResponseDto[]> {
  return apiFetch<PublicationAuditEventResponseDto[]>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/code-changes/${encodeURIComponent(codeChangeId)}/audit`
  );
}
