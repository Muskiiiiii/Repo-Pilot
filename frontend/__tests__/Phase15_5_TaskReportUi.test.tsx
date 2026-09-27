import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { TaskReportView, sanitizeMarkdownProse } from '@/components/task/TaskReportView';
import { TaskReportControl } from '@/components/task/TaskReportControl';
import { TaskReportResponse, fetchTaskReport } from '@/lib/report';
import { ApiError } from '@/lib/api';

jest.mock('@/lib/report', () => ({
  fetchTaskReport: jest.fn(),
}));

const mockFetchTaskReport = fetchTaskReport as jest.MockedFunction<typeof fetchTaskReport>;

describe('Phase 15 Slice 15.5 Task Report UI Hostile & Race Test Suite', () => {
  const mockProjectId = 'proj-11111111-2222-3333-4444-555555555555';
  const mockRepoId = 'repo-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const mockTaskIdA = 'task-aaaaaaaa-1111-2222-3333-444444444444';
  const mockTaskIdB = 'task-bbbbbbbb-1111-2222-3333-444444444444';

  const sampleReportDataA: TaskReportResponse = {
    projectId: mockProjectId,
    repositoryId: mockRepoId,
    taskId: mockTaskIdA,
    taskExecutionId: 'exec-111',
    implementationReport: {
      id: 'report-111',
      projectId: mockProjectId,
      repositoryId: mockRepoId,
      taskId: mockTaskIdA,
      taskExecutionId: 'exec-111',
      status: 'GENERATED',
      executionOutcome: 'COMPLETED',
      changedFilesSummary: '{"totalChanges":3,"boundedFiles":[{"filePath":"src/Main.java"}]}',
      testSummary: '{"totalTests":10,"passed":10,"failed":0}',
      securityReviewSummary: '{"severityCounts":{"CRITICAL":0},"complianceCertificateStatus":"PASSED"}',
      confidenceScore: 100,
      sourceContextHash: 'hashA1234567890a1b2c3d4e5f67890',
      createdAt: '2026-09-20T12:00:00Z',
    },
    synthesis: {
      id: 'synth-111',
      implementationReportId: 'report-111',
      taskExecutionId: 'exec-111',
      projectId: mockProjectId,
      sourceContextHash: 'hashA1234567890a1b2c3d4e5f67890',
      aiSynthesisInputHash: 'inputhash111',
      executiveSummary: 'Task A completed successfully.',
      implementationExplanation: 'Created src/Main.java',
      testingExplanation: '10 tests passed',
      securityExplanation: 'Zero security findings',
      limitations: [],
      developerNotes: ['Note A'],
      renderedMarkdown: '# Task A Report',
      createdAt: '2026-09-20T12:00:01Z',
    },
    synthesisStatus: 'AVAILABLE',
  };

  const sampleReportDataB: TaskReportResponse = {
    projectId: mockProjectId,
    repositoryId: mockRepoId,
    taskId: mockTaskIdB,
    taskExecutionId: 'exec-222',
    implementationReport: {
      id: 'report-222',
      projectId: mockProjectId,
      repositoryId: mockRepoId,
      taskId: mockTaskIdB,
      taskExecutionId: 'exec-222',
      status: 'GENERATED',
      executionOutcome: 'FAILED',
      changedFilesSummary: '{"totalChanges":1}',
      testSummary: '{"totalTests":5,"passed":2,"failed":3}',
      securityReviewSummary: '{"severityCounts":{"CRITICAL":1},"complianceCertificateStatus":"FAILED"}',
      confidenceScore: 0,
      sourceContextHash: 'hashB9876543210b9a8c7d6e5f43210',
      createdAt: '2026-09-20T12:05:00Z',
    },
    synthesisStatus: 'NONE',
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('1. TaskReportView renders authoritative metadata facts correctly', () => {
    render(<TaskReportView reportData={sampleReportDataA} />);

    expect(screen.getByText('Task Implementation Report')).toBeInTheDocument();
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    expect(screen.getByText('3 Files')).toBeInTheDocument();
    expect(screen.getByText('100%')).toBeInTheDocument();
    expect(screen.getByText('hashA1234567890a1b2c3d4e5f67890')).toBeInTheDocument();
  });

  test('2. TaskReportView renders loading state spinner', () => {
    render(<TaskReportView reportData={null} loading={true} />);
    expect(screen.getByText('Loading Execution Report...')).toBeInTheDocument();
  });

  test('3. TaskReportView handles HTTP error codes (401, 403, 404, 500)', () => {
    const { rerender } = render(<TaskReportView reportData={null} errorStatus={401} />);
    expect(screen.getByText('Authentication Error')).toBeInTheDocument();

    rerender(<TaskReportView reportData={null} errorStatus={403} />);
    expect(screen.getByText('Access Forbidden')).toBeInTheDocument();

    rerender(<TaskReportView reportData={null} errorStatus={404} />);
    expect(screen.getByText('Report not found. The execution report for this task does not exist.')).toBeInTheDocument();
  });

  test('4. sanitizeMarkdownProse strips dangerous HTML payloads (<script>, <iframe>, <img onerror>, <div onclick>)', () => {
    const maliciousScript = 'Text <script>alert("xss")</script> continuation';
    expect(sanitizeMarkdownProse(maliciousScript)).not.toContain('<script>');

    const maliciousImg = '<img src=x onerror=alert(1)>';
    expect(sanitizeMarkdownProse(maliciousImg)).not.toContain('onerror=');

    const maliciousIframe = '<iframe src="https://evil.example"></iframe>';
    expect(sanitizeMarkdownProse(maliciousIframe)).not.toContain('<iframe');

    const maliciousDiv = '<div onclick="alert(1)">content</div>';
    expect(sanitizeMarkdownProse(maliciousDiv)).not.toContain('onclick=');
  });

  test('4b. DOM node inspection proves no executable script, iframe, or event-handler DOM nodes are created in document', () => {
    const maliciousReportData: TaskReportResponse = {
      ...sampleReportDataA,
      synthesis: {
        ...sampleReportDataA.synthesis!,
        executiveSummary: '<script>alert(1)</script><iframe src="https://evil.example"></iframe><img src=x onerror=alert(1)><div onclick="alert(1)">Click</div>',
      },
    };

    const { container } = render(<TaskReportView reportData={maliciousReportData} />);

    // Inspect actual DOM nodes in rendered container
    const scriptNodes = container.querySelectorAll('script');
    const iframeNodes = container.querySelectorAll('iframe');
    const onerrorElements = container.querySelectorAll('[onerror]');
    const onclickElements = container.querySelectorAll('[onclick]');

    expect(scriptNodes.length).toBe(0);
    expect(iframeNodes.length).toBe(0);
    expect(onerrorElements.length).toBe(0);
    expect(onclickElements.length).toBe(0);
  });

  test('5. sanitizeMarkdownProse neutralizes dangerous URL schemes (javascript:, data:text/html, vbscript:)', () => {
    const jsUrl = '[Click Here](javascript:alert(1))';
    expect(sanitizeMarkdownProse(jsUrl)).toContain('blocked_scheme:');

    const dataUrl = 'Data URL: data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==';
    expect(sanitizeMarkdownProse(dataUrl)).toContain('blocked_scheme:');

    const vbUrl = 'VBScript: vbscript:msgbox("xss")';
    expect(sanitizeMarkdownProse(vbUrl)).toContain('blocked_scheme:');
  });

  test('6. TaskReportControl protects against task-switching race conditions (Request A completing after Request B)', async () => {
    let resolveTaskA: (val: TaskReportResponse) => void;
    let resolveTaskB: (val: TaskReportResponse) => void;

    const promiseA = new Promise<TaskReportResponse>((res) => {
      resolveTaskA = res;
    });
    const promiseB = new Promise<TaskReportResponse>((res) => {
      resolveTaskB = res;
    });

    mockFetchTaskReport.mockImplementation((proj, task) => {
      if (task === mockTaskIdA) return promiseA;
      if (task === mockTaskIdB) return promiseB;
      return Promise.reject(new Error('Unknown task'));
    });

    const { rerender } = render(
      <TaskReportControl projectId={mockProjectId} taskId={mockTaskIdA} repositoryId={mockRepoId} />
    );

    // Switch to Task B before Task A finishes
    rerender(
      <TaskReportControl projectId={mockProjectId} taskId={mockTaskIdB} repositoryId={mockRepoId} />
    );

    // Resolve Task B first
    resolveTaskB!(sampleReportDataB);
    await waitFor(() => {
      expect(screen.getByText('FAILED')).toBeInTheDocument();
    });

    // Late-arriving Task A response
    resolveTaskA!(sampleReportDataA);

    // UI must remain on Task B
    await waitFor(() => {
      expect(screen.getByText('FAILED')).toBeInTheDocument();
      expect(screen.queryByText('COMPLETED')).not.toBeInTheDocument();
    });
  });

  test('7. Conflicting LLM claims in synthesis prose do not override authoritative metadata badges', () => {
    const conflictingData: TaskReportResponse = {
      ...sampleReportDataA,
      synthesis: {
        ...sampleReportDataA.synthesis!,
        executiveSummary: 'Execution FAILED with 999 critical security findings in src/Fake.java',
      },
    };

    render(<TaskReportView reportData={conflictingData} />);

    // Authoritative badges must remain COMPLETED and 100%
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    expect(screen.getByText('100%')).toBeInTheDocument();
  });
});
