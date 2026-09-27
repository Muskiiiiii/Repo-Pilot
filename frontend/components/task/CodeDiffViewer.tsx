'use client';

import React, { useState } from 'react';

interface CodeDiffViewerProps {
  filePath: string;
  operation: string;
  diff: string;
  oldContent?: string;
  newContent?: string;
}

export const CodeDiffViewer: React.FC<CodeDiffViewerProps> = ({
  filePath,
  operation,
  diff,
  oldContent,
  newContent,
}) => {
  const [viewMode, setViewMode] = useState<'diff' | 'original' | 'proposed'>('diff');

  const diffLines = (diff || '').split('\n');

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-950 overflow-hidden font-mono text-xs my-3">
      {/* Header controls */}
      <div className="flex items-center justify-between bg-zinc-900 px-4 py-2 border-b border-zinc-800 font-sans text-sm">
        <div className="flex items-center space-x-2 font-mono">
          <span className="font-semibold text-zinc-200">{filePath}</span>
          <span
            className={`px-2 py-0.5 text-xs font-semibold rounded ${
              operation === 'CREATE'
                ? 'bg-green-900/60 text-green-300 border border-green-700'
                : operation === 'DELETE'
                ? 'bg-red-900/60 text-red-300 border border-red-700'
                : 'bg-blue-900/60 text-blue-300 border border-blue-700'
            }`}
          >
            {operation}
          </span>
        </div>

        <div className="flex space-x-1 bg-zinc-950 p-1 rounded border border-zinc-800 text-xs">
          <button
            onClick={() => setViewMode('diff')}
            className={`px-2.5 py-1 rounded transition-colors ${
              viewMode === 'diff' ? 'bg-zinc-800 text-white font-medium' : 'text-zinc-400 hover:text-zinc-200'
            }`}
          >
            Unified Diff
          </button>
          <button
            onClick={() => setViewMode('original')}
            className={`px-2.5 py-1 rounded transition-colors ${
              viewMode === 'original' ? 'bg-zinc-800 text-white font-medium' : 'text-zinc-400 hover:text-zinc-200'
            }`}
          >
            Original ({oldContent ? oldContent.split('\n').length : 0} lines)
          </button>
          <button
            onClick={() => setViewMode('proposed')}
            className={`px-2.5 py-1 rounded transition-colors ${
              viewMode === 'proposed' ? 'bg-zinc-800 text-white font-medium' : 'text-zinc-400 hover:text-zinc-200'
            }`}
          >
            Proposed ({newContent ? newContent.split('\n').length : 0} lines)
          </button>
        </div>
      </div>

      {/* Content View */}
      {viewMode === 'diff' && (
        <div className="overflow-x-auto max-h-96 p-2 text-xs">
          {diffLines.length === 0 || (diffLines.length === 1 && !diffLines[0]) ? (
            <div className="p-4 text-zinc-500 italic">No content changes detected.</div>
          ) : (
            diffLines.map((line, idx) => {
              if (line.startsWith('---') || line.startsWith('+++')) {
                return (
                  <div key={idx} className="text-zinc-500 font-semibold py-0.5 select-none">
                    {line}
                  </div>
                );
              }
              if (line.startsWith('@@')) {
                return (
                  <div key={idx} className="bg-zinc-900 text-cyan-400 py-1 px-2 font-medium select-none border-y border-zinc-800/80">
                    {line}
                  </div>
                );
              }
              if (line.startsWith('+')) {
                return (
                  <div key={idx} className="bg-green-950/40 text-green-300 font-mono py-0.5 px-2 flex whitespace-pre">
                    <span className="w-6 shrink-0 text-green-600 select-none text-right pr-2">+</span>
                    <span>{line.substring(1)}</span>
                  </div>
                );
              }
              if (line.startsWith('-')) {
                return (
                  <div key={idx} className="bg-red-950/40 text-red-300 font-mono py-0.5 px-2 flex whitespace-pre">
                    <span className="w-6 shrink-0 text-red-600 select-none text-right pr-2">-</span>
                    <span>{line.substring(1)}</span>
                  </div>
                );
              }
              return (
                <div key={idx} className="text-zinc-300 font-mono py-0.5 px-2 flex whitespace-pre">
                  <span className="w-6 shrink-0 text-zinc-600 select-none text-right pr-2"> </span>
                  <span>{line.startsWith(' ') ? line.substring(1) : line}</span>
                </div>
              );
            })
          )}
        </div>
      )}

      {viewMode === 'original' && (
        <pre className="overflow-x-auto max-h-96 p-4 text-zinc-300 font-mono text-xs whitespace-pre bg-zinc-950">
          {oldContent || '(File was created in this change)'}
        </pre>
      )}

      {viewMode === 'proposed' && (
        <pre className="overflow-x-auto max-h-96 p-4 text-green-300 font-mono text-xs whitespace-pre bg-zinc-950">
          {newContent || '(File was deleted in this change)'}
        </pre>
      )}
    </div>
  );
};
