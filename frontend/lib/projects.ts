import { apiFetch } from './api';
import { Project, ProjectMember, CreateProjectPayload, UpdateProjectPayload, AddMemberPayload } from '@/types/project';

export async function fetchProjects(query?: string): Promise<Project[]> {
  const endpoint = query ? `/projects?query=${encodeURIComponent(query)}` : '/projects';
  return apiFetch<Project[]>(endpoint);
}

export async function fetchProjectById(id: string): Promise<Project> {
  return apiFetch<Project>(`/projects/${id}`);
}

export async function createProject(payload: CreateProjectPayload): Promise<Project> {
  return apiFetch<Project>('/projects', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function updateProject(id: string, payload: UpdateProjectPayload): Promise<Project> {
  return apiFetch<Project>(`/projects/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export async function deleteProject(id: string): Promise<void> {
  await apiFetch(`/projects/${id}`, {
    method: 'DELETE',
  });
}

export async function fetchProjectMembers(id: string): Promise<ProjectMember[]> {
  return apiFetch<ProjectMember[]>(`/projects/${id}/members`);
}

export async function addProjectMember(id: string, payload: AddMemberPayload): Promise<ProjectMember> {
  return apiFetch<ProjectMember>(`/projects/${id}/members`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export async function removeProjectMember(id: string, memberId: string): Promise<void> {
  await apiFetch(`/projects/${id}/members/${memberId}`, {
    method: 'DELETE',
  });
}
