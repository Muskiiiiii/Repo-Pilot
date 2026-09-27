'use client';

import React, { useState, useEffect, useRef } from 'react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import {
  AuditExportJobResponse,
  AuditExportJobStatus,
  CreateAuditExportRequest,
} from '@/types/auditExport';
import {
  listAuditExportJobs,
  getAuditExportJob,
  createAuditExportJob,
  downloadAuditExport,
} from '@/lib/auditExport';

interface AuditExportGovernanceViewProps {
  organizationId: string;
  userRole?: 'ORG_ADMIN' | 'ORG_MEMBER';
}

const SCOPES = [
  { id: 'FULL', label: 'Full Governance Audit' },
  { id: 'AUDIT_LOGS', label: 'Audit Logs Only' },
  { id: 'LLM_LIFECYCLE', label: 'LLM Execution & Fencing' },
  { id: 'POLICY_CHANGES', label: 'Policy Administration' },
];

export const AuditExportGovernanceView: React.FC<AuditExportGovernanceViewProps> = ({
  organizationId,
  userRole = 'ORG_MEMBER',
}) => {
  const [jobs, setJobs] = useState<AuditExportJobResponse[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Selected Job for Details Modal
  const [selectedJob, setSelectedJob] = useState<AuditExportJobResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState<boolean>(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  // Create Modal State
  const [isCreateModalOpen, setIsCreateModalOpen] = useState<boolean>(false);
  const [exportScope, setExportScope] = useState<string>('FULL');
  const [filterFrom, setFilterFrom] = useState<string>('');
  const [filterTo, setFilterTo] = useState<string>('');
  const [createLoading, setCreateLoading] = useState<boolean>(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Download State
  const [downloadingJobId, setDownloadingJobId] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  // Race Safety Guards
  const activeOrgRef = useRef<string>(organizationId);
  const activeJobRef = useRef<string | null>(null);

  // Fetch Jobs on Org Change with Race Protection
  useEffect(() => {
    activeOrgRef.current = organizationId;
    setSelectedJob(null);
    activeJobRef.current = null;
    setError(null);
    setLoading(true);

    let isSubscribed = true;

    listAuditExportJobs(organizationId, 0, 20)
      .then((data) => {
        // Race Guard: Ignore stale response if active org has changed
        if (isSubscribed && activeOrgRef.current === organizationId) {
          setJobs(data?.content || []);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (isSubscribed && activeOrgRef.current === organizationId) {
          setError(err.status === 403 ? 'Access Forbidden' : 'Failed to load export jobs');
          setLoading(false);
        }
      });

    return () => {
      isSubscribed = false;
    };
  }, [organizationId]);

  // Handle Job Select with Race Protection
  const handleSelectJob = (jobId: string) => {
    activeJobRef.current = jobId;
    setSelectedJob(null);
    setDetailError(null);
    setDetailLoading(true);

    let isSubscribed = true;

    getAuditExportJob(organizationId, jobId)
      .then((data) => {
        // Race Guard: Ignore stale response if active org or active job changed
        if (
          isSubscribed &&
          activeOrgRef.current === organizationId &&
          activeJobRef.current === jobId
        ) {
          setSelectedJob(data);
          setDetailLoading(false);
        }
      })
      .catch((err) => {
        if (
          isSubscribed &&
          activeOrgRef.current === organizationId &&
          activeJobRef.current === jobId
        ) {
          setDetailError(err.status === 404 ? 'Export job not found' : 'Failed to load job details');
          setDetailLoading(false);
        }
      });
  };

  // Handle Create Export Submission (ORG_ADMIN Only)
  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (userRole !== 'ORG_ADMIN') return;

    setCreateLoading(true);
    setCreateError(null);

    const req: CreateAuditExportRequest = {
      exportScope,
      filterFromTimestamp: filterFrom ? new Date(filterFrom).toISOString() : undefined,
      filterToTimestamp: filterTo ? new Date(filterTo).toISOString() : undefined,
    };

    try {
      const newJob = await createAuditExportJob(organizationId, req);
      setIsCreateModalOpen(false);
      setJobs((prev) => [newJob, ...prev]);
    } catch (err: any) {
      setCreateError(
        err.status === 429
          ? 'Maximum active export limit reached. Please wait for current exports to complete.'
          : err.status === 403
          ? 'Organization Admin role required to initiate exports'
          : 'Failed to initiate audit export'
      );
    } finally {
      setCreateLoading(false);
    }
  };

  // Handle Controlled Download Action
  const handleDownload = async (jobId: string) => {
    setDownloadingJobId(jobId);
    setDownloadError(null);

    try {
      await downloadAuditExport(organizationId, jobId);
    } catch (err: any) {
      setDownloadError(
        err.status === 410
          ? 'Export artifact has expired or been purged'
          : err.status === 409
          ? 'Single-use download token already consumed or job not ready'
          : 'Failed to download audit export'
      );
    } finally {
      setDownloadingJobId(null);
    }
  };

  // Status Badge Mapper
  const renderStatusBadge = (status: AuditExportJobStatus) => {
    switch (status) {
      case 'COMPLETED':
        return <Badge variant="success">COMPLETED</Badge>;
      case 'PROCESSING':
      case 'CLAIMED':
        return <Badge variant="warning">PROCESSING</Badge>;
      case 'PENDING':
        return <Badge variant="info">PENDING</Badge>;
      case 'FAILED':
        return <Badge variant="danger">FAILED</Badge>;
      case 'EXPIRED':
      case 'CANCELLED':
      case 'DELETED':
        return <Badge variant="secondary">{status}</Badge>;
      default:
        return <Badge variant="secondary">{status}</Badge>;
    }
  };

  const formatBytes = (bytes?: number | null) => {
    if (!bytes || bytes === 0) return 'N/A';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  return (
    <div className="flex flex-col gap-6 max-w-6xl w-full" data-testid="audit-export-governance-view">
      {/* Header & Controls */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-100">Enterprise Audit Export & Governance</h1>
          <p className="text-xs text-dark-muted">
            Inspect, initiate, and download tamper-evident, AES-256-GCM encrypted audit outbox snapshots.
          </p>
        </div>

        {userRole === 'ORG_ADMIN' && (
          <Button
            data-testid="create-export-btn"
            variant="primary"
            onClick={() => setIsCreateModalOpen(true)}
            aria-label="Initiate New Audit Export"
          >
            Initiate Export
          </Button>
        )}
      </div>

      {/* Download Error Alert */}
      {downloadError && (
        <div
          data-testid="download-error-alert"
          role="alert"
          className="p-3 bg-red-950/40 border border-red-800 rounded text-xs text-red-300"
        >
          {downloadError}
        </div>
      )}

      {/* Export Jobs Table */}
      <Card title="Export Jobs History">
        {loading ? (
          <div data-testid="loading-spinner" className="p-6 text-center text-xs text-dark-muted">
            Loading audit export jobs...
          </div>
        ) : error ? (
          <div data-testid="error-message" role="alert" className="p-6 text-center text-xs text-red-400">
            {error}
          </div>
        ) : jobs.length === 0 ? (
          <div data-testid="empty-jobs-state" className="p-6 text-center text-xs text-dark-muted">
            No audit export jobs found for this organization.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs border-collapse" aria-label="Audit Export Jobs Table">
              <thead>
                <tr className="border-b border-dark-border bg-dark-bg/60 text-gray-400 uppercase text-[10px] tracking-wider">
                  <th className="py-2.5 px-3">Job ID</th>
                  <th className="py-2.5 px-3">Status</th>
                  <th className="py-2.5 px-3">Scope</th>
                  <th className="py-2.5 px-3">Created At</th>
                  <th className="py-2.5 px-3">Completed At</th>
                  <th className="py-2.5 px-3">Size</th>
                  <th className="py-2.5 px-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-dark-border/40">
                {jobs.map((job) => (
                  <tr
                    key={job.jobId}
                    data-testid={`job-row-${job.jobId}`}
                    className="hover:bg-dark-bg/40 transition-colors"
                  >
                    <td className="py-3 px-3 font-mono text-dark-muted text-[11px]">
                      {job.jobId.slice(0, 8)}...
                    </td>
                    <td className="py-3 px-3">
                      <span data-testid={`job-status-${job.jobId}`}>{renderStatusBadge(job.status)}</span>
                    </td>
                    <td className="py-3 px-3 font-medium text-gray-200">{job.exportScope}</td>
                    <td className="py-3 px-3 text-dark-muted">
                      {new Date(job.createdAt).toLocaleString()}
                    </td>
                    <td className="py-3 px-3 text-dark-muted">
                      {job.completedAt ? new Date(job.completedAt).toLocaleString() : '-'}
                    </td>
                    <td className="py-3 px-3 font-mono text-dark-muted">{formatBytes(job.artifactSizeBytes)}</td>
                    <td className="py-3 px-3 text-right flex items-center justify-end gap-2">
                      <Button
                        data-testid={`view-details-btn-${job.jobId}`}
                        variant="secondary"
                        size="sm"
                        onClick={() => handleSelectJob(job.jobId)}
                        aria-label={`View details for job ${job.jobId}`}
                      >
                        Inspect
                      </Button>

                      {job.status === 'COMPLETED' && (
                        <Button
                          data-testid={`download-btn-${job.jobId}`}
                          variant="primary"
                          size="sm"
                          disabled={downloadingJobId === job.jobId}
                          onClick={() => handleDownload(job.jobId)}
                          aria-label={`Download export ${job.jobId}`}
                        >
                          {downloadingJobId === job.jobId ? 'Downloading...' : 'Download'}
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Selected Job Inspection Modal */}
      {selectedJob && (
        <div
          data-testid="job-details-modal"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4"
          role="dialog"
          aria-labelledby="job-details-title"
          aria-modal="true"
        >
          <div className="bg-dark-card border border-dark-border rounded-lg max-w-2xl w-full p-6 space-y-4 shadow-xl text-xs">
            <div className="flex items-center justify-between border-b border-dark-border pb-3">
              <h2 id="job-details-title" className="text-sm font-bold text-gray-100">
                Audit Export Inspection & Metadata
              </h2>
              <Button
                data-testid="close-modal-btn"
                variant="secondary"
                size="sm"
                onClick={() => setSelectedJob(null)}
                aria-label="Close Job Details Modal"
              >
                Close
              </Button>
            </div>

            {detailLoading ? (
              <div className="p-4 text-center text-dark-muted">Loading job details...</div>
            ) : detailError ? (
              <div className="p-4 text-center text-red-400" role="alert">{detailError}</div>
            ) : (
              <div className="space-y-3">
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <span className="text-dark-muted">Job ID:</span>
                    <p data-testid="detail-job-id" className="font-mono text-gray-200">{selectedJob.jobId}</p>
                  </div>
                  <div>
                    <span className="text-dark-muted">Status:</span>
                    <div data-testid="detail-status" className="mt-0.5">{renderStatusBadge(selectedJob.status)}</div>
                  </div>
                  <div>
                    <span className="text-dark-muted">Export Scope:</span>
                    <p data-testid="detail-scope" className="font-medium text-gray-200">{selectedJob.exportScope}</p>
                  </div>
                  <div>
                    <span className="text-dark-muted">Requested By User:</span>
                    <p data-testid="detail-requested-by" className="font-mono text-dark-muted">{selectedJob.requestedByUserId}</p>
                  </div>
                  <div>
                    <span className="text-dark-muted">Snapshot Timestamp:</span>
                    <p data-testid="detail-snapshot-time" className="text-gray-300">{new Date(selectedJob.snapshotTimestamp).toUTCString()}</p>
                  </div>
                  <div>
                    <span className="text-dark-muted">Max Outbox Sequence:</span>
                    <p data-testid="detail-max-seq" className="font-mono text-gray-300">{selectedJob.maxSequenceNumber}</p>
                  </div>
                  <div>
                    <span className="text-dark-muted">Artifact Record Count:</span>
                    <p data-testid="detail-record-count" className="font-mono text-gray-300">{selectedJob.recordCount ?? 'N/A'}</p>
                  </div>
                  <div>
                    <span className="text-dark-muted">Encrypted Size:</span>
                    <p data-testid="detail-size" className="font-mono text-gray-300">{formatBytes(selectedJob.artifactSizeBytes)}</p>
                  </div>
                </div>

                {selectedJob.artifactChecksumSha256 && (
                  <div className="pt-2 border-t border-dark-border/40">
                    <span className="text-dark-muted">SHA-256 Checksum:</span>
                    <p data-testid="detail-checksum" className="font-mono text-[11px] text-brand-primary break-all bg-dark-bg p-1.5 rounded border border-dark-border">
                      {selectedJob.artifactChecksumSha256}
                    </p>
                  </div>
                )}

                {selectedJob.status === 'FAILED' && selectedJob.errorCode && (
                  <div className="p-3 bg-red-950/40 border border-red-800 rounded text-red-300 space-y-1">
                    <span className="font-bold">Failure Category:</span>
                    <p data-testid="detail-error-code" className="font-mono text-[11px]">{selectedJob.errorCode}</p>
                    {selectedJob.errorMessage && (
                      <p data-testid="detail-error-message" className="text-dark-muted">{selectedJob.errorMessage}</p>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Initiate Export Modal (ORG_ADMIN) */}
      {isCreateModalOpen && (
        <div
          data-testid="create-export-modal"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4"
          role="dialog"
          aria-labelledby="create-export-title"
          aria-modal="true"
        >
          <form
            onSubmit={handleCreateSubmit}
            className="bg-dark-card border border-dark-border rounded-lg max-w-lg w-full p-6 space-y-4 shadow-xl text-xs"
          >
            <div className="flex items-center justify-between border-b border-dark-border pb-3">
              <h2 id="create-export-title" className="text-sm font-bold text-gray-100">
                Initiate Governance Audit Export
              </h2>
              <Button
                type="button"
                data-testid="close-create-modal-btn"
                variant="secondary"
                size="sm"
                onClick={() => setIsCreateModalOpen(false)}
                aria-label="Close Modal"
              >
                Cancel
              </Button>
            </div>

            {createError && (
              <div data-testid="create-error-alert" role="alert" className="p-2.5 bg-red-950/40 border border-red-800 rounded text-red-300">
                {createError}
              </div>
            )}

            <div className="space-y-3">
              <div>
                <label htmlFor="export-scope-select" className="block text-dark-muted mb-1 font-semibold">
                  Export Scope
                </label>
                <select
                  id="export-scope-select"
                  data-testid="export-scope-select"
                  value={exportScope}
                  onChange={(e) => setExportScope(e.target.value)}
                  className="w-full bg-dark-bg border border-dark-border rounded p-2 text-gray-200 focus:outline-none focus:border-brand-primary"
                >
                  {SCOPES.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.label} ({s.id})
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label htmlFor="filter-from-input" className="block text-dark-muted mb-1 font-semibold">
                  Filter From Timestamp (Optional)
                </label>
                <input
                  id="filter-from-input"
                  data-testid="filter-from-input"
                  type="datetime-local"
                  value={filterFrom}
                  onChange={(e) => setFilterFrom(e.target.value)}
                  className="w-full bg-dark-bg border border-dark-border rounded p-2 text-gray-200 focus:outline-none focus:border-brand-primary"
                />
              </div>

              <div>
                <label htmlFor="filter-to-input" className="block text-dark-muted mb-1 font-semibold">
                  Filter To Timestamp (Optional)
                </label>
                <input
                  id="filter-to-input"
                  data-testid="filter-to-input"
                  type="datetime-local"
                  value={filterTo}
                  onChange={(e) => setFilterTo(e.target.value)}
                  className="w-full bg-dark-bg border border-dark-border rounded p-2 text-gray-200 focus:outline-none focus:border-brand-primary"
                />
              </div>

              <div className="p-3 bg-dark-bg rounded border border-dark-border/60 text-dark-muted space-y-1">
                <span className="font-semibold text-gray-300">Deterministic Boundary Confirmation:</span>
                <p>
                  Snapshot boundary will capture all monotonic audit sequence records up to the exact instant of initiation. The export will be GZIP compressed and AES-256-GCM encrypted.
                </p>
              </div>
            </div>

            <div className="flex items-center justify-end gap-2 border-t border-dark-border pt-3">
              <Button
                type="button"
                variant="secondary"
                onClick={() => setIsCreateModalOpen(false)}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                data-testid="confirm-create-export-btn"
                variant="primary"
                disabled={createLoading}
              >
                {createLoading ? 'Initiating...' : 'Confirm & Initiate'}
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
};
