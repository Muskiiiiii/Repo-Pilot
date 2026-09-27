import React from 'react';

export default function Loading() {
  return (
    <div className="flex h-64 w-full items-center justify-center">
      <div className="flex flex-col items-center gap-3">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-brand-primary border-t-transparent" />
        <p className="text-xs text-dark-muted font-medium">Loading RepoPilot workspace...</p>
      </div>
    </div>
  );
}
