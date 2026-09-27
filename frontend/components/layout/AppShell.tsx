'use client';

import React from 'react';
import { usePathname } from 'next/navigation';
import { Header } from './Header';
import { Sidebar } from './Sidebar';
import { AuthGuard } from '@/components/auth/AuthGuard';

interface AppShellProps {
  children: React.ReactNode;
}

export const AppShell: React.FC<AppShellProps> = ({ children }) => {
  const pathname = usePathname();
  const isAuthPage = pathname === '/login' || pathname === '/register';

  if (isAuthPage) {
    return <main className="min-h-screen bg-slate-950 text-gray-100">{children}</main>;
  }

  return (
    <AuthGuard>
      <div className="min-h-screen bg-dark-bg text-gray-100 dark">
        <Header />
        <div className="flex">
          <Sidebar />
          <main className="flex-1 p-6 overflow-y-auto">
            {children}
          </main>
        </div>
      </div>
    </AuthGuard>
  );
};
