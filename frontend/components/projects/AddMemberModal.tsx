'use client';

import React, { useState } from 'react';
import { ProjectRole } from '@/types/project';
import { addProjectMember } from '@/lib/projects';
import { X, UserPlus, AlertCircle } from 'lucide-react';

interface AddMemberModalProps {
  isOpen: boolean;
  projectId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export const AddMemberModal: React.FC<AddMemberModalProps> = ({
  isOpen,
  projectId,
  onClose,
  onSuccess,
}) => {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<ProjectRole>('MEMBER');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!email.trim()) {
      setError('User email is required.');
      return;
    }

    setSubmitting(true);
    try {
      await addProjectMember(projectId, { email: email.trim(), role });
      setEmail('');
      onSuccess();
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to add project member.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 backdrop-blur-sm p-4">
      <div className="w-full max-w-md rounded-xl border border-slate-800 bg-slate-900 shadow-2xl overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-800 px-6 py-4">
          <div className="flex items-center gap-2 text-indigo-400 font-semibold">
            <UserPlus className="h-5 w-5" />
            <h3 className="text-lg text-slate-100">Add Team Member</h3>
          </div>
          <button
            onClick={onClose}
            className="rounded-lg p-1 text-slate-400 hover:bg-slate-800 hover:text-slate-200 transition-colors"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {error && (
            <div className="flex items-center gap-3 rounded-lg bg-red-950/60 border border-red-800/50 p-3 text-sm text-red-300">
              <AlertCircle className="h-4 w-4 shrink-0 text-red-400" />
              <p>{error}</p>
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-slate-200 mb-1">
              Member Email address
            </label>
            <input
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="colleague@repopilot.ai"
              className="block w-full rounded-lg border border-slate-800 bg-slate-950/60 py-2 px-3 text-slate-100 placeholder-slate-500 focus:border-indigo-500 focus:outline-none sm:text-sm"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-200 mb-1">
              Project Role
            </label>
            <select
              value={role}
              onChange={(e) => setRole(e.target.value as ProjectRole)}
              className="block w-full rounded-lg border border-slate-800 bg-slate-950/60 py-2 px-3 text-slate-100 focus:border-indigo-500 focus:outline-none sm:text-sm"
            >
              <option value="ADMIN">Admin (Full write & member management)</option>
              <option value="MEMBER">Member (Developer / Task creation)</option>
              <option value="VIEWER">Viewer (Read-only access)</option>
            </select>
          </div>

          <div className="flex items-center justify-end gap-3 pt-4 border-t border-slate-800">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg border border-slate-800 bg-slate-950 px-4 py-2 text-sm font-medium text-slate-300 hover:bg-slate-800 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="flex items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50 transition-colors"
            >
              {submitting ? (
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"></div>
              ) : (
                'Add Member'
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
