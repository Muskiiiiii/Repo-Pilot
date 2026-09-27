import { apiFetch } from './api';

export interface TaskItem {
  id: string;
  projectId: string;
  repositoryId: string;
  title: string;
  description: string;
  status: string;
  priority: string;
  issueUnderstanding?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ProposedChange {
  filePath: string;
  symbolName?: string;
  startLine?: number;
  endLine?: number;
  reason: string;
  changeDescription: string;
}

export interface EvidenceReference {
  chunkId: string;
  filePath: string;
  fileLanguage?: string;
  symbolName?: string;
  chunkType?: string;
  startLine: number;
  endLine: number;
  contentSnippet?: string;
  similarityScore: number;
  distance: number;
  reason: string;
}

export interface PlanItem {
  id: string;
  taskId: string;
  version: number;
  summary: string;
  problemAnalysis: string;
  relevantFiles: string[];
  relevantSymbols: string[];
  currentBehavior: string;
  proposedChanges: ProposedChange[];
  stepByStepPlan: string[];
  dataApiChanges: string;
  testingStrategy: string[];
  risks: string[];
  assumptions: string[];
  openQuestions: string[];
  confidenceLevel: string;
  confidenceScore: number;
  isAmbiguous: boolean;
  ambiguityReason?: string | null;
  evidenceReferences: EvidenceReference[];
  status: string;
  createdAt: string;
}

export interface CodeChangeItem {
  id: string;
  taskId: string;
  planId?: string;
  repositoryId?: string;
  filePath: string;
  changeType: string; // CREATE, MODIFY, DELETE
  reason: string;
  oldContent?: string;
  newContent?: string;
  originalHash?: string;
  proposedHash?: string;
  diff: string;
  affectedSymbols: string[];
  evidenceReferences: EvidenceReference[];
  confidenceLevel: string;
  confidenceScore: number;
  startLine?: number;
  endLine?: number;
  status: string; // GENERATED, VALIDATION_FAILED, REJECTED, APPROVED
  validationFailureReason?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CodeChangeSet {
  taskId: string;
  planId: string;
  status: string;
  summary: string;
  changes: CodeChangeItem[];
  createdAt: string;
}

export async function createTask(
  projectId: string,
  repositoryId: string,
  title: string,
  description: string
): Promise<TaskItem> {
  return apiFetch<TaskItem>(
    `/projects/${projectId}/repositories/${repositoryId}/tasks`,
    {
      method: 'POST',
      body: JSON.stringify({ title, description }),
    }
  );
}

export async function getTask(
  projectId: string,
  repositoryId: string,
  taskId: string
): Promise<TaskItem> {
  return apiFetch<TaskItem>(
    `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}`
  );
}

export async function generatePlan(
  projectId: string,
  repositoryId: string,
  taskId: string
): Promise<PlanItem> {
  return apiFetch<PlanItem>(
    `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/plan`,
    {
      method: 'POST',
    }
  );
}

export async function getPlan(
  projectId: string,
  repositoryId: string,
  taskId: string
): Promise<PlanItem> {
  return apiFetch<PlanItem>(
    `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/plan`
  );
}

export async function generateCodeChanges(
  projectId: string,
  repositoryId: string,
  taskId: string,
  planId: string
): Promise<CodeChangeSet> {
  return apiFetch<CodeChangeSet>(
    `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/plans/${planId}/generate-code`,
    {
      method: 'POST',
    }
  );
}

export async function getCodeChanges(
  projectId: string,
  repositoryId: string,
  taskId: string
): Promise<CodeChangeItem[]> {
  return apiFetch<CodeChangeItem[]>(
    `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/code-changes`
  );
}
