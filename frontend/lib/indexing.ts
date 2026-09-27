import { apiFetch } from './api';

export interface RepositoryIndexProgress {
  repositoryId: string;
  status: 'NOT_INDEXED' | 'INDEXING' | 'INDEXED' | 'FAILED' | 'CANCELLED';
  totalFiles: number;
  processedFiles: number;
  totalChunks: number;
  embeddedChunks: number;
  remainingEmbeddings?: number;
  progressPercentage: number;
  ratePerMinute?: number | null;
  estimatedRemainingSeconds?: number | null;
  lastProgressAt?: string | null;
  health?: 'HEALTHY' | 'SLOW' | 'STALLED' | 'STOPPED' | 'READY' | 'FAILED' | 'NOT_INDEXED';
  canStop?: boolean;
  canResume?: boolean;
  errorMessage: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
}

export async function triggerRepositoryIndexing(
  projectId: string,
  repositoryId: string,
  branch?: string
): Promise<RepositoryIndexProgress> {
  const query = branch ? `?branch=${encodeURIComponent(branch)}` : '';
  return apiFetch<RepositoryIndexProgress>(
    `/projects/${projectId}/repositories/${repositoryId}/index${query}`,
    {
      method: 'POST',
    }
  );
}

export async function stopRepositoryIndexing(
  projectId: string,
  repositoryId: string
): Promise<RepositoryIndexProgress> {
  return apiFetch<RepositoryIndexProgress>(
    `/projects/${projectId}/repositories/${repositoryId}/index/stop`,
    {
      method: 'POST',
    }
  );
}

export async function resumeRepositoryIndexing(
  projectId: string,
  repositoryId: string
): Promise<RepositoryIndexProgress> {
  return apiFetch<RepositoryIndexProgress>(
    `/projects/${projectId}/repositories/${repositoryId}/index/resume`,
    {
      method: 'POST',
    }
  );
}

export async function fetchRepositoryIndexStatus(
  projectId: string,
  repositoryId: string
): Promise<RepositoryIndexProgress> {
  return apiFetch<RepositoryIndexProgress>(
    `/projects/${projectId}/repositories/${repositoryId}/index/status`
  );
}
