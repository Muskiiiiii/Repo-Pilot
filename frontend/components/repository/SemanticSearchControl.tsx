'use client';

import React, { useState } from 'react';
import { SearchResultItem, searchSemanticCode } from '@/lib/search';
import {
  Search,
  Sparkles,
  FileCode,
  ChevronDown,
  ChevronRight,
  AlertCircle,
  RefreshCw,
  Sliders,
  CheckCircle2,
  Code2,
} from 'lucide-react';

interface SemanticSearchControlProps {
  projectId: string;
  repositoryId: string;
  isIndexed: boolean;
}

export function SemanticSearchControl({
  projectId,
  repositoryId,
  isIndexed,
}: SemanticSearchControlProps) {
  const [query, setQuery] = useState('');
  const [topK, setTopK] = useState(10);
  const [results, setResults] = useState<SearchResultItem[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedChunks, setExpandedChunks] = useState<Record<string, boolean>>({});

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) return;

    setLoading(true);
    setError(null);

    try {
      const response = await searchSemanticCode(projectId, repositoryId, query.trim(), topK);
      setResults(response.results);
      // Auto-expand top 3 results
      const initialExpanded: Record<string, boolean> = {};
      response.results.slice(0, 3).forEach((item) => {
        initialExpanded[item.chunkId] = true;
      });
      setExpandedChunks(initialExpanded);
    } catch (err: any) {
      setError(err.message || 'Failed to execute semantic search.');
      setResults(null);
    } finally {
      setLoading(false);
    }
  };

  const toggleExpand = (chunkId: string) => {
    setExpandedChunks((prev) => ({
      ...prev,
      [chunkId]: !prev[chunkId],
    }));
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-slate-800 pb-4">
        <div className="flex items-center space-x-2.5">
          <div className="p-2 rounded-lg bg-blue-500/10 border border-blue-500/20 text-blue-400">
            <Sparkles className="h-5 w-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
              Semantic Code Search
              <span className="text-[10px] font-semibold bg-blue-500/15 text-blue-300 border border-blue-500/30 px-2 py-0.5 rounded-full uppercase tracking-wider">
                1024d Vector RAG
              </span>
            </h2>
            <p className="text-xs text-slate-400">
              Query your indexed codebase using natural language concepts and intent
            </p>
          </div>
        </div>
      </div>

      {!isIndexed && (
        <div className="flex items-center space-x-3 rounded-lg bg-amber-950/40 border border-amber-800/40 p-4 text-amber-300 text-xs">
          <AlertCircle className="h-4 w-4 shrink-0 text-amber-400" />
          <span>
            Repository is not indexed yet. Please trigger codebase indexing above to enable semantic search.
          </span>
        </div>
      )}

      {/* Search Input Bar */}
      <form onSubmit={handleSearch} className="space-y-3">
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
          <div className="relative flex-1">
            <Search className="absolute left-3.5 top-3 h-4 w-4 text-slate-400" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="e.g. How does user authentication work? or Where is data formatted?"
              disabled={!isIndexed || loading}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg pl-10 pr-4 py-2.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-blue-500 font-mono disabled:opacity-50"
            />
          </div>

          <div className="flex items-center space-x-2">
            <div className="flex items-center space-x-1.5 bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-xs text-slate-300">
              <Sliders className="h-3.5 w-3.5 text-slate-400" />
              <span className="text-slate-400">topK:</span>
              <select
                value={topK}
                onChange={(e) => setTopK(Number(e.target.value))}
                disabled={!isIndexed || loading}
                className="bg-transparent font-mono text-slate-200 focus:outline-none"
              >
                <option value={5} className="bg-slate-900">5</option>
                <option value={10} className="bg-slate-900">10</option>
                <option value={20} className="bg-slate-900">20</option>
                <option value={50} className="bg-slate-900">50</option>
              </select>
            </div>

            <button
              type="submit"
              disabled={!isIndexed || !query.trim() || loading}
              className="px-5 py-2.5 rounded-lg bg-blue-600 hover:bg-blue-500 disabled:bg-slate-800 disabled:text-slate-500 text-white font-medium text-sm flex items-center justify-center space-x-2 transition-colors shrink-0"
            >
              {loading ? (
                <>
                  <RefreshCw className="h-4 w-4 animate-spin mr-1" />
                  <span>Searching...</span>
                </>
              ) : (
                <>
                  <Search className="h-4 w-4 mr-1" />
                  <span>Search</span>
                </>
              )}
            </button>
          </div>
        </div>
      </form>

      {/* Error Message */}
      {error && (
        <div className="flex items-center space-x-2 text-rose-400 text-xs bg-rose-950/40 border border-rose-800/40 p-3 rounded-lg">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* Results Container */}
      {results && (
        <div className="space-y-4 pt-2">
          <div className="flex items-center justify-between text-xs text-slate-400 border-b border-slate-800 pb-2">
            <span>
              Found <strong className="text-slate-200">{results.length}</strong> ranked semantic chunk match(es)
            </span>
            <span className="font-mono text-slate-500">pgvector &lt;=&gt; Cosine Distance</span>
          </div>

          {results.length === 0 ? (
            <div className="text-center py-10 text-slate-500 text-sm">
              No matching code chunks found for query &quot;{query}&quot;.
            </div>
          ) : (
            <div className="space-y-3">
              {results.map((item, idx) => {
                const isExpanded = !!expandedChunks[item.chunkId];
                const similarityPct = (item.similarityScore * 100).toFixed(1);

                return (
                  <div
                    key={item.chunkId || idx}
                    className="bg-slate-950 border border-slate-800/80 rounded-lg overflow-hidden transition-colors hover:border-slate-700"
                  >
                    {/* Item Header Bar */}
                    <div
                      onClick={() => toggleExpand(item.chunkId)}
                      className="p-3.5 flex flex-col sm:flex-row sm:items-center justify-between gap-2 cursor-pointer bg-slate-900/60 hover:bg-slate-800/40 select-none"
                    >
                      <div className="flex items-center space-x-3 truncate">
                        <span className="flex items-center justify-center h-6 w-6 rounded text-xs font-mono font-bold bg-slate-800 text-blue-400 shrink-0">
                          #{idx + 1}
                        </span>
                        <FileCode className="h-4 w-4 text-blue-400 shrink-0" />
                        <span className="font-mono text-xs font-semibold text-slate-200 truncate">
                          {item.filePath}
                        </span>
                        {item.symbolName && (
                          <span className="hidden md:inline-flex text-[11px] font-mono text-slate-400 bg-slate-800/80 px-2 py-0.5 rounded truncate">
                            {item.symbolName}
                          </span>
                        )}
                      </div>

                      <div className="flex items-center space-x-2 shrink-0">
                        {item.chunkType && (
                          <span className="text-[10px] uppercase tracking-wider font-semibold text-slate-400 bg-slate-800 px-2 py-0.5 rounded">
                            {item.chunkType}
                          </span>
                        )}
                        <span className="text-[11px] font-mono text-slate-400">
                          L{item.startLine}–L{item.endLine}
                        </span>
                        <span className="text-[11px] font-mono font-semibold text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 px-2.5 py-0.5 rounded">
                          {similarityPct}% match
                        </span>
                        {isExpanded ? (
                          <ChevronDown className="h-4 w-4 text-slate-400" />
                        ) : (
                          <ChevronRight className="h-4 w-4 text-slate-400" />
                        )}
                      </div>
                    </div>

                    {/* Expandable Code Preview */}
                    {isExpanded && (
                      <div className="p-4 border-t border-slate-800/60 bg-slate-950">
                        {item.symbolName && (
                          <div className="mb-2 text-xs font-mono text-slate-400 flex items-center space-x-2">
                            <Code2 className="h-3.5 w-3.5 text-blue-400" />
                            <span>Symbol: <strong className="text-slate-200">{item.symbolName}</strong></span>
                          </div>
                        )}
                        <pre className="p-3.5 rounded bg-slate-900 border border-slate-800 overflow-x-auto text-xs font-mono text-slate-300 max-h-[350px]">
                          <code>{item.content}</code>
                        </pre>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
