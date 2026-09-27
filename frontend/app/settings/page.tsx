'use client';

import React from 'react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { ConnectGithubCard } from '@/components/github/ConnectGithubCard';

export default function SettingsPage() {
  return (
    <div className="flex flex-col gap-6 max-w-4xl">
      <div>
        <h1 className="text-xl font-bold text-gray-100">Settings</h1>
        <p className="text-xs text-dark-muted">
          Configure platform security, GitHub OAuth connection, LLM providers, and execution bounds.
        </p>
      </div>

      {/* Real GitHub OAuth Integration */}
      <ConnectGithubCard />

      {/* AI Settings */}
      <Card title="AI Agent Configuration">
        <div className="flex flex-col gap-4 text-xs">
          <div className="flex items-center justify-between">
            <div>
              <p className="font-semibold text-gray-200">LLM Provider & Model</p>
              <p className="text-dark-muted">Configured default LLM model for planning & code generation</p>
            </div>
            <span className="font-mono text-xs bg-dark-bg px-2.5 py-1 rounded border border-dark-border text-brand-primary">gpt-4o</span>
          </div>

          <div className="flex items-center justify-between border-t border-dark-border/40 pt-3">
            <div>
              <p className="font-semibold text-gray-200">Maximum Automated Test Retries</p>
              <p className="text-dark-muted">Self-correction loop iteration limit before intervention request</p>
            </div>
            <span className="font-mono text-xs bg-dark-bg px-2.5 py-1 rounded border border-dark-border text-brand-accent">3 Iterations</span>
          </div>

          <div className="flex items-center justify-between border-t border-dark-border/40 pt-3">
            <div>
              <p className="font-semibold text-gray-200">Human Plan Approval Gate</p>
              <p className="text-dark-muted">Require explicit user approval before code modification step</p>
            </div>
            <Badge variant="success">ENFORCED</Badge>
          </div>
        </div>
      </Card>
    </div>
  );
}
