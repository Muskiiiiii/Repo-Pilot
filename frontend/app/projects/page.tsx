'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { Project } from '@/types/project';
import { fetchProjects } from '@/lib/projects';
import { CreateProjectModal } from '@/components/projects/CreateProjectModal';
import { Folder, Plus, Search, GitBranch, CheckSquare, Users, Shield, ArrowRight, RefreshCw, AlertCircle } from 'lucide-react';

export default function ProjectsPage() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);

  const loadProjects = async (query?: string) => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchProjects(query);
      setProjects(data);
    } catch (err: any) {
      setError(err.message || 'Failed to load projects.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadProjects(searchQuery);
  }, [searchQuery]);

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {/* Header & Controls */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-100 tracking-tight flex items-center gap-2">
            <Folder className="h-6 w-6 text-indigo-400" />
            Projects
          </h1>
          <p className="text-sm text-slate-400 mt-1">
            Manage your AI software engineering workspaces and connected repositories.
          </p>
        </div>

        <button
          onClick={() => setIsCreateModalOpen(true)}
          className="flex items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 transition-colors"
        >
          <Plus className="h-4 w-4" />
          Create Project
        </button>
      </div>

      {/* Search Bar */}
      <div className="relative max-w-md">
        <Search className="absolute left-3 top-3 h-4 w-4 text-slate-500" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="Filter projects by name or description..."
          className="block w-full rounded-lg border border-slate-800 bg-slate-900 py-2 pl-9 pr-3 text-slate-100 placeholder-slate-500 focus:border-indigo-500 focus:outline-none sm:text-sm"
        />
      </div>

      {/* Error Alert */}
      {error && (
        <div className="flex items-center justify-between rounded-lg bg-red-950/60 border border-red-800/50 p-4 text-sm text-red-300">
          <div className="flex items-center gap-3">
            <AlertCircle className="h-5 w-5 text-red-400 shrink-0" />
            <p>{error}</p>
          </div>
          <button
            onClick={() => loadProjects(searchQuery)}
            className="flex items-center gap-1 text-xs font-semibold underline hover:text-red-200"
          >
            <RefreshCw className="h-3.5 w-3.5" /> Retry
          </button>
        </div>
      )}

      {/* Loading Skeletons */}
      {loading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {[1, 2, 3].map((i) => (
            <div key={i} className="animate-pulse rounded-xl border border-slate-800 bg-slate-900 p-6 space-y-4">
              <div className="h-5 w-1/2 bg-slate-800 rounded"></div>
              <div className="h-4 w-5/6 bg-slate-800/60 rounded"></div>
              <div className="h-4 w-2/3 bg-slate-800/40 rounded"></div>
              <div className="pt-4 flex justify-between border-t border-slate-800">
                <div className="h-4 w-1/4 bg-slate-800 rounded"></div>
                <div className="h-4 w-1/4 bg-slate-800 rounded"></div>
              </div>
            </div>
          ))}
        </div>
      ) : projects.length === 0 ? (
        /* Empty State */
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-slate-800 bg-slate-900/40 py-16 px-4 text-center">
          <div className="flex h-16 w-16 items-center justify-center rounded-full bg-indigo-600/10 text-indigo-400 mb-4 border border-indigo-500/20">
            <Folder className="h-8 w-8" />
          </div>
          <h3 className="text-lg font-semibold text-slate-100">No Projects Found</h3>
          <p className="text-sm text-slate-400 max-w-sm mt-1 mb-6">
            {searchQuery ? 'No projects match your search criteria.' : 'Create your first software project to start connecting repositories and AI tasks.'}
          </p>
          {!searchQuery && (
            <button
              onClick={() => setIsCreateModalOpen(true)}
              className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500 transition-colors"
            >
              <Plus className="h-4 w-4" />
              Create Project Now
            </button>
          )}
        </div>
      ) : (
        /* Projects Grid */
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {projects.map((project) => (
            <div
              key={project.id}
              className="group flex flex-col justify-between rounded-xl border border-slate-800 bg-slate-900 p-6 hover:border-indigo-500/50 hover:shadow-lg transition-all"
            >
              <div className="space-y-3">
                <div className="flex items-start justify-between gap-2">
                  <h3 className="text-lg font-bold text-slate-100 group-hover:text-indigo-400 transition-colors line-clamp-1">
                    {project.name}
                  </h3>
                  <span
                    className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                      project.userRole === 'OWNER'
                        ? 'bg-indigo-500/20 text-indigo-300 border border-indigo-500/30'
                        : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                    }`}
                  >
                    <Shield className="h-3 w-3" />
                    {project.userRole}
                  </span>
                </div>

                <p className="text-xs text-slate-400 line-clamp-2 min-h-[2rem]">
                  {project.description || 'No description provided.'}
                </p>

                <div className="flex items-center gap-4 text-xs text-slate-400 pt-2">
                  <span className="flex items-center gap-1">
                    <GitBranch className="h-3.5 w-3.5 text-slate-500" />
                    {project.repositoryCount} {project.repositoryCount === 1 ? 'Repo' : 'Repos'}
                  </span>
                  <span className="flex items-center gap-1">
                    <CheckSquare className="h-3.5 w-3.5 text-slate-500" />
                    {project.taskCount} Tasks
                  </span>
                  <span className="flex items-center gap-1">
                    <Users className="h-3.5 w-3.5 text-slate-500" />
                    {project.memberCount} Members
                  </span>
                </div>
              </div>

              <div className="mt-6 pt-4 border-t border-slate-800/80 flex items-center justify-between">
                <span className="text-[10px] text-slate-500">
                  Updated {new Date(project.updatedAt).toLocaleDateString()}
                </span>
                <Link
                  href={`/projects/${project.id}`}
                  className="flex items-center gap-1 text-xs font-semibold text-indigo-400 group-hover:text-indigo-300 group-hover:translate-x-0.5 transition-all"
                >
                  View Overview
                  <ArrowRight className="h-3.5 w-3.5" />
                </Link>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Create Project Modal */}
      <CreateProjectModal
        isOpen={isCreateModalOpen}
        onClose={() => setIsCreateModalOpen(false)}
        onSuccess={() => loadProjects(searchQuery)}
      />
    </div>
  );
}
