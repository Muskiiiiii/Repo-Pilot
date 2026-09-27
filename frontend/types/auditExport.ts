export type AuditExportJobStatus =
  | 'PENDING'
  | 'CLAIMED'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'DELETED';

export interface AuditExportJobResponse {
  jobId: string;
  organizationId: string;
  requestedByUserId: string;
  status: AuditExportJobStatus;
  snapshotTimestamp: string;
  maxSequenceNumber: number;
  maxAuditOutboxId?: string | null;
  exportScope: string;
  filterFromTimestamp?: string | null;
  filterToTimestamp?: string | null;
  fencingVersion: number;
  keyVersion?: number | null;
  recordCount?: number | null;
  artifactSizeBytes?: number | null;
  artifactChecksumSha256?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
  expiresAt?: string | null;
}

export interface CreateAuditExportRequest {
  exportScope: string;
  filterFromTimestamp?: string | null;
  filterToTimestamp?: string | null;
  idempotencyKey?: string | null;
}

export interface AuditExportTokenResponse {
  token: string;
  jobId: string;
  expiresAt: string;
}

export interface PageResponse<T> {
  content: T[];
  pageable: {
    pageNumber: number;
    pageSize: number;
  };
  totalElements: number;
  totalPages: number;
  last: boolean;
  first: boolean;
}
