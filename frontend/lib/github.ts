import { apiFetch } from './api';
import {
  GithubAuthorizeResponse,
  GithubRepo,
  GithubStatus,
  ImportRepositoryRequest,
  ProjectRepository,
  Branch,
  FileTreeItem,
  FileContent,
} from '@/types/github';

export async function fetchGithubAuthorizeUrl(): Promise<GithubAuthorizeResponse> {
  return apiFetch<GithubAuthorizeResponse>('/github/authorize');
}

export async function handleGithubCallback(code: string, state: string): Promise<GithubStatus> {
  return apiFetch<GithubStatus>('/github/callback', {
    method: 'POST',
    body: JSON.stringify({ code, state }),
  });
}

export async function fetchGithubStatus(): Promise<GithubStatus> {
  return apiFetch<GithubStatus>('/github/status');
}

export async function disconnectGithub(): Promise<void> {
  await apiFetch('/github/disconnect', {
    method: 'POST',
  });
}

export async function fetchUserGithubRepos(): Promise<GithubRepo[]> {
  return apiFetch<GithubRepo[]>('/github/repositories');
}

export async function fetchProjectRepositories(projectId: string): Promise<ProjectRepository[]> {
  return apiFetch<ProjectRepository[]>(`/projects/${projectId}/repositories`);
}

export async function importRepositoryToProject(
  projectId: string,
  req: ImportRepositoryRequest
): Promise<ProjectRepository> {
  return apiFetch<ProjectRepository>(`/projects/${projectId}/repositories/import`, {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export async function fetchProjectRepositoryDetails(
  projectId: string,
  repositoryId: string
): Promise<ProjectRepository> {
  return apiFetch<ProjectRepository>(`/projects/${projectId}/repositories/${repositoryId}`);
}

export async function fetchRepositoryBranches(
  projectId: string,
  repositoryId: string
): Promise<Branch[]> {
  return apiFetch<Branch[]>(`/projects/${projectId}/repositories/${repositoryId}/branches`);
}

export async function fetchRepositoryFileTree(
  projectId: string,
  repositoryId: string,
  branch?: string
): Promise<FileTreeItem[]> {
  const query = branch ? `?branch=${encodeURIComponent(branch)}` : '';
  return apiFetch<FileTreeItem[]>(`/projects/${projectId}/repositories/${repositoryId}/files${query}`);
}

export async function fetchRepositoryFileContent(
  projectId: string,
  repositoryId: string,
  branch: string,
  path: string
): Promise<FileContent> {
  const query = `?branch=${encodeURIComponent(branch)}&path=${encodeURIComponent(path)}`;
  return apiFetch<FileContent>(`/projects/${projectId}/repositories/${repositoryId}/files/content${query}`);
}
