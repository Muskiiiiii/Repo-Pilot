'use client';

import React from 'react';
import { TaskReportResponse } from '@/lib/report';
import { Badge } from '@/components/ui/Badge';
import { Card } from '@/components/ui/Card';
import { StatusDot, StatusType } from '@/components/ui/StatusDot';
import {
  FileCode,
  ShieldCheck,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Sparkles,
  Info,
  Clock,
  Hash,
  AlertCircle,
  Lock,
} from 'lucide-react';

export interface TaskReportViewProps {
  reportData: TaskReportResponse | null;
  loading?: boolean;
  error?: string | null;
  errorStatus?: number | null;
}

export function sanitizeMarkdownProse(prose: string | undefined | null): string {
  if (!prose) return '';

  // 1. Escapes or strips raw dangerous HTML tags
  let clean = prose
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '&lt;script&gt;[REDACTED_SCRIPT]&lt;/script&gt;')
    .replace(/<iframe\b[^<]*(?:(?!<\/iframe>)<[^<]*)*<\/iframe>/gi, '&lt;iframe&gt;[REDACTED_IFRAME]&lt;/iframe&gt;')
    .replace(/<div\b[^>]*\bonclick\s*=\s*['"]?[^>]*>/gi, '&lt;div [onclick_disabled]&gt;')
    .replace(/<img\b[^>]*\bonerror\s*=\s*['"]?[^>]*>/gi, '&lt;img [onerror_disabled]&gt;');

  // 2. Neutralize dangerous URL schemes (javascript:, data:text/html, vbscript:)
  clean = clean.replace(/(javascript:|vbscript:|data\s*:\s*text\/html)/gi, 'blocked_scheme:');

  return clean;
}

export function TaskReportView({
  reportData,
  loading = false,
  error = null,
  errorStatus = null,
}: TaskReportViewProps) {
  if (loading) {
    return (
      <div className="flex h-96 w-full flex-col items-center justify-center gap-3 text-slate-400">
        <div className="flex items-center gap-3">
          <StatusDot status="running" pulse />
          <span className="text-base font-medium text-slate-200">Loading Execution Report...</span>
        </div>
        <p className="text-xs text-slate-500">Retrieving historical report evidence & authoritative facts</p>
      </div>
    );
  }

  if (error || errorStatus) {
    const getErrorMessage = () => {
      if (errorStatus === 401) return 'Authentication required. Please sign in to view this report.';
      if (errorStatus === 403) return 'Access denied. You do not have permission to view this project report.';
      if (errorStatus === 404) return 'Report not found. The execution report for this task does not exist.';
      if (errorStatus && errorStatus >= 500) return 'Server error. Internal service error retrieving report.';
      return error || 'Unable to load task report.';
    };

    const isAuth = errorStatus === 401;
    const isForbidden = errorStatus === 403;

    return (
      <div className="mx-auto max-w-3xl space-y-4 py-8">
        <div className={`flex items-center gap-3 rounded-xl border p-6 shadow-sm ${
          isAuth || isForbidden
            ? 'border-amber-800/50 bg-amber-950/60 text-amber-200'
            : 'border-rose-800/50 bg-rose-950/60 text-rose-200'
        }`}>
          {isAuth || isForbidden ? (
            <Lock className="h-6 w-6 text-amber-400 shrink-0" />
          ) : (
            <AlertCircle className="h-6 w-6 text-rose-400 shrink-0" />
          )}
          <div>
            <h3 className="text-base font-semibold">
              {isAuth ? 'Authentication Error' : isForbidden ? 'Access Forbidden' : 'Error Loading Task Report'}
            </h3>
            <p className="mt-1 text-sm">{getErrorMessage()}</p>
          </div>
        </div>
      </div>
    );
  }

  if (!reportData || !reportData.implementationReport) {
    return (
      <div className="mx-auto max-w-3xl py-12 text-center">
        <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-8 text-slate-400">
          <Info className="mx-auto h-8 w-8 text-slate-500 mb-3" />
          <h3 className="text-base font-medium text-slate-200">No Implementation Report Available</h3>
          <p className="mt-1 text-sm text-slate-400">Task execution has not completed or report has not been generated yet.</p>
        </div>
      </div>
    );
  }

  const report = reportData.implementationReport;
  const synthesis = reportData.synthesis;
  const synthesisStatus = reportData.synthesisStatus;

  // Parse authoritative JSON metrics
  let changedFilesCount = 0;
  try {
    const cf = JSON.parse(report.changedFilesSummary || '{}');
    changedFilesCount = cf.totalChanges || (cf.boundedFiles ? cf.boundedFiles.length : 0);
  } catch (e) {
    changedFilesCount = 0;
  }

  let totalTests = 0;
  let passedTests = 0;
  let failedTests = 0;
  try {
    const ts = JSON.parse(report.testSummary || '{}');
    totalTests = ts.totalTests || 0;
    passedTests = ts.passed || 0;
    failedTests = ts.failed || 0;
  } catch (e) {
    totalTests = 0;
  }

  let criticalFindings = 0;
  let certStatus = 'NONE';
  try {
    const sec = JSON.parse(report.securityReviewSummary || '{}');
    certStatus = sec.complianceCertificateStatus || 'NONE';
    if (sec.severityCounts) {
      criticalFindings = sec.severityCounts.CRITICAL || 0;
    }
  } catch (e) {
    certStatus = 'NONE';
  }

  const isCompleted = report.executionOutcome === 'COMPLETED';

  return (
    <div className="mx-auto max-w-5xl space-y-6 py-6" data-testid="task-report-view">
      {/* Header Banner */}
      <div className="flex flex-wrap items-center justify-between gap-4 rounded-xl border border-slate-800 bg-slate-900 p-6 shadow-md">
        <div className="space-y-1">
          <div className="flex items-center gap-3">
            <span className="text-xl font-bold text-slate-100">Task Implementation Report</span>
            <Badge variant={isCompleted ? 'success' : 'danger'}>
              {report.executionOutcome}
            </Badge>
          </div>
          <p className="text-xs text-slate-400 flex items-center gap-2">
            <Clock className="h-3.5 w-3.5" />
            <span>Generated: {new Date(report.createdAt).toLocaleString()}</span>
          </p>
        </div>
        <div className="flex items-center gap-4 text-right">
          <div>
            <div className="text-xs text-slate-400">Confidence Score</div>
            <div className="text-lg font-bold text-slate-100">{report.confidenceScore}%</div>
          </div>
        </div>
      </div>

      {/* Authoritative Fact Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Changed Files Card */}
        <Card className="p-5 space-y-2 border-slate-800 bg-slate-900/80">
          <div className="flex items-center gap-2 text-slate-300 font-semibold text-sm">
            <FileCode className="h-4 w-4 text-blue-400" />
            <span>Changed Files Evidence</span>
          </div>
          <div className="text-2xl font-bold text-slate-100">{changedFilesCount} Files</div>
          <p className="text-xs text-slate-400">Authoritative git change summary snapshot</p>
        </Card>

        {/* Test Summary Card */}
        <Card className="p-5 space-y-2 border-slate-800 bg-slate-900/80">
          <div className="flex items-center gap-2 text-slate-300 font-semibold text-sm">
            <CheckCircle2 className="h-4 w-4 text-emerald-400" />
            <span>Test Execution Evidence</span>
          </div>
          <div className="text-2xl font-bold text-slate-100">
            {passedTests} / {totalTests} <span className="text-xs font-normal text-slate-400">Passed</span>
          </div>
          {failedTests > 0 && (
            <p className="text-xs text-rose-400 flex items-center gap-1">
              <XCircle className="h-3.5 w-3.5" /> {failedTests} Failing Tests
            </p>
          )}
        </Card>

        {/* Security Review Card */}
        <Card className="p-5 space-y-2 border-slate-800 bg-slate-900/80">
          <div className="flex items-center gap-2 text-slate-300 font-semibold text-sm">
            <ShieldCheck className="h-4 w-4 text-purple-400" />
            <span>Security Posture</span>
          </div>
          <div className="text-2xl font-bold text-slate-100">
            {criticalFindings === 0 ? 'CLEAN' : `${criticalFindings} Critical`}
          </div>
          <p className="text-xs text-slate-400">Certificate Status: {certStatus}</p>
        </Card>
      </div>

      {/* Cryptographic Context Hash Card */}
      <Card className="p-4 border-slate-800 bg-slate-950/60 text-xs text-slate-400 font-mono space-y-1">
        <div className="flex items-center gap-2 font-semibold text-slate-300">
          <Hash className="h-3.5 w-3.5 text-slate-400" />
          <span>Source Context Hash (Immutable Evidence)</span>
        </div>
        <div className="break-all text-slate-400">{report.sourceContextHash}</div>
      </Card>

      {/* Optional AI Explanatory Synthesis Section */}
      {synthesisStatus === 'AVAILABLE' && synthesis && (
        <div className="space-y-4 border-t border-slate-800 pt-6">
          <div className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-amber-400" />
            <h3 className="text-lg font-bold text-slate-100">AI Synthesis Explanation</h3>
            <Badge variant="warning">Explanatory Prose</Badge>
          </div>

          <Card className="p-6 border-slate-800 bg-slate-900/90 space-y-4">
            {synthesis.executiveSummary && (
              <div>
                <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-1">Executive Summary</h4>
                <div
                  className="text-sm text-slate-200 whitespace-pre-wrap leading-relaxed"
                  data-testid="ai-executive-summary"
                >
                  {sanitizeMarkdownProse(synthesis.executiveSummary)}
                </div>
              </div>
            )}

            {synthesis.implementationExplanation && (
              <div>
                <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-1">Implementation Analysis</h4>
                <div
                  className="text-sm text-slate-300 whitespace-pre-wrap leading-relaxed"
                  data-testid="ai-implementation-explanation"
                >
                  {sanitizeMarkdownProse(synthesis.implementationExplanation)}
                </div>
              </div>
            )}

            {synthesis.developerNotes && synthesis.developerNotes.length > 0 && (
              <div>
                <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-1">Developer Notes</h4>
                <ul className="list-disc list-inside text-sm text-slate-300 space-y-1">
                  {synthesis.developerNotes.map((note, idx) => (
                    <li key={idx}>{sanitizeMarkdownProse(note)}</li>
                  ))}
                </ul>
              </div>
            )}
          </Card>
        </div>
      )}

      {synthesisStatus === 'NONE' && (
        <div className="rounded-xl border border-dashed border-slate-800 bg-slate-950/40 p-6 text-center text-slate-500 text-xs">
          AI synthesis explanation has not been generated for this execution report yet.
        </div>
      )}
    </div>
  );
}
