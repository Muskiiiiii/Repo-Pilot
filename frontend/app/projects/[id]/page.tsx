'use client';

import React, { useEffect, useState, useCallback } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { Project } from '@/types/project';
import { ProjectRepository } from '@/types/github';
import { fetchProjectById } from '@/lib/projects';
import { fetchProjectRepositories } from '@/lib/github';
import { ImportRepositoryModal } from '@/components/github/ImportRepositoryModal';
import { RepositoryIndexControl } from '@/components/github/RepositoryIndexControl';
import {
  Folder,
  GitBranch,
  CheckSquare,
  Users,
  Settings,
  Shield,
  Clock,
  Plus,
  AlertCircle,
  ArrowLeft,
  Activity,
  Code2,
  Lock,
  Globe,
  ExternalLink,
} from 'lucide-react';

export default function ProjectDashboardPage() {
  const params = useParams();
  const router = useRouter();
  const projectId = params.id as string;

  const [project, setProject] = useState<Project | null>(null);
  const [repositories, setRepositories] = useState<ProjectRepository[]>([]);
  const [isImportModalOpen, setIsImportModalOpen] = useState(false);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const pData = await fetchProjectById(projectId);
      setProject(pData);

      const rData = await fetchProjectRepositories(projectId);
      setRepositories(rData);
    } catch (err: any) {
      setError(err.message || 'Failed to load project details.');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    if (projectId) {
      loadData();
    }
  }, [projectId, loadData]);

  if (loading) {
    return (
      <div className="flex h-96 w-full items-center justify-center text-slate-400">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-indigo-500 border-t-transparent"></div>
          <p className="text-sm font-medium">Loading project workspace...</p>
        </div>
      </div>
    );
  }

  if (error || !project) {
    return (
      <div className="max-w-3xl mx-auto my-12 space-y-4">
        <div className="flex items-center gap-3 rounded-xl bg-red-950/60 border border-red-800/50 p-6 text-red-200">
          <AlertCircle className="h-6 w-6 text-red-400 shrink-0" />
          <div>
            <h3 className="font-semibold text-base">Error Loading Project</h3>
            <p className="text-sm text-red-300">{error || 'Project not found or access denied.'}</p>
          </div>
        </div>
        <Link
          href="/projects"
          className="inline-flex items-center gap-2 text-sm font-medium text-indigo-400 hover:text-indigo-300"
        >
          <ArrowLeft className="h-4 w-4" /> Back to Projects List
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {/* Top Breadcrumb & Actions */}
      <div className="flex items-center justify-between">
        <Link
          href="/projects"
          className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-400 hover:text-slate-200 transition-colors"
        >
          <ArrowLeft className="h-3.5 w-3.5" /> Back to Projects
        </Link>

        {project.userRole === 'OWNER' || project.userRole === 'ADMIN' ? (
          <Link
            href={`/projects/${project.id}/settings`}
            className="flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-3 py-1.5 text-xs font-medium text-slate-300 hover:bg-slate-800 transition-colors"
          >
            <Settings className="h-3.5 w-3.5 text-slate-400" />
            Project Settings
          </Link>
        ) : null}
      </div>

      {/* Project Header Banner */}
      <div className="rounded-xl border border-slate-800 bg-slate-900 p-6 space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="space-y-1">
            <div className="flex items-center gap-3">
              <h1 className="text-2xl font-bold text-slate-100 tracking-tight">{project.name}</h1>
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/20 px-2.5 py-0.5 text-xs font-semibold text-emerald-300 border border-emerald-500/30">
                {project.status}
              </span>
              <span className="inline-flex items-center gap-1 rounded-full bg-indigo-500/20 px-2.5 py-0.5 text-xs font-semibold text-indigo-300 border border-indigo-500/30">
                <Shield className="h-3 w-3" />
                Role: {project.userRole}
              </span>
            </div>
            <p className="text-sm text-slate-400">{project.description || 'No description available for this project workspace.'}</p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-6 pt-4 border-t border-slate-800 text-xs text-slate-400">
          <span className="flex items-center gap-1.5">
            <Folder className="h-4 w-4 text-slate-500" />
            Owner: <strong className="text-slate-200">{project.owner?.name || project.owner?.email}</strong>
          </span>
          <span className="flex items-center gap-1.5">
            <Clock className="h-4 w-4 text-slate-500" />
            Created {new Date(project.createdAt).toLocaleDateString()}
          </span>
        </div>
      </div>

      {/* Navigation Tabs */}
      <div className="border-b border-slate-800 flex gap-6 text-sm font-medium">
        <button className="border-b-2 border-indigo-500 text-indigo-400 pb-3 font-semibold">
          Overview
        </button>
        <Link href="/tasks" className="text-slate-400 hover:text-slate-200 pb-3 transition-colors">
          Tasks ({project.taskCount})
        </Link>
        <button
          onClick={() => setIsImportModalOpen(true)}
          className="text-slate-400 hover:text-slate-200 pb-3 transition-colors flex items-center gap-1"
        >
          Repositories ({repositories.length})
        </button>
        <Link href={`/projects/${project.id}/settings`} className="text-slate-400 hover:text-slate-200 pb-3 transition-colors">
          Members ({project.memberCount})
        </Link>
      </div>

      {/* Dashboard Metrics Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="rounded-xl border border-slate-800 bg-slate-900 p-5 space-y-2">
          <div className="flex items-center justify-between text-slate-400">
            <span className="text-xs font-medium">Repositories</span>
            <GitBranch className="h-4 w-4 text-indigo-400" />
          </div>
          <p className="text-2xl font-bold text-slate-100">{repositories.length}</p>
          <p className="text-[11px] text-slate-500">Imported GitHub Repos</p>
        </div>

        <div className="rounded-xl border border-slate-800 bg-slate-900 p-5 space-y-2">
          <div className="flex items-center justify-between text-slate-400">
            <span className="text-xs font-medium">Active Tasks</span>
            <CheckSquare className="h-4 w-4 text-emerald-400" />
          </div>
          <p className="text-2xl font-bold text-slate-100">{project.taskCount}</p>
          <p className="text-[11px] text-slate-500">Engineering tasks</p>
        </div>

        <div className="rounded-xl border border-slate-800 bg-slate-900 p-5 space-y-2">
          <div className="flex items-center justify-between text-slate-400">
            <span className="text-xs font-medium">Team Members</span>
            <Users className="h-4 w-4 text-purple-400" />
          </div>
          <p className="text-2xl font-bold text-slate-100">{project.memberCount}</p>
          <p className="text-[11px] text-slate-500">Project contributors</p>
        </div>

        <div className="rounded-xl border border-slate-800 bg-slate-900 p-5 space-y-2">
          <div className="flex items-center justify-between text-slate-400">
            <span className="text-xs font-medium">AI Workflow Status</span>
            <Activity className="h-4 w-4 text-cyan-400" />
          </div>
          <p className="text-2xl font-bold text-emerald-400">Ready</p>
          <p className="text-[11px] text-slate-500">LangGraph Agent Engine</p>
        </div>
      </div>

      {/* Main Sections: Repositories & Tasks */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Connected Repositories Section */}
        <div className="rounded-xl border border-slate-800 bg-slate-900 p-6 space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-semibold text-slate-100 flex items-center gap-2">
              <Code2 className="h-5 w-5 text-indigo-400" />
              Imported Repositories
            </h3>
            <button
              onClick={() => setIsImportModalOpen(true)}
              className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500 transition-colors shadow"
            >
              <Plus className="h-3.5 w-3.5" /> Import Repository
            </button>
          </div>

          {repositories.length === 0 ? (
            <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-800 bg-slate-950/40 p-8 text-center">
              <GitBranch className="h-8 w-8 text-slate-600 mb-2" />
              <p className="text-sm font-medium text-slate-300">No Repositories Imported</p>
              <p className="text-xs text-slate-500 mt-1 mb-4">Import a GitHub repository to browse branches and view code files.</p>
              <button
                onClick={() => setIsImportModalOpen(true)}
                className="flex items-center gap-2 rounded-lg bg-indigo-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500 transition-colors"
              >
                <Plus className="h-3.5 w-3.5" /> Import Repository
              </button>
            </div>
          ) : (
            <div className="space-y-3">
              {repositories.map((repo) => (
                <div
                  key={repo.id}
                  className="p-4 bg-slate-950/60 border border-slate-800/80 rounded-lg flex items-center justify-between hover:border-slate-700 transition-colors"
                >
                  <div className="space-y-1">
                    <div className="flex items-center space-x-2">
                      <span className="font-semibold text-slate-100 text-sm">{repo.ownerName} / {repo.repoName}</span>
                      {repo.visibility === 'PRIVATE' ? (
                        <span className="inline-flex items-center text-[10px] text-amber-400 bg-amber-500/10 px-1.5 py-0.5 rounded border border-amber-500/20">
                          <Lock className="h-3 w-3 mr-1" /> Private
                        </span>
                      ) : (
                        <span className="inline-flex items-center text-[10px] text-slate-400 bg-slate-800 px-1.5 py-0.5 rounded">
                          <Globe className="h-3 w-3 mr-1" /> Public
                        </span>
                      )}
                    </div>
                    <div className="flex items-center space-x-3 text-xs text-slate-400">
                      <span>Default Branch: <strong className="font-mono text-slate-300">{repo.defaultBranch}</strong></span>
                    </div>
                  </div>

                  <div className="flex items-center space-x-3">
                    <RepositoryIndexControl
                      compact
                      projectId={projectId}
                      repositoryId={repo.id}
                      initialStatus={repo.indexStatus}
                      onStatusChange={loadData}
                    />

                    <Link
                      href={`/projects/${projectId}/repositories/${repo.id}`}
                      className="inline-flex items-center px-3 py-1.5 text-xs font-medium text-indigo-400 hover:text-indigo-300 bg-indigo-500/10 hover:bg-indigo-500/20 rounded-lg border border-indigo-500/20 transition-colors"
                    >
                      View Files <ExternalLink className="h-3 w-3 ml-1.5" />
                    </Link>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Development Tasks Section */}
        <div className="rounded-xl border border-slate-800 bg-slate-900 p-6 space-y-4">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-semibold text-slate-100 flex items-center gap-2">
              <CheckSquare className="h-5 w-5 text-emerald-400" />
              Development Tasks
            </h3>
            <Link href="/tasks" className="text-xs font-medium text-indigo-400 hover:text-indigo-300">
              View All Tasks
            </Link>
          </div>

          <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-800 bg-slate-950/40 p-8 text-center">
            <CheckSquare className="h-8 w-8 text-slate-600 mb-2" />
            <p className="text-sm font-medium text-slate-300">No Tasks Created Yet</p>
            <p className="text-xs text-slate-500 mt-1 mb-4">Create an engineering task to trigger the AI issue analysis & coding pipeline.</p>
            <Link href="/tasks" className="flex items-center gap-2 rounded-lg bg-emerald-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-emerald-500 transition-colors">
              <Plus className="h-3.5 w-3.5" /> Create Task
            </Link>
          </div>
        </div>
      </div>

      {/* Import Repository Modal */}
      <ImportRepositoryModal
        isOpen={isImportModalOpen}
        onClose={() => setIsImportModalOpen(false)}
        projectId={projectId}
        onSuccess={loadData}
      />
    </div>
  );
}
