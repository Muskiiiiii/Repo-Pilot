import React from 'react';
import { clsx } from 'clsx';

interface BadgeProps {
  children: React.ReactNode;
  variant?: 'default' | 'success' | 'warning' | 'danger' | 'purple' | 'outline';
  size?: 'sm' | 'md';
}

export const Badge: React.FC<BadgeProps> = ({
  children,
  variant = 'default',
  size = 'md',
}) => {
  const variantStyles = {
    default: 'bg-dark-border text-gray-300 border-transparent',
    success: 'bg-brand-success/15 text-brand-success border-brand-success/30',
    warning: 'bg-brand-warning/15 text-brand-warning border-brand-warning/30',
    danger: 'bg-brand-danger/15 text-brand-danger border-brand-danger/30',
    purple: 'bg-brand-accent/15 text-brand-accent border-brand-accent/30',
    outline: 'bg-transparent text-gray-400 border-dark-border',
  };

  const sizeStyles = {
    sm: 'px-2 py-0.5 text-xs',
    md: 'px-2.5 py-1 text-xs font-medium',
  };

  return (
    <span
      className={clsx(
        'inline-flex items-center rounded-md border',
        variantStyles[variant],
        sizeStyles[size]
      )}
    >
      {children}
    </span>
  );
};
