'use client';

import React, { useEffect, useState, useCallback } from 'react';
import { GithubStatus } from '@/types/github';
import { fetchGithubStatus, fetchGithubAuthorizeUrl, disconnectGithub } from '@/lib/github';
import { Github, CheckCircle, AlertCircle, LogOut, RefreshCw } from 'lucide-react';

export function ConnectGithubCard() {
  const [status, setStatus] = useState<GithubStatus | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [actionLoading, setActionLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const loadStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchGithubStatus();
      setStatus(data);
    } catch (err: any) {
      setError(err.message || 'Failed to check GitHub connection status.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStatus();
  }, [loadStatus]);

  const handleConnect = async () => {
    setActionLoading(true);
    setError(null);
    try {
      const auth = await fetchGithubAuthorizeUrl();
      if (auth && auth.authorizeUrl) {
        window.location.href = auth.authorizeUrl;
      } else {
        throw new Error('Failed to obtain GitHub authorization link.');
      }
    } catch (err: any) {
      setError(err.message || 'Failed to initiate GitHub connection.');
      setActionLoading(false);
    }
  };

  const handleDisconnect = async () => {
    if (!confirm('Are you sure you want to disconnect your GitHub account?')) return;
    setActionLoading(true);
    setError(null);
    try {
      await disconnectGithub();
      setSuccess('GitHub account disconnected successfully.');
      await loadStatus();
    } catch (err: any) {
      setError(err.message || 'Failed to disconnect GitHub account.');
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 shadow-sm">
      <div className="flex items-start justify-between">
        <div className="flex items-center space-x-3">
          <div className="p-3 bg-slate-800 rounded-lg text-slate-100">
            <Github className="h-6 w-6" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-slate-100">GitHub Integration</h3>
            <p className="text-sm text-slate-400">
              Connect your GitHub account to import repositories and allow RepoPilot AI agents to read codebase files.
            </p>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center text-slate-400 text-sm">
            <RefreshCw className="h-4 w-4 animate-spin mr-2" /> Loading...
          </div>
        ) : status?.connected ? (
          <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
            <CheckCircle className="h-3.5 w-3.5 mr-1.5" /> Connected
          </span>
        ) : (
          <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-amber-500/10 text-amber-400 border border-amber-500/20">
            Not Connected
          </span>
        )}
      </div>

      {error && (
        <div className="mt-4 p-3 bg-rose-500/10 border border-rose-500/20 rounded-lg text-rose-400 text-sm flex items-center">
          <AlertCircle className="h-4 w-4 mr-2 flex-shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {success && (
        <div className="mt-4 p-3 bg-emerald-500/10 border border-emerald-500/20 rounded-lg text-emerald-400 text-sm flex items-center">
          <CheckCircle className="h-4 w-4 mr-2 flex-shrink-0" />
          <span>{success}</span>
        </div>
      )}

      <div className="mt-6 pt-4 border-t border-slate-800/80 flex items-center justify-between">
        {status?.connected ? (
          <div className="text-sm text-slate-300">
            Connected as <span className="font-mono font-semibold text-blue-400">@{status.githubUsername}</span>
            {status.connectedAt && (
              <span className="text-xs text-slate-500 block mt-0.5">
                Since {new Date(status.connectedAt).toLocaleDateString()}
              </span>
            )}
          </div>
        ) : (
          <div className="text-sm text-slate-400">
            No GitHub account linked.
          </div>
        )}

        <div>
          {status?.connected ? (
            <button
              onClick={handleDisconnect}
              disabled={actionLoading}
              className="inline-flex items-center px-4 py-2 text-sm font-medium text-rose-400 hover:text-rose-300 hover:bg-rose-500/10 rounded-lg border border-rose-500/20 transition-colors disabled:opacity-50"
            >
              {actionLoading ? (
                <RefreshCw className="h-4 w-4 animate-spin mr-2" />
              ) : (
                <LogOut className="h-4 w-4 mr-2" />
              )}
              Disconnect
            </button>
          ) : (
            <button
              onClick={handleConnect}
              disabled={actionLoading || loading}
              className="inline-flex items-center px-4 py-2 text-sm font-medium text-slate-900 bg-slate-100 hover:bg-white rounded-lg transition-colors shadow disabled:opacity-50"
            >
              {actionLoading ? (
                <RefreshCw className="h-4 w-4 animate-spin mr-2 text-slate-900" />
              ) : (
                <Github className="h-4 w-4 mr-2 text-slate-900" />
              )}
              Connect GitHub Account
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
