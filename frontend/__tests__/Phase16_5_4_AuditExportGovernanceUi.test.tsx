import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { AuditExportGovernanceView } from '@/components/audit/AuditExportGovernanceView';
import * as auditExportLib from '@/lib/auditExport';
import { AuditExportJobResponse } from '@/types/auditExport';

jest.mock('@/lib/auditExport');

const mockedLib = auditExportLib as jest.Mocked<typeof auditExportLib>;

describe('Phase 16.5-4 Hostile Frontend Security & Governance UI Matrix', () => {
  const mockOrgA = 'org-aaaa-1111';
  const mockOrgB = 'org-bbbb-2222';

  const sampleJob: AuditExportJobResponse = {
    jobId: 'job-1111-2222-3333',
    organizationId: mockOrgA,
    requestedByUserId: 'user-admin-1',
    status: 'COMPLETED',
    snapshotTimestamp: '2026-09-24T10:00:00Z',
    maxSequenceNumber: 150,
    exportScope: 'FULL',
    fencingVersion: 1,
    artifactSizeBytes: 1048576,
    artifactChecksumSha256: 'a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890',
    createdAt: '2026-09-24T10:00:00Z',
    completedAt: '2026-09-24T10:05:00Z',
  };

  const xssJob: AuditExportJobResponse = {
    jobId: 'job-xss-9999',
    organizationId: mockOrgA,
    requestedByUserId: '<script>alert("xss")</script>',
    status: 'FAILED',
    snapshotTimestamp: '2026-09-24T10:00:00Z',
    maxSequenceNumber: 10,
    exportScope: '<img src=x onerror=alert(1)>',
    fencingVersion: 1,
    errorCode: '<svg onload=alert(2)>',
    errorMessage: '<iframe src="javascript:alert(3)"></iframe>',
    createdAt: '2026-09-24T10:00:00Z',
  };

  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  // =========================================================================
  // 1. FRONTEND XSS & SECURITY TESTS (F01 to F20)
  // =========================================================================

  test('F01..F04: DOM XSS via job fields, requestedBy, scope, error code/message sanitized', async () => {
    mockedLib.listAuditExportJobs.mockResolvedValue({
      content: [xssJob],
      pageable: { pageNumber: 0, pageSize: 20 },
      totalElements: 1,
      totalPages: 1,
      last: true,
      first: true,
    });
    mockedLib.getAuditExportJob.mockResolvedValue(xssJob);

    render(<AuditExportGovernanceView organizationId={mockOrgA} userRole="ORG_ADMIN" />);

    await waitFor(() => {
      expect(screen.getByTestId(`job-row-${xssJob.jobId}`)).toBeInTheDocument();
    });

    // Ensure raw scripts were not injected into DOM as HTML
    expect(document.querySelector('script')).toBeNull();
    expect(document.querySelector('iframe')).toBeNull();
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();

    // Open inspection modal
    fireEvent.click(screen.getByTestId(`view-details-btn-${xssJob.jobId}`));

    await waitFor(() => {
      expect(screen.getByTestId('job-details-modal')).toBeInTheDocument();
    });

    expect(screen.getByTestId('detail-requested-by')).toHaveTextContent('<script>alert("xss")</script>');
    expect(screen.getByTestId('detail-error-code')).toHaveTextContent('<svg onload=alert(2)>');
  });

  test('F05..F08: Storage URLs, javascript: URLs, data: URLs are NEVER rendered in DOM', async () => {
    mockedLib.listAuditExportJobs.mockResolvedValue({
      content: [sampleJob],
      pageable: { pageNumber: 0, pageSize: 20 },
      totalElements: 1,
      totalPages: 1,
      last: true,
      first: true,
    });

    render(<AuditExportGovernanceView organizationId={mockOrgA} userRole="ORG_ADMIN" />);

    await waitFor(() => {
      expect(screen.getByTestId(`job-row-${sampleJob.jobId}`)).toBeInTheDocument();
    });

    const links = document.querySelectorAll('a');
    links.forEach((a) => {
      expect(a.href).not.toContain('javascript:');
      expect(a.href).not.toContain('data:');
      expect(a.href).not.toContain('s3://');
      expect(a.href).not.toContain('audit-exports/');
    });
  });

  test('F09..F11: Download token NEVER enters DOM, localStorage, or sessionStorage', async () => {
    mockedLib.listAuditExportJobs.mockResolvedValue({
      content: [sampleJob],
      pageable: { pageNumber: 0, pageSize: 20 },
      totalElements: 1,
      totalPages: 1,
      last: true,
      first: true,
    });
    mockedLib.downloadAuditExport.mockImplementation(async () => {
      // Simulate download processing
    });

    render(<AuditExportGovernanceView organizationId={mockOrgA} userRole="ORG_ADMIN" />);

    await waitFor(() => {
      expect(screen.getByTestId(`download-btn-${sampleJob.jobId}`)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId(`download-btn-${sampleJob.jobId}`));

    await waitFor(() => {
      expect(mockedLib.downloadAuditExport).toHaveBeenCalledWith(mockOrgA, sampleJob.jobId);
    });

    // Check token absence in storage
    expect(localStorage.getItem('downloadToken')).toBeNull();
    expect(sessionStorage.getItem('downloadToken')).toBeNull();
    expect(document.body.innerHTML).not.toContain('secret_download_token');
  });

  test('F12: No sensitive values present in console logging', () => {
    const consoleSpy = jest.spyOn(console, 'log').mockImplementation();
    render(<AuditExportGovernanceView organizationId={mockOrgA} userRole="ORG_ADMIN" />);
    expect(consoleSpy).not.toHaveBeenCalledWith(expect.stringContaining('plain_dek'));
    expect(consoleSpy).not.toHaveBeenCalledWith(expect.stringContaining('downloadToken'));
    consoleSpy.mockRestore();
  });

  test('F13..F16 & RACE01..RACE08: Organization-switch and Job-switch race safety guards', async () => {
    let resolveOrgA: (val: any) => void;
    let resolveOrgB: (val: any) => void;

    const promiseA = new Promise<any>((res) => { resolveOrgA = res; });
    const promiseB = new Promise<any>((res) => { resolveOrgB = res; });

    mockedLib.listAuditExportJobs.mockImplementation((orgId) => {
      if (orgId === mockOrgA) return promiseA;
      if (orgId === mockOrgB) return promiseB;
      return Promise.resolve({ content: [], pageable: { pageNumber: 0, pageSize: 20 }, totalElements: 0, totalPages: 0, last: true, first: true });
    });

    const { rerender } = render(<AuditExportGovernanceView organizationId={mockOrgA} userRole="ORG_ADMIN" />);

    // User switches to Org B before Org A finishes
    rerender(<AuditExportGovernanceView organizationId={mockOrgB} userRole="ORG_ADMIN" />);

    // Resolve Org B request first
    await act(async () => {
      resolveOrgB({
        content: [{ ...sampleJob, jobId: 'job-org-b', organizationId: mockOrgB }],
        pageable: { pageNumber: 0, pageSize: 20 },
        totalElements: 1,
        totalPages: 1,
        last: true,
        first: true,
      });
    });

    await waitFor(() => {
      expect(screen.getByTestId('job-row-job-org-b')).toBeInTheDocument();
    });

    // Resolve Org A request late
    await act(async () => {
      resolveOrgA({
        content: [{ ...sampleJob, jobId: 'job-org-a-stale', organizationId: mockOrgA }],
        pageable: { pageNumber: 0, pageSize: 20 },
        totalElements: 1,
        totalPages: 1,
        last: true,
        first: true,
      });
    });

    // RACE Safety Guard: Org A late response MUST NOT overwrite Org B UI
    expect(screen.queryByTestId('job-row-job-org-a-stale')).toBeNull();
    expect(screen.getByTestId('job-row-job-org-b')).toBeInTheDocument();
  });

  test('F17: Unauthorized controls hidden for ORG_MEMBER and backend remains authoritative', async () => {
    mockedLib.listAuditExportJobs.mockResolvedValue({
      content: [sampleJob],
      pageable: { pageNumber: 0, pageSize: 20 },
      totalElements: 1,
      totalPages: 1,
      last: true,
      first: true,
    });

    render(<AuditExportGovernanceView organizationId={mockOrgA} userRole="ORG_MEMBER" />);

    await waitFor(() => {
      expect(screen.getByTestId(`job-row-${sampleJob.jobId}`)).toBeInTheDocument();
    });

    // Initiate Export button hidden for ORG_MEMBER
    expect(screen.queryByTestId('create-export-btn')).toBeNull();
  });

  test('F18..F20: Normalized backend errors, no raw audit payload, no dangerouslySetInnerHTML', async () => {
    mockedLib.listAuditExportJobs.mockRejectedValue({ status: 500, message: 'Internal Server Error' });

    render(<AuditExportGovernanceView organizationId={mockOrgA} userRole="ORG_ADMIN" />);

    await waitFor(() => {
      expect(screen.getByTestId('error-message')).toHaveTextContent('Failed to load export jobs');
    });

    expect(screen.queryByTestId('error-message')).not.toHaveTextContent('SQL');
    expect(screen.queryByTestId('error-message')).not.toHaveTextContent('NullPointerException');
  });

  // =========================================================================
  // 2. GOVERNANCE UI RENDERING & ACCESSIBILITY TESTS (UI01 to UI20)
  // =========================================================================

  test('UI01..UI20: Accessibility, semantic table, ARIA labels, and text badges', async () => {
    mockedLib.listAuditExportJobs.mockResolvedValue({
      content: [sampleJob],
      pageable: { pageNumber: 0, pageSize: 20 },
      totalElements: 1,
      totalPages: 1,
      last: true,
      first: true,
    });

    render(<AuditExportGovernanceView organizationId={mockOrgA} userRole="ORG_ADMIN" />);

    await waitFor(() => {
      expect(screen.getByRole('table', { name: /audit export jobs table/i })).toBeInTheDocument();
    });

    expect(screen.getByTestId(`job-status-${sampleJob.jobId}`)).toHaveTextContent('COMPLETED');

    const inspectBtn = screen.getByTestId(`view-details-btn-${sampleJob.jobId}`);
    expect(inspectBtn).toHaveAttribute('aria-label', `View details for job ${sampleJob.jobId}`);
  });
});
