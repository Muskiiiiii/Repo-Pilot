export type AuditEventType =
  | 'APPROVAL_CREATED'
  | 'APPROVAL_REVOKED'
  | 'APPROVAL_STALE_SUPERSEDED'
  | 'PUBLICATION_REQUESTED'
  | 'PUBLICATION_VERIFICATION_PASSED'
  | 'REMOTE_IDENTITY_VERIFIED'
  | 'CREDENTIAL_ACQUIRED_REDACTED'
  | 'PUSH_STARTED'
  | 'PUSH_SUCCEEDED'
  | 'PUSH_FAILED'
  | 'PUSH_UNKNOWN_EXTERNAL_STATE'
  | 'PUSH_RECONCILED'
  | 'PR_CREATION_STARTED'
  | 'PR_CREATION_SUCCEEDED'
  | 'PR_CREATION_RECONCILED'
  | 'PR_CREATION_CONFLICT'
  | 'PUBLICATION_COMPLETED'
  | 'PUBLICATION_FAILED'
  | 'AUTHORIZATION_DENIED'
  | 'BINDING_VERIFICATION_FAILED';

export interface PublicationAuditEventResponseDto {
  id: string;
  eventSequence: number;
  eventTimestamp: string;
  projectId: string;
  repositoryId: string;
  taskId: string;
  codeChangeId: string;
  approvalId?: string | null;
  publicationId?: string | null;
  actorUserId?: string | null;
  actorEmail?: string | null;
  actorRole?: string | null;
  eventType: AuditEventType;
  previousState?: string | null;
  resultingState?: string | null;
  githubRepoId?: string | null;
  repoOwner?: string | null;
  repoName?: string | null;
  baseBranch?: string | null;
  branchName?: string | null;
  commitSha?: string | null;
  githubPrNumber?: number | null;
  prUrl?: string | null;
  outcomeCategory?: string | null;
  reasonCode?: string | null;
  summary?: string | null;
}
