import { User } from './auth';

export type ProjectStatus = 'ACTIVE' | 'ARCHIVED' | 'DELETED';
export type ProjectRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER';

export interface Project {
  id: string;
  name: string;
  description?: string;
  owner: User;
  status: ProjectStatus;
  userRole: ProjectRole;
  createdAt: string;
  updatedAt: string;
  memberCount: number;
  repositoryCount: number;
  taskCount: number;
}

export interface ProjectMember {
  id: string;
  user: User;
  role: ProjectRole;
  createdAt: string;
}

export interface CreateProjectPayload {
  name: string;
  description?: string;
}

export interface UpdateProjectPayload {
  name: string;
  description?: string;
  status?: ProjectStatus;
}

export interface AddMemberPayload {
  email: string;
  role: ProjectRole;
}
