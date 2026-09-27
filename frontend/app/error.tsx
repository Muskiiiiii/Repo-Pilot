'use client';

import React from 'react';
import { Button } from '@/components/ui/Button';
import { AlertTriangle } from 'lucide-react';

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="flex h-64 w-full items-center justify-center">
      <div className="flex flex-col items-center gap-4 text-center max-w-md">
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-danger/10 text-brand-danger">
          <AlertTriangle className="h-6 w-6" />
        </div>
        <div>
          <h2 className="text-base font-bold text-gray-100">Something went wrong</h2>
          <p className="mt-1 text-xs text-dark-muted">{error.message || 'An unexpected error occurred in the UI.'}</p>
        </div>
        <Button size="sm" onClick={() => reset()}>
          Try Again
        </Button>
      </div>
    </div>
  );
}
