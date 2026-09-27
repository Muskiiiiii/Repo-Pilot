'use client';

import React from 'react';
import Link from 'next/link';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { StatusDot } from '@/components/ui/StatusDot';
import {
  FolderGit2,
  CheckSquare,
  GitPullRequest,
  TrendingUp,
  Plus,
  ArrowRight,
  GitBranch,
  CheckCircle2,
  Clock,
  ExternalLink
} from 'lucide-react';

export default function DashboardPage() {
  const metrics = [
    { label: 'Total Projects', value: '3', icon: FolderGit2, color: 'text-brand-primary' },
    { label: 'Active Tasks', value: '2', icon: CheckSquare, color: 'text-brand-accent' },
    { label: 'Pull Requests', value: '12', icon: GitPullRequest, color: 'text-brand-success' },
    { label: 'Test Pass Rate', value: '94%', icon: TrendingUp, color: 'text-emerald-400' },
  ];

  const activeTasks = [
    {
      id: 'task-101',
      title: 'Add JWT Authentication & Refresh Token Handling',
      project: 'E-Commerce Backend',
      status: 'running' as const,
      step: 'Running Automated Tests (Attempt 1/3)',
      time: '2 mins ago',
    },
    {
      id: 'task-102',
      title: 'Fix Payment Webhook Timeout and Idempotency Key',
      project: 'Payment Microservice',
      status: 'waiting' as const,
      step: 'Awaiting Human Plan Approval',
      time: '15 mins ago',
    },
  ];

  const recentProjects = [
    {
      id: 'proj-1',
      name: 'E-Commerce Backend',
      repo: 'github.com/org/ecommerce-backend',
      branch: 'main',
      status: 'READY',
      tasksCount: 14,
      updated: '10 mins ago',
    },
    {
      id: 'proj-2',
      name: 'Payment Microservice',
      repo: 'github.com/org/payment-service',
      branch: 'main',
      status: 'READY',
      tasksCount: 8,
      updated: '2 hours ago',
    },
    {
      id: 'proj-3',
      name: 'User Management API',
      repo: 'github.com/org/user-api',
      branch: 'develop',
      status: 'INDEXING',
      tasksCount: 3,
      updated: 'Yesterday',
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-100">Engineering Dashboard</h1>
          <p className="text-xs text-dark-muted">
            Overview of AI agent task runs, codebase indexing, and GitHub delivery.
          </p>
        </div>
        <Link href="/tasks/new">
          <Button size="sm" className="gap-2">
            <Plus className="h-4 w-4" />
            <span>New Task</span>
          </Button>
        </Link>
      </div>

      {/* Metrics Row */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {metrics.map((m) => {
          const Icon = m.icon;
          return (
            <Card key={m.label} className="flex items-center justify-between">
              <div>
                <p className="text-xs font-medium text-dark-muted">{m.label}</p>
                <p className="mt-1 text-2xl font-bold text-gray-100">{m.value}</p>
              </div>
              <div className={`rounded-lg bg-dark-bg p-2.5 ${m.color}`}>
                <Icon className="h-5 w-5" />
              </div>
            </Card>
          );
        })}
      </div>

      {/* Main Grid Section */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Active AI Tasks Column */}
        <div className="lg:col-span-2 flex flex-col gap-4">
          <Card
            title="Active AI Tasks"
            description="Real-time status of multi-agent engineering workflows"
            action={
              <Link href="/tasks" className="flex items-center gap-1 text-xs text-brand-primary hover:underline">
                View all <ArrowRight className="h-3 w-3" />
              </Link>
            }
          >
            <div className="flex flex-col gap-3">
              {activeTasks.map((t) => (
                <div
                  key={t.id}
                  className="flex flex-col gap-2 rounded-md border border-dark-border bg-dark-bg p-3.5 transition-colors hover:border-dark-hover"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-gray-200">{t.project}</span>
                    <StatusDot status={t.status} label={t.status} />
                  </div>
                  <h4 className="text-sm font-semibold text-gray-100">{t.title}</h4>
                  <div className="flex items-center justify-between text-xs text-dark-muted">
                    <span className="flex items-center gap-1 text-brand-accent font-medium">
                      <Clock className="h-3 w-3" /> {t.step}
                    </span>
                    <span>{t.time}</span>
                  </div>
                </div>
              ))}
            </div>
          </Card>

          {/* Recent Activity Stream */}
          <Card title="Recent Audit & AI Activity">
            <div className="flex flex-col gap-3 text-xs">
              <div className="flex items-start gap-3 border-b border-dark-border/50 pb-2.5">
                <CheckCircle2 className="mt-0.5 h-4 w-4 text-brand-success" />
                <div className="flex-1">
                  <p className="font-medium text-gray-200">
                    Pull Request <span className="text-brand-primary">#142 (Add JWT Auth)</span> created on GitHub
                  </p>
                  <p className="text-dark-muted">15 minutes ago • 42/42 tests passed • Review score 8.7/10</p>
                </div>
              </div>

              <div className="flex items-start gap-3 border-b border-dark-border/50 pb-2.5">
                <GitBranch className="mt-0.5 h-4 w-4 text-brand-accent" />
                <div className="flex-1">
                  <p className="font-medium text-gray-200">
                    Branch <code className="text-brand-accent">repopilot/task-101-add-jwt</code> created
                  </p>
                  <p className="text-dark-muted">22 minutes ago • Automated commit generated</p>
                </div>
              </div>
            </div>
          </Card>
        </div>

        {/* Projects List Column */}
        <div className="flex flex-col gap-4">
          <Card
            title="Connected Projects"
            description="Repositories indexed for RAG & AI workflow"
            action={
              <Link href="/projects" className="text-xs text-brand-primary hover:underline">
                Manage
              </Link>
            }
          >
            <div className="flex flex-col gap-3">
              {recentProjects.map((p) => (
                <div
                  key={p.id}
                  className="flex flex-col gap-2 rounded-md border border-dark-border bg-dark-bg p-3"
                >
                  <div className="flex items-center justify-between">
                    <h4 className="text-xs font-semibold text-gray-100">{p.name}</h4>
                    <Badge variant={p.status === 'READY' ? 'success' : 'warning'}>{p.status}</Badge>
                  </div>
                  <p className="text-[11px] text-dark-muted flex items-center gap-1">
                    <ExternalLink className="h-3 w-3" /> {p.repo}
                  </p>
                  <div className="flex items-center justify-between text-[11px] text-dark-muted pt-1 border-t border-dark-border/40">
                    <span>Branch: <code className="text-gray-300">{p.branch}</code></span>
                    <span>{p.tasksCount} tasks</span>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
