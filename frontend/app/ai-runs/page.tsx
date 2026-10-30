'use client';

import React from 'react';
import Link from 'next/link';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import {
  Cpu,
  Bot,
  PlayCircle,
  CheckCircle2,
  AlertTriangle,
  Clock,
  Layers,
  ArrowRight,
  ShieldCheck,
  Terminal,
} from 'lucide-react';

export default function AiRunsPage() {
  const runs = [
    {
      id: 'RUN-501',
      task: 'TASK-101: Add JWT Authentication & Refresh Token Handling',
      agent: 'Coder Agent + Tester Sandbox',
      status: 'RUNNING' as const,
      model: 'gpt-4o',
      iteration: 'Attempt 1 of 3',
      tokensUsed: '14,280',
      duration: '42s',
      startedAt: '1 minute ago',
    },
    {
      id: 'RUN-500',
      task: 'TASK-101: Codebase Analysis & Retrieval',
      agent: 'Codebase Understanding Agent (RAG)',
      status: 'COMPLETED' as const,
      model: 'voyage-code-4',
      iteration: 'Single pass',
      tokensUsed: '8,420',
      duration: '18s',
      startedAt: '3 minutes ago',
    },
    {
      id: 'RUN-499',
      task: 'TASK-102: Fix Payment Webhook Timeout and Idempotency Key',
      agent: 'Planner Agent (Plan Synthesis)',
      status: 'COMPLETED' as const,
      model: 'gpt-4o',
      iteration: 'Single pass',
      tokensUsed: '19,550',
      duration: '31s',
      startedAt: '25 minutes ago',
    },
    {
      id: 'RUN-498',
      task: 'TASK-98: Cursor-based Pagination Implementation',
      agent: 'Reviewer Agent (Security & Policy Check)',
      status: 'COMPLETED' as const,
      model: 'gpt-4o',
      iteration: 'Self-Correction Pass 2/3',
      tokensUsed: '32,100',
      duration: '54s',
      startedAt: 'Yesterday',
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-100 flex items-center gap-2">
            <Cpu className="h-6 w-6 text-brand-accent" />
            AI Agent Runs
          </h1>
          <p className="text-xs text-dark-muted mt-1">
            Real-time status of multi-agent execution pipeline (Planner, Coder, Sandbox Tester, and Reviewer).
          </p>
        </div>
      </div>

      {/* Agents Overview Cards */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
        <Card className="p-4">
          <div className="flex items-center gap-2 text-xs font-semibold text-brand-primary">
            <Layers className="h-4 w-4" />
            <span>Supervisor Agent</span>
          </div>
          <div className="mt-2 text-xl font-bold text-gray-100">Active</div>
          <div className="text-[11px] text-dark-muted mt-1">Orchestrating workflow graph</div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center gap-2 text-xs font-semibold text-emerald-400">
            <Bot className="h-4 w-4" />
            <span>Codebase RAG</span>
          </div>
          <div className="mt-2 text-xl font-bold text-gray-100">Ready</div>
          <div className="text-[11px] text-dark-muted mt-1">Hybrid pgvector 1024D search</div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center gap-2 text-xs font-semibold text-indigo-400">
            <Terminal className="h-4 w-4" />
            <span>Docker Sandbox</span>
          </div>
          <div className="mt-2 text-xl font-bold text-gray-100">1 Running</div>
          <div className="text-[11px] text-dark-muted mt-1">Isolated test execution</div>
        </Card>
        <Card className="p-4">
          <div className="flex items-center gap-2 text-xs font-semibold text-purple-400">
            <ShieldCheck className="h-4 w-4" />
            <span>Security Reviewer</span>
          </div>
          <div className="mt-2 text-xl font-bold text-gray-100">Enforced</div>
          <div className="text-[11px] text-dark-muted mt-1">Immutability & policy audits</div>
        </Card>
      </div>

      {/* Runs Table */}
      <Card>
        <div className="border-b border-dark-border px-5 py-3.5">
          <h2 className="text-sm font-semibold text-gray-200">Execution History</h2>
        </div>
        <div className="flex flex-col divide-y divide-dark-border/60">
          {runs.map((r) => (
            <div
              key={r.id}
              className="flex flex-col gap-3 p-5 transition-colors hover:bg-dark-surface/40 md:flex-row md:items-center md:justify-between"
            >
              <div className="flex items-start gap-3.5">
                <div className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${
                  r.status === 'RUNNING'
                    ? 'bg-brand-primary/10 text-brand-primary animate-pulse'
                    : 'bg-emerald-500/10 text-emerald-400'
                }`}>
                  {r.status === 'RUNNING' ? <PlayCircle className="h-4 w-4" /> : <CheckCircle2 className="h-4 w-4" />}
                </div>
                <div className="flex flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold text-gray-100">{r.task}</span>
                    <Badge variant={r.status === 'RUNNING' ? 'purple' : 'success'}>
                      {r.status}
                    </Badge>
                  </div>
                  <div className="flex flex-wrap items-center gap-3 text-xs text-dark-muted">
                    <span className="font-medium text-gray-300">{r.agent}</span>
                    <span>•</span>
                    <span className="font-mono text-[11px] text-indigo-300">{r.model}</span>
                    <span>•</span>
                    <span className="text-[11px]">{r.iteration}</span>
                    <span>•</span>
                    <span className="flex items-center gap-1 text-[11px]">
                      <Clock className="h-3 w-3 text-dark-muted" />
                      {r.duration} ({r.startedAt})
                    </span>
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-3 self-end md:self-center text-xs font-mono text-dark-muted">
                <span>{r.tokensUsed} tokens</span>
              </div>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}
