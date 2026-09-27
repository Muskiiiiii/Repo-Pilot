'use client';

import React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  LayoutDashboard,
  FolderGit2,
  CheckSquare,
  GitPullRequest,
  Cpu,
  Activity,
  Settings,
} from 'lucide-react';
import { clsx } from 'clsx';

export const Sidebar: React.FC = () => {
  const pathname = usePathname();

  const navItems = [
    { name: 'Dashboard', href: '/dashboard', icon: LayoutDashboard },
    { name: 'Projects', href: '/projects', icon: FolderGit2 },
    { name: 'Tasks', href: '/tasks', icon: CheckSquare },
    { name: 'Pull Requests', href: '/pull-requests', icon: GitPullRequest },
    { name: 'AI Runs', href: '/ai-runs', icon: Cpu },
    { name: 'Activity', href: '/activity', icon: Activity },
    { name: 'Settings', href: '/settings', icon: Settings },
  ];

  return (
    <aside className="sticky top-14 h-[calc(100vh-3.5rem)] w-56 border-r border-dark-border bg-dark-bg p-3">
      <nav className="flex flex-col gap-1">
        <div className="px-2 py-1.5 text-[10px] font-bold uppercase tracking-wider text-dark-muted">
          Navigation
        </div>

        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive = pathname === item.href || pathname.startsWith(item.href + '/');

          return (
            <Link
              key={item.href}
              href={item.href}
              className={clsx(
                'flex items-center gap-3 rounded-md px-3 py-2 text-xs font-medium transition-colors',
                isActive
                  ? 'bg-brand-primary/10 text-brand-primary border-l-2 border-brand-primary font-semibold'
                  : 'text-dark-muted hover:bg-dark-surface hover:text-gray-200'
              )}
            >
              <Icon className="h-4 w-4" />
              <span>{item.name}</span>
            </Link>
          );
        })}
      </nav>

      {/* System Status Widget */}
      <div className="absolute bottom-4 left-3 right-3 rounded-lg border border-dark-border bg-dark-surface p-3 text-xs">
        <div className="flex items-center justify-between text-gray-300">
          <span className="font-medium">System Status</span>
          <span className="text-[10px] text-brand-success font-semibold">ONLINE</span>
        </div>
        <div className="mt-2 h-1.5 w-full rounded-full bg-dark-bg">
          <div className="h-1.5 w-full rounded-full bg-brand-success" />
        </div>
        <p className="mt-1 text-[10px] text-dark-muted">RepoPilot Platform Engine</p>
      </div>
    </aside>
  );
};
