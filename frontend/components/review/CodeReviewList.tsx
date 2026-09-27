'use client';

import React from 'react';
import Link from 'next/link';
import { CodeReviewResultDto } from '@/types/review';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { StatusDot, StatusType } from '@/components/ui/StatusDot';
import { ShieldCheck, Clock, ArrowRight, ShieldAlert, FileText } from 'lucide-react';

interface CodeReviewListProps {
  projectId: string;
  repositoryId: string;
  taskId: string;
  reviews: CodeReviewResultDto[];
  loading?: boolean;
  error?: string | null;
}

export function CodeReviewList({
  projectId,
  repositoryId,
  taskId,
  reviews,
  loading = false,
  error = null,
}: CodeReviewListProps) {
  if (loading) {
    return (
      <div className="flex h-48 w-full items-center justify-center text-slate-400">
        <StatusDot status="running" pulse label="Loading Code Reviews..." />
      </div>
    );
  }

  if (error) {
    return (
      <Card className="border-rose-900/50 bg-rose-950/30 p-6 text-rose-300">
        <h3 className="font-semibold text-sm">Error Loading Reviews</h3>
        <p className="text-xs text-rose-400/80 mt-1">{error}</p>
      </Card>
    );
  }

  if (!reviews || reviews.length === 0) {
    return (
      <Card className="border-slate-800 bg-slate-900/60 p-8 text-center text-slate-400">
        <FileText className="mx-auto h-10 w-10 text-slate-500" />
        <h3 className="mt-3 text-base font-semibold text-slate-200">No Code Reviews Available</h3>
        <p className="mt-1 text-xs text-slate-400 max-w-sm mx-auto">
          No automated AI code reviews have been triggered for this task yet.
        </p>
      </Card>
    );
  }

  const mapStatusToDot = (status: string): StatusType => {
    switch (status) {
      case 'RUNNING':
      case 'PENDING':
        return 'running';
      case 'COMPLETED':
        return 'success';
      case 'FAILED':
        return 'error';
      case 'CANCELLED':
        return 'warning';
      default:
        return 'idle';
    }
  };

  const getRiskBadgeVariant = (risk: string): 'danger' | 'warning' | 'purple' | 'success' | 'outline' => {
    switch (risk) {
      case 'CRITICAL':
      case 'HIGH':
        return 'danger';
      case 'MEDIUM':
        return 'warning';
      case 'LOW':
        return 'success';
      case 'INFO':
        return 'purple';
      default:
        return 'outline';
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-bold text-slate-100">Code Reviews ({reviews.length})</h2>
        <span className="text-xs text-slate-400 flex items-center gap-1.5">
          <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" />
          Read-Only Audit Trail
        </span>
      </div>

      <div className="space-y-3">
        {reviews.map((review) => {
          const detailUrl = `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/reviews/${review.reviewId}`;

          return (
            <Card
              key={review.reviewId}
              className="border-slate-800 bg-slate-900/90 hover:border-slate-700 transition-all"
            >
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
                <div className="space-y-1.5 flex-1 min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="purple" size="sm">
                      {review.reviewMode || 'CODE_CHANGE_REVIEW'}
                    </Badge>
                    <StatusDot status={mapStatusToDot(review.status)} label={review.status} />
                    <Badge variant={getRiskBadgeVariant(review.overallRiskScore)}>
                      Risk: {review.overallRiskScore || 'LOW'}
                    </Badge>
                  </div>

                  <p className="text-xs text-slate-300 line-clamp-2">
                    {review.summary || 'No summary available.'}
                  </p>

                  <div className="flex items-center gap-3 text-[11px] text-slate-400 font-mono">
                    <span className="flex items-center gap-1">
                      <ShieldAlert className="h-3 w-3 text-slate-400" />
                      Findings: {review.acceptedFindingsCount} / {review.totalAiFindings}
                    </span>
                    <span>•</span>
                    <span className="flex items-center gap-1">
                      <Clock className="h-3 w-3 text-slate-400" />
                      {review.createdAt ? new Date(review.createdAt).toLocaleString() : 'N/A'}
                    </span>
                  </div>
                </div>

                <div>
                  <Link
                    href={detailUrl}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-800 px-3.5 py-2 text-xs font-semibold text-blue-400 hover:bg-slate-700 hover:text-blue-300 transition-colors"
                  >
                    <span>View Review Details</span>
                    <ArrowRight className="h-3.5 w-3.5" />
                  </Link>
                </div>
              </div>
            </Card>
          );
        })}
      </div>
    </div>
  );
}
