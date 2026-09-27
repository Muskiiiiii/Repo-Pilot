'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  triggerRepositoryIndexing,
  stopRepositoryIndexing,
  resumeRepositoryIndexing,
  fetchRepositoryIndexStatus,
  RepositoryIndexProgress,
} from '@/lib/indexing';
import { RefreshCw, Database, CheckCircle2, AlertCircle, Cpu, Square, Play, Clock, Activity, Zap } from 'lucide-react';

interface RepositoryIndexControlProps {
  projectId: string;
  repositoryId: string;
  branch?: string;
  initialStatus?: string;
  onStatusChange?: () => void;
  compact?: boolean;
}

function formatTimeAgo(isoDateStr?: string | null): string {
  if (!isoDateStr) return 'Never';
  const diffMs = Date.now() - new Date(isoDateStr).getTime();
  if (isNaN(diffMs) || diffMs < 0) return 'Just now';
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 60) return `${diffSec}s ago`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDays = Math.floor(diffHr / 24);
  return `${diffDays}d ago`;
}

function formatEta(seconds?: number | null): string {
  if (seconds === undefined || seconds === null) return 'Calculating...';
  if (seconds <= 0) return 'Moments remaining';
  if (seconds < 60) return `~${seconds}s`;
  const mins = Math.floor(seconds / 60);
  if (mins < 60) return `~${mins}m`;
  const hours = Math.floor(mins / 60);
  const remainingMins = mins % 60;
  if (hours < 24) return `~${hours}h ${remainingMins}m`;
  const days = Math.floor(hours / 24);
  return `~${days}d ${hours % 24}h`;
}

export const RepositoryIndexControl: React.FC<RepositoryIndexControlProps> = ({
  projectId,
  repositoryId,
  branch,
  initialStatus,
  onStatusChange,
  compact = false,
}) => {
  const [progress, setProgress] = useState<RepositoryIndexProgress | null>(null);
  const [isActionLoading, setIsActionLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollIntervalRef = useRef<NodeJS.Timeout | null>(null);

  const loadStatus = useCallback(async () => {
    try {
      const data = await fetchRepositoryIndexStatus(projectId, repositoryId);
      setProgress(data);
      if (data.status !== 'INDEXING' && pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
        if (onStatusChange) {
          onStatusChange();
        }
      }
    } catch (err: any) {
      console.warn('Failed to fetch index status:', err.message);
    }
  }, [projectId, repositoryId, onStatusChange]);

  const startPolling = useCallback(() => {
    if (pollIntervalRef.current) return;
    pollIntervalRef.current = setInterval(() => {
      loadStatus();
    }, 2500);
  }, [loadStatus]);

  useEffect(() => {
    loadStatus();
    return () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
      }
    };
  }, [loadStatus]);

  useEffect(() => {
    if (progress?.status === 'INDEXING') {
      startPolling();
    }
  }, [progress?.status, startPolling]);

  const handleIndex = async () => {
    setIsActionLoading(true);
    setError(null);
    try {
      const result = await triggerRepositoryIndexing(projectId, repositoryId, branch);
      setProgress(result);
      if (result.status === 'INDEXING') {
        startPolling();
      }
      if (onStatusChange) {
        onStatusChange();
      }
    } catch (err: any) {
      setError(err.message || 'Failed to start indexing process');
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleStop = async () => {
    setIsActionLoading(true);
    setError(null);
    try {
      const result = await stopRepositoryIndexing(projectId, repositoryId);
      setProgress(result);
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
      if (onStatusChange) {
        onStatusChange();
      }
    } catch (err: any) {
      setError(err.message || 'Failed to stop indexing process');
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleResume = async () => {
    setIsActionLoading(true);
    setError(null);
    try {
      const result = await resumeRepositoryIndexing(projectId, repositoryId);
      setProgress(result);
      if (result.status === 'INDEXING') {
        startPolling();
      }
      if (onStatusChange) {
        onStatusChange();
      }
    } catch (err: any) {
      setError(err.message || 'Failed to resume indexing process');
    } finally {
      setIsActionLoading(false);
    }
  };

  const status = progress?.status || initialStatus || 'NOT_INDEXED';
  const health = progress?.health || (status === 'INDEXED' ? 'READY' : status === 'CANCELLED' ? 'STOPPED' : status === 'FAILED' ? 'FAILED' : 'NOT_INDEXED');
  const embeddedCount = progress?.embeddedChunks ?? 0;
  const totalChunks = progress?.totalChunks ?? 0;
  const remainingCount = progress?.remainingEmbeddings ?? Math.max(0, totalChunks - embeddedCount);
  const percentage = progress?.progressPercentage !== undefined ? progress.progressPercentage : (status === 'INDEXED' ? 100 : 0);

  if (compact) {
    return (
      <div className="flex items-center gap-2">
        {status === 'INDEXED' && (
          <span className="inline-flex items-center text-xs text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20">
            <CheckCircle2 className="h-3 w-3 mr-1" /> Indexed
          </span>
        )}
        {status === 'INDEXING' && (
          <span className="inline-flex items-center text-xs text-amber-400 bg-amber-500/10 px-2 py-0.5 rounded border border-amber-500/20">
            <RefreshCw className="h-3 w-3 mr-1 animate-spin" /> Indexing ({embeddedCount}/{totalChunks})
          </span>
        )}
        {status === 'CANCELLED' && (
          <span className="inline-flex items-center text-xs text-slate-400 bg-slate-800 px-2 py-0.5 rounded border border-slate-700">
            <Square className="h-3 w-3 mr-1 text-slate-400" /> Paused
          </span>
        )}
        {status === 'FAILED' && (
          <span className="inline-flex items-center text-xs text-rose-400 bg-rose-500/10 px-2 py-0.5 rounded border border-rose-500/20">
            <AlertCircle className="h-3 w-3 mr-1" /> Failed
          </span>
        )}
        {status === 'NOT_INDEXED' && (
          <span className="inline-flex items-center text-xs text-slate-400 bg-slate-800 px-2 py-0.5 rounded border border-slate-700">
            <Database className="h-3 w-3 mr-1" /> Not Indexed
          </span>
        )}

        {progress?.canStop && (
          <button
            onClick={handleStop}
            disabled={isActionLoading}
            className="inline-flex items-center px-2 py-1 text-xs font-medium text-rose-400 hover:text-rose-300 bg-rose-500/10 hover:bg-rose-500/20 rounded border border-rose-500/20 transition-colors disabled:opacity-50"
            title="Stop indexing process"
          >
            <Square className="h-3 w-3 mr-1 fill-current" /> Stop
          </button>
        )}

        {progress?.canResume && status !== 'NOT_INDEXED' && (
          <button
            onClick={handleResume}
            disabled={isActionLoading}
            className="inline-flex items-center px-2 py-1 text-xs font-medium text-emerald-400 hover:text-emerald-300 bg-emerald-500/10 hover:bg-emerald-500/20 rounded border border-emerald-500/20 transition-colors disabled:opacity-50"
            title="Resume missing embeddings"
          >
            <Play className="h-3 w-3 mr-1 fill-current" /> Resume
          </button>
        )}

        {(!progress?.canStop && !progress?.canResume) || status === 'NOT_INDEXED' ? (
          <button
            onClick={handleIndex}
            disabled={isActionLoading || status === 'INDEXING'}
            className="inline-flex items-center px-2 py-1 text-xs font-medium text-indigo-400 hover:text-indigo-300 bg-indigo-500/10 hover:bg-indigo-500/20 rounded border border-indigo-500/20 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isActionLoading ? (
              <RefreshCw className="h-3 w-3 animate-spin mr-1" />
            ) : (
              <Cpu className="h-3 w-3 mr-1" />
            )}
            {status === 'INDEXED' ? 'Re-index' : 'Index'}
          </button>
        ) : null}
      </div>
    );
  }

  return (
    <div className="bg-slate-950/80 border border-slate-800/90 rounded-xl p-5 space-y-4">
      {/* Header Banner */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-400">
            <Cpu className="h-5 w-5" />
          </div>
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <h4 className="text-sm font-semibold text-slate-200">Codebase Indexing & Vector Embeddings</h4>

              {status === 'INDEXED' && (
                <span className="inline-flex items-center text-[11px] font-medium text-emerald-400 bg-emerald-500/10 px-2.5 py-0.5 rounded-full border border-emerald-500/20">
                  <CheckCircle2 className="h-3.5 w-3.5 mr-1" /> Codebase Ready
                </span>
              )}

              {status === 'INDEXING' && health === 'STALLED' && (
                <span className="inline-flex items-center text-[11px] font-medium text-amber-400 bg-amber-500/10 px-2.5 py-0.5 rounded-full border border-amber-500/20">
                  <AlertCircle className="h-3.5 w-3.5 mr-1 text-amber-400" /> Stalled — No Recent Progress
                </span>
              )}

              {status === 'INDEXING' && health === 'SLOW' && (
                <span className="inline-flex items-center text-[11px] font-medium text-amber-300 bg-amber-500/10 px-2.5 py-0.5 rounded-full border border-amber-500/20">
                  <Activity className="h-3.5 w-3.5 mr-1 animate-pulse" /> Slow Indexing Rate
                </span>
              )}

              {status === 'INDEXING' && health === 'HEALTHY' && (
                <span className="inline-flex items-center text-[11px] font-medium text-indigo-400 bg-indigo-500/10 px-2.5 py-0.5 rounded-full border border-indigo-500/20">
                  <RefreshCw className="h-3.5 w-3.5 mr-1 animate-spin" /> Indexing Codebase...
                </span>
              )}

              {status === 'CANCELLED' && (
                <span className="inline-flex items-center text-[11px] font-medium text-slate-300 bg-slate-800 px-2.5 py-0.5 rounded-full border border-slate-700">
                  <Square className="h-3.5 w-3.5 mr-1 fill-current" /> Indexing Paused
                </span>
              )}

              {status === 'FAILED' && (
                <span className="inline-flex items-center text-[11px] font-medium text-rose-400 bg-rose-500/10 px-2.5 py-0.5 rounded-full border border-rose-500/20">
                  <AlertCircle className="h-3.5 w-3.5 mr-1" /> Indexing Failed
                </span>
              )}

              {status === 'NOT_INDEXED' && (
                <span className="inline-flex items-center text-[11px] font-medium text-slate-400 bg-slate-800 px-2.5 py-0.5 rounded-full border border-slate-700">
                  <Database className="h-3.5 w-3.5 mr-1" /> Not Indexed
                </span>
              )}
            </div>
            <p className="text-xs text-slate-400 mt-1">
              Extracts semantic code chunks and vector embeddings (pgvector 1024-dim) for RAG code search & AI planning.
            </p>
          </div>
        </div>

        {/* Action Controls */}
        <div className="flex items-center gap-2 shrink-0">
          {progress?.canStop && (
            <button
              onClick={handleStop}
              disabled={isActionLoading}
              className="inline-flex items-center px-3.5 py-1.5 text-xs font-semibold text-rose-300 bg-rose-950/60 hover:bg-rose-900/80 rounded-lg border border-rose-800/60 transition-colors disabled:opacity-50"
            >
              <Square className="h-3.5 w-3.5 mr-1.5 fill-current" />
              Stop Indexing
            </button>
          )}

          {progress?.canResume && (status === 'CANCELLED' || status === 'FAILED') && (
            <button
              onClick={handleResume}
              disabled={isActionLoading}
              className="inline-flex items-center px-3.5 py-1.5 text-xs font-semibold text-white bg-emerald-600 hover:bg-emerald-500 rounded-lg shadow transition-colors disabled:opacity-50"
            >
              {isActionLoading ? (
                <RefreshCw className="h-3.5 w-3.5 animate-spin mr-1.5" />
              ) : (
                <Play className="h-3.5 w-3.5 mr-1.5 fill-current" />
              )}
              Resume Indexing
            </button>
          )}

          {(!progress?.canStop && status !== 'CANCELLED' && status !== 'FAILED') || status === 'NOT_INDEXED' ? (
            <button
              onClick={handleIndex}
              disabled={isActionLoading || status === 'INDEXING'}
              className="inline-flex items-center px-3.5 py-1.5 text-xs font-semibold text-white bg-indigo-600 hover:bg-indigo-500 rounded-lg shadow transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {isActionLoading || status === 'INDEXING' ? (
                <>
                  <RefreshCw className="h-3.5 w-3.5 animate-spin mr-1.5" />
                  Indexing...
                </>
              ) : (
                <>
                  <Cpu className="h-3.5 w-3.5 mr-1.5" />
                  {status === 'INDEXED' ? 'Re-index Repository' : 'Index Repository'}
                </>
              )}
            </button>
          ) : null}
        </div>
      </div>

      {/* Progress Bar Component */}
      <div className="space-y-1.5 pt-1">
        <div className="flex items-center justify-between text-xs text-slate-300">
          <span className="font-medium">Embedding Progress</span>
          <span className="font-mono text-indigo-400 font-semibold">{percentage.toFixed(1)}%</span>
        </div>
        <div className="w-full bg-slate-900 rounded-full h-2 overflow-hidden border border-slate-800">
          <div
            className={`h-full transition-all duration-500 ${
              status === 'INDEXED'
                ? 'bg-emerald-500'
                : health === 'STALLED'
                ? 'bg-amber-500'
                : status === 'CANCELLED'
                ? 'bg-slate-500'
                : status === 'FAILED'
                ? 'bg-rose-500'
                : 'bg-indigo-500'
            }`}
            style={{ width: `${Math.min(100, Math.max(0, percentage))}%` }}
          />
        </div>
      </div>

      {/* Detailed Metrics Grid */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-2 text-xs border-t border-slate-800/80">
        <div className="bg-slate-900/60 border border-slate-800/60 p-2.5 rounded-lg space-y-0.5">
          <span className="text-[11px] text-slate-400">Files Processed</span>
          <p className="font-semibold text-slate-200 font-mono">
            {progress?.processedFiles ?? 0} / {progress?.totalFiles ?? 0}
          </p>
        </div>

        <div className="bg-slate-900/60 border border-slate-800/60 p-2.5 rounded-lg space-y-0.5">
          <span className="text-[11px] text-slate-400">CodeChunks</span>
          <p className="font-semibold text-slate-200 font-mono">
            {totalChunks}
          </p>
        </div>

        <div className="bg-slate-900/60 border border-slate-800/60 p-2.5 rounded-lg space-y-0.5">
          <span className="text-[11px] text-slate-400">Embeddings</span>
          <p className="font-semibold text-indigo-300 font-mono">
            {embeddedCount} / {totalChunks}
          </p>
          <p className="text-[10px] text-slate-500 font-mono">{remainingCount} remaining</p>
        </div>

        <div className="bg-slate-900/60 border border-slate-800/60 p-2.5 rounded-lg space-y-0.5">
          <span className="text-[11px] text-slate-400 flex items-center gap-1">
            <Zap className="h-3 w-3 text-amber-400" /> Processing Rate
          </span>
          <p className="font-semibold text-slate-200 font-mono">
            {progress?.ratePerMinute !== undefined && progress?.ratePerMinute !== null
              ? `~${progress.ratePerMinute.toFixed(1)} emb/min`
              : 'Calculating...'}
          </p>
        </div>
      </div>

      {/* Health & Status Secondary Metrics */}
      <div className="pt-1 text-[11px] text-slate-400 flex flex-wrap items-center justify-between gap-2 border-t border-slate-800/40">
        <div className="flex items-center gap-4">
          <span className="flex items-center gap-1 text-slate-400">
            <Clock className="h-3 w-3 text-slate-500" />
            Estimated remaining: <strong className="text-slate-200 font-mono ml-1">{formatEta(progress?.estimatedRemainingSeconds)}</strong>
          </span>
          <span>
            Last progress: <strong className="text-slate-300 font-mono ml-1">{formatTimeAgo(progress?.lastProgressAt)}</strong>
          </span>
        </div>

        {status === 'CANCELLED' && (
          <span className="text-slate-400 font-mono">
            Existing {embeddedCount} embeddings preserved intact
          </span>
        )}
      </div>

      {/* Error Message */}
      {(error || progress?.errorMessage) && (
        <div className="flex items-start gap-2 p-2.5 rounded-lg bg-rose-950/40 border border-rose-800/40 text-xs text-rose-300">
          <AlertCircle className="h-4 w-4 text-rose-400 shrink-0 mt-0.5" />
          <p>{error || progress?.errorMessage}</p>
        </div>
      )}
    </div>
  );
};
