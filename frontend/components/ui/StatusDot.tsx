import React from 'react';
import { clsx } from 'clsx';

export type StatusType = 'idle' | 'running' | 'waiting' | 'success' | 'warning' | 'error';

interface StatusDotProps {
  status: StatusType;
  label?: string;
  pulse?: boolean;
}

export const StatusDot: React.FC<StatusDotProps> = ({ status, label, pulse = false }) => {
  const colorMap: Record<StatusType, string> = {
    idle: 'bg-gray-400',
    running: 'bg-brand-primary',
    waiting: 'bg-brand-warning',
    success: 'bg-brand-success',
    warning: 'bg-brand-warning',
    error: 'bg-brand-danger',
  };

  return (
    <div className="inline-flex items-center gap-2 text-sm text-gray-300">
      <span className="relative flex h-2.5 w-2.5">
        {(pulse || status === 'running') && (
          <span
            className={clsx(
              'absolute inline-flex h-full w-full animate-ping rounded-full opacity-75',
              colorMap[status]
            )}
          />
        )}
        <span className={clsx('relative inline-flex h-2.5 w-2.5 rounded-full', colorMap[status])} />
      </span>
      {label && <span className="capitalize">{label}</span>}
    </div>
  );
};
