import React from 'react';
import { render, screen } from '@testing-library/react';
import { PublicationAuditTimeline } from '@/components/approval/PublicationAuditTimeline';
import { PublicationAuditEventResponseDto } from '@/types/audit';
import { fetchTaskAuditTrail, fetchCodeChangeAuditTrail } from '@/lib/audit';
import * as apiModule from '@/lib/api';

jest.mock('@/lib/api', () => ({
  apiFetch: jest.fn(),
}));

const mockApiFetch = apiModule.apiFetch as jest.MockedFunction<typeof apiModule.apiFetch>;

describe('Phase 12.2-F-G Publication Audit UI Test Suite', () => {
  const projectId = 'proj-1111';
  const repositoryId = 'repo-2222';
  const taskId = 'task-3333';
  const codeChangeId = 'change-4444';

  const sampleEvents: PublicationAuditEventResponseDto[] = [
    {
      id: 'evt-1',
      eventSequence: 1,
      eventTimestamp: '2026-09-16T04:00:00Z',
      projectId,
      repositoryId,
      taskId,
      codeChangeId,
      approvalId: 'app-1',
      actorUserId: 'user-1',
      actorEmail: 'lead@repopilot.io',
      actorRole: 'ADMIN',
      eventType: 'APPROVAL_CREATED',
      previousState: 'UNAPPROVED',
      resultingState: 'APPROVED',
      commitSha: '1234567890abcdef1234567890abcdef12345678',
      summary: 'Human approval granted for code change',
    },
    {
      id: 'evt-2',
      eventSequence: 2,
      eventTimestamp: '2026-09-16T04:01:00Z',
      projectId,
      repositoryId,
      taskId,
      codeChangeId,
      approvalId: 'app-1',
      publicationId: 'pub-1',
      actorUserId: 'user-1',
      actorEmail: 'lead@repopilot.io',
      actorRole: 'ADMIN',
      eventType: 'PR_CREATION_SUCCEEDED',
      previousState: 'PR_CREATING',
      resultingState: 'PR_CREATED',
      commitSha: 'fedcba0987654321fedcba0987654321fedcba09',
      githubPrNumber: 42,
      prUrl: 'https://github.com/org/repo/pull/42',
      outcomeCategory: 'SUCCESS',
      summary: 'GitHub Pull Request #42 created successfully',
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
  });

  // ==========================================
  // 1. TIMELINE RENDERING & STATES
  // ==========================================
  describe('Timeline Rendering & UI States', () => {
    test('1. loading state renders spinner and text', () => {
      render(<PublicationAuditTimeline events={[]} loading={true} />);
      expect(screen.getByText('Loading Security Audit Trail...')).toBeInTheDocument();
    });

    test('2. error state renders error message', () => {
      render(<PublicationAuditTimeline events={[]} error="Failed to fetch audit events" />);
      expect(screen.getByText('Failed to fetch audit events')).toBeInTheDocument();
    });

    test('3. empty state renders empty message', () => {
      render(<PublicationAuditTimeline events={[]} />);
      expect(screen.getByText('No audit events recorded for this task execution yet.')).toBeInTheDocument();
    });

    test('4. event list renders events in sequence with badges', () => {
      render(<PublicationAuditTimeline events={sampleEvents} />);
      expect(screen.getByText('Publication & Security Audit Trail')).toBeInTheDocument();
      expect(screen.getByText('APPROVAL_CREATED')).toBeInTheDocument();
      expect(screen.getByText('PR_CREATION_SUCCEEDED')).toBeInTheDocument();
      expect(screen.getByText('#1')).toBeInTheDocument();
      expect(screen.getByText('#2')).toBeInTheDocument();
    });

    test('5. valid PR URL renders as clickable link', () => {
      render(<PublicationAuditTimeline events={sampleEvents} />);
      const link = screen.getByRole('link', { name: /Link/i });
      expect(link).toHaveAttribute('href', 'https://github.com/org/repo/pull/42');
      expect(link).toHaveAttribute('target', '_blank');
    });

    test('6. unsafe PR URL renders as plain text without link', () => {
      const unsafeEvents: PublicationAuditEventResponseDto[] = [
        {
          ...sampleEvents[1],
          prUrl: 'javascript:alert("hacked")',
        },
      ];

      render(<PublicationAuditTimeline events={unsafeEvents} />);
      expect(screen.queryByRole('link', { name: /Link/i })).toBeNull();
      expect(screen.getByText('#42')).toBeInTheDocument();
    });

    test('7. XSS payloads in event summary or actor email render strictly as text', () => {
      const xssEvents: PublicationAuditEventResponseDto[] = [
        {
          ...sampleEvents[0],
          summary: '<script>alert("XSS Audit Summary")</script>',
          actorEmail: '<img src=x onerror=alert("XSS Actor")>',
        },
      ];

      render(<PublicationAuditTimeline events={xssEvents} />);
      expect(screen.getByText('<script>alert("XSS Audit Summary")</script>')).toBeInTheDocument();
      expect(document.querySelector('script[src]')).toBeNull();
    });
  });

  // ==========================================
  // 2. FRONTEND API HELPER TESTS
  // ==========================================
  describe('Frontend Audit API Helpers', () => {
    test('fetchTaskAuditTrail invokes apiFetch with correct endpoint', async () => {
      mockApiFetch.mockResolvedValue(sampleEvents);

      const result = await fetchTaskAuditTrail(projectId, repositoryId, taskId);

      expect(mockApiFetch).toHaveBeenCalledWith(
        `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/audit`
      );
      expect(result).toEqual(sampleEvents);
    });

    test('fetchCodeChangeAuditTrail invokes apiFetch with correct endpoint', async () => {
      mockApiFetch.mockResolvedValue(sampleEvents);

      const result = await fetchCodeChangeAuditTrail(projectId, repositoryId, taskId, codeChangeId);

      expect(mockApiFetch).toHaveBeenCalledWith(
        `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/code-changes/${codeChangeId}/audit`
      );
      expect(result).toEqual(sampleEvents);
    });
  });
});
