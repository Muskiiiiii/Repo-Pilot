'use client';

import React, { useState } from 'react';
import {
  CodeReviewResultDto,
  ValidatedFindingCandidate,
  FindingSeverity,
  FindingSide,
} from '@/types/review';
import { Badge } from '@/components/ui/Badge';
import { Card } from '@/components/ui/Card';
import { StatusDot, StatusType } from '@/components/ui/StatusDot';
import {
  ShieldAlert,
  ShieldCheck,
  AlertTriangle,
  FileCode,
  Clock,
  Cpu,
  Hash,
  ChevronDown,
  ChevronUp,
  Info,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Filter,
} from 'lucide-react';

interface CodeReviewDetailsViewProps {
  review: CodeReviewResultDto | null;
  loading?: boolean;
  error?: string | null;
  errorStatus?: number | null;
}

export function CodeReviewDetailsView({
  review,
  loading = false,
  error = null,
  errorStatus = null,
}: CodeReviewDetailsViewProps) {
  const [severityFilter, setSeverityFilter] = useState<string>('ALL');
  const [sideFilter, setSideFilter] = useState<string>('ALL');
  const [expandedFindings, setExpandedFindings] = useState<Record<string, boolean>>({});

  // Render Loading State
  if (loading) {
    return (
      <div className="flex h-96 w-full flex-col items-center justify-center gap-3 text-slate-400">
        <div className="flex items-center gap-3">
          <StatusDot status="running" pulse />
          <span className="text-base font-medium text-slate-200">Loading AI Code Review...</span>
        </div>
        <p className="text-xs text-slate-500">Retrieving trusted review metadata and findings</p>
      </div>
    );
  }

  // Render Error State with HTTP status code mapping
  if (error || !review) {
    const getErrorMessage = () => {
      if (errorStatus === 401) return 'Authentication required. Please sign in to view this review.';
      if (errorStatus === 403) return 'Access denied. You do not have permission to view this review.';
      if (errorStatus === 404) return 'Review not found. The requested review does not exist or was deleted.';
      if (errorStatus === 409) return 'State conflict. The review context is no longer consistent.';
      if (errorStatus === 422) return 'Invalid review data. The backend returned unprocessable review details.';
      if (errorStatus === 429) return 'Rate limit exceeded. Please wait a moment before trying again.';
      if (errorStatus && errorStatus >= 500) return 'Backend service error. The server encountered an internal issue.';
      return error || 'Unable to load code review details.';
    };

    return (
      <div className="mx-auto max-w-3xl space-y-4 py-8">
        <div className="flex items-center gap-3 rounded-xl border border-rose-800/50 bg-rose-950/60 p-6 text-rose-200 shadow-sm">
          <AlertCircle className="h-6 w-6 text-rose-400 shrink-0" />
          <div>
            <h3 className="text-base font-semibold">Error Loading Code Review</h3>
            <p className="mt-1 text-sm text-rose-300">{getErrorMessage()}</p>
          </div>
        </div>
      </div>
    );
  }

  // Map backend status to StatusDot status
  const mapStatusToDot = (status: string): StatusType => {
    switch (status) {
      case 'RUNNING':
      case 'PENDING':
        return 'running';
      case 'COMPLETED':
        return 'success';
      case 'FAILED':
        return 'error';
      case 'CANCELLED':
        return 'warning';
      default:
        return 'idle';
    }
  };

  // Map risk score to style
  const getRiskBadgeVariant = (risk: string): 'danger' | 'warning' | 'purple' | 'success' | 'outline' => {
    switch (risk) {
      case 'CRITICAL':
      case 'HIGH':
        return 'danger';
      case 'MEDIUM':
        return 'warning';
      case 'LOW':
        return 'success';
      case 'INFO':
        return 'purple';
      default:
        return 'outline';
    }
  };

  // Map finding severity badge variant
  const getSeverityBadgeVariant = (severity?: FindingSeverity): 'danger' | 'warning' | 'purple' | 'success' | 'outline' => {
    switch (severity) {
      case 'CRITICAL':
      case 'HIGH':
        return 'danger';
      case 'MEDIUM':
        return 'warning';
      case 'LOW':
        return 'success';
      case 'INFO':
        return 'purple';
      default:
        return 'outline';
    }
  };

  // Toggle finding details card
  const toggleFinding = (index: number) => {
    const key = `finding-${index}`;
    setExpandedFindings((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  };

  // Filter findings defensively
  const allFindings = Array.isArray(review.findings) ? review.findings : [];
  const filteredFindings = allFindings.filter((f) => {
    if (!f) return false;
    if (severityFilter !== 'ALL' && f.severity !== severityFilter) return false;
    if (sideFilter !== 'ALL' && f.side !== sideFilter) return false;
    return true;
  });

  return (
    <div className="space-y-6">
      {/* Review Header Card */}
      <Card className="border-slate-800 bg-slate-900/90 p-6 shadow-md">
        <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <div className="space-y-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="purple" size="md">
                {review.reviewMode || 'CODE_CHANGE_REVIEW'}
              </Badge>
              <StatusDot status={mapStatusToDot(review.status)} label={review.status} />
              <Badge variant={getRiskBadgeVariant(review.overallRiskScore)}>
                Overall Risk: {review.overallRiskScore || 'UNKNOWN'}
              </Badge>
            </div>
            <h1 className="text-xl font-bold text-slate-100">AI Code Review Summary</h1>
          </div>

          <div className="flex items-center gap-2 text-xs text-slate-400">
            <ShieldCheck className="h-4 w-4 text-emerald-400" />
            <span className="font-semibold text-slate-300">Read-Only Safety View</span>
          </div>
        </div>

        {/* Plain Text Summary */}
        <div className="mt-4 rounded-lg border border-slate-800 bg-slate-950/70 p-4">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400">Review Summary</h4>
          <p className="mt-1.5 whitespace-pre-wrap break-words text-sm text-slate-200">
            {review.summary || 'No summary provided.'}
          </p>
        </div>

        {/* Metadata Grid */}
        <div className="mt-4 grid grid-cols-1 gap-3 rounded-lg border border-slate-800/80 bg-slate-950/40 p-4 text-xs text-slate-300 sm:grid-cols-2 md:grid-cols-4">
          <div className="flex items-center gap-2">
            <Clock className="h-4 w-4 text-slate-400 shrink-0" />
            <div>
              <span className="block text-[10px] text-slate-500">Execution Time</span>
              <span className="font-mono text-slate-200">{review.executionTimeMs ? `${review.executionTimeMs} ms` : 'N/A'}</span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Cpu className="h-4 w-4 text-slate-400 shrink-0" />
            <div>
              <span className="block text-[10px] text-slate-500">AI Model</span>
              <span className="font-mono text-slate-200">{review.modelIdentifier || 'claude-3-5-sonnet'}</span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Hash className="h-4 w-4 text-slate-400 shrink-0" />
            <div className="min-w-0 flex-1">
              <span className="block text-[10px] text-slate-500">Context Hash</span>
              <span className="block truncate font-mono text-[11px] text-slate-300" title={review.reviewContextHash || ''}>
                {review.reviewContextHash || 'N/A'}
              </span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <ShieldAlert className="h-4 w-4 text-slate-400 shrink-0" />
            <div>
              <span className="block text-[10px] text-slate-500">Findings Accepted / AI Total</span>
              <span className="font-medium text-slate-200">
                {review.acceptedFindingsCount} / {review.totalAiFindings}
              </span>
            </div>
          </div>
        </div>
      </Card>

      {/* Non-Terminal State Handling (RUNNING / PENDING) */}
      {(review.status === 'RUNNING' || review.status === 'PENDING') && (
        <Card className="border-blue-900/50 bg-blue-950/30 p-5">
          <div className="flex items-center gap-3 text-blue-300">
            <StatusDot status="running" pulse />
            <div>
              <h3 className="font-semibold text-sm">Review Execution In Progress</h3>
              <p className="text-xs text-blue-400/80">The backend AI review analysis is currently running. Results will update automatically.</p>
            </div>
          </div>
        </Card>
      )}

      {/* Terminal Failure State (FAILED) */}
      {review.status === 'FAILED' && (
        <Card className="border-rose-900/50 bg-rose-950/30 p-5">
          <div className="flex items-center gap-3 text-rose-300">
            <XCircle className="h-5 w-5 text-rose-400 shrink-0" />
            <div>
              <h3 className="font-semibold text-sm">Code Review Execution Failed</h3>
              <p className="text-xs text-rose-400/80">The backend review pipeline encountered an error during analysis. No validated findings were saved.</p>
            </div>
          </div>
        </Card>
      )}

      {/* Terminal Cancelled State (CANCELLED) */}
      {review.status === 'CANCELLED' && (
        <Card className="border-amber-900/50 bg-amber-950/30 p-5">
          <div className="flex items-center gap-3 text-amber-300">
            <AlertTriangle className="h-5 w-5 text-amber-400 shrink-0" />
            <div>
              <h3 className="font-semibold text-sm">Code Review Cancelled</h3>
              <p className="text-xs text-amber-400/80">This review execution was cancelled before completion.</p>
            </div>
          </div>
        </Card>
      )}

      {/* Findings Section */}
      <div className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-lg font-bold text-slate-100">Validated Review Findings</h2>
            <p className="text-xs text-slate-400">
              Showing {filteredFindings.length} of {allFindings.length} findings
            </p>
          </div>

          {/* Filters */}
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1 text-xs text-slate-400">
              <Filter className="h-3.5 w-3.5" />
              <span>Severity:</span>
            </div>
            <select
              aria-label="Filter findings by severity"
              value={severityFilter}
              onChange={(e) => setSeverityFilter(e.target.value)}
              className="rounded-md border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs text-slate-200 focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              <option value="ALL">All Severities</option>
              <option value="CRITICAL">Critical</option>
              <option value="HIGH">High</option>
              <option value="MEDIUM">Medium</option>
              <option value="LOW">Low</option>
              <option value="INFO">Info</option>
            </select>

            <div className="flex items-center gap-1 text-xs text-slate-400 ml-2">
              <span>Side:</span>
            </div>
            <select
              aria-label="Filter findings by side"
              value={sideFilter}
              onChange={(e) => setSideFilter(e.target.value)}
              className="rounded-md border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs text-slate-200 focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              <option value="ALL">All Sides</option>
              <option value="PROPOSED">Proposed Change</option>
              <option value="ORIGINAL">Original Baseline</option>
            </select>
          </div>
        </div>

        {/* Empty Findings State */}
        {allFindings.length === 0 && review.status === 'COMPLETED' && (
          <Card className="border-slate-800 bg-slate-900/60 p-8 text-center">
            <CheckCircle2 className="mx-auto h-10 w-10 text-emerald-400" />
            <h3 className="mt-3 text-base font-semibold text-slate-200">Zero Findings Detected</h3>
            <p className="mt-1 text-xs text-slate-400 max-w-md mx-auto">
              The deterministic validation engine and AI review found no security vulnerabilities, logic defects, or syntax issues in this review context.
            </p>
          </Card>
        )}

        {/* No Filtered Match State */}
        {allFindings.length > 0 && filteredFindings.length === 0 && (
          <Card className="border-slate-800 bg-slate-900/60 p-6 text-center text-slate-400 text-xs">
            No findings match the selected severity and side filters.
          </Card>
        )}

        {/* Findings List */}
        <div className="space-y-3">
          {filteredFindings.map((finding: ValidatedFindingCandidate, index: number) => {
            const isExpanded = !!expandedFindings[`finding-${index}`];
            const lineRangeText =
              finding.startLine != null
                ? finding.endLine != null && finding.endLine !== finding.startLine
                  ? `L${finding.startLine}-${finding.endLine}`
                  : `L${finding.startLine}`
                : 'Full File';

            return (
              <Card
                key={finding.fingerprint || `finding-${index}`}
                className="border-slate-800 bg-slate-900/90 transition-all hover:border-slate-700"
              >
                {/* Card Header / Toggle */}
                <div
                  role="button"
                  tabIndex={0}
                  aria-expanded={isExpanded}
                  onClick={() => toggleFinding(index)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      toggleFinding(index);
                    }
                  }}
                  className="flex cursor-pointer items-start justify-between gap-3 p-4 focus:outline-none focus:ring-1 focus:ring-blue-500 rounded-lg"
                >
                  <div className="space-y-1.5 flex-1 min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant={getSeverityBadgeVariant(finding.severity)}>
                        {finding.severity || 'UNKNOWN'}
                      </Badge>
                      {finding.category && <Badge variant="outline">{finding.category}</Badge>}
                      {finding.confidence && (
                        <span className="text-[11px] text-slate-400">
                          Confidence: <strong className="text-slate-200">{finding.confidence}</strong>
                        </span>
                      )}
                      <Badge variant={finding.side === 'PROPOSED' ? 'purple' : 'outline'}>
                        Side: {finding.side || 'PROPOSED'}
                      </Badge>
                    </div>

                    {/* Plain Text Title */}
                    <h3 className="text-sm font-semibold text-slate-100 break-words">
                      {finding.title || 'Untitled Finding'}
                    </h3>

                    {/* Code Location */}
                    <div className="flex items-center gap-2 text-xs font-mono text-slate-400 truncate">
                      <FileCode className="h-3.5 w-3.5 text-blue-400 shrink-0" />
                      <span className="truncate" title={finding.filePath || 'Unknown file'}>
                        {finding.filePath || 'Unknown file'}
                      </span>
                      <span className="text-slate-500 font-semibold">•</span>
                      <span className="text-blue-300 font-semibold">{lineRangeText}</span>
                    </div>
                  </div>

                  <button
                    type="button"
                    tabIndex={-1}
                    className="p-1 text-slate-400 hover:text-slate-200 focus:outline-none"
                    aria-label={isExpanded ? 'Collapse finding details' : 'Expand finding details'}
                  >
                    {isExpanded ? <ChevronUp className="h-5 w-5" /> : <ChevronDown className="h-5 w-5" />}
                  </button>
                </div>

                {/* Expanded Details Body */}
                {isExpanded && (
                  <div className="border-t border-slate-800 p-4 space-y-4 bg-slate-950/50 rounded-b-lg">
                    {/* Security Claim Status Banner */}
                    <div
                      className={`flex items-start gap-2.5 rounded-md border p-3 text-xs ${
                        finding.isUnverifiedSecurityClaim
                          ? 'border-amber-800/60 bg-amber-950/40 text-amber-200'
                          : 'border-emerald-800/60 bg-emerald-950/30 text-emerald-200'
                      }`}
                    >
                      {finding.isUnverifiedSecurityClaim ? (
                        <AlertTriangle className="h-4 w-4 text-amber-400 shrink-0 mt-0.5" />
                      ) : (
                        <ShieldCheck className="h-4 w-4 text-emerald-400 shrink-0 mt-0.5" />
                      )}
                      <div>
                        <span className="font-semibold block">
                          {finding.isUnverifiedSecurityClaim
                            ? 'AI security claim — not independently verified'
                            : 'Verified finding metadata'}
                        </span>
                        <p className="mt-0.5 text-[11px] opacity-90">
                          {finding.isUnverifiedSecurityClaim
                            ? 'This finding contains security assertions produced by AI analysis that have not been deterministically proven by static AST inspection.'
                            : 'This finding structure and evidence scope have been validated by backend deterministic pre-checks.'}
                        </p>
                      </div>
                    </div>

                    {/* CWE Identifier (Plain text reference, NOT clickable external link) */}
                    {finding.cweId && (
                      <div className="text-xs text-slate-300 flex items-center gap-2">
                        <span className="text-slate-400">CWE Standard Reference:</span>
                        <span className="font-mono bg-slate-900 border border-slate-700 px-2 py-0.5 rounded text-slate-200">
                          {finding.cweId}
                        </span>
                      </div>
                    )}

                    {/* Description */}
                    {finding.description && (
                      <div className="space-y-1">
                        <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Description</h4>
                        <p className="text-xs text-slate-300 whitespace-pre-wrap break-words">
                          {finding.description}
                        </p>
                      </div>
                    )}

                    {/* Evidence Snippet */}
                    {finding.evidence && (
                      <div className="space-y-1">
                        <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wider">Evidence Snippet</h4>
                        <div className="max-h-48 overflow-y-auto rounded-lg border border-slate-800 bg-slate-900 p-3 font-mono text-xs text-slate-300 whitespace-pre-wrap break-all">
                          {finding.evidence}
                        </div>
                      </div>
                    )}

                    {/* Read-Only AI Remediation Guidance */}
                    {finding.remediation && (
                      <div className="space-y-1 rounded-md border border-slate-800 bg-slate-900/90 p-3">
                        <div className="flex items-center gap-2">
                          <Info className="h-4 w-4 text-blue-400 shrink-0" />
                          <h4 className="text-xs font-semibold text-blue-300 uppercase tracking-wider">
                            AI Remediation Guidance (Read-Only Information)
                          </h4>
                        </div>
                        <p className="text-xs text-slate-300 whitespace-pre-wrap break-words mt-1">
                          {finding.remediation}
                        </p>
                        <p className="text-[10px] text-slate-500 italic mt-1.5">
                          Note: This AI guidance is for developer reference only. No code fixes will be applied automatically.
                        </p>
                      </div>
                    )}

                    {/* Fingerprint */}
                    {finding.fingerprint && (
                      <div className="pt-2 border-t border-slate-800/80 flex items-center justify-between text-[11px] text-slate-500 font-mono">
                        <span>Fingerprint:</span>
                        <span className="truncate max-w-xs text-slate-400" title={finding.fingerprint}>
                          {finding.fingerprint}
                        </span>
                      </div>
                    )}
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      </div>
    </div>
  );
}
