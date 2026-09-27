'use client';

import React, { useEffect, useState } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';
import { handleGithubCallback } from '@/lib/github';
import { CheckCircle, AlertCircle, RefreshCw, Github } from 'lucide-react';

export default function GithubCallbackPage() {
  const searchParams = useSearchParams();
  const router = useRouter();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    const code = searchParams.get('code');
    const state = searchParams.get('state');

    if (!code || !state) {
      setError('Missing authorization code or state parameter from GitHub.');
      setLoading(false);
      return;
    }

    const processCallback = async () => {
      try {
        await handleGithubCallback(code, state);
        setSuccess(true);
        setTimeout(() => {
          router.push('/settings');
        }, 2000);
      } catch (err: any) {
        setError(err.message || 'Failed to complete GitHub authentication.');
      } finally {
        setLoading(false);
      }
    };

    processCallback();
  }, [searchParams, router]);

  return (
    <div className="min-h-screen bg-slate-950 flex items-center justify-center p-4">
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-8 max-w-md w-full text-center shadow-2xl space-y-6">
        <div className="flex justify-center">
          <div className="p-4 bg-slate-800 rounded-full text-slate-100">
            <Github className="h-10 w-10" />
          </div>
        </div>

        {loading && (
          <div className="space-y-3">
            <h2 className="text-xl font-semibold text-slate-100">Connecting GitHub Account</h2>
            <p className="text-slate-400 text-sm">Validating OAuth tokens with RepoPilot backend...</p>
            <div className="pt-2 flex justify-center">
              <RefreshCw className="h-6 w-6 animate-spin text-blue-400" />
            </div>
          </div>
        )}

        {success && (
          <div className="space-y-3">
            <div className="flex justify-center text-emerald-400">
              <CheckCircle className="h-12 w-12" />
            </div>
            <h2 className="text-xl font-semibold text-slate-100">GitHub Connected!</h2>
            <p className="text-slate-400 text-sm">Your GitHub account was successfully linked. Redirecting...</p>
          </div>
        )}

        {error && (
          <div className="space-y-4">
            <div className="flex justify-center text-rose-400">
              <AlertCircle className="h-12 w-12" />
            </div>
            <h2 className="text-xl font-semibold text-slate-100">Connection Failed</h2>
            <p className="text-rose-400 text-sm bg-rose-500/10 p-3 border border-rose-500/20 rounded-lg">{error}</p>
            <button
              onClick={() => router.push('/settings')}
              className="inline-flex items-center px-4 py-2 text-sm font-medium bg-slate-800 hover:bg-slate-700 text-slate-100 rounded-lg transition-colors"
            >
              Return to Settings
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
