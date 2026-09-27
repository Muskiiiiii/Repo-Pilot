'use client';

import React from 'react';
import { PublicationAuditEventResponseDto } from '@/types/audit';
import { isSafeGithubPrUrl } from './HumanApprovalPublicationView';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import {
  Clock,
  CheckCircle2,
  AlertTriangle,
  GitPullRequest,
  GitBranch,
  ShieldCheck,
  ShieldAlert,
  User,
  ExternalLink,
  Hash,
  Activity,
} from 'lucide-react';

interface PublicationAuditTimelineProps {
  events: PublicationAuditEventResponseDto[];
  loading?: boolean;
  error?: string | null;
}

export function PublicationAuditTimeline({
  events,
  loading = false,
  error = null,
}: PublicationAuditTimelineProps) {
  if (loading) {
    return (
      <Card className="flex h-48 w-full flex-col items-center justify-center gap-3 border-slate-800 bg-slate-900/80 p-6">
        <div className="flex items-center gap-3 text-slate-400">
          <Activity className="h-5 w-5 animate-spin text-indigo-400" />
          <span className="text-sm font-medium text-slate-200">Loading Security Audit Trail...</span>
        </div>
      </Card>
    );
  }

  if (error) {
    return (
      <Card className="border-red-500/30 bg-red-500/10 p-6 text-red-300">
        <div className="flex items-center gap-3 text-sm">
          <AlertTriangle className="h-5 w-5 text-red-400" />
          <span>{error}</span>
        </div>
      </Card>
    );
  }

  if (!events || events.length === 0) {
    return (
      <Card className="flex h-36 w-full items-center justify-center border-slate-800 bg-slate-900/80 p-6 text-xs text-slate-400">
        No audit events recorded for this task execution yet.
      </Card>
    );
  }

  return (
    <Card className="flex flex-col gap-6 border-slate-800 bg-slate-900/90 p-6 shadow-xl">
      <div className="flex items-center justify-between border-b border-slate-800 pb-4">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-500/10 text-indigo-400">
            <Activity className="h-5 w-5" />
          </div>
          <div>
            <h3 className="text-base font-semibold text-slate-100">Publication & Security Audit Trail</h3>
            <p className="text-xs text-slate-400">Immutable, server-generated lifecycle audit timeline</p>
          </div>
        </div>
        <Badge variant="info">{events.length} Events</Badge>
      </div>

      <div className="relative flex flex-col gap-6 pl-4 before:absolute before:left-2 before:top-2 before:bottom-2 before:w-0.5 before:bg-slate-800">
        {events.map((event) => {
          const isFailure = event.eventType.includes('FAILED') || event.eventType.includes('DENIED');
          const isSuccess = event.eventType.includes('SUCCEEDED') || event.eventType.includes('CREATED') || event.eventType.includes('PASSED');
          const isUnknown = event.eventType.includes('UNKNOWN');

          return (
            <div key={event.id || `evt-${event.eventSequence}`} className="relative flex flex-col gap-2 pl-6">
              {/* Timeline dot */}
              <div className={`absolute -left-1.5 top-1.5 h-3 w-3 rounded-full border-2 ${
                isFailure ? 'border-red-500 bg-red-950' :
                isSuccess ? 'border-emerald-500 bg-emerald-950' :
                isUnknown ? 'border-amber-500 bg-amber-950' :
                'border-indigo-500 bg-indigo-950'
              }`} />

              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-xs text-slate-400">#{event.eventSequence}</span>
                  <Badge variant={isFailure ? 'danger' : isSuccess ? 'success' : isUnknown ? 'warning' : 'info'}>
                    {event.eventType}
                  </Badge>
                  {event.outcomeCategory && (
                    <span className="text-xs text-slate-400">({event.outcomeCategory})</span>
                  )}
                </div>
                <div className="flex items-center gap-1.5 text-xs text-slate-400 font-mono">
                  <Clock className="h-3.5 w-3.5 text-slate-500" />
                  <span>{new Date(event.eventTimestamp).toLocaleString()}</span>
                </div>
              </div>

              {/* Event details */}
              <div className="flex flex-col gap-1.5 rounded-lg border border-slate-800/80 bg-slate-950/60 p-3 text-xs text-slate-300">
                {event.summary && (
                  <p className="font-medium text-slate-200">{event.summary}</p>
                )}

                <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 pt-1 border-t border-slate-800/40 text-slate-400">
                  <div className="flex items-center gap-1.5">
                    <User className="h-3.5 w-3.5 text-slate-500" />
                    <span>Actor: <strong className="text-slate-200 font-normal">{event.actorEmail || 'SYSTEM'} ({event.actorRole || 'SYSTEM'})</strong></span>
                  </div>

                  {event.previousState && event.resultingState && (
                    <div className="flex items-center gap-1.5">
                      <span>Transition: <strong className="text-slate-200 font-mono font-normal">{event.previousState} &rarr; {event.resultingState}</strong></span>
                    </div>
                  )}

                  {event.commitSha && (
                    <div className="flex items-center gap-1.5">
                      <Hash className="h-3.5 w-3.5 text-slate-500" />
                      <span>Commit: <strong className="font-mono text-slate-200">{event.commitSha.slice(0, 10)}...</strong></span>
                    </div>
                  )}

                  {event.githubPrNumber && (
                    <div className="flex items-center gap-1.5">
                      <GitPullRequest className="h-3.5 w-3.5 text-indigo-400" />
                      <span>PR #{event.githubPrNumber}: {
                        isSafeGithubPrUrl(event.prUrl) ? (
                          <a
                            href={event.prUrl!}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 font-semibold text-indigo-400 underline hover:text-indigo-300"
                          >
                            Link <ExternalLink className="h-3 w-3" />
                          </a>
                        ) : (
                          <span className="font-mono text-slate-300">#{event.githubPrNumber}</span>
                        )
                      }</span>
                    </div>
                  )}

                  {event.reasonCode && (
                    <div className="col-span-full text-slate-400">
                      Reason Code: <span className="font-mono text-amber-300">{event.reasonCode}</span>
                    </div>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
