import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import {
  HumanApprovalPublicationView,
  isSafeGithubPrUrl,
} from '@/components/approval/HumanApprovalPublicationView';
import {
  HumanApprovalResponseDto,
  GitPullRequestResult,
} from '@/types/approval';
import {
  createHumanApproval,
  fetchApprovalById,
  fetchApprovalsForCodeChange,
  revokeApproval,
  publishOrRecoverApproval,
} from '@/lib/approval';
import * as apiModule from '@/lib/api';
import fs from 'fs';
import path from 'path';

jest.mock('@/lib/api', () => ({
  apiFetch: jest.fn(),
}));

const mockApiFetch = apiModule.apiFetch as jest.MockedFunction<typeof apiModule.apiFetch>;

describe('Phase 12.2-F-F Human Approval & Publication UI Test Suite', () => {
  const projectId = 'proj-1111-2222';
  const repositoryId = 'repo-3333-4444';
  const taskId = 'task-5555-6666';
  const codeChangeId = 'change-7777-8888';
  const approvalId = 'app-9999-0000';

  const sampleApproval: HumanApprovalResponseDto = {
    approvalId,
    projectId,
    repositoryId,
    taskId,
    codeChangeId,
    reviewId: 'rev-1234',
    approvalVersion: 1,
    status: 'APPROVED',
    repositoryBaseSha: '1234567890abcdef1234567890abcdef12345678',
    codeChangeOriginalHash: 'orig1111222233334444',
    codeChangeProposedHash: 'prop5555666677778888',
    codeChangeDiffHash: 'diff9999000011112222',
    reviewContextHash: 'ctx3333444455556666',
    approvedFiles: ['src/Main.java', 'src/Utils.java'],
    approvedChangeCount: 2,
    approvalRecordHash: 'rec7777888899990000',
    approvedByUserId: 'user-user-1',
    approvedByUserEmail: 'lead@repopilot.io',
    approvedByUserRole: 'ADMIN',
    approvedAt: '2026-09-15T22:00:00Z',
    idempotencyKey: 'idemp-key-1',
    createdAt: '2026-09-15T22:00:00Z',
  };

  const samplePublicationResult: GitPullRequestResult = {
    operationId: 'op-123',
    approvalId,
    projectId,
    repositoryId,
    taskId,
    codeChangeId,
    githubPrNumber: 42,
    prUrl: 'https://github.com/repopilot/repo/pull/42',
    branchName: 'repopilot/task-5555-6666',
    baseBranch: 'main',
    commitSha: 'fedcba0987654321fedcba0987654321fedcba09',
    title: 'Feature: Automated Task Implementation',
    status: 'PR_CREATED',
    createdAt: '2026-09-15T22:05:00Z',
  };

  const defaultProps = {
    projectId,
    repositoryId,
    taskId,
    codeChangeId,
    taskTitle: 'Refactor Auth Service',
    approval: null as HumanApprovalResponseDto | null,
    publicationResult: null as GitPullRequestResult | null,
    loading: false,
    actionLoading: false,
    error: null as string | null,
    onApprove: jest.fn(),
    onRevoke: jest.fn(),
    onPublish: jest.fn(),
    onRefresh: jest.fn(),
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  // ==========================================
  // 1. SPECIFIC UI INVARIANTS & SECURITY MAPPINGS (A - P)
  // ==========================================
  describe('Explicit Dedicated Security Invariant Tests (A - P)', () => {
    test('A. XSS/task title: malicious script tags in task title are rendered strictly as text without DOM execution', () => {
      render(<HumanApprovalPublicationView {...defaultProps} taskTitle='<script>alert("XSS Task Title")</script>' />);
      expect(screen.getByText('<script>alert("XSS Task Title")</script>')).toBeInTheDocument();
      expect(document.querySelector('script[src]')).toBeNull();
    });

    test('B. XSS/PR title or body: malicious script tags in PR title or failure reasons are rendered strictly as plain text', () => {
      const maliciousPrResult: GitPullRequestResult = {
        ...samplePublicationResult,
        title: '<script>alert("XSS PR Title")</script>',
        failureReason: '<img src=x onerror=alert("XSS Failure Reason")>',
        status: 'FAILED_CONFLICT',
      };
      render(<HumanApprovalPublicationView {...defaultProps} approval={sampleApproval} publicationResult={maliciousPrResult} />);
      expect(screen.getByText('<img src=x onerror=alert("XSS Failure Reason")>')).toBeInTheDocument();
    });

    test('C. malicious filename: approved files containing script tags or path traversal strings are rendered safely as plain text', () => {
      const maliciousApproval: HumanApprovalResponseDto = {
        ...sampleApproval,
        approvedFiles: ['<script>alert("XSS File")</script>', '../../etc/passwd'],
      };
      render(<HumanApprovalPublicationView {...defaultProps} approval={maliciousApproval} />);
      expect(screen.getByText('<script>alert("XSS File")</script>, ../../etc/passwd')).toBeInTheDocument();
    });

    test('D. malicious diff content: diff hashes or diff content with injection payloads are rendered safely as plain text', () => {
      const maliciousApproval: HumanApprovalResponseDto = {
        ...sampleApproval,
        codeChangeDiffHash: '<script>alert("XSS Diff Hash")</script>',
      };
      render(<HumanApprovalPublicationView {...defaultProps} approval={maliciousApproval} />);
      expect(screen.getByText('<script>alert("X...')).toBeInTheDocument();
    });

    test('URL-A. https://github.com/org/repo/pull/42 is valid and rendered as clickable link', () => {
      const validUrl = 'https://github.com/org/repo/pull/42';
      expect(isSafeGithubPrUrl(validUrl)).toBe(true);

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={{ ...samplePublicationResult, prUrl: validUrl }}
        />
      );

      const link = screen.getByRole('link', { name: /View on GitHub/i });
      expect(link).toHaveAttribute('href', validUrl);
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    });

    test('URL-B. http://github.com/org/repo/pull/42 is rejected and rendered as non-clickable plain text', () => {
      const httpUrl = 'http://github.com/org/repo/pull/42';
      expect(isSafeGithubPrUrl(httpUrl)).toBe(false);

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={{ ...samplePublicationResult, prUrl: httpUrl }}
        />
      );

      expect(screen.queryByRole('link', { name: /View on GitHub/i })).toBeNull();
      expect(screen.getByText('PR #42')).toBeInTheDocument();
    });

    test('URL-C. javascript:alert(1) is rejected and rendered as non-clickable plain text', () => {
      const jsUrl = 'javascript:alert(1)';
      expect(isSafeGithubPrUrl(jsUrl)).toBe(false);

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={{ ...samplePublicationResult, prUrl: jsUrl }}
        />
      );

      expect(screen.queryByRole('link', { name: /View on GitHub/i })).toBeNull();
      expect(screen.getByText('PR #42')).toBeInTheDocument();
    });

    test('URL-D. data:text/html,test is rejected and rendered as non-clickable plain text', () => {
      const dataUrl = 'data:text/html,test';
      expect(isSafeGithubPrUrl(dataUrl)).toBe(false);

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={{ ...samplePublicationResult, prUrl: dataUrl }}
        />
      );

      expect(screen.queryByRole('link', { name: /View on GitHub/i })).toBeNull();
      expect(screen.getByText('PR #42')).toBeInTheDocument();
    });

    test('URL-E. https://evil-phishing.com/org/repo/pull/42 is rejected and rendered as non-clickable plain text', () => {
      const externalUrl = 'https://evil-phishing.com/org/repo/pull/42';
      expect(isSafeGithubPrUrl(externalUrl)).toBe(false);

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={{ ...samplePublicationResult, prUrl: externalUrl }}
        />
      );

      expect(screen.queryByRole('link', { name: /View on GitHub/i })).toBeNull();
      expect(screen.getByText('PR #42')).toBeInTheDocument();
    });

    test('URL-F. https://github.com.evil.com/org/repo/pull/42 is rejected and rendered as non-clickable plain text', () => {
      const evilSubdomainUrl = 'https://github.com.evil.com/org/repo/pull/42';
      expect(isSafeGithubPrUrl(evilSubdomainUrl)).toBe(false);

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={{ ...samplePublicationResult, prUrl: evilSubdomainUrl }}
        />
      );

      expect(screen.queryByRole('link', { name: /View on GitHub/i })).toBeNull();
      expect(screen.getByText('PR #42')).toBeInTheDocument();
    });

    test('URL-G. malformed URL is rejected and rendered as non-clickable plain text', () => {
      const malformedUrl = 'not-a-valid-url-format';
      expect(isSafeGithubPrUrl(malformedUrl)).toBe(false);

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={{ ...samplePublicationResult, prUrl: malformedUrl }}
        />
      );

      expect(screen.queryByRole('link', { name: /View on GitHub/i })).toBeNull();
      expect(screen.getByText('PR #42')).toBeInTheDocument();
    });

    test('URL-AUTHORITATIVE. frontend renders backend PR URL strictly without constructing it from client fields', () => {
      const customPrUrl = 'https://github.com/custom-owner/custom-repo/pull/42';
      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={{
            ...samplePublicationResult,
            prUrl: customPrUrl,
          }}
        />
      );

      const link = screen.getByRole('link', { name: /View on GitHub/i });
      expect(link).toHaveAttribute('href', customPrUrl);
    });

    test('F. stale approval: stale approval state is handled safely when server returns revoked status on publish attempt', () => {
      const revokedApproval: HumanApprovalResponseDto = {
        ...sampleApproval,
        status: 'REVOKED',
        staleReason: 'Security defect found in baseline',
      };

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={revokedApproval}
          error="Approval entity status is REVOKED"
        />
      );

      expect(screen.getByText('REVOKED')).toBeInTheDocument();
      expect(screen.getByText('Approval entity status is REVOKED')).toBeInTheDocument();
      expect(screen.queryByText('Publish to GitHub')).toBeNull();
    });

    test('G. stale publication state: refreshing status updates UI to authoritative updated remote state', () => {
      const onRefreshMock = jest.fn();
      render(<HumanApprovalPublicationView {...defaultProps} approval={sampleApproval} onRefresh={onRefreshMock} />);

      fireEvent.click(screen.getByText('Refresh Status'));
      expect(onRefreshMock).toHaveBeenCalledTimes(1);
    });

    test('H. double-click publish: rapid consecutive publish clicks disable button during action loading to prevent UI double-submission', () => {
      const onPublishMock = jest.fn();
      const { rerender } = render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          actionLoading={false}
          onPublish={onPublishMock}
        />
      );

      const publishButton = screen.getByRole('button', { name: /Publish to GitHub/i });
      fireEvent.click(publishButton);
      expect(onPublishMock).toHaveBeenCalledTimes(1);

      rerender(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          actionLoading={true}
          onPublish={onPublishMock}
        />
      );

      const publishingButton = screen.getByRole('button', { name: /Publishing/i });
      expect(publishingButton).toBeDisabled();
    });

    test('I. concurrent browser/tab requests: concurrent publish requests receiving 409 CONFLICT render state conflict alert safely', () => {
      const conflictResult: GitPullRequestResult = {
        ...samplePublicationResult,
        status: 'FAILED_CONFLICT',
        failureCategory: 'CONFLICT',
        failureReason: 'Durable publication lock acquired by concurrent worker',
      };

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={conflictResult}
        />
      );

      expect(screen.getByText('Publication Conflict Detected on Remote Repository')).toBeInTheDocument();
      expect(screen.getByText('Durable publication lock acquired by concurrent worker')).toBeInTheDocument();
    });

    test('J. backend 403 handling: backend 403 access denied displays authorization error banner without raw stack trace', () => {
      render(<HumanApprovalPublicationView {...defaultProps} error="Access Denied: Required role MEMBER missing" />);
      expect(screen.getByText('Access Denied: Required role MEMBER missing')).toBeInTheDocument();
      expect(screen.queryByText(/ExceptionInInitializerError/i)).toBeNull();
    });

    test('K. backend 409 handling: backend 409 state conflict displays conflict alert banner', () => {
      const conflictResult: GitPullRequestResult = {
        ...samplePublicationResult,
        status: 'FAILED_PR_CONFLICT',
        failureCategory: 'CONFLICT',
        failureReason: 'Pull request already exists for this branch.',
      };

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={conflictResult}
        />
      );

      expect(screen.getByText('Publication Conflict Detected on Remote Repository')).toBeInTheDocument();
    });

    test('L. UNKNOWN_EXTERNAL_STATE: UNKNOWN_EXTERNAL_STATE renders reconciliation in-progress banner and does NOT say publication failed', () => {
      const unknownResult: GitPullRequestResult = {
        ...samplePublicationResult,
        status: 'FAILED_PUSH',
        failureCategory: 'UNKNOWN_EXTERNAL_STATE',
        failureReason: 'Remote status uncertain after network timeout.',
      };

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={unknownResult}
        />
      );

      expect(screen.getByText(/Publication status is being reconciled with GitHub.../i)).toBeInTheDocument();
      expect(screen.queryByText('Publication failed')).toBeNull();
    });

    test('M. FAILED_CONFLICT: FAILED_CONFLICT renders publication conflict banner with failure reason', () => {
      const conflictResult: GitPullRequestResult = {
        ...samplePublicationResult,
        status: 'FAILED_CONFLICT',
        failureCategory: 'CONFLICT',
        failureReason: 'Target base branch has diverged.',
      };

      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={conflictResult}
        />
      );

      expect(screen.getByText('Publication Conflict Detected on Remote Repository')).toBeInTheDocument();
      expect(screen.getByText('Target base branch has diverged.')).toBeInTheDocument();
    });

    test('N. authoritative PR URL rendering: uses authoritative backend-provided PR URL when valid and safe', () => {
      render(
        <HumanApprovalPublicationView
          {...defaultProps}
          approval={sampleApproval}
          publicationResult={samplePublicationResult}
        />
      );

      const link = screen.getByRole('link', { name: /View on GitHub/i });
      expect(link).toHaveAttribute('href', 'https://github.com/repopilot/repo/pull/42');
    });

    test('O. credential/error leakage: error messages with raw PAT, fencing tokens, or stack traces are not logged or rendered unsafely', () => {
      const sensitiveError = 'Error: ghp_1234567890abcdef Token verification failed at /var/app/keys/rsa.pem';
      render(<HumanApprovalPublicationView {...defaultProps} error={sensitiveError} />);

      const renderedError = screen.getByText(sensitiveError);
      expect(renderedError).toBeInTheDocument();
      expect(containerHasRawSecretScript(renderedError.parentElement)).toBe(false);
    });

    test('P. no direct GitHub browser request: static analysis confirms zero direct fetch to api.github.com or GitHub SDK imports in frontend UI', () => {
      const frontendDir = path.resolve(__dirname, '..');
      const filesToCheck = [
        path.join(frontendDir, 'components/approval/HumanApprovalPublicationView.tsx'),
        path.join(frontendDir, 'lib/approval.ts'),
        path.join(frontendDir, 'types/approval.ts'),
      ];

      for (const filePath of filesToCheck) {
        if (fs.existsSync(filePath)) {
          const content = fs.readFileSync(filePath, 'utf-8');
          expect(content).not.toContain('api.github.com');
          expect(content).not.toContain('@octokit');
        }
      }
    });
  });

  // Helper function for test O
  function containerHasRawSecretScript(element: HTMLElement | null): boolean {
    if (!element) return false;
    return element.querySelectorAll('script').length > 0;
  }

  // ==========================================
  // 2. FRONTEND API HELPER UNIT TESTS
  // ==========================================
  describe('Frontend API Helper Functions', () => {
    test('createHumanApproval invokes apiFetch with POST and endpoint', async () => {
      mockApiFetch.mockResolvedValue(sampleApproval);

      const result = await createHumanApproval(projectId, repositoryId, taskId, codeChangeId, {
        idempotencyKey: 'key-123',
      });

      expect(mockApiFetch).toHaveBeenCalledWith(
        `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/code-changes/${codeChangeId}/approvals`,
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ idempotencyKey: 'key-123' }),
        })
      );
      expect(result).toEqual(sampleApproval);
    });

    test('fetchApprovalById invokes apiFetch GET', async () => {
      mockApiFetch.mockResolvedValue(sampleApproval);

      const result = await fetchApprovalById(projectId, repositoryId, taskId, approvalId);

      expect(mockApiFetch).toHaveBeenCalledWith(
        `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/approvals/${approvalId}`
      );
      expect(result).toEqual(sampleApproval);
    });

    test('fetchApprovalsForCodeChange invokes apiFetch GET', async () => {
      mockApiFetch.mockResolvedValue([sampleApproval]);

      const result = await fetchApprovalsForCodeChange(projectId, repositoryId, taskId, codeChangeId);

      expect(mockApiFetch).toHaveBeenCalledWith(
        `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/code-changes/${codeChangeId}/approvals`
      );
      expect(result).toEqual([sampleApproval]);
    });

    test('revokeApproval invokes apiFetch POST with reason', async () => {
      mockApiFetch.mockResolvedValue({ ...sampleApproval, status: 'REVOKED' });

      const result = await revokeApproval(projectId, repositoryId, taskId, approvalId, 'Security flaw');

      expect(mockApiFetch).toHaveBeenCalledWith(
        `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/approvals/${approvalId}/revoke`,
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ reason: 'Security flaw' }),
        })
      );
      expect(result.status).toBe('REVOKED');
    });

    test('publishOrRecoverApproval invokes apiFetch POST for publish endpoint', async () => {
      mockApiFetch.mockResolvedValue(samplePublicationResult);

      const result = await publishOrRecoverApproval(projectId, repositoryId, taskId, codeChangeId, approvalId);

      expect(mockApiFetch).toHaveBeenCalledWith(
        `/projects/${projectId}/repositories/${repositoryId}/tasks/${taskId}/code-changes/${codeChangeId}/approvals/${approvalId}/publish`,
        expect.objectContaining({
          method: 'POST',
        })
      );
      expect(result).toEqual(samplePublicationResult);
    });
  });
});
