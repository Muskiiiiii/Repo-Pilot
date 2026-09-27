'use client';

import React, { useEffect, useState, useCallback, useRef } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { CodeReviewResultDto } from '@/types/review';
import { fetchCodeReview } from '@/lib/review';
import { CodeReviewDetailsView } from '@/components/review/CodeReviewDetailsView';
import { ArrowLeft, ShieldCheck, ChevronRight } from 'lucide-react';
import { ApiError } from '@/lib/api';

export default function CodeReviewDetailPage() {
  const params = useParams();
  const projectId = typeof params?.id === 'string' ? params.id : '';
  const repositoryId = typeof params?.repoId === 'string' ? params.repoId : '';
  const taskId = typeof params?.taskId === 'string' ? params.taskId : '';
  const reviewId = typeof params?.reviewId === 'string' ? params.reviewId : '';

  const [review, setReview] = useState<CodeReviewResultDto | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | null>(null);

  const pollCountRef = useRef<number>(0);
  const maxPolls = 20; // Maximum 20 polls = 60 seconds max bounded polling

  const loadReviewData = useCallback(
    async (isPollingCall = false) => {
      if (!projectId || !repositoryId || !taskId || !reviewId) {
        setError('Invalid route parameters.');
        setErrorStatus(400);
        setLoading(false);
        return;
      }

      if (!isPollingCall) {
        setLoading(true);
      }
      setError(null);
      setErrorStatus(null);

      try {
        const data = await fetchCodeReview(projectId, repositoryId, taskId, reviewId);
        setReview(data);
        return data;
      } catch (err: any) {
        const status = err instanceof ApiError ? err.status : 500;
        setErrorStatus(status);
        setError(err.message || 'Failed to load code review details.');
        return null;
      } finally {
        if (!isPollingCall) {
          setLoading(false);
        }
      }
    },
    [projectId, repositoryId, taskId, reviewId]
  );

  useEffect(() => {
    let timerId: NodeJS.Timeout | null = null;

    const executeLoadAndPoll = async () => {
      const data = await loadReviewData(false);

      // Bounded Polling Strategy: Only poll if status is non-terminal (RUNNING or PENDING)
      if (data && (data.status === 'RUNNING' || data.status === 'PENDING')) {
        pollCountRef.current = 0;

        timerId = setInterval(async () => {
          pollCountRef.current += 1;
          if (pollCountRef.current >= maxPolls) {
            if (timerId) clearInterval(timerId);
            return;
          }

          const polledData = await loadReviewData(true);
          if (polledData && polledData.status !== 'RUNNING' && polledData.status !== 'PENDING') {
            if (timerId) clearInterval(timerId);
          }
        }, 3000);
      }
    };

    executeLoadAndPoll();

    return () => {
      if (timerId) clearInterval(timerId);
    };
  }, [loadReviewData]);

  const taskUrl = `/projects/${projectId}/repositories/${repositoryId}`;

  return (
    <div className="max-w-6xl mx-auto py-6 space-y-6">
      {/* Breadcrumbs Navigation */}
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
          <Link href={taskUrl} className="hover:text-slate-200 transition-colors">
            Repository
          </Link>
          <ChevronRight className="h-3 w-3 text-slate-600" />
          <span className="text-slate-200 font-medium truncate max-w-xs">
            Review {reviewId.substring(0, 8)}...
          </span>
        </div>

        <Link
          href={taskUrl}
          className="inline-flex items-center gap-1.5 text-xs text-blue-400 hover:text-blue-300 transition-colors"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          <span>Back to Repository Explorer</span>
        </Link>
      </div>

      {/* Main Review View */}
      <CodeReviewDetailsView
        review={review}
        loading={loading}
        error={error}
        errorStatus={errorStatus}
      />
    </div>
  );
}
