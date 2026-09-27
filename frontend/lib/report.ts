import { apiFetch } from './api';

export interface ImplementationReportDto {
  id: string;
  projectId: string;
  repositoryId?: string;
  taskId: string;
  taskExecutionId: string;
  status: string;
  executionOutcome: string;
  changedFilesSummary: string;
  testSummary: string;
  securityReviewSummary: string;
  confidenceScore: number;
  sourceContextHash: string;
  correlationId?: string;
  failureReason?: string;
  createdAt: string;
}

export interface ReportSynthesisDto {
  id: string;
  implementationReportId: string;
  taskExecutionId: string;
  projectId: string;
  sourceContextHash: string;
  aiSynthesisInputHash: string;
  synthesizerVersion?: string;
  promptVersion?: string;
  modelIdentifier?: string;
  validatorVersion?: string;
  executiveSummary: string;
  implementationExplanation: string;
  testingExplanation: string;
  securityExplanation: string;
  limitations: string[];
  developerNotes: string[];
  renderedMarkdown: string;
  createdAt: string;
}

export interface TaskReportResponse {
  projectId: string;
  repositoryId?: string;
  taskId: string;
  taskExecutionId?: string;
  implementationReport: ImplementationReportDto;
  synthesis?: ReportSynthesisDto | null;
  synthesisStatus: 'AVAILABLE' | 'NONE' | 'FAILED';
}

export async function fetchTaskReport(
  projectId: string,
  taskId: string,
  repositoryId?: string,
  signal?: AbortSignal
): Promise<TaskReportResponse> {
  const path = repositoryId
    ? `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/report`
    : `/projects/${projectId}/tasks/${taskId}/report`;

  return apiFetch<TaskReportResponse>(path, { signal });
}
