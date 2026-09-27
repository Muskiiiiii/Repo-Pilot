'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import {
  TaskItem,
  PlanItem,
  CodeChangeItem,
  createTask,
  generatePlan,
  generateCodeChanges,
  getCodeChanges,
} from '@/lib/task';
import { CodeDiffViewer } from './CodeDiffViewer';
import {
  Brain,
  FileCode,
  AlertTriangle,
  CheckCircle2,
  RefreshCw,
  PlusCircle,
  ChevronDown,
  ChevronRight,
  ShieldAlert,
  Sparkles,
  ListChecks,
  Code2,
  Eye,
  Lock,
} from 'lucide-react';

interface TaskPlanningControlProps {
  projectId: string;
  repositoryId: string;
  isIndexed: boolean;
}

export function TaskPlanningControl({
  projectId,
  repositoryId,
  isIndexed,
}: TaskPlanningControlProps) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [task, setTask] = useState<TaskItem | null>(null);
  const [plan, setPlan] = useState<PlanItem | null>(null);
  const [codeChanges, setCodeChanges] = useState<CodeChangeItem[]>([]);
  const [activeTab, setActiveTab] = useState<'plan' | 'changes'>('plan');

  const [creatingTask, setCreatingTask] = useState(false);
  const [generatingPlan, setGeneratingPlan] = useState(false);
  const [generatingCode, setGeneratingCode] = useState(false);

  const [error, setError] = useState<string | null>(null);
  const [expandedEvidence, setExpandedEvidence] = useState<Record<string, boolean>>({});

  const handleCreateTask = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !description.trim()) return;

    setCreatingTask(true);
    setError(null);
    setPlan(null);
    setCodeChanges([]);

    try {
      const createdTask = await createTask(projectId, repositoryId, title.trim(), description.trim());
      setTask(createdTask);
    } catch (err: any) {
      setError(err.message || 'Failed to create developer task.');
    } finally {
      setCreatingTask(false);
    }
  };

  const handleGeneratePlan = async () => {
    if (!task) return;
    setGeneratingPlan(true);
    setError(null);

    try {
      const generatedPlan = await generatePlan(projectId, repositoryId, task.id);
      setPlan(generatedPlan);
    } catch (err: any) {
      setError(err.message || 'Failed to generate implementation plan.');
    } finally {
      setGeneratingPlan(false);
    }
  };

  const handleGenerateCode = async () => {
    if (!task || !plan) return;
    setGeneratingCode(true);
    setError(null);

    try {
      const changeSet = await generateCodeChanges(projectId, repositoryId, task.id, plan.id);
      setCodeChanges(changeSet.changes || []);
      setActiveTab('changes');
    } catch (err: any) {
      setError(err.message || 'Failed to generate proposed code changes.');
    } finally {
      setGeneratingCode(false);
    }
  };

  const toggleEvidence = (chunkId: string) => {
    setExpandedEvidence((prev) => ({
      ...prev,
      [chunkId]: !prev[chunkId],
    }));
  };

  let parsedUnderstanding: any = null;
  if (task?.issueUnderstanding) {
    try {
      parsedUnderstanding = JSON.parse(task.issueUnderstanding);
    } catch (e) {
      parsedUnderstanding = null;
    }
  }

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-slate-800 pb-4">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-400">
            <Brain className="h-5 w-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
              AI Issue Understanding, Planning &amp; Code Generation
              <span className="text-[10px] font-semibold bg-indigo-500/15 text-indigo-300 border border-indigo-500/30 px-2 py-0.5 rounded-full uppercase tracking-wider">
                Phase 8 &amp; 9 Architecture
              </span>
            </h2>
            <p className="text-xs text-slate-400">
              Submit developer issues to extract problem analysis, perform semantic code retrieval, and generate evidence-backed proposed code diffs
            </p>
          </div>
        </div>
      </div>

      {!isIndexed && (
        <div className="flex items-center space-x-3 rounded-lg bg-amber-950/40 border border-amber-800/40 p-4 text-amber-300 text-xs">
          <AlertTriangle className="h-4 w-4 shrink-0 text-amber-400" />
          <span>
            Repository is not indexed yet. Please index the codebase before generating AI implementation plans or code changes.
          </span>
        </div>
      )}

      {/* Task Submission Form */}
      {!task && (
        <form onSubmit={handleCreateTask} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1">
              Issue / Task Title *
            </label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="e.g. Add email validation to user registration"
              disabled={!isIndexed || creatingTask}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3.5 py-2.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-indigo-500 font-mono disabled:opacity-50"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1">
              Issue / Task Description *
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Describe the requested behavior, expected error messages, or technical acceptance criteria..."
              rows={3}
              disabled={!isIndexed || creatingTask}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg p-3 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-indigo-500 font-mono disabled:opacity-50"
            />
          </div>

          <div className="flex justify-end">
            <button
              type="submit"
              disabled={!isIndexed || !title.trim() || !description.trim() || creatingTask}
              className="px-5 py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:bg-slate-800 disabled:text-slate-500 text-white font-medium text-sm flex items-center space-x-2 transition-colors"
            >
              {creatingTask ? (
                <>
                  <RefreshCw className="h-4 w-4 animate-spin mr-1" />
                  <span>Submitting Task...</span>
                </>
              ) : (
                <>
                  <PlusCircle className="h-4 w-4 mr-1" />
                  <span>Submit Task &amp; Start Analysis</span>
                </>
              )}
            </button>
          </div>
        </form>
      )}

      {/* Error Message */}
      {error && (
        <div className="flex items-center space-x-2 text-rose-400 text-xs bg-rose-950/40 border border-rose-800/40 p-3 rounded-lg">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* Active Task Details */}
      {task && (
        <div className="space-y-6">
          <div className="bg-slate-950 border border-slate-800 rounded-lg p-4 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-2">
                <span className="text-xs font-semibold px-2.5 py-0.5 rounded bg-indigo-500/10 text-indigo-400 border border-indigo-500/20 font-mono">
                  TASK-{task.id.substring(0, 8)}
                </span>
                <span className="text-xs text-slate-400 uppercase tracking-wider font-semibold bg-slate-800 px-2 py-0.5 rounded">
                  Status: {task.status}
                </span>
              </div>

              <div className="flex space-x-2">
                {!plan && (
                  <button
                    onClick={handleGeneratePlan}
                    disabled={generatingPlan}
                    className="px-4 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:bg-slate-800 disabled:text-slate-500 text-white font-medium text-xs flex items-center space-x-2 transition-colors"
                  >
                    {generatingPlan ? (
                      <>
                        <RefreshCw className="h-3.5 w-3.5 animate-spin mr-1" />
                        <span>Generating Plan...</span>
                      </>
                    ) : (
                      <>
                        <Sparkles className="h-3.5 w-3.5 mr-1 text-indigo-300" />
                        <span>Generate Implementation Plan</span>
                      </>
                    )}
                  </button>
                )}

                {plan && (
                  <button
                    onClick={handleGenerateCode}
                    disabled={generatingCode}
                    className="px-4 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 disabled:bg-slate-800 disabled:text-slate-500 text-white font-medium text-xs flex items-center space-x-2 transition-colors"
                  >
                    {generatingCode ? (
                      <>
                        <RefreshCw className="h-3.5 w-3.5 animate-spin mr-1" />
                        <span>Generating Code Diffs...</span>
                      </>
                    ) : (
                      <>
                        <Code2 className="h-3.5 w-3.5 mr-1 text-emerald-300" />
                        <span>Generate Proposed Code Changes</span>
                      </>
                    )}
                  </button>
                )}
              </div>
            </div>

            <div>
              <h3 className="text-base font-bold text-slate-100">{task.title}</h3>
              <p className="text-xs text-slate-300 mt-1 font-mono">{task.description}</p>
            </div>
          </div>

          {/* Tab Switcher if Plan or Changes exist */}
          {plan && (
            <div className="flex space-x-2 border-b border-slate-800 pb-2 font-sans text-xs">
              <button
                onClick={() => setActiveTab('plan')}
                className={`px-4 py-2 rounded-lg font-medium flex items-center space-x-2 transition-colors ${
                  activeTab === 'plan'
                    ? 'bg-indigo-600/20 text-indigo-300 border border-indigo-500/30'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Sparkles className="h-4 w-4" />
                <span>Implementation Plan &amp; Evidence</span>
              </button>

              <button
                onClick={() => setActiveTab('changes')}
                className={`px-4 py-2 rounded-lg font-medium flex items-center space-x-2 transition-colors ${
                  activeTab === 'changes'
                    ? 'bg-emerald-600/20 text-emerald-300 border border-emerald-500/30'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Code2 className="h-4 w-4" />
                <span>Proposed Code Changes ({codeChanges.length})</span>
              </button>

              <Link
                href={`/projects/${projectId}/repositories/${repositoryId}/tasks/${task.id}/reviews`}
                className="px-4 py-2 rounded-lg font-medium flex items-center space-x-2 text-purple-300 bg-purple-600/20 border border-purple-500/30 hover:bg-purple-600/30 transition-colors ml-auto"
              >
                <ShieldAlert className="h-4 w-4" />
                <span>View AI Code Reviews (Read-Only)</span>
              </Link>
            </div>
          )}

          {/* TAB 1: IMPLEMENTATION PLAN */}
          {plan && activeTab === 'plan' && (
            <div className="space-y-6">
              {/* Confidence & Ambiguity Banner */}
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-4 rounded-lg bg-slate-950 border border-slate-800">
                <div className="flex items-center space-x-3">
                  <span className="text-xs font-semibold text-slate-400">AI Confidence:</span>
                  <span
                    className={`text-xs font-mono font-bold px-3 py-1 rounded border ${
                      plan.confidenceLevel === 'HIGH'
                        ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                        : plan.confidenceLevel === 'MEDIUM'
                        ? 'bg-amber-500/10 text-amber-400 border-amber-500/20'
                        : 'bg-rose-500/10 text-rose-400 border-rose-500/20'
                    }`}
                  >
                    {plan.confidenceLevel} ({Math.round(plan.confidenceScore * 100)}%)
                  </span>
                </div>

                {plan.isAmbiguous && (
                  <div className="flex items-center space-x-2 text-amber-400 text-xs bg-amber-950/40 border border-amber-800/40 px-3 py-1.5 rounded">
                    <ShieldAlert className="h-4 w-4 shrink-0" />
                    <span>{plan.ambiguityReason || 'Task is ambiguous or codebase evidence is limited.'}</span>
                  </div>
                )}
              </div>

              {/* Codebase Evidence */}
              <div className="bg-slate-950 border border-emerald-900/40 rounded-xl p-4 space-y-4">
                <div className="flex items-center justify-between border-b border-slate-800 pb-3">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-emerald-400 flex items-center gap-2">
                    <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
                    FACT / CODEBASE EVIDENCE
                  </h3>
                  <span className="text-[11px] font-mono text-slate-500">pgvector &lt;=&gt; Cosine Search</span>
                </div>

                <div className="space-y-3">
                  {plan.evidenceReferences?.map((ev, idx) => {
                    const isExpanded = !!expandedEvidence[ev.chunkId];
                    return (
                      <div key={ev.chunkId || idx} className="bg-slate-900 border border-slate-800 rounded-lg overflow-hidden">
                        <div
                          onClick={() => toggleEvidence(ev.chunkId)}
                          className="p-3 flex items-center justify-between cursor-pointer hover:bg-slate-800/50 select-none"
                        >
                          <div className="flex items-center space-x-2.5 truncate">
                            <FileCode className="h-4 w-4 text-emerald-400 shrink-0" />
                            <span className="font-mono text-xs font-semibold text-slate-200 truncate">{ev.filePath}</span>
                            {ev.symbolName && (
                              <span className="text-[11px] font-mono text-slate-400 bg-slate-800 px-2 py-0.5 rounded">
                                {ev.symbolName}
                              </span>
                            )}
                          </div>

                          <div className="flex items-center space-x-2 shrink-0">
                            <span className="text-[11px] font-mono font-semibold text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 px-2 py-0.5 rounded">
                              {(ev.similarityScore * 100).toFixed(1)}% match
                            </span>
                            {isExpanded ? <ChevronDown className="h-4 w-4 text-slate-400" /> : <ChevronRight className="h-4 w-4 text-slate-400" />}
                          </div>
                        </div>

                        {isExpanded && ev.contentSnippet && (
                          <div className="p-3.5 border-t border-slate-800 bg-slate-950 space-y-2">
                            <pre className="p-3 rounded bg-slate-900 border border-slate-800 text-xs font-mono text-slate-300 overflow-x-auto max-h-[300px]">
                              <code>{ev.contentSnippet}</code>
                            </pre>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>

              {/* Implementation Plan Details */}
              <div className="bg-slate-950 border border-indigo-900/40 rounded-xl p-5 space-y-6">
                <h3 className="text-xs font-bold uppercase tracking-wider text-indigo-400 flex items-center gap-2 border-b border-slate-800 pb-3">
                  <Sparkles className="h-4 w-4 text-indigo-400" /> AI Implementation Plan
                </h3>
                <div className="space-y-3">
                  <h4 className="text-xs font-semibold text-slate-300">Executive Summary &amp; Analysis</h4>
                  <p className="text-xs text-slate-200 bg-slate-900 p-3.5 rounded-lg border border-slate-800 leading-relaxed font-mono">
                    {plan.problemAnalysis || plan.summary}
                  </p>
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: AI PROPOSED CODE CHANGES */}
          {plan && activeTab === 'changes' && (
            <div className="space-y-6">
              {/* Mandatory Phase 9 Read-Only Safety Banner */}
              <div className="bg-amber-950/30 border border-amber-500/40 rounded-xl p-4 space-y-2">
                <div className="flex items-center space-x-2 text-amber-300 font-bold text-xs uppercase tracking-wider">
                  <Lock className="h-4 w-4 text-amber-400 shrink-0" />
                  <span>AI PROPOSED CHANGE — READ-ONLY HUMAN REVIEW</span>
                </div>
                <p className="text-xs text-amber-200/80 leading-relaxed">
                  These proposed code modifications are AI-generated based strictly on the approved Phase 8 implementation plan and codebase evidence citations.
                  <strong className="text-amber-200 font-semibold"> No files have been overwritten in the repository, no Git commits have been created, and no pull requests have been pushed to GitHub.</strong>
                </p>
              </div>

              {codeChanges.length === 0 ? (
                <div className="bg-slate-950 border border-slate-800 rounded-xl p-8 text-center space-y-4">
                  <Code2 className="h-8 w-8 text-slate-500 mx-auto" />
                  <div>
                    <h3 className="text-sm font-semibold text-slate-200">No Code Changes Generated Yet</h3>
                    <p className="text-xs text-slate-400 mt-1">
                      Click below to generate grounded proposed code diffs for this task.
                    </p>
                  </div>
                  <button
                    onClick={handleGenerateCode}
                    disabled={generatingCode}
                    className="px-5 py-2.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 disabled:bg-slate-800 text-white font-medium text-xs inline-flex items-center space-x-2 transition-colors"
                  >
                    {generatingCode ? (
                      <>
                        <RefreshCw className="h-4 w-4 animate-spin mr-1" />
                        <span>Generating Code Diffs...</span>
                      </>
                    ) : (
                      <>
                        <Code2 className="h-4 w-4 mr-1 text-emerald-300" />
                        <span>Generate Proposed Code Changes</span>
                      </>
                    )}
                  </button>
                </div>
              ) : (
                <div className="space-y-6">
                  {codeChanges.map((change) => (
                    <div key={change.id} className="bg-slate-950 border border-slate-800 rounded-xl p-5 space-y-4">
                      {/* Change metadata header */}
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-slate-800 pb-3">
                        <div className="flex items-center space-x-3">
                          <span className="font-mono text-sm font-bold text-slate-100">{change.filePath}</span>
                          <span
                            className={`px-2.5 py-0.5 text-xs font-semibold rounded font-mono ${
                              change.changeType === 'CREATE'
                                ? 'bg-green-900/60 text-green-300 border border-green-700'
                                : change.changeType === 'DELETE'
                                ? 'bg-red-900/60 text-red-300 border border-red-700'
                                : 'bg-blue-900/60 text-blue-300 border border-blue-700'
                            }`}
                          >
                            {change.changeType}
                          </span>
                        </div>

                        <div className="flex items-center space-x-2">
                          <span className="text-xs text-slate-400">Validation:</span>
                          <span
                            className={`text-xs font-mono font-semibold px-2.5 py-0.5 rounded border ${
                              change.status === 'GENERATED'
                                ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                                : 'bg-rose-500/10 text-rose-400 border-rose-500/20'
                            }`}
                          >
                            {change.status}
                          </span>
                        </div>
                      </div>

                      {change.validationFailureReason && (
                        <div className="bg-rose-950/40 border border-rose-800/40 text-rose-300 p-3 rounded-lg text-xs flex items-center space-x-2">
                          <AlertTriangle className="h-4 w-4 shrink-0 text-rose-400" />
                          <span>{change.validationFailureReason}</span>
                        </div>
                      )}

                      <div className="text-xs text-slate-300 space-y-1">
                        <span className="font-semibold text-slate-400">Reason for Change:</span>
                        <p className="font-mono bg-slate-900 p-2.5 rounded border border-slate-800">{change.reason}</p>
                      </div>

                      {/* Unified Code Diff Viewer */}
                      <CodeDiffViewer
                        filePath={change.filePath}
                        operation={change.changeType}
                        diff={change.diff}
                        oldContent={change.oldContent}
                        newContent={change.newContent}
                      />
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
