'use client';

import React from 'react';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { StatusDot } from '@/components/ui/StatusDot';
import { Plus, CheckSquare, Clock, ArrowRight, ShieldCheck, AlertTriangle } from 'lucide-react';

export default function TasksPage() {
  const tasks = [
    {
      id: 'TASK-101',
      title: 'Add JWT Authentication & Refresh Token Handling',
      project: 'E-Commerce Backend',
      status: 'running' as const,
      step: 'Running Automated Tests (Attempt 1/3)',
      confidence: '91%',
      createdAt: '10 mins ago',
    },
    {
      id: 'TASK-102',
      title: 'Fix Payment Webhook Timeout and Idempotency Key',
      project: 'Payment Microservice',
      status: 'waiting' as const,
      step: 'Awaiting Human Plan Approval',
      confidence: '88%',
      createdAt: '25 mins ago',
    },
    {
      id: 'TASK-103',
      title: 'Implement Pagination for Users API Endpoint',
      project: 'E-Commerce Backend',
      status: 'success' as const,
      step: 'Pull Request #142 Created',
      confidence: '95%',
      createdAt: '2 hours ago',
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-gray-100">Engineering Tasks</h1>
          <p className="text-xs text-dark-muted">
            Track natural language issue execution, AI plan creation, code diffs, and PR delivery.
          </p>
        </div>
        <Button size="sm" className="gap-2">
          <Plus className="h-4 w-4" />
          <span>New Task</span>
        </Button>
      </div>

      {/* Task List */}
      <Card>
        <div className="flex flex-col divide-y divide-dark-border/60">
          {tasks.map((t) => (
            <div key={t.id} className="flex items-center justify-between py-4 first:pt-0 last:pb-0">
              <div className="flex flex-col gap-1.5">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-xs text-brand-primary">{t.id}</span>
                  <Badge variant="outline">{t.project}</Badge>
                  <StatusDot status={t.status} label={t.status} />
                </div>
                <h3 className="text-sm font-semibold text-gray-100">{t.title}</h3>
                <p className="text-xs text-dark-muted flex items-center gap-2">
                  <span className="flex items-center gap-1 text-brand-accent font-medium">
                    <Clock className="h-3 w-3" /> {t.step}
                  </span>
                  <span>•</span>
                  <span>Confidence: <strong className="text-gray-200">{t.confidence}</strong></span>
                </p>
              </div>

              <div className="flex items-center gap-3">
                <Button size="sm" variant="secondary" className="gap-1 text-xs">
                  <span>Workspace</span>
                  <ArrowRight className="h-3.5 w-3.5" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}
