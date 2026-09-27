'use client';

import React, { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { Project, ProjectMember } from '@/types/project';
import {
  fetchProjectById,
  updateProject,
  deleteProject,
  fetchProjectMembers,
  removeProjectMember,
} from '@/lib/projects';
import { AddMemberModal } from '@/components/projects/AddMemberModal';
import { DeleteProjectModal } from '@/components/projects/DeleteProjectModal';
import {
  Settings,
  Users,
  Trash2,
  Save,
  UserPlus,
  Shield,
  ArrowLeft,
  AlertCircle,
  CheckCircle2,
  UserX
} from 'lucide-react';

export default function ProjectSettingsPage() {
  const params = useParams();
  const router = useRouter();
  const projectId = params.id as string;

  const [project, setProject] = useState<Project | null>(null);
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const [isAddMemberOpen, setIsAddMemberOpen] = useState(false);
  const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const p = await fetchProjectById(projectId);
      setProject(p);
      setName(p.name);
      setDescription(p.description || '');

      const m = await fetchProjectMembers(projectId);
      setMembers(m);
    } catch (err: any) {
      setError(err.message || 'Failed to load project settings.');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    if (projectId) {
      loadData();
    }
  }, [projectId, loadData]);

  const handleUpdateGeneral = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setSuccessMsg(null);

    try {
      const updated = await updateProject(projectId, { name: name.trim(), description: description.trim() });
      setProject(updated);
      setSuccessMsg('Project settings updated successfully.');
    } catch (err: any) {
      setError(err.message || 'Failed to update project.');
    } finally {
      setSaving(false);
    }
  };

  const handleRemoveMember = async (memberId: string, memberEmail: string) => {
    if (!confirm(`Are you sure you want to remove ${memberEmail} from this project?`)) {
      return;
    }
    try {
      await removeProjectMember(projectId, memberId);
      const updatedMembers = await fetchProjectMembers(projectId);
      setMembers(updatedMembers);
    } catch (err: any) {
      alert(err.message || 'Failed to remove member.');
    }
  };

  const handleDeleteProject = async () => {
    await deleteProject(projectId);
    router.push('/projects');
  };

  if (loading) {
    return (
      <div className="flex h-96 w-full items-center justify-center text-slate-400">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-indigo-500 border-t-transparent"></div>
      </div>
    );
  }

  if (!project) {
    return (
      <div className="max-w-xl mx-auto my-12 text-center text-slate-400">
        <p>Project not found or access denied.</p>
        <Link href="/projects" className="text-indigo-400 underline mt-2 inline-block">
          Back to Projects
        </Link>
      </div>
    );
  }

  const isOwnerOrAdmin = project.userRole === 'OWNER' || project.userRole === 'ADMIN';

  return (
    <div className="space-y-8 max-w-4xl mx-auto">
      {/* Top Breadcrumb */}
      <div className="flex items-center justify-between">
        <Link
          href={`/projects/${project.id}`}
          className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-400 hover:text-slate-200 transition-colors"
        >
          <ArrowLeft className="h-3.5 w-3.5" /> Back to Project Overview
        </Link>
        <h1 className="text-xl font-bold text-slate-100 tracking-tight flex items-center gap-2">
          <Settings className="h-5 w-5 text-indigo-400" />
          Project Settings
        </h1>
      </div>

      {/* General Alerts */}
      {error && (
        <div className="flex items-center gap-3 rounded-lg bg-red-950/60 border border-red-800/50 p-4 text-sm text-red-300">
          <AlertCircle className="h-5 w-5 shrink-0 text-red-400" />
          <p>{error}</p>
        </div>
      )}

      {successMsg && (
        <div className="flex items-center gap-3 rounded-lg bg-emerald-950/60 border border-emerald-800/50 p-4 text-sm text-emerald-300">
          <CheckCircle2 className="h-5 w-5 shrink-0 text-emerald-400" />
          <p>{successMsg}</p>
        </div>
      )}

      {/* General Info Settings Section */}
      <div className="rounded-xl border border-slate-800 bg-slate-900 p-6 space-y-6">
        <div className="border-b border-slate-800 pb-4">
          <h2 className="text-base font-semibold text-slate-100">General Information</h2>
          <p className="text-xs text-slate-400 mt-0.5">Update project name and description.</p>
        </div>

        <form onSubmit={handleUpdateGeneral} className="space-y-5">
          <div>
            <label className="block text-sm font-medium text-slate-200 mb-1">Project Name</label>
            <input
              type="text"
              required
              disabled={!isOwnerOrAdmin}
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="block w-full rounded-lg border border-slate-800 bg-slate-950/60 py-2 px-3 text-slate-100 placeholder-slate-500 focus:border-indigo-500 focus:outline-none sm:text-sm disabled:opacity-50"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-200 mb-1">Description</label>
            <textarea
              rows={3}
              disabled={!isOwnerOrAdmin}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="block w-full rounded-lg border border-slate-800 bg-slate-950/60 py-2 px-3 text-slate-100 placeholder-slate-500 focus:border-indigo-500 focus:outline-none sm:text-sm resize-none disabled:opacity-50"
            />
          </div>

          {isOwnerOrAdmin && (
            <div className="flex justify-end">
              <button
                type="submit"
                disabled={saving}
                className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-50 transition-colors"
              >
                {saving ? (
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"></div>
                ) : (
                  <>
                    <Save className="h-4 w-4" /> Save Changes
                  </>
                )}
              </button>
            </div>
          )}
        </form>
      </div>

      {/* Team Members Section */}
      <div className="rounded-xl border border-slate-800 bg-slate-900 p-6 space-y-6">
        <div className="flex items-center justify-between border-b border-slate-800 pb-4">
          <div>
            <h2 className="text-base font-semibold text-slate-100 flex items-center gap-2">
              <Users className="h-5 w-5 text-indigo-400" /> Team Members ({members.length})
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">Manage project collaborators and permissions.</p>
          </div>

          {isOwnerOrAdmin && (
            <button
              onClick={() => setIsAddMemberOpen(true)}
              className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-indigo-500 transition-colors"
            >
              <UserPlus className="h-3.5 w-3.5" /> Add Member
            </button>
          )}
        </div>

        <div className="divide-y divide-slate-800/60">
          {members.map((member) => (
            <div key={member.id} className="flex items-center justify-between py-3">
              <div className="flex items-center gap-3">
                <div className="flex h-9 w-9 items-center justify-center rounded-full bg-indigo-600/20 text-xs font-bold text-indigo-300 border border-indigo-500/30">
                  {member.user?.name ? member.user.name[0].toUpperCase() : 'U'}
                </div>
                <div>
                  <p className="text-sm font-medium text-slate-100">{member.user?.name}</p>
                  <p className="text-xs text-slate-400">{member.user?.email}</p>
                </div>
              </div>

              <div className="flex items-center gap-3">
                <span className="inline-flex items-center gap-1 rounded-full bg-slate-800 px-2.5 py-0.5 text-xs font-medium text-slate-300 border border-slate-700">
                  <Shield className="h-3 w-3 text-indigo-400" />
                  {member.role}
                </span>

                {isOwnerOrAdmin && member.role !== 'OWNER' && (
                  <button
                    onClick={() => handleRemoveMember(member.id, member.user?.email)}
                    className="rounded-lg p-1.5 text-slate-500 hover:bg-red-950/40 hover:text-red-400 transition-colors"
                    title="Remove Member"
                  >
                    <UserX className="h-4 w-4" />
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Danger Zone: Delete Project (Owner Only) */}
      {project.userRole === 'OWNER' && (
        <div className="rounded-xl border border-red-900/50 bg-slate-900 p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-base font-semibold text-red-400 flex items-center gap-2">
                <Trash2 className="h-5 w-5" /> Danger Zone
              </h2>
              <p className="text-xs text-slate-400 mt-1">
                Delete this project workspace and all connected settings.
              </p>
            </div>
            <button
              onClick={() => setIsDeleteModalOpen(true)}
              className="rounded-lg bg-red-600/20 border border-red-500/30 px-4 py-2 text-xs font-semibold text-red-300 hover:bg-red-600 hover:text-white transition-colors"
            >
              Delete Project
            </button>
          </div>
        </div>
      )}

      {/* Modals */}
      <AddMemberModal
        isOpen={isAddMemberOpen}
        projectId={projectId}
        onClose={() => setIsAddMemberOpen(false)}
        onSuccess={loadData}
      />

      <DeleteProjectModal
        isOpen={isDeleteModalOpen}
        projectName={project.name}
        onClose={() => setIsDeleteModalOpen(false)}
        onConfirm={handleDeleteProject}
      />
    </div>
  );
}
