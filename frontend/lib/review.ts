import { apiFetch } from './api';
import { CodeReviewResultDto } from '@/types/review';

/**
 * Fetches a single code review by ID.
 * Performs safe GET request to backend REST API.
 */
export async function fetchCodeReview(
  projectId: string,
  repositoryId: string,
  taskId: string,
  reviewId: string
): Promise<CodeReviewResultDto> {
  return apiFetch<CodeReviewResultDto>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/reviews/${encodeURIComponent(reviewId)}`
  );
}

/**
 * Fetches all code reviews associated with a task.
 */
export async function fetchCodeReviewsForTask(
  projectId: string,
  repositoryId: string,
  taskId: string
): Promise<CodeReviewResultDto[]> {
  return apiFetch<CodeReviewResultDto[]>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/reviews`
  );
}

/**
 * Fetches all code reviews associated with a specific code change.
 */
export async function fetchCodeReviewsForCodeChange(
  projectId: string,
  repositoryId: string,
  taskId: string,
  codeChangeId: string
): Promise<CodeReviewResultDto[]> {
  return apiFetch<CodeReviewResultDto[]>(
    `/projects/${encodeURIComponent(projectId)}/repositories/${encodeURIComponent(repositoryId)}/tasks/${encodeURIComponent(taskId)}/code-changes/${encodeURIComponent(codeChangeId)}/reviews`
  );
}
