import React from 'react';
import { clsx } from 'clsx';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline' | 'danger' | 'ghost';
  size?: 'sm' | 'md' | 'lg';
  isLoading?: boolean;
}

export const Button: React.FC<ButtonProps> = ({
  children,
  variant = 'primary',
  size = 'md',
  isLoading = false,
  className,
  disabled,
  ...props
}) => {
  const variantStyles = {
    primary: 'bg-brand-primary text-gray-900 font-semibold hover:bg-brand-primary/90 focus:ring-brand-primary/50',
    secondary: 'bg-dark-surface text-gray-200 border border-dark-border hover:bg-dark-hover focus:ring-dark-border',
    outline: 'bg-transparent text-gray-300 border border-dark-border hover:bg-dark-surface focus:ring-dark-border',
    danger: 'bg-brand-danger text-white hover:bg-brand-danger/90 focus:ring-brand-danger/50',
    ghost: 'bg-transparent text-gray-400 hover:text-gray-100 hover:bg-dark-hover',
  };

  const sizeStyles = {
    sm: 'px-2.5 py-1.5 text-xs rounded-md',
    md: 'px-4 py-2 text-sm rounded-md',
    lg: 'px-5 py-2.5 text-base rounded-lg',
  };

  return (
    <button
      disabled={disabled || isLoading}
      className={clsx(
        'inline-flex items-center justify-center font-medium transition-colors focus:outline-none focus:ring-2 disabled:opacity-50 disabled:cursor-not-allowed',
        variantStyles[variant],
        sizeStyles[size],
        className
      )}
      {...props}
    >
      {isLoading ? (
        <span className="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
      ) : null}
      {children}
    </button>
  );
};
