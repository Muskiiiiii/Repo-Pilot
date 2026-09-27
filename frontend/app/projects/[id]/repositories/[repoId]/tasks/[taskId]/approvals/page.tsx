'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { useParams, useSearchParams } from 'next/navigation';
import { HumanApprovalResponseDto, GitPullRequestResult } from '@/types/approval';
import {
  fetchApprovalsForCodeChange,
  createHumanApproval,
  revokeApproval,
  publishOrRecoverApproval,
} from '@/lib/approval';
import { HumanApprovalPublicationView } from '@/components/approval/HumanApprovalPublicationView';
import { ArrowLeft, ChevronRight } from 'lucide-react';

export default function TaskApprovalPublicationPage() {
  const params = useParams();
  const searchParams = useSearchParams();

  const projectId = typeof params?.id === 'string' ? params.id : '';
  const repositoryId = typeof params?.repoId === 'string' ? params.repoId : '';
  const taskId = typeof params?.taskId === 'string' ? params.taskId : '';
  const codeChangeIdParam = searchParams?.get('codeChangeId');
  const codeChangeId = codeChangeIdParam || taskId; // Fallback to taskId if single-change task

  const [approval, setApproval] = useState<HumanApprovalResponseDto | null>(null);
  const [publicationResult, setPublicationResult] = useState<GitPullRequestResult | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [actionLoading, setActionLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const loadApprovalData = useCallback(async () => {
    if (!projectId || !repositoryId || !taskId || !codeChangeId) {
      setError('Invalid route parameters.');
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const approvals = await fetchApprovalsForCodeChange(projectId, repositoryId, taskId, codeChangeId);
      if (approvals && approvals.length > 0) {
        // Pick the active / latest approval
        setApproval(approvals[0]);
      } else {
        setApproval(null);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to load human approval record.');
    } finally {
      setLoading(false);
    }
  }, [projectId, repositoryId, taskId, codeChangeId]);

  useEffect(() => {
    loadApprovalData();
  }, [loadApprovalData]);

  const handleApprove = async () => {
    setActionLoading(true);
    setError(null);
    try {
      const created = await createHumanApproval(projectId, repositoryId, taskId, codeChangeId, {
        idempotencyKey: `approval-${codeChangeId}-${Date.now()}`,
      });
      setApproval(created);
    } catch (err: any) {
      setError(err.message || 'Failed to approve code change.');
    } finally {
      setActionLoading(false);
    }
  };

  const handleRevoke = async (reason: string) => {
    if (!approval) return;
    setActionLoading(true);
    setError(null);
    try {
      const revoked = await revokeApproval(projectId, repositoryId, taskId, approval.approvalId, reason);
      setApproval(revoked);
    } catch (err: any) {
      setError(err.message || 'Failed to revoke approval.');
    } finally {
      setActionLoading(false);
    }
  };

  const handlePublish = async () => {
    if (!approval) return;
    setActionLoading(true);
    setError(null);
    try {
      const result = await publishOrRecoverApproval(
        projectId,
        repositoryId,
        taskId,
        codeChangeId,
        approval.approvalId
      );
      setPublicationResult(result);
    } catch (err: any) {
      setError(err.message || 'Failed to publish to remote GitHub repository.');
    } finally {
      setActionLoading(false);
    }
  };

  const repositoryUrl = `/projects/${projectId}/repositories/${repositoryId}`;

  return (
    <div className="max-w-5xl mx-auto py-6 space-y-6">
      {/* Breadcrumb Header */}
      <div className="flex flex-wrap items-center justify-between gap-3 text-xs">
        <div className="flex items-center gap-1.5 text-slate-400">
          <Link href="/projects" className="hover:text-slate-200 transition-colors">
            Projects
          </Link>
          <ChevronRight className="h-3 w-3 text-slate-600" />
          <Link href={`/projects/${projectId}`} className="hover:text-slate-200 transition-colors">
            Project
          </Link>
          <ChevronRight className="h-3 w-3 text-slate-600" />
          <Link href={repositoryUrl} className="hover:text-slate-200 transition-colors">
            Repository
          </Link>
          <ChevronRight className="h-3 w-3 text-slate-600" />
          <span className="text-slate-200 font-medium">Approval & Publication</span>
        </div>

        <Link
          href={repositoryUrl}
          className="inline-flex items-center gap-1.5 text-xs text-blue-400 hover:text-blue-300 transition-colors"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          <span>Back to Repository Explorer</span>
        </Link>
      </div>

      <HumanApprovalPublicationView
        projectId={projectId}
        repositoryId={repositoryId}
        taskId={taskId}
        codeChangeId={codeChangeId}
        taskTitle={`Task Approval & Publication (${taskId.slice(0, 8)})`}
        approval={approval}
        publicationResult={publicationResult}
        loading={loading}
        actionLoading={actionLoading}
        error={error}
        onApprove={handleApprove}
        onRevoke={handleRevoke}
        onPublish={handlePublish}
        onRefresh={loadApprovalData}
      />
    </div>
  );
}
