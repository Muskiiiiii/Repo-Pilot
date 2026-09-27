'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { CodeReviewResultDto } from '@/types/review';
import { fetchCodeReviewsForTask } from '@/lib/review';
import { CodeReviewList } from '@/components/review/CodeReviewList';
import { ArrowLeft, ChevronRight } from 'lucide-react';

export default function TaskCodeReviewsPage() {
  const params = useParams();
  const projectId = typeof params?.id === 'string' ? params.id : '';
  const repositoryId = typeof params?.repoId === 'string' ? params.repoId : '';
  const taskId = typeof params?.taskId === 'string' ? params.taskId : '';

  const [reviews, setReviews] = useState<CodeReviewResultDto[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const loadReviews = useCallback(async () => {
    if (!projectId || !repositoryId || !taskId) {
      setError('Invalid route parameters.');
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const data = await fetchCodeReviewsForTask(projectId, repositoryId, taskId);
      setReviews(data || []);
    } catch (err: any) {
      setError(err.message || 'Failed to load code reviews.');
    } finally {
      setLoading(false);
    }
  }, [projectId, repositoryId, taskId]);

  useEffect(() => {
    loadReviews();
  }, [loadReviews]);

  const taskUrl = `/projects/${projectId}/repositories/${repositoryId}`;

  return (
    <div className="max-w-5xl mx-auto py-6 space-y-6">
      {/* Breadcrumbs */}
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
          <span className="text-slate-200 font-medium">Task Code Reviews</span>
        </div>

        <Link
          href={taskUrl}
          className="inline-flex items-center gap-1.5 text-xs text-blue-400 hover:text-blue-300 transition-colors"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          <span>Back to Repository Explorer</span>
        </Link>
      </div>

      <CodeReviewList
        projectId={projectId}
        repositoryId={repositoryId}
        taskId={taskId}
        reviews={reviews}
        loading={loading}
        error={error}
      />
    </div>
  );
}
