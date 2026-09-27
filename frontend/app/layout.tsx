import type { Metadata } from 'next';
import './globals.css';
import { AppShell } from '@/components/layout/AppShell';
import { AuthProvider } from '@/context/AuthContext';

export const metadata: Metadata = {
  title: 'RepoPilot — AI-Powered Software Engineering Platform',
  description: 'AI software engineering platform with codebase RAG, agentic workflows, automated testing, and GitHub PR delivery.',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body>
        <AuthProvider>
          <AppShell>{children}</AppShell>
        </AuthProvider>
      </body>
    </html>
  );
}
