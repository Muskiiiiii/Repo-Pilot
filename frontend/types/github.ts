export interface GithubStatus {
  connected: boolean;
  githubUsername: string | null;
  connectedAt: string | null;
}

export interface GithubAuthorizeResponse {
  authorizeUrl: string;
  state: string;
}

export interface GithubRepo {
  id: string;
  owner: string;
  name: string;
  fullName: string;
  defaultBranch: string;
  cloneUrl: string;
  htmlUrl: string;
  isPrivate: boolean;
  description: string;
}

export interface ImportRepositoryRequest {
  githubRepoId?: string;
  ownerName: string;
  repoName: string;
  defaultBranch?: string;
  cloneUrl?: string;
  webUrl?: string;
  visibility?: string;
}

export interface ProjectRepository {
  id: string;
  projectId: string;
  githubRepoId: string;
  ownerName: string;
  repoName: string;
  defaultBranch: string;
  cloneUrl: string;
  webUrl: string;
  visibility: string;
  indexStatus: string;
  lastIndexedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Branch {
  id: string | null;
  name: string;
  commitSha: string;
  isDefault: boolean;
}

export interface FileTreeItem {
  path: string;
  type: string; // "blob" | "tree"
  size: number;
  sha: string;
}

export interface FileContent {
  path: string;
  branch: string;
  content: string;
  encoding: string;
  size: number;
  isBinary: boolean;
}
