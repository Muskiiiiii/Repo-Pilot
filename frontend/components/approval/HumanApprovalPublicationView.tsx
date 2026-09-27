'use client';

import React, { useState } from 'react';
import {
  HumanApprovalResponseDto,
  GitPullRequestResult,
  ApprovalStatus,
  PullRequestPublicationStatus,
} from '@/types/approval';
import { Badge } from '@/components/ui/Badge';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { StatusDot } from '@/components/ui/StatusDot';
import {
  CheckCircle2,
  XCircle,
  AlertTriangle,
  GitPullRequest,
  GitBranch,
  ShieldCheck,
  ShieldAlert,
  Hash,
  FileCode,
  ExternalLink,
  RefreshCw,
  Info,
  Clock,
  Lock,
} from 'lucide-react';

interface HumanApprovalPublicationViewProps {
  projectId: string;
  repositoryId: string;
  taskId: string;
  codeChangeId: string;
  taskTitle?: string;
  approval: HumanApprovalResponseDto | null;
  publicationResult: GitPullRequestResult | null;
  loading?: boolean;
  actionLoading?: boolean;
  error?: string | null;
  onApprove: () => Promise<void>;
  onRevoke: (reason: string) => Promise<void>;
  onPublish: () => Promise<void>;
  onRefresh: () => void;
}

export function isSafeGithubPrUrl(url?: string | null): boolean {
  if (!url || typeof url !== 'string') return false;
  const trimmed = url.trim();
  const lower = trimmed.toLowerCase();
  if (lower.startsWith('javascript:') || lower.startsWith('data:') || lower.startsWith('vbscript:')) {
    return false;
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'https:') {
      return false;
    }
    const host = parsed.hostname.toLowerCase();
    if (host !== 'github.com') {
      return false;
    }
    return /^\/[^/]+\/[^/]+\/pull\/\d+$/.test(parsed.pathname);
  } catch {
    return false;
  }
}

export function HumanApprovalPublicationView({
  projectId,
  repositoryId,
  taskId,
  codeChangeId,
  taskTitle = 'Task Implementation',
  approval,
  publicationResult,
  loading = false,
  actionLoading = false,
  error = null,
  onApprove,
  onRevoke,
  onPublish,
  onRefresh,
}: HumanApprovalPublicationViewProps) {
  const [showConfirmModal, setShowConfirmModal] = useState(false);
  const [revokeReason, setRevokeReason] = useState('');
  const [showRevokeModal, setShowRevokeModal] = useState(false);

  if (loading) {
    return (
      <div className="flex h-64 w-full flex-col items-center justify-center gap-3 text-slate-400">
        <div className="flex items-center gap-3">
          <StatusDot status="running" pulse />
          <span className="text-sm font-medium text-slate-200">Loading Human Approval & Publication State...</span>
        </div>
      </div>
    );
  }

  const currentPublicationStatus: PullRequestPublicationStatus | null =
    publicationResult?.status || null;

  const isPrCreated = currentPublicationStatus === 'PR_CREATED' || currentPublicationStatus === 'PR_RECONCILED';
  const isUnknownState = currentPublicationStatus === 'FAILED_PUSH' && publicationResult?.failureCategory === 'UNKNOWN_EXTERNAL_STATE' ||
                         currentPublicationStatus === 'FAILED_PR_CREATION' && publicationResult?.failureCategory === 'UNKNOWN_EXTERNAL_STATE';
  const isConflictState = currentPublicationStatus === 'FAILED_CONFLICT' || currentPublicationStatus === 'FAILED_PR_CONFLICT';

  const handleConfirmApprove = async () => {
    setShowConfirmModal(false);
    await onApprove();
  };

  const handleConfirmRevoke = async () => {
    if (!revokeReason.trim()) return;
    setShowRevokeModal(false);
    await onRevoke(revokeReason);
    setRevokeReason('');
  };

  return (
    <div className="flex w-full flex-col gap-6">
      {/* Header Banner */}
      <Card className="flex flex-col gap-4 border-slate-800 bg-slate-900/90 p-6 shadow-xl">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-indigo-500/10 text-indigo-400">
              <ShieldCheck className="h-6 w-6" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-slate-100">{taskTitle}</h2>
              <p className="text-xs text-slate-400">Phase 12.2 Human Approval & Remote Publication Boundary</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <Button
              variant="secondary"
              size="sm"
              onClick={onRefresh}
              disabled={actionLoading}
              className="flex items-center gap-2"
            >
              <RefreshCw className={`h-4 w-4 ${actionLoading ? 'animate-spin' : ''}`} />
              Refresh Status
            </Button>

            {!approval && (
              <Button
                variant="primary"
                size="sm"
                onClick={() => setShowConfirmModal(true)}
                disabled={actionLoading}
                className="flex items-center gap-2 bg-emerald-600 hover:bg-emerald-500"
              >
                <CheckCircle2 className="h-4 w-4" />
                Approve Change
              </Button>
            )}

            {approval && approval.status === 'APPROVED' && !isPrCreated && (
              <>
                <Button
                  variant="primary"
                  size="sm"
                  onClick={onPublish}
                  disabled={actionLoading}
                  className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-500"
                >
                  <GitPullRequest className="h-4 w-4" />
                  {actionLoading ? 'Publishing...' : 'Publish to GitHub'}
                </Button>

                <Button
                  variant="danger"
                  size="sm"
                  onClick={() => setShowRevokeModal(true)}
                  disabled={actionLoading}
                  className="flex items-center gap-2"
                >
                  <XCircle className="h-4 w-4" />
                  Revoke
                </Button>
              </>
            )}
          </div>
        </div>

        {/* Global Error Alert */}
        {error && (
          <div className="flex items-center gap-3 rounded-lg border border-red-500/20 bg-red-500/10 p-4 text-sm text-red-300">
            <AlertTriangle className="h-5 w-5 shrink-0 text-red-400" />
            <span>{error}</span>
          </div>
        )}

        {/* Reconciling / Unknown State Banner */}
        {isUnknownState && (
          <div className="flex items-center gap-3 rounded-lg border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-200">
            <Clock className="h-5 w-5 shrink-0 text-amber-400 animate-pulse" />
            <div>
              <span className="font-semibold">Publication status is being reconciled with GitHub...</span>
              <p className="text-xs text-amber-300/80">Out-of-band remote verification in progress. Please click 'Refresh Status' to fetch updated state.</p>
            </div>
          </div>
        )}

        {/* Conflict State Banner */}
        {isConflictState && (
          <div className="flex items-center gap-3 rounded-lg border border-red-500/30 bg-red-500/10 p-4 text-sm text-red-200">
            <ShieldAlert className="h-5 w-5 shrink-0 text-red-400" />
            <div>
              <span className="font-semibold">Publication Conflict Detected on Remote Repository</span>
              <p className="text-xs text-red-300/80">{publicationResult?.failureReason || 'Remote branch or PR state conflicts with local commit SHA.'}</p>
            </div>
          </div>
        )}
      </Card>

      {/* Main Grid Details */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Authoritative Approval Record Card */}
        <Card className="flex flex-col gap-4 border-slate-800 bg-slate-900/80 p-6">
          <div className="flex items-center justify-between border-b border-slate-800 pb-3">
            <h3 className="text-base font-semibold text-slate-200 flex items-center gap-2">
              <Lock className="h-4 w-4 text-emerald-400" />
              Human Approval Record
            </h3>
            {approval ? (
              <Badge variant={approval.status === 'APPROVED' ? 'success' : 'danger'}>
                {approval.status}
              </Badge>
            ) : (
              <Badge variant="warning">UNAPPROVED</Badge>
            )}
          </div>

          {approval ? (
            <div className="flex flex-col gap-3 text-xs text-slate-300">
              <div className="flex justify-between py-1 border-b border-slate-800/50">
                <span className="text-slate-400">Approval ID:</span>
                <span className="font-mono text-slate-200">{approval.approvalId}</span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-800/50">
                <span className="text-slate-400">Approved By:</span>
                <span className="text-slate-200">{approval.approvedByUserEmail} ({approval.approvedByUserRole})</span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-800/50">
                <span className="text-slate-400">Base Commit SHA:</span>
                <span className="font-mono text-slate-200">{approval.repositoryBaseSha ? `${approval.repositoryBaseSha.slice(0, 10)}...` : 'N/A'}</span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-800/50">
                <span className="text-slate-400">Code Change Diff Hash:</span>
                <span className="font-mono text-indigo-300">{approval.codeChangeDiffHash ? `${approval.codeChangeDiffHash.slice(0, 16)}...` : 'N/A'}</span>
              </div>
              <div className="flex justify-between py-1 border-b border-slate-800/50">
                <span className="text-slate-400">Approval Record Hash:</span>
                <span className="font-mono text-emerald-300">{approval.approvalRecordHash ? `${approval.approvalRecordHash.slice(0, 16)}...` : 'N/A'}</span>
              </div>
              <div className="flex justify-between py-1">
                <span className="text-slate-400">Approved Files ({approval.approvedChangeCount}):</span>
                <span className="text-slate-200">{approval.approvedFiles.join(', ')}</span>
              </div>
            </div>
          ) : (
            <div className="flex h-32 items-center justify-center text-xs text-slate-400">
              No approval record exists for this code change yet. Click 'Approve Change' to inspect and approve.
            </div>
          )}
        </Card>

        {/* Remote Publication & PR Card */}
        <Card className="flex flex-col gap-4 border-slate-800 bg-slate-900/80 p-6">
          <div className="flex items-center justify-between border-b border-slate-800 pb-3">
            <h3 className="text-base font-semibold text-slate-200 flex items-center gap-2">
              <GitPullRequest className="h-4 w-4 text-indigo-400" />
              GitHub Remote Publication State
            </h3>
            {publicationResult ? (
              <Badge variant={isPrCreated ? 'success' : isConflictState ? 'danger' : 'info'}>
                {publicationResult.status}
              </Badge>
            ) : (
              <Badge variant="neutral">NOT PUBLISHED</Badge>
            )}
          </div>

          {publicationResult ? (
            <div className="flex flex-col gap-3 text-xs text-slate-300">
              {publicationResult.githubPrNumber && (
                <div className="flex justify-between items-center py-2 bg-emerald-500/10 border border-emerald-500/20 rounded-md px-3 text-emerald-300">
                  <span className="font-medium">GitHub Pull Request #{publicationResult.githubPrNumber}:</span>
                  {isSafeGithubPrUrl(publicationResult.prUrl) ? (
                    <a
                      href={publicationResult.prUrl!}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center gap-1 font-semibold underline hover:text-emerald-200"
                    >
                      View on GitHub <ExternalLink className="h-3 w-3" />
                    </a>
                  ) : (
                    <span className="font-mono text-slate-300">PR #{publicationResult.githubPrNumber}</span>
                  )}
                </div>
              )}

              {publicationResult.branchName && (
                <div className="flex justify-between py-1 border-b border-slate-800/50">
                  <span className="text-slate-400">Remote Branch:</span>
                  <span className="font-mono text-indigo-300">{publicationResult.branchName}</span>
                </div>
              )}
              {publicationResult.baseBranch && (
                <div className="flex justify-between py-1 border-b border-slate-800/50">
                  <span className="text-slate-400">Base Branch:</span>
                  <span className="font-mono text-slate-200">{publicationResult.baseBranch}</span>
                </div>
              )}
              {publicationResult.commitSha && (
                <div className="flex justify-between py-1 border-b border-slate-800/50">
                  <span className="text-slate-400">Remote Commit SHA:</span>
                  <span className="font-mono text-slate-200">{publicationResult.commitSha.slice(0, 10)}...</span>
                </div>
              )}
              {publicationResult.failureCategory && (
                <div className="flex justify-between py-1 text-red-400">
                  <span>Failure Category:</span>
                  <span className="font-semibold">{publicationResult.failureCategory}</span>
                </div>
              )}
            </div>
          ) : (
            <div className="flex h-32 items-center justify-center text-xs text-slate-400">
              Publication has not been initiated for this approval record.
            </div>
          )}
        </Card>
      </div>

      {/* Approve Confirmation Modal */}
      {showConfirmModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 p-4 backdrop-blur-sm">
          <div className="w-full max-w-lg rounded-xl border border-slate-800 bg-slate-900 p-6 shadow-2xl flex flex-col gap-4">
            <div className="flex items-center gap-3 border-b border-slate-800 pb-3">
              <CheckCircle2 className="h-6 w-6 text-emerald-400" />
              <h3 className="text-lg font-semibold text-slate-100">Confirm Human Approval</h3>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Please confirm that you are explicitly approving this proposed code change. Once approved, the approval record will be cryptographically bound to the base SHA and diff content.
            </p>

            <div className="flex flex-col gap-2 rounded-lg bg-slate-950 p-3 text-xs font-mono text-slate-300 border border-slate-800">
              <div>Task ID: {taskId}</div>
              <div>Code Change ID: {codeChangeId}</div>
            </div>

            <div className="flex justify-end gap-3 pt-2">
              <Button variant="secondary" size="sm" onClick={() => setShowConfirmModal(false)}>
                Cancel
              </Button>
              <Button variant="primary" size="sm" onClick={handleConfirmApprove} className="bg-emerald-600 hover:bg-emerald-500">
                Confirm & Approve
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Revoke Modal */}
      {showRevokeModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 p-4 backdrop-blur-sm">
          <div className="w-full max-w-lg rounded-xl border border-slate-800 bg-slate-900 p-6 shadow-2xl flex flex-col gap-4">
            <div className="flex items-center gap-3 border-b border-slate-800 pb-3">
              <XCircle className="h-6 w-6 text-red-400" />
              <h3 className="text-lg font-semibold text-slate-100">Revoke Human Approval</h3>
            </div>
            <p className="text-xs text-slate-300">
              Provide a reason for revoking this approval:
            </p>
            <input
              type="text"
              className="w-full rounded-md border border-slate-700 bg-slate-950 p-2 text-xs text-slate-100 focus:border-red-500 focus:outline-none"
              placeholder="Reason for revocation..."
              value={revokeReason}
              onChange={(e) => setRevokeReason(e.target.value)}
            />
            <div className="flex justify-end gap-3 pt-2">
              <Button variant="secondary" size="sm" onClick={() => setShowRevokeModal(false)}>
                Cancel
              </Button>
              <Button variant="danger" size="sm" onClick={handleConfirmRevoke} disabled={!revokeReason.trim()}>
                Confirm Revocation
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
