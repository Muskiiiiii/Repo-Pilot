'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';
import { Project } from '@/types/project';
import { fetchProjects } from '@/lib/projects';
import { Search, Bell, HelpCircle, Code2, ChevronDown, LogOut, Folder, Plus } from 'lucide-react';

export const Header: React.FC = () => {
  const { user, logout } = useAuth();
  const router = useRouter();

  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProject, setSelectedProject] = useState<Project | null>(null);
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);

  useEffect(() => {
    if (user) {
      fetchProjects()
        .then((data) => {
          setProjects(data);
          if (data.length > 0) {
            setSelectedProject(data[0]);
          }
        })
        .catch((err) => console.warn('Header projects fetch error:', err));
    }
  }, [user]);

  const handleLogout = async () => {
    await logout();
    router.push('/login');
  };

  const getInitials = (name?: string) => {
    if (!name) return 'U';
    return name
      .split(' ')
      .map((n) => n[0])
      .join('')
      .toUpperCase()
      .substring(0, 2);
  };

  return (
    <header className="sticky top-0 z-30 flex h-14 w-full items-center justify-between border-b border-dark-border bg-dark-bg/95 px-4 backdrop-blur">
      {/* Brand & Project Selector */}
      <div className="flex items-center gap-4">
        <Link href="/dashboard" className="flex items-center gap-2 font-bold text-gray-100">
          <div className="flex h-7 w-7 items-center justify-center rounded-md bg-indigo-500/20 text-indigo-400 border border-indigo-500/30">
            <Code2 className="h-4 w-4" />
          </div>
          <span className="text-base tracking-tight">RepoPilot</span>
        </Link>

        <div className="h-4 w-[1px] bg-dark-border" />

        {/* Project Selector Dropdown */}
        <div className="relative">
          <button
            onClick={() => setIsDropdownOpen(!isDropdownOpen)}
            className="flex items-center gap-2 rounded-md border border-slate-800 bg-slate-900 px-2.5 py-1 text-xs font-medium text-slate-200 hover:bg-slate-800 transition-colors"
          >
            <span className="h-2 w-2 rounded-full bg-emerald-500" />
            <span className="max-w-[140px] truncate">{selectedProject ? selectedProject.name : 'Select Project'}</span>
            <ChevronDown className="h-3 w-3 text-slate-400" />
          </button>

          {isDropdownOpen && (
            <div className="absolute left-0 top-full mt-1.5 w-60 rounded-xl border border-slate-800 bg-slate-900 shadow-xl py-1 z-50">
              <div className="px-3 py-1.5 text-[10px] font-semibold text-slate-500 uppercase tracking-wider">
                Your Workspaces
              </div>
              <div className="max-h-48 overflow-y-auto divide-y divide-slate-800/40">
                {projects.length === 0 ? (
                  <div className="px-3 py-2 text-xs text-slate-400">No projects found</div>
                ) : (
                  projects.map((p) => (
                    <button
                      key={p.id}
                      onClick={() => {
                        setSelectedProject(p);
                        setIsDropdownOpen(false);
                        router.push(`/projects/${p.id}`);
                      }}
                      className={`w-full text-left px-3 py-2 text-xs flex items-center justify-between hover:bg-slate-800 transition-colors ${
                        selectedProject?.id === p.id ? 'text-indigo-400 font-semibold bg-indigo-950/20' : 'text-slate-200'
                      }`}
                    >
                      <span className="truncate">{p.name}</span>
                      <span className="text-[10px] text-slate-500 uppercase ml-2">{p.userRole}</span>
                    </button>
                  ))
                )}
              </div>
              <div className="border-t border-slate-800 pt-1">
                <Link
                  href="/projects"
                  onClick={() => setIsDropdownOpen(false)}
                  className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-indigo-400 hover:bg-slate-800 transition-colors"
                >
                  <Plus className="h-3.5 w-3.5" /> All Projects
                </Link>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Global Search Bar */}
      <div className="relative w-72 hidden md:block">
        <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-dark-muted" />
        <input
          type="text"
          placeholder="Search repository, tasks... (⌘K)"
          className="w-full rounded-md border border-dark-border bg-dark-surface py-1.5 pl-8 pr-3 text-xs text-gray-200 placeholder-dark-muted focus:border-indigo-500 focus:outline-none"
        />
      </div>

      {/* Action Icons & Profile */}
      <div className="flex items-center gap-3">
        <button className="rounded-md p-1.5 text-dark-muted hover:bg-dark-surface hover:text-gray-200" title="Notifications">
          <Bell className="h-4 w-4" />
        </button>
        <button className="rounded-md p-1.5 text-dark-muted hover:bg-dark-surface hover:text-gray-200" title="Help">
          <HelpCircle className="h-4 w-4" />
        </button>

        <div className="h-4 w-[1px] bg-dark-border" />

        {user && (
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <div className="flex h-7 w-7 items-center justify-center rounded-full bg-indigo-600/30 text-xs font-bold text-indigo-300 border border-indigo-500/30">
                {getInitials(user.name)}
              </div>
              <div className="hidden lg:flex flex-col">
                <span className="text-xs font-medium text-gray-200 leading-tight">{user.name}</span>
                <span className="text-[10px] text-slate-400 leading-tight">{user.email}</span>
              </div>
            </div>

            <button
              onClick={handleLogout}
              className="flex items-center gap-1.5 rounded-md border border-slate-800 bg-slate-900 px-2.5 py-1 text-xs font-medium text-slate-400 hover:border-red-900/50 hover:bg-red-950/30 hover:text-red-300 transition-all"
              title="Sign Out"
            >
              <LogOut className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">Logout</span>
            </button>
          </div>
        )}
      </div>
    </header>
  );
};
