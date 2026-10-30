'use client';

import React from 'react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import {
  Activity,
  FolderGit2,
  CheckSquare,
  GitPullRequest,
  ShieldAlert,
  ShieldCheck,
  Clock,
  UserCheck,
  Database,
} from 'lucide-react';

export default function ActivityPage() {
  const activities = [
    {
      id: 'ACT-1',
      type: 'PROJECT_CREATED',
      title: 'Project created: testing',
      user: 'Muskan',
      organization: 'Default Organization',
      timestamp: 'Just now',
      icon: FolderGit2,
      iconColor: 'text-brand-primary bg-brand-primary/10',
    },
    {
      id: 'ACT-2',
      type: 'REPO_INDEXING_STARTED',
      title: 'Repository indexing started: Muskiiiii / MedSense',
      user: 'System Worker',
      organization: 'Default Organization',
      timestamp: '15 minutes ago',
      icon: Database,
      iconColor: 'text-indigo-400 bg-indigo-500/10',
    },
    {
      id: 'ACT-3',
      type: 'HUMAN_APPROVAL_SUBMITTED',
      title: 'Plan approval requested for TASK-102',
      user: 'AI Supervisor',
      organization: 'Default Organization',
      timestamp: '35 minutes ago',
      icon: UserCheck,
      iconColor: 'text-amber-400 bg-amber-500/10',
    },
    {
      id: 'ACT-4',
      type: 'PR_PUBLISHED',
      title: 'Pull Request published: #14 on Muskiiiii / MedSense',
      user: 'PR Publisher Agent',
      organization: 'Default Organization',
      timestamp: '1 hour ago',
      icon: GitPullRequest,
      iconColor: 'text-emerald-400 bg-emerald-500/10',
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div>
        <h1 className="text-xl font-bold text-gray-100 flex items-center gap-2">
          <Activity className="h-6 w-6 text-brand-primary" />
          Audit & System Activity
        </h1>
        <p className="text-xs text-dark-muted mt-1">
          Chronological audit trail of all repository indexing, task approvals, agent executions, and pull requests.
        </p>
      </div>

      {/* Activity Timeline Card */}
      <Card>
        <div className="border-b border-dark-border px-5 py-3.5">
          <h2 className="text-sm font-semibold text-gray-200">Recent Activity Logs</h2>
        </div>
        <div className="flex flex-col divide-y divide-dark-border/60">
          {activities.map((item) => {
            const Icon = item.icon;
            return (
              <div
                key={item.id}
                className="flex items-start gap-4 p-5 transition-colors hover:bg-dark-surface/40"
              >
                <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${item.iconColor}`}>
                  <Icon className="h-5 w-5" />
                </div>
                <div className="flex flex-1 flex-col gap-1">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-sm font-semibold text-gray-100">{item.title}</span>
                    <span className="flex items-center gap-1 text-xs text-dark-muted">
                      <Clock className="h-3 w-3" />
                      {item.timestamp}
                    </span>
                  </div>
                  <div className="flex flex-wrap items-center gap-2 text-xs text-dark-muted">
                    <span className="text-gray-300 font-medium">Actor: {item.user}</span>
                    <span>•</span>
                    <span>Org: {item.organization}</span>
                    <span>•</span>
                    <Badge variant="outline">{item.type}</Badge>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </Card>
    </div>
  );
}
