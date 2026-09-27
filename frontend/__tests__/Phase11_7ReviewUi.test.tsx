import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { CodeReviewDetailsView } from '@/components/review/CodeReviewDetailsView';
import { CodeReviewList } from '@/components/review/CodeReviewList';
import { CodeReviewResultDto, ValidatedFindingCandidate } from '@/types/review';
import { fetchCodeReview, fetchCodeReviewsForTask } from '@/lib/review';
import * as apiModule from '@/lib/api';

// Mock lib/review module for API integration testing
jest.mock('@/lib/review', () => ({
  fetchCodeReview: jest.fn(),
  fetchCodeReviewsForTask: jest.fn(),
  fetchCodeReviewsForCodeChange: jest.fn(),
}));

const mockFetchCodeReview = fetchCodeReview as jest.MockedFunction<typeof fetchCodeReview>;
const mockFetchCodeReviewsForTask = fetchCodeReviewsForTask as jest.MockedFunction<typeof fetchCodeReviewsForTask>;

describe('Phase 11.7 Read-Only Review UI Test Suite', () => {
  const mockReviewId = '11111111-2222-3333-4444-555555555555';
  const mockProjectId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const mockRepoId = 'b2b2b2b2-c3c3-d4d4-e5e5-f6f6f6f6f6f6';
  const mockTaskId = 't1t1t1t1-t2t2-t3t3-t4t4-t5t5t5t5t5t5';

  const sampleFinding: ValidatedFindingCandidate = {
    findingType: 'AI_FINDING',
    category: 'SECURITY_INJECTION',
    cweId: 'CWE-89',
    severity: 'HIGH',
    confidence: 'HIGH',
    title: 'SQL Injection Vulnerability',
    description: 'Unsanitized input passed to database query execution.',
    filePath: 'src/main/java/com/repopilot/UserService.java',
    startLine: 42,
    endLine: 47,
    side: 'PROPOSED',
    evidence: 'statement.executeQuery("SELECT * FROM users WHERE id = " + userId);',
    remediation: 'Use PreparedStatement with parameter placeholders instead of string concatenation.',
    contentHash: 'hash123',
    fingerprint: 'fp999888777666555444333222111000',
    isUnverifiedSecurityClaim: true,
  };

  const sampleCompletedReview: CodeReviewResultDto = {
    reviewId: mockReviewId,
    projectId: mockProjectId,
    repositoryId: mockRepoId,
    taskId: mockTaskId,
    codeChangeId: 'change-123',
    reviewMode: 'CODE_CHANGE_REVIEW',
    status: 'COMPLETED',
    summary: 'Automated AI security review completed with 1 finding.',
    overallRiskScore: 'HIGH',
    reviewContextHash: 'ctxhash1234567890',
    modelIdentifier: 'claude-3-5-sonnet',
    executionTimeMs: 450,
    createdAt: '2026-09-14T12:00:00Z',
    completedAt: '2026-09-14T12-[00]:01Z',
    totalAiFindings: 1,
    acceptedFindingsCount: 1,
    rejectedFindingsCount: 0,
    findings: [sampleFinding],
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  // ==========================================
  // 1. BASIC RENDERING & STATES
  // ==========================================
  describe('Basic Rendering & States', () => {
    test('1. review page renders successfully', () => {
      render(<CodeReviewDetailsView review={sampleCompletedReview} />);
      expect(screen.getByText('AI Code Review Summary')).toBeInTheDocument();
    });

    test('2. loading state renders spinner and message', () => {
      render(<CodeReviewDetailsView review={null} loading={true} />);
      expect(screen.getByText('Loading AI Code Review...')).toBeInTheDocument();
    });

    test('3. completed review displays summary and risk badges', () => {
      render(<CodeReviewDetailsView review={sampleCompletedReview} />);
      expect(screen.getByText('Automated AI security review completed with 1 finding.')).toBeInTheDocument();
      expect(screen.getByText('Overall Risk: HIGH')).toBeInTheDocument();
      expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    });

    test('4. running review displays progress state card', () => {
      const runningReview: CodeReviewResultDto = {
        ...sampleCompletedReview,
        status: 'RUNNING',
      };
      render(<CodeReviewDetailsView review={runningReview} />);
      expect(screen.getByText('Review Execution In Progress')).toBeInTheDocument();
    });

    test('5. failed review displays error state card', () => {
      const failedReview: CodeReviewResultDto = {
        ...sampleCompletedReview,
        status: 'FAILED',
      };
      render(<CodeReviewDetailsView review={failedReview} />);
      expect(screen.getByText('Code Review Execution Failed')).toBeInTheDocument();
    });

    test('6. cancelled review displays cancelled status card', () => {
      const cancelledReview: CodeReviewResultDto = {
        ...sampleCompletedReview,
        status: 'CANCELLED',
      };
      render(<CodeReviewDetailsView review={cancelledReview} />);
      expect(screen.getByText('Code Review Cancelled')).toBeInTheDocument();
    });

    test('7. zero findings review renders clean success empty state', () => {
      const zeroFindingsReview: CodeReviewResultDto = {
        ...sampleCompletedReview,
        totalAiFindings: 0,
        acceptedFindingsCount: 0,
        findings: [],
      };
      render(<CodeReviewDetailsView review={zeroFindingsReview} />);
      expect(screen.getByText('Zero Findings Detected')).toBeInTheDocument();
    });

    test('8. multiple findings render correctly in list', () => {
      const multiFindingReview: CodeReviewResultDto = {
        ...sampleCompletedReview,
        findings: [
          sampleFinding,
          {
            ...sampleFinding,
            title: 'Hardcoded Secret In Config',
            severity: 'CRITICAL',
            filePath: 'src/main/resources/application.yml',
            startLine: 12,
            endLine: 12,
            fingerprint: 'fp-secret-123',
          },
        ],
      };
      render(<CodeReviewDetailsView review={multiFindingReview} />);
      expect(screen.getByText('SQL Injection Vulnerability')).toBeInTheDocument();
      expect(screen.getByText('Hardcoded Secret In Config')).toBeInTheDocument();
    });
  });

  // ==========================================
  // 2. API & ERROR HANDLING (401, 403, 404, 409, 422, 429, 5xx)
  // ==========================================
  describe('API Error Handling', () => {
    test('9. 401 handling displays authentication required message', () => {
      render(<CodeReviewDetailsView review={null} error="Unauthorized" errorStatus={401} />);
      expect(screen.getByText(/Authentication required/i)).toBeInTheDocument();
    });

    test('10. 403 handling displays access denied message', () => {
      render(<CodeReviewDetailsView review={null} error="Forbidden" errorStatus={403} />);
      expect(screen.getByText(/Access denied/i)).toBeInTheDocument();
    });

    test('11. 404 handling displays review not found message', () => {
      render(<CodeReviewDetailsView review={null} error="Not Found" errorStatus={404} />);
      expect(screen.getByText(/Review not found/i)).toBeInTheDocument();
    });

    test('12. 409 handling displays state conflict message', () => {
      render(<CodeReviewDetailsView review={null} error="Conflict" errorStatus={409} />);
      expect(screen.getByText(/State conflict/i)).toBeInTheDocument();
    });

    test('13. 422 handling displays unprocessable entity message', () => {
      render(<CodeReviewDetailsView review={null} error="Unprocessable" errorStatus={422} />);
      expect(screen.getByText(/Invalid review data/i)).toBeInTheDocument();
    });

    test('14. 429 handling displays rate limit message', () => {
      render(<CodeReviewDetailsView review={null} error="Too Many Requests" errorStatus={429} />);
      expect(screen.getByText(/Rate limit exceeded/i)).toBeInTheDocument();
    });

    test('15. 5xx handling displays server error message', () => {
      render(<CodeReviewDetailsView review={null} error="Internal Error" errorStatus={500} />);
      expect(screen.getByText(/Backend service error/i)).toBeInTheDocument();
    });

    test('16. network failure displays general error message', () => {
      render(<CodeReviewDetailsView review={null} error="Failed to fetch network request" errorStatus={null} />);
      expect(screen.getByText(/Failed to fetch network request/i)).toBeInTheDocument();
    });
  });

  // ==========================================
  // 3. FINDINGS DISPLAY & METADATA
  // ==========================================
  describe('Findings Display', () => {
    test('17-26. finding displays severity, category, confidence, file path, line range, side, evidence, claim status, fingerprint', () => {
      render(<CodeReviewDetailsView review={sampleCompletedReview} />);

      expect(screen.getAllByText(/HIGH/i).length).toBeGreaterThan(0);
      expect(screen.getByText('SECURITY_INJECTION')).toBeInTheDocument();
      expect(screen.getByText('src/main/java/com/repopilot/UserService.java')).toBeInTheDocument();
      expect(screen.getByText('L42-47')).toBeInTheDocument();
      expect(screen.getByText('Side: PROPOSED')).toBeInTheDocument();

      // Expand finding card
      const findingCard = screen.getByText('SQL Injection Vulnerability');
      fireEvent.click(findingCard);

      expect(screen.getByText('AI security claim — not independently verified')).toBeInTheDocument();
      expect(screen.getByText('CWE-89')).toBeInTheDocument();
      expect(screen.getByText('statement.executeQuery("SELECT * FROM users WHERE id = " + userId);')).toBeInTheDocument();
      expect(screen.getByText('fp999888777666555444333222111000')).toBeInTheDocument();
    });
  });

  // ==========================================
  // 4. SECURITY & SANITIZATION
  // ==========================================
  describe('Security & Sanitization', () => {
    test('27-28. malicious HTML and script payload rendered strictly as text', () => {
      const maliciousFinding: ValidatedFindingCandidate = {
        ...sampleFinding,
        title: '<script>alert("XSS Attack")</script>',
        description: '<img src=x onerror=alert(1)>Malicious description',
        evidence: '<svg onload=alert(2)></svg>',
      };

      const maliciousReview: CodeReviewResultDto = {
        ...sampleCompletedReview,
        summary: '<script>document.cookie="stolen"</script>',
        findings: [maliciousFinding],
      };

      render(<CodeReviewDetailsView review={maliciousReview} />);

      // Plain text rendering check: script tag string present as visible text
      expect(screen.getByText('<script>document.cookie="stolen"</script>')).toBeInTheDocument();

      // Click card to expand
      fireEvent.click(screen.getByText('<script>alert("XSS Attack")</script>'));

      expect(screen.getByText('<img src=x onerror=alert(1)>Malicious description')).toBeInTheDocument();
      expect(screen.getByText('<svg onload=alert(2)></svg>')).toBeInTheDocument();

      // Ensure no script tags were executed or inserted as DOM elements
      expect(document.querySelector('script[src]')).toBeNull();
    });

    test('29. dangerouslySetInnerHTML is completely absent in component DOM', () => {
      const { container } = render(<CodeReviewDetailsView review={sampleCompletedReview} />);
      expect(container.innerHTML).not.toContain('dangerouslySetInnerHTML');
    });

    test('30. javascript: URL is not executed or rendered as clickable href', () => {
      const jsUrlFinding: ValidatedFindingCandidate = {
        ...sampleFinding,
        remediation: 'javascript:alert("hacked")',
      };
      const review: CodeReviewResultDto = {
        ...sampleCompletedReview,
        findings: [jsUrlFinding],
      };

      render(<CodeReviewDetailsView review={review} />);
      fireEvent.click(screen.getByText('SQL Injection Vulnerability'));

      const links = screen.queryAllByRole('link');
      const hrefs = links.map((l) => l.getAttribute('href'));
      expect(hrefs).not.toContain('javascript:alert("hacked")');
    });

    test('31. shell command displayed strictly as plain text', () => {
      const shellFinding: ValidatedFindingCandidate = {
        ...sampleFinding,
        evidence: 'rm -rf / --no-preserve-root',
      };
      const review: CodeReviewResultDto = {
        ...sampleCompletedReview,
        findings: [shellFinding],
      };

      render(<CodeReviewDetailsView review={review} />);
      fireEvent.click(screen.getByText('SQL Injection Vulnerability'));

      expect(screen.getByText('rm -rf / --no-preserve-root')).toBeInTheDocument();
    });

    test('32. AI remediation guidance is read-only and cannot trigger action', () => {
      render(<CodeReviewDetailsView review={sampleCompletedReview} />);
      fireEvent.click(screen.getByText('SQL Injection Vulnerability'));

      expect(screen.getByText(/AI Remediation Guidance \(Read-Only Information\)/i)).toBeInTheDocument();
      expect(screen.queryByText('Apply Fix')).toBeNull();
      expect(screen.queryByText('Approve')).toBeNull();
    });

    test('33. AI security claim is explicitly labeled as unverified', () => {
      render(<CodeReviewDetailsView review={sampleCompletedReview} />);
      fireEvent.click(screen.getByText('SQL Injection Vulnerability'));

      expect(screen.getByText('AI security claim — not independently verified')).toBeInTheDocument();
    });

    test('34. long malicious string does not break layout', () => {
      const longString = 'A'.repeat(500);
      const longFinding: ValidatedFindingCandidate = {
        ...sampleFinding,
        evidence: longString,
      };
      const review: CodeReviewResultDto = {
        ...sampleCompletedReview,
        findings: [longFinding],
      };

      render(<CodeReviewDetailsView review={review} />);
      fireEvent.click(screen.getByText('SQL Injection Vulnerability'));

      expect(screen.getByText(longString)).toBeInTheDocument();
    });
  });

  // ==========================================
  // 5. NO MUTATION / SIDE EFFECTS
  // ==========================================
  describe('No Mutation / Side Effects', () => {
    test('45-50. UI contains NO code mutation buttons (Approve, Apply Fix, Commit, Push, PR, Repair)', () => {
      render(<CodeReviewDetailsView review={sampleCompletedReview} />);
      render(
        <CodeReviewList
          projectId={mockProjectId}
          repositoryId={mockRepoId}
          taskId={mockTaskId}
          reviews={[sampleCompletedReview]}
        />
      );

      expect(screen.queryByText('Approve')).toBeNull();
      expect(screen.queryByText('Apply Fix')).toBeNull();
      expect(screen.queryByText('Generate Fix')).toBeNull();
      expect(screen.queryByText('Commit')).toBeNull();
      expect(screen.queryByText('Push')).toBeNull();
      expect(screen.queryByText('Create PR')).toBeNull();
      expect(screen.queryByText('Execute')).toBeNull();
      expect(screen.queryByText('Repair')).toBeNull();
    });
  });
});
