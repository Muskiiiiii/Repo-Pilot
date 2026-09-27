'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { Branch, FileContent, FileTreeItem, ProjectRepository } from '@/types/github';
import {
  fetchProjectRepositoryDetails,
  fetchRepositoryBranches,
  fetchRepositoryFileTree,
  fetchRepositoryFileContent,
} from '@/lib/github';
import { RepositoryIndexControl } from '@/components/github/RepositoryIndexControl';
import { SemanticSearchControl } from '@/components/repository/SemanticSearchControl';
import { TaskPlanningControl } from '@/components/task/TaskPlanningControl';
import {
  ArrowLeft,
  GitBranch,
  FileText,
  Folder,
  Globe,
  Lock,
  RefreshCw,
  AlertCircle,
  FileCode,
  CheckCircle2,
} from 'lucide-react';

export default function RepositoryExplorerPage() {
  const params = useParams();
  const projectId = params.id as string;
  const repositoryId = params.repoId as string;

  const [repo, setRepo] = useState<ProjectRepository | null>(null);
  const [branches, setBranches] = useState<Branch[]>([]);
  const [selectedBranch, setSelectedBranch] = useState<string>('');
  const [fileTree, setFileTree] = useState<FileTreeItem[]>([]);
  const [selectedFile, setSelectedFile] = useState<FileContent | null>(null);
  const [selectedFilePath, setSelectedFilePath] = useState<string | null>(null);

  const [loadingRepo, setLoadingRepo] = useState(true);
  const [loadingTree, setLoadingTree] = useState(false);
  const [loadingFile, setLoadingFile] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadRepoAndBranches = useCallback(async () => {
    setLoadingRepo(true);
    setError(null);
    try {
      const repositoryData = await fetchProjectRepositoryDetails(projectId, repositoryId);
      setRepo(repositoryData);
      setSelectedBranch(repositoryData.defaultBranch || 'main');

      const branchesData = await fetchRepositoryBranches(projectId, repositoryId);
      setBranches(branchesData);
    } catch (err: any) {
      setError(err.message || 'Failed to load repository details.');
    } finally {
      setLoadingRepo(false);
    }
  }, [projectId, repositoryId]);

  const loadFileTree = useCallback(async (branch: string) => {
    setLoadingTree(true);
    try {
      const tree = await fetchRepositoryFileTree(projectId, repositoryId, branch);
      setFileTree(tree);
    } catch (err: any) {
      console.warn('File tree fetch warning:', err.message);
      setFileTree([]);
    } finally {
      setLoadingTree(false);
    }
  }, [projectId, repositoryId]);

  useEffect(() => {
    loadRepoAndBranches();
  }, [loadRepoAndBranches]);

  useEffect(() => {
    if (selectedBranch) {
      loadFileTree(selectedBranch);
      setSelectedFile(null);
      setSelectedFilePath(null);
    }
  }, [selectedBranch, loadFileTree]);

  const handleFileSelect = async (path: string) => {
    setSelectedFilePath(path);
    setLoadingFile(true);
    try {
      const fileData = await fetchRepositoryFileContent(projectId, repositoryId, selectedBranch, path);
      setSelectedFile(fileData);
    } catch (err: any) {
      setError(`Failed to read file ${path}: ` + err.message);
    } finally {
      setLoadingFile(false);
    }
  };

  if (loadingRepo) {
    return (
      <div className="flex h-96 w-full items-center justify-center text-slate-400">
        <RefreshCw className="h-8 w-8 animate-spin text-blue-400 mr-3" />
        <span>Loading repository Explorer...</span>
      </div>
    );
  }

  if (error && !repo) {
    return (
      <div className="max-w-3xl mx-auto my-12 space-y-4">
        <div className="flex items-center gap-3 rounded-xl bg-rose-950/60 border border-rose-800/50 p-6 text-rose-200">
          <AlertCircle className="h-6 w-6 text-rose-400 shrink-0" />
          <div>
            <h3 className="font-semibold text-base">Error Loading Repository</h3>
            <p className="text-sm text-rose-300">{error}</p>
          </div>
        </div>
        <Link href={`/projects/${projectId}`} className="inline-flex items-center gap-2 text-sm text-blue-400 hover:underline">
          <ArrowLeft className="h-4 w-4" /> Back to Project
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {/* Top Header */}
      <div className="flex items-center justify-between">
        <Link
          href={`/projects/${projectId}`}
          className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-400 hover:text-slate-200 transition-colors"
        >
          <ArrowLeft className="h-3.5 w-3.5" /> Back to Project
        </Link>
      </div>

      {/* Repo Title Banner */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div className="space-y-1">
          <div className="flex items-center space-x-3">
            <h1 className="text-2xl font-bold text-slate-100">{repo?.ownerName} / {repo?.repoName}</h1>
            {repo?.visibility === 'PRIVATE' ? (
              <span className="inline-flex items-center text-xs text-amber-400 bg-amber-500/10 px-2.5 py-0.5 rounded border border-amber-500/20">
                <Lock className="h-3 w-3 mr-1" /> Private
              </span>
            ) : (
              <span className="inline-flex items-center text-xs text-slate-400 bg-slate-800 px-2.5 py-0.5 rounded border border-slate-700">
                <Globe className="h-3 w-3 mr-1" /> Public
              </span>
            )}
          </div>
          <p className="text-xs text-slate-400">
            Default Branch: <span className="font-mono text-slate-200">{repo?.defaultBranch}</span>
          </p>
        </div>

        {/* Branch Selector Dropdown */}
        <div className="flex items-center space-x-2">
          <GitBranch className="h-4 w-4 text-slate-400" />
          <select
            value={selectedBranch}
            onChange={(e) => setSelectedBranch(e.target.value)}
            className="bg-slate-950 border border-slate-800 rounded-lg px-3 py-1.5 text-sm text-slate-200 focus:outline-none focus:border-blue-500 font-mono"
          >
            {branches.map((b) => (
              <option key={b.name} value={b.name}>
                {b.name} {b.isDefault ? '(default)' : ''}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Codebase Indexing Control */}
      <RepositoryIndexControl
        projectId={projectId}
        repositoryId={repositoryId}
        branch={selectedBranch}
        initialStatus={repo?.indexStatus}
      />

      {/* Semantic Code Search */}
      <SemanticSearchControl
        projectId={projectId}
        repositoryId={repositoryId}
        isIndexed={repo?.indexStatus === 'INDEXED'}
      />

      {/* AI Task & Implementation Planner */}
      <TaskPlanningControl
        projectId={projectId}
        repositoryId={repositoryId}
        isIndexed={repo?.indexStatus === 'INDEXED'}
      />

      {/* File Tree & Content Split View */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 min-h-[500px]">
        {/* Left: File Tree Browser */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col">
          <div className="flex items-center justify-between border-b border-slate-800 pb-3 mb-3">
            <h3 className="text-sm font-semibold text-slate-200 flex items-center gap-1.5">
              <Folder className="h-4 w-4 text-blue-400" /> Repository Files
            </h3>
            <span className="text-xs text-slate-500">{fileTree.length} items</span>
          </div>

          {loadingTree ? (
            <div className="flex-1 flex items-center justify-center text-slate-400 text-xs py-12">
              <RefreshCw className="h-4 w-4 animate-spin mr-2" /> Loading tree...
            </div>
          ) : fileTree.length === 0 ? (
            <div className="flex-1 flex items-center justify-center text-slate-500 text-xs py-12">
              No files found in branch &quot;{selectedBranch}&quot;.
            </div>
          ) : (
            <div className="overflow-y-auto max-h-[550px] space-y-1 pr-1 text-xs">
              {fileTree.map((item) => {
                const isSelected = selectedFilePath === item.path;
                return (
                  <button
                    key={item.path}
                    onClick={() => item.type === 'blob' && handleFileSelect(item.path)}
                    disabled={item.type !== 'blob'}
                    className={`w-full text-left px-2.5 py-1.5 rounded flex items-center justify-between truncate transition-colors ${
                      isSelected
                        ? 'bg-blue-600/20 text-blue-300 border border-blue-500/30'
                        : item.type === 'blob'
                        ? 'text-slate-300 hover:bg-slate-800/60'
                        : 'text-slate-500 cursor-default'
                    }`}
                  >
                    <span className="truncate flex items-center gap-2">
                      {item.type === 'tree' ? (
                        <Folder className="h-3.5 w-3.5 text-blue-400 shrink-0" />
                      ) : (
                        <FileCode className="h-3.5 w-3.5 text-slate-400 shrink-0" />
                      )}
                      <span className="font-mono">{item.path}</span>
                    </span>
                    {item.size > 0 && (
                      <span className="text-[10px] text-slate-500 font-mono ml-2 shrink-0">
                        {Math.round(item.size / 1024)} KB
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Right: File Viewer */}
        <div className="lg:col-span-2 bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col">
          {loadingFile ? (
            <div className="flex-1 flex items-center justify-center text-slate-400 text-sm py-24">
              <RefreshCw className="h-5 w-5 animate-spin mr-2 text-blue-400" /> Reading file contents...
            </div>
          ) : selectedFile ? (
            <div className="space-y-3 flex-1 flex flex-col">
              <div className="flex items-center justify-between border-b border-slate-800 pb-3">
                <div className="flex items-center space-x-2">
                  <FileText className="h-4 w-4 text-blue-400" />
                  <span className="font-mono text-sm font-semibold text-slate-200">{selectedFile.path}</span>
                </div>
                <span className="text-xs text-slate-400 font-mono">
                  {selectedFile.size} bytes | {selectedFile.branch}
                </span>
              </div>

              {selectedFile.isBinary ? (
                <div className="p-8 text-center text-slate-500 text-sm">
                  Binary file cannot be previewed.
                </div>
              ) : (
                <pre className="flex-1 bg-slate-950 p-4 rounded-lg overflow-x-auto text-xs font-mono text-slate-300 border border-slate-800 max-h-[500px]">
                  <code>{selectedFile.content}</code>
                </pre>
              )}
            </div>
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center text-slate-500 py-24 text-center">
              <FileCode className="h-10 w-10 text-slate-700 mb-3" />
              <p className="text-sm font-medium text-slate-400">Select a file from the repository tree</p>
              <p className="text-xs text-slate-600 mt-1">Click any file on the left sidebar to inspect code.</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
