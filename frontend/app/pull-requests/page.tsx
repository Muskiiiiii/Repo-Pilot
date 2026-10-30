'use client';

import React from 'react';
import Link from 'next/link';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import {
  GitPullRequest,
  GitBranch,
  ExternalLink,
  Clock,
  CheckCircle2,
  AlertCircle,
  Plus,
  ArrowRight,
  GitCommit,
  ShieldCheck,
} from 'lucide-react';

export default function PullRequestsPage() {
  const pullRequests = [
    {
      id: 'PR-104',
      githubPrNumber: 14,
      title: 'feat: add JWT auth middleware and token refreshing logic',
      repository: 'Muskiiiii / MedSense',
      branch: 'repopilot/task-101-jwt-auth',
      baseBranch: 'main',
      status: 'OPEN' as const,
      task: 'TASK-101',
      author: 'RepoPilot AI Agent',
      createdAt: '15 minutes ago',
      url: 'https://github.com/Muskiiiii/MedSense/pull/14',
      testsPassed: true,
      approvalStatus: 'APPROVED' as const,
    },
    {
      id: 'PR-103',
      githubPrNumber: 13,
      title: 'fix: handle webhook timeout and idempotency validation',
      repository: 'Muskiiiii / MedSense',
      branch: 'repopilot/task-102-webhook-fix',
      baseBranch: 'main',
      status: 'OPEN' as const,
      task: 'TASK-102',
      author: 'RepoPilot AI Agent',
      createdAt: '1 hour ago',
      url: 'https://github.com/Muskiiiii/MedSense/pull/13',
      testsPassed: true,
      approvalStatus: 'PENDING' as const,
    },
    {
      id: 'PR-102',
      githubPrNumber: 12,
      title: 'refactor: implement cursor-based pagination for listings API',
      repository: 'Muskiiiii / MedSense',
      branch: 'repopilot/task-98-pagination',
      baseBranch: 'main',
      status: 'MERGED' as const,
      task: 'TASK-98',
      author: 'RepoPilot AI Agent',
      createdAt: 'Yesterday',
      url: 'https://github.com/Muskiiiii/MedSense/pull/12',
      testsPassed: true,
      approvalStatus: 'APPROVED' as const,
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-100 flex items-center gap-2">
            <GitPullRequest className="h-6 w-6 text-brand-primary" />
            Pull Requests
          </h1>
          <p className="text-xs text-dark-muted mt-1">
            Automated pull requests created by RepoPilot agents after test verification and human review approval.
          </p>
        </div>
      </div>

      {/* Metrics Row */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <Card className="flex items-center gap-4 p-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-emerald-500/10 text-emerald-400">
            <GitPullRequest className="h-5 w-5" />
          </div>
          <div>
            <div className="text-xl font-bold text-gray-100">2</div>
            <div className="text-xs text-dark-muted">Open Pull Requests</div>
          </div>
        </Card>
        <Card className="flex items-center gap-4 p-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-indigo-500/10 text-indigo-400">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <div>
            <div className="text-xl font-bold text-gray-100">100%</div>
            <div className="text-xs text-dark-muted">Sandbox Test Pass Rate</div>
          </div>
        </Card>
        <Card className="flex items-center gap-4 p-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-purple-500/10 text-purple-400">
            <CheckCircle2 className="h-5 w-5" />
          </div>
          <div>
            <div className="text-xl font-bold text-gray-100">1</div>
            <div className="text-xs text-dark-muted">Merged Recently</div>
          </div>
        </Card>
      </div>

      {/* PRs List */}
      <Card>
        <div className="border-b border-dark-border px-5 py-3.5">
          <h2 className="text-sm font-semibold text-gray-200">Active Pull Requests</h2>
        </div>
        <div className="flex flex-col divide-y divide-dark-border/60">
          {pullRequests.map((pr) => (
            <div
              key={pr.id}
              className="flex flex-col gap-3 p-5 transition-colors hover:bg-dark-surface/40 md:flex-row md:items-center md:justify-between"
            >
              <div className="flex items-start gap-3.5">
                <div className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${
                  pr.status === 'MERGED'
                    ? 'bg-purple-500/10 text-purple-400'
                    : 'bg-emerald-500/10 text-emerald-400'
                }`}>
                  <GitPullRequest className="h-4 w-4" />
                </div>
                <div className="flex flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold text-gray-100 hover:text-brand-primary cursor-pointer transition-colors">
                      {pr.title}
                    </span>
                    <span className="text-xs font-mono text-dark-muted">#{pr.githubPrNumber}</span>
                    <Badge variant={pr.status === 'MERGED' ? 'purple' : 'success'}>
                      {pr.status}
                    </Badge>
                    <Badge variant={pr.approvalStatus === 'APPROVED' ? 'success' : 'warning'}>
                      {pr.approvalStatus}
                    </Badge>
                  </div>
                  <div className="flex flex-wrap items-center gap-3 text-xs text-dark-muted">
                    <span>{pr.repository}</span>
                    <span>•</span>
                    <span className="flex items-center gap-1 font-mono text-[11px] text-gray-300">
                      <GitBranch className="h-3 w-3 text-dark-muted" />
                      {pr.branch}
                    </span>
                    <span>•</span>
                    <span className="flex items-center gap-1 text-[11px]">
                      <Clock className="h-3 w-3 text-dark-muted" />
                      {pr.createdAt}
                    </span>
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-2 self-end md:self-center">
                <a
                  href={pr.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-center gap-1.5 rounded-lg border border-dark-border bg-dark-surface px-3 py-1.5 text-xs font-medium text-gray-300 hover:border-gray-600 hover:text-white transition-colors"
                >
                  <span>View on GitHub</span>
                  <ExternalLink className="h-3 w-3" />
                </a>
              </div>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}
