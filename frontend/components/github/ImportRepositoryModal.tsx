'use client';

import React, { useEffect, useState, useCallback } from 'react';
import { GithubRepo, ImportRepositoryRequest } from '@/types/github';
import { fetchUserGithubRepos, importRepositoryToProject, fetchGithubStatus } from '@/lib/github';
import { X, Search, GitBranch, Lock, Globe, AlertCircle, CheckCircle, RefreshCw, Github } from 'lucide-react';

interface ImportRepositoryModalProps {
  isOpen: boolean;
  onClose: () => void;
  projectId: string;
  onSuccess: () => void;
}

export function ImportRepositoryModal({ isOpen, onClose, projectId, onSuccess }: ImportRepositoryModalProps) {
  const [repos, setRepos] = useState<GithubRepo[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [importingRepoId, setImportingRepoId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notConnected, setNotConnected] = useState(false);

  const loadUserRepos = useCallback(async () => {
    setLoading(true);
    setError(null);
    setNotConnected(false);

    try {
      const status = await fetchGithubStatus();
      if (!status.connected) {
        setNotConnected(true);
        setLoading(false);
        return;
      }

      const userRepos = await fetchUserGithubRepos();
      setRepos(userRepos);
    } catch (err: any) {
      if (err.response?.status === 401 || err.status === 401) {
        setNotConnected(true);
      } else {
        setError(err.message || 'Failed to fetch repositories from GitHub.');
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isOpen) {
      loadUserRepos();
    } else {
      setSearchQuery('');
      setError(null);
    }
  }, [isOpen, loadUserRepos]);

  if (!isOpen) return null;

  const filteredRepos = repos.filter(
    (r) =>
      r.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      r.owner.toLowerCase().includes(searchQuery.toLowerCase()) ||
      r.fullName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const handleImport = async (repo: GithubRepo) => {
    setImportingRepoId(repo.id);
    setError(null);

    const req: ImportRepositoryRequest = {
      githubRepoId: repo.id,
      ownerName: repo.owner,
      repoName: repo.name,
      defaultBranch: repo.defaultBranch || 'main',
      cloneUrl: repo.cloneUrl,
      webUrl: repo.htmlUrl,
      visibility: repo.isPrivate ? 'PRIVATE' : 'PUBLIC',
    };

    try {
      await importRepositoryToProject(projectId, req);
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message || `Failed to import ${repo.fullName}.`);
    } finally {
      setImportingRepoId(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="bg-slate-900 border border-slate-800 rounded-xl w-full max-w-2xl overflow-hidden shadow-2xl flex flex-col max-h-[85vh]">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-800">
          <div className="flex items-center space-x-2">
            <Github className="h-5 w-5 text-blue-400" />
            <h2 className="text-lg font-semibold text-slate-100">Import GitHub Repository</h2>
          </div>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-slate-200 transition-colors p-1 rounded-lg hover:bg-slate-800"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Content */}
        <div className="p-6 overflow-y-auto flex-1 space-y-4">
          {error && (
            <div className="p-3 bg-rose-500/10 border border-rose-500/20 rounded-lg text-rose-400 text-sm flex items-center">
              <AlertCircle className="h-4 w-4 mr-2 flex-shrink-0" />
              <span>{error}</span>
            </div>
          )}

          {notConnected ? (
            <div className="text-center py-8">
              <Github className="h-12 w-12 text-slate-500 mx-auto mb-3" />
              <h3 className="text-slate-200 font-medium mb-1">GitHub Account Not Connected</h3>
              <p className="text-slate-400 text-sm max-w-md mx-auto mb-4">
                Please connect your GitHub account in User Settings to import repositories into your project.
              </p>
              <a
                href="/settings"
                className="inline-flex items-center px-4 py-2 text-sm font-medium bg-blue-600 hover:bg-blue-500 text-white rounded-lg transition-colors"
              >
                Go to Settings
              </a>
            </div>
          ) : (
            <>
              {/* Search Bar */}
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
                <input
                  type="text"
                  placeholder="Search repositories by name or owner..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full bg-slate-950 border border-slate-800 rounded-lg pl-9 pr-4 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-blue-500"
                />
              </div>

              {/* Repo List */}
              {loading ? (
                <div className="text-center py-12 text-slate-400 flex items-center justify-center">
                  <RefreshCw className="h-5 w-5 animate-spin mr-2" /> Loading your GitHub repositories...
                </div>
              ) : filteredRepos.length === 0 ? (
                <div className="text-center py-10 text-slate-400">
                  <p>No repositories found.</p>
                </div>
              ) : (
                <div className="space-y-2 max-h-96 overflow-y-auto pr-1">
                  {filteredRepos.map((repo) => {
                    const isImporting = importingRepoId === repo.id;
                    return (
                      <div
                        key={repo.id}
                        className="flex items-center justify-between p-3.5 bg-slate-950/60 border border-slate-800/80 rounded-lg hover:border-slate-700 transition-colors"
                      >
                        <div className="flex-1 min-w-0 mr-4">
                          <div className="flex items-center space-x-2">
                            <span className="font-semibold text-slate-100 truncate text-sm">
                              {repo.fullName}
                            </span>
                            {repo.isPrivate ? (
                              <span className="inline-flex items-center text-xs text-amber-400 bg-amber-500/10 px-2 py-0.5 rounded border border-amber-500/20">
                                <Lock className="h-3 w-3 mr-1" /> Private
                              </span>
                            ) : (
                              <span className="inline-flex items-center text-xs text-slate-400 bg-slate-800 px-2 py-0.5 rounded">
                                <Globe className="h-3 w-3 mr-1" /> Public
                              </span>
                            )}
                          </div>
                          {repo.description && (
                            <p className="text-xs text-slate-400 truncate mt-0.5">{repo.description}</p>
                          )}
                          <div className="flex items-center text-xs text-slate-500 mt-1">
                            <GitBranch className="h-3 w-3 mr-1" /> Default: {repo.defaultBranch}
                          </div>
                        </div>

                        <button
                          onClick={() => handleImport(repo)}
                          disabled={isImporting || importingRepoId !== null}
                          className="px-3.5 py-1.5 text-xs font-medium bg-blue-600 hover:bg-blue-500 text-white rounded-lg transition-colors shadow disabled:opacity-50 flex-shrink-0 flex items-center"
                        >
                          {isImporting ? (
                            <>
                              <RefreshCw className="h-3.5 w-3.5 animate-spin mr-1.5" /> Importing...
                            </>
                          ) : (
                            'Import'
                          )}
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
