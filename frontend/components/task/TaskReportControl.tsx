'use client';

import React, { useState, useEffect, useRef } from 'react';
import { TaskReportResponse, fetchTaskReport } from '@/lib/report';
import { TaskReportView } from './TaskReportView';
import { ApiError } from '@/lib/api';

export interface TaskReportControlProps {
  projectId: string;
  taskId: string;
  repositoryId?: string;
}

export function TaskReportControl({
  projectId,
  taskId,
  repositoryId,
}: TaskReportControlProps) {
  const [reportData, setReportData] = useState<TaskReportResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | null>(null);

  const activeTaskIdRef = useRef<string>(taskId);

  useEffect(() => {
    activeTaskIdRef.current = taskId;
    setLoading(true);
    setError(null);
    setErrorStatus(null);
    setReportData(null);

    const controller = new AbortController();

    fetchTaskReport(projectId, taskId, repositoryId, controller.signal)
      .then((data) => {
        // Race Condition Guard: If selected taskId changed while fetch was in-flight, discard stale payload
        if (activeTaskIdRef.current !== taskId) {
          return;
        }
        setReportData(data);
        setLoading(false);
      })
      .catch((err) => {
        if (activeTaskIdRef.current !== taskId) {
          return;
        }
        if (err.name === 'AbortError') {
          return;
        }
        if (err instanceof ApiError) {
          setErrorStatus(err.status);
          setError(err.message);
        } else {
          setError(err.message || 'Failed to load task report.');
        }
        setLoading(false);
      });

    return () => {
      controller.abort();
    };
  }, [projectId, taskId, repositoryId]);

  return (
    <TaskReportView
      reportData={reportData}
      loading={loading}
      error={error}
      errorStatus={errorStatus}
    />
  );
}
