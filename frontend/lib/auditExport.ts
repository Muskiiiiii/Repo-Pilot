import { apiFetch } from './api';
import {
  AuditExportJobResponse,
  AuditExportTokenResponse,
  CreateAuditExportRequest,
  PageResponse,
} from '@/types/auditExport';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080/api/v1';

/**
 * Fetches paginated audit export jobs for an organization.
 */
export async function listAuditExportJobs(
  orgId: string,
  page = 0,
  size = 20
): Promise<PageResponse<AuditExportJobResponse>> {
  return apiFetch<PageResponse<AuditExportJobResponse>>(
    `/organizations/${encodeURIComponent(orgId)}/audit-exports?page=${page}&size=${size}`
  );
}

/**
 * Fetches metadata for a specific audit export job.
 */
export async function getAuditExportJob(
  orgId: string,
  jobId: string
): Promise<AuditExportJobResponse> {
  return apiFetch<AuditExportJobResponse>(
    `/organizations/${encodeURIComponent(orgId)}/audit-exports/${encodeURIComponent(jobId)}`
  );
}

/**
 * Requests creation of a new audit export job (ORG_ADMIN required).
 */
export async function createAuditExportJob(
  orgId: string,
  request: CreateAuditExportRequest
): Promise<AuditExportJobResponse> {
  return apiFetch<AuditExportJobResponse>(
    `/organizations/${encodeURIComponent(orgId)}/audit-exports`,
    {
      method: 'POST',
      body: JSON.stringify(request),
    }
  );
}

/**
 * Requests single-use download token and downloads audit export artifact.
 * Tokens are consumed immediately server-side and NEVER stored in localStorage/sessionStorage.
 */
export async function downloadAuditExport(
  orgId: string,
  jobId: string
): Promise<void> {
  const tokenResp = await apiFetch<AuditExportTokenResponse>(
    `/organizations/${encodeURIComponent(orgId)}/audit-exports/${encodeURIComponent(jobId)}/download-token`,
    {
      method: 'POST',
    }
  );

  const downloadUrl = `${API_BASE_URL}/organizations/${encodeURIComponent(orgId)}/audit-exports/${encodeURIComponent(jobId)}/download?token=${encodeURIComponent(tokenResp.token)}`;

  const token = typeof window !== 'undefined' ? localStorage.getItem('accessToken') : null;
  const headers: Record<string, string> = {};
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(downloadUrl, { headers });
  if (!response.ok) {
    throw new Error(`Download failed with status ${response.status}`);
  }

  const blob = await response.blob();
  if (typeof window !== 'undefined') {
    const blobUrl = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = `audit-export-${jobId}.jsonl.gz`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(blobUrl);
  }
}
