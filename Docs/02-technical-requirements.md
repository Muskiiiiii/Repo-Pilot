# RepoPilot

## Technical Requirements Document (TRD)

**Project:** RepoPilot — AI-Powered Software Engineering Platform
**Document Type:** Technical Requirements Document
**Version:** 1.0
**Status:** Development Specification

---

# 1. Technical Objective

RepoPilot shall be implemented as a modular, cloud-ready, AI-native software engineering platform.

The system shall:

1. Authenticate users securely.

2. Connect users to GitHub using OAuth.

3. Import and analyze GitHub repositories.

4. Build a searchable representation of source code.

5. Use RAG to retrieve relevant code context.

6. Convert natural-language issues into structured engineering tasks.

7. Generate implementation plans using AI agents.

8. Generate structured code changes.

9. Execute tests in an isolated environment.

10. Iteratively fix test failures.

11. Perform AI-assisted code review.

12. Require human approval before repository write operations.

13. Create branches, commits and Pull Requests.

14. Generate an explainable implementation report.

15. Maintain audit logs and execution history.

16. Provide production-oriented security, observability and reliability.


---

# 2. Architecture

## 2.1 Architectural Style

RepoPilot shall use a **modular service-oriented architecture**.

Initial services:

```text
┌───────────────────────────────────────────────┐
│                  Client                       │
│              Next.js Frontend                 │
└──────────────────────┬────────────────────────┘
                       │ HTTPS / REST
                       ▼
┌───────────────────────────────────────────────┐
│              Spring Boot Backend              │
│                                               │
│ Auth | Projects | Tasks | GitHub | API       │
└───────────┬───────────────┬───────────────┬───┘
            │               │               │
            ▼               ▼               ▼
       PostgreSQL         Redis         GitHub API
            │
            ▼
         pgvector
            │
            │ Internal API / Jobs
            ▼
┌───────────────────────────────────────────────┐
│              Python AI Service                │
│                                               │
│ LangGraph + RAG + LLM + Agent Orchestration  │
└──────────────────────┬────────────────────────┘
                       │
                       ▼
              Isolated Code Runner
```

---

# 3. Technology Requirements

## 3.1 Frontend

Required:

- Next.js

- TypeScript

- Tailwind CSS


Responsibilities:

- Authentication UI

- Project management UI

- GitHub repository selection

- Repository indexing status

- Task creation

- AI execution progress

- Plan review

- Code diff visualization

- Test results

- Code review results

- Pull Request status

- Implementation reports


---

# 4. Backend Requirements

## 4.1 Backend Technology

Required:

```text
Java
Spring Boot
Spring Security
REST APIs
```

The Spring Boot application shall act as the primary application backend.

## 4.2 Backend Responsibilities

The backend shall handle:

- Authentication.

- Authorization.

- User management.

- Project management.

- Repository management.

- GitHub integration.

- Task management.

- Task state management.

- AI job orchestration.

- Database operations.

- Audit logging.

- Rate limiting.

- API validation.

- Communication with the AI service.


---

# 5. AI Service Requirements

## 5.1 Technology

Required:

```text
Python
FastAPI
LangGraph
LLM API
Embedding API
```

## 5.2 Responsibilities

The AI service shall handle:

- Issue understanding.

- Code retrieval.

- RAG.

- Planning.

- Agent orchestration.

- Code generation.

- Test generation.

- Failure analysis.

- Code review.

- AI confidence calculation.

- AI response validation.


The AI service shall not directly expose sensitive credentials to LLM prompts.

---

# 6. Database Requirements

## 6.1 Primary Database

```text
PostgreSQL
```

## 6.2 Vector Database

```text
pgvector
```

The vector database shall be implemented using PostgreSQL + pgvector initially rather than introducing a separate vector database.

---

# 7. Cache and Queue Requirements

## 7.1 Redis

Redis shall support:

- Caching.

- Job state.

- Background task coordination.

- Rate limiting.

- Temporary execution state.


## 7.2 Background Jobs

Long-running operations shall execute asynchronously.

Examples:

- Repository indexing.

- Embedding generation.

- AI planning.

- Code generation.

- Test execution.

- Code review.


The HTTP API must not remain blocked for the entire AI workflow.

---

# 8. GitHub Integration

## 8.1 Authentication

GitHub OAuth shall be used.

Flow:

```text
User
 ↓
RepoPilot
 ↓
GitHub OAuth
 ↓
GitHub Authorization
 ↓
Authorization Code
 ↓
RepoPilot Backend
 ↓
Access Token
```

Tokens shall be securely stored and never logged.

---

# 9. GitHub API Operations

The backend shall support:

### Read operations

```text
GET repositories
GET repository metadata
GET branches
GET files
GET commits
GET issues
GET pull requests
GET CI status
GET PR comments
```

### Write operations

```text
CREATE branch
CREATE commit
PUSH changes
CREATE Pull Request
```

Write operations must require appropriate authorization and human approval.

---

# 10. Repository Indexing Architecture

## 10.1 Indexing Pipeline

```text
GitHub Repository
       ↓
Repository Fetch
       ↓
File Scanner
       ↓
File Filtering
       ↓
Language Detection
       ↓
Code Parsing
       ↓
Code Chunking
       ↓
Metadata Extraction
       ↓
Embedding Generation
       ↓
PostgreSQL + pgvector
```

---

# 11. File Filtering

The indexing system shall exclude:

```text
.git/
node_modules/
target/
build/
dist/
.env
```

and other generated/binary files where appropriate.

The exclusion list shall be configurable.

---

# 12. Code Chunking

The system shall divide source code into meaningful chunks.

Preferred hierarchy:

```text
Repository
 ↓
File
 ↓
Class / Module
 ↓
Function / Method
 ↓
Code Chunk
```

Chunks should preserve useful context.

Each chunk should contain metadata.

Example:

```json
{
  "repositoryId": "...",
  "filePath": "src/auth/AuthService.java",
  "language": "java",
  "symbol": "AuthService.login",
  "startLine": 20,
  "endLine": 65,
  "commitSha": "..."
}
```

---

# 13. Embedding Requirements

Each code chunk shall be converted into an embedding.

The embedding record shall maintain:

- Chunk ID.

- Repository ID.

- File ID.

- Commit SHA.

- Embedding vector.

- Metadata.


The embedding model shall be configurable.

---

# 14. RAG Architecture

## 14.1 Retrieval Pipeline

```text
User Issue
    ↓
Query Understanding
    ↓
Query Embedding
    ↓
Vector Search
    ↓
Keyword Search
    ↓
Metadata Filtering
    ↓
Optional Re-ranking
    ↓
Top-K Context
    ↓
LLM
```

---

# 15. Hybrid Retrieval

The retrieval system should combine:

```text
Semantic Search
        +
Keyword Search
        +
Metadata Filtering
```

This is preferred over relying exclusively on vector similarity.

---

# 16. Retrieval Metadata

Retrieval should support filtering by:

- Repository.

- Branch.

- Commit.

- File type.

- Programming language.

- Directory.

- Symbol/class/function.


The system must prevent retrieval from unrelated repositories.

---

# 17. Issue Processing

The system shall convert a natural-language issue into a structured representation.

Example:

```json
{
  "objective": "Add JWT authentication",
  "requirements": [
    "Add login endpoint",
    "Generate JWT",
    "Validate JWT",
    "Protect APIs"
  ],
  "constraints": [],
  "acceptanceCriteria": []
}
```

---

# 18. Agent Architecture

The AI system shall use LangGraph for agent orchestration.

## 18.1 High-Level Graph

```text
                    START
                      │
                      ▼
               Issue Analyzer
                      │
                      ▼
                Codebase Agent
                      │
                      ▼
                Planner Agent
                      │
                      ▼
              Human Approval
                      │
                      ▼
                Coding Agent
                      │
                      ▼
                Testing Agent
                      │
                      ▼
               Tests Passed?
                ┌─────┴─────┐
               NO           YES
                │             │
                ▼             ▼
          Failure Agent   Review Agent
                │             │
                ▼             ▼
           Coding Agent    Validation
                │             │
                └──────┬──────┘
                       ▼
                 Human Approval
                       │
                       ▼
                   GitHub PR
                       │
                      END
```

---

# 19. Supervisor Agent

The Supervisor Agent shall:

- Control execution flow.

- Select the next agent.

- Validate agent outputs.

- Enforce maximum iterations.

- Handle failures.

- Stop unsafe workflows.

- Prevent unauthorized tool execution.


---

# 20. Planner Agent

Input:

```text
Issue
+
Repository Context
+
Retrieved Code
```

Output:

```text
Implementation Plan
Affected Files
Dependencies
Testing Strategy
Risks
```

The plan shall be structured rather than free-form wherever possible.

---

# 21. Coding Agent

The Coding Agent shall:

- Read retrieved code.

- Follow approved implementation plan.

- Generate new files.

- Modify existing files.

- Generate deletion requests where required.

- Produce structured patches.


It should not directly push changes to GitHub.

---

# 22. Structured Code Change Format

AI-generated changes should use a structured schema.

Example:

```json
{
  "changes": [
    {
      "file": "src/auth/JwtService.java",
      "action": "CREATE",
      "reason": "Provides JWT generation and validation",
      "content": "..."
    },
    {
      "file": "src/config/SecurityConfig.java",
      "action": "MODIFY",
      "reason": "Adds JWT authentication filter",
      "patch": "..."
    }
  ]
}
```

Supported actions:

```text
CREATE
MODIFY
DELETE
```

---

# 23. Diff Generation

The system shall generate a standard code diff.

Example:

```diff
+ import ...
+
+ public class JwtService {
+     ...
+ }
```

The frontend shall render the diff in a developer-friendly interface.

---

# 24. Test Agent

The Test Agent shall:

1. Detect test framework.

2. Detect existing tests.

3. Identify required test coverage.

4. Generate tests.

5. Execute tests.

6. Capture output.

7. Analyze failures.


---

# 25. Code Execution Architecture

Generated code shall execute in an isolated environment.

```text
AI Generated Code
       ↓
Execution Request
       ↓
Isolated Docker Container
       ↓
Run Tests
       ↓
Capture Output
       ↓
Destroy Container
```

The main application container must not execute untrusted generated code directly.

---

# 26. Code Runner Security

The execution environment shall have:

- CPU limits.

- Memory limits.

- Execution timeout.

- Process limits.

- Restricted filesystem access.

- Restricted network access.

- Temporary workspace.

- Automatic cleanup.


The exact limits shall be configurable.

---

# 27. Self-Correction Loop

The AI workflow shall support automatic correction.

```text
Generate
   ↓
Compile
   ↓
Run Tests
   ↓
Failure?
   │
   ├── YES → Analyze Failure
   │             ↓
   │          Generate Fix
   │             ↓
   │          Test Again
   │
   └── NO → Continue
```

Maximum retries:

```text
3
```

The retry count must be configurable.

---

# 28. Code Review Agent

The Review Agent shall analyze:

- Correctness.

- Security.

- Performance.

- Maintainability.

- Code quality.

- Error handling.

- Testing.

- Architectural consistency.


Output:

```json
{
  "score": 8.7,
  "criticalIssues": [],
  "warnings": [],
  "suggestions": []
}
```

---

# 29. AI Output Validation

AI outputs shall be validated before being accepted.

Validation should check:

- Required fields.

- Valid JSON/schema.

- File paths.

- Allowed operations.

- Repository scope.

- Tool permissions.

- Patch validity.

- Generated code syntax where possible.


Invalid AI output must not be executed.

---

# 30. AI Confidence

Important AI decisions should include confidence information.

Example:

```json
{
  "confidence": 0.91,
  "evidence": [
    "Existing Spring Security configuration found",
    "Existing UserService found",
    "JWT dependency detected"
  ]
}
```

Confidence should not be treated as mathematical proof of correctness.

---

# 31. Human-in-the-Loop

There shall be at least two approval points.

## Approval 1

After planning:

```text
AI Plan
 ↓
Human Approval
 ↓
Code Generation
```

## Approval 2

Before GitHub write operations:

```text
Code
 ↓
Tests
 ↓
Review
 ↓
Human Approval
 ↓
GitHub
```

---

# 32. Task State Machine

The backend shall maintain explicit task states.

```text
CREATED
   ↓
ANALYZING
   ↓
PLANNING
   ↓
AWAITING_PLAN_APPROVAL
   ↓
GENERATING
   ↓
TESTING
   ↓
FIXING
   ↓
REVIEWING
   ↓
AWAITING_CODE_APPROVAL
   ↓
CREATING_PR
   ↓
COMPLETED
```

Failure states:

```text
FAILED
CANCELLED
```

Invalid state transitions must be rejected by the backend.

---

# 33. Background Execution

AI execution shall be asynchronous.

Example:

```text
POST /tasks
     ↓
Create Task
     ↓
Queue Job
     ↓
Return taskId
     ↓
Worker Processes Job
```

The frontend retrieves or subscribes to progress using:

- SSE

- WebSocket


---

# 34. Task Execution Record

Each execution shall store:

```text
taskId
agentRunId
agentName
status
startedAt
completedAt
model
tokenUsage
toolsUsed
retryCount
error
```

Sensitive prompt content should not be stored unnecessarily.

---

# 35. Database Schema

Core tables/entities:

```text
users
projects
repositories
repository_branches
repository_files
code_chunks
embeddings
tasks
task_executions
agent_runs
plans
code_changes
test_runs
test_results
code_reviews
pull_requests
audit_logs
```

---

# 36. User Entity

Example fields:

```text
id
email
passwordHash
name
role
createdAt
updatedAt
```

---

# 37. Project Entity

Example:

```text
id
userId / ownerId
name
description
createdAt
updatedAt
```

---

# 38. Repository Entity

Example:

```text
id
projectId
githubRepositoryId
owner
name
defaultBranch
connectedAt
lastIndexedAt
indexStatus
```

---

# 39. Task Entity

Example:

```text
id
projectId
title
description
status
createdBy
createdAt
updatedAt
```

---

# 40. Code Chunk Entity

Example:

```text
id
repositoryId
fileId
content
language
symbol
startLine
endLine
commitSha
embedding
```

---

# 41. Code Change Entity

Example:

```text
id
taskId
filePath
action
patch
reason
approved
createdAt
```

---

# 42. Test Run Entity

Example:

```text
id
taskId
status
totalTests
passedTests
failedTests
duration
output
startedAt
completedAt
```

---

# 43. Code Review Entity

Example:

```text
id
taskId
score
criticalIssues
warnings
suggestions
securityFindings
createdAt
```

---

# 44. Pull Request Entity

Example:

```text
id
taskId
githubPrId
branchName
commitSha
prUrl
status
createdAt
```

---

# 45. Audit Log Entity

Example:

```text
id
userId
projectId
taskId
action
status
timestamp
metadata
```

---

# 46. API Architecture

The API shall follow REST conventions.

Base URL:

```text
/api
```

---

# 47. Authentication APIs

```text
POST /api/auth/register
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me
```

---

# 48. Project APIs

```text
GET    /api/projects
POST   /api/projects
GET    /api/projects/{projectId}
PUT    /api/projects/{projectId}
DELETE /api/projects/{projectId}
```

---

# 49. GitHub APIs

```text
GET  /api/github/repositories
GET  /api/github/repositories/{id}
GET  /api/github/repositories/{id}/branches

POST /api/projects/{projectId}/repository
DELETE /api/projects/{projectId}/repository
```

---

# 50. Indexing APIs

```text
POST /api/projects/{projectId}/index
GET  /api/projects/{projectId}/index/status
```

---

# 51. Task APIs

```text
POST   /api/projects/{projectId}/tasks
GET    /api/tasks/{taskId}
POST   /api/tasks/{taskId}/cancel
GET    /api/tasks/{taskId}/status
```

---

# 52. Plan APIs

```text
GET  /api/tasks/{taskId}/plan
POST /api/tasks/{taskId}/plan/approve
POST /api/tasks/{taskId}/plan/reject
```

---

# 53. Code Change APIs

```text
GET  /api/tasks/{taskId}/changes
POST /api/tasks/{taskId}/changes/approve
POST /api/tasks/{taskId}/changes/reject
```

---

# 54. Testing APIs

```text
GET  /api/tasks/{taskId}/tests
POST /api/tasks/{taskId}/tests/run
GET  /api/tasks/{taskId}/tests/{testRunId}
```

---

# 55. Review APIs

```text
GET /api/tasks/{taskId}/review
```

---

# 56. Report APIs

```text
GET /api/tasks/{taskId}/report
```

---

# 57. Pull Request APIs

```text
POST /api/tasks/{taskId}/pull-request
GET  /api/tasks/{taskId}/pull-request
```

---

# 58. Internal AI APIs

The Python AI service may expose:

```text
POST /internal/analyze-task
POST /internal/retrieve-code
POST /internal/create-plan
POST /internal/generate-code
POST /internal/generate-tests
POST /internal/review-code
POST /internal/analyze-failure
POST /internal/fix-code
```

These APIs shall be internal and must not be publicly accessible.

---

# 59. API Security

Every protected endpoint shall validate:

```text
Authentication
Authorization
Input
Resource ownership
```

Example:

```text
User A
 ↓
GET /api/projects/project-B
 ↓
Authorization Check
 ↓
403 Forbidden
```

---

# 60. API Validation

Request validation shall include:

- Required fields.

- String length.

- Allowed enum values.

- Valid IDs.

- Valid file paths.

- Maximum request size.


Malformed requests shall return appropriate HTTP status codes.

---

# 61. Error Handling

The backend shall use centralized exception handling.

Response structure:

```json
{
  "timestamp": "2026-09-11T10:00:00Z",
  "status": 400,
  "code": "INVALID_TASK",
  "message": "Task description is required.",
  "requestId": "..."
}
```

Responses must not expose:

- Stack traces.

- Secrets.

- Access tokens.

- Database credentials.

- Internal implementation details.


---

# 62. Authentication Security

Required:

- Password hashing.

- JWT access tokens.

- Refresh tokens.

- Token expiration.

- Secure token handling.

- Logout support.


---

# 63. GitHub Token Security

GitHub tokens shall:

- Never be logged.

- Never be returned to frontend unnecessarily.

- Never be inserted into LLM prompts.

- Be encrypted/protected at rest where appropriate.

- Use minimum required permissions.

- Be revocable.


---

# 64. Secret Management

Secrets must never be hardcoded.

Examples:

```text
OPENAI_API_KEY
GITHUB_CLIENT_SECRET
JWT_SECRET
DATABASE_PASSWORD
AWS_SECRET_ACCESS_KEY
```

Development:

```text
.env
```

Production:

Use a secure cloud secret-management solution.

---

# 65. Prompt Injection Protection

Repository content must be treated as untrusted input.

Potential injection sources:

```text
README
Source comments
Documentation
GitHub issues
Commit messages
Test files
Configuration files
```

The AI system shall distinguish:

```text
System Instructions
        ≠
Repository Data
```

Repository content must not be allowed to override system-level instructions.

---

# 66. Agent Tool Permissions

Agents shall operate using least privilege.

Example:

```text
Planner Agent
 ├── READ repository
 ├── SEARCH repository
 └── NO WRITE permission
```

```text
Coding Agent
 ├── READ repository
 ├── CREATE patch
 └── NO GitHub push permission
```

```text
GitHub Delivery Component
 ├── CREATE branch
 ├── COMMIT
 └── CREATE PR
```

GitHub write actions require human approval.

---

# 67. Rate Limiting

Rate limits shall apply to:

- Login.

- Registration.

- AI task creation.

- Repository indexing.

- GitHub API operations.

- AI endpoints.


Redis may be used to implement distributed rate limiting.

---

# 68. Repository Access Isolation

Every repository operation must verify:

```text
Authenticated User
       ↓
Project Ownership / Membership
       ↓
Repository Association
       ↓
GitHub Authorization
```

No cross-project repository retrieval shall be permitted.

---

# 69. Code Execution Security

Generated code shall run outside the main application process.

Required controls:

```text
CPU limit
Memory limit
Timeout
Filesystem isolation
Network restriction
Temporary workspace
Container cleanup
```

---

# 70. Docker Architecture

Development environment:

```text
docker-compose

├── frontend
├── backend
├── ai-service
├── postgres
└── redis
```

Code execution containers shall be created separately and destroyed after execution.

---

# 71. Configuration Management

Configuration shall be externalized.

Example:

```text
DATABASE_URL
REDIS_URL
GITHUB_CLIENT_ID
GITHUB_CLIENT_SECRET
OPENAI_API_KEY
JWT_SECRET
AI_SERVICE_URL
```

No environment-specific values shall be embedded in source code.

---

# 72. CI/CD

GitHub Actions shall execute:

```text
Checkout
   ↓
Install Dependencies
   ↓
Build
   ↓
Unit Tests
   ↓
Integration Tests
   ↓
Static Analysis
   ↓
Security Checks
   ↓
Docker Build
   ↓
Deploy
```

The pipeline must fail when required checks fail.

---

# 73. Backend Testing

Required:

### Unit Tests

Test:

- Services.

- Utilities.

- Validators.

- Business logic.


### Integration Tests

Test:

- Database.

- Redis.

- GitHub integration where practical.

- AI service communication.


### API Tests

Test:

- Authentication.

- Authorization.

- Validation.

- Error handling.

- Core workflows.


---

# 74. Frontend Testing

Required:

- Component tests.

- API integration tests.

- User workflow tests.

- End-to-end tests.


Critical E2E workflow:

```text
Login
 ↓
Connect GitHub
 ↓
Create Project
 ↓
Select Repository
 ↓
Create Task
 ↓
Review Plan
 ↓
Approve
 ↓
Review Changes
 ↓
Approve
 ↓
View PR
```

---

# 75. AI Testing

AI functionality shall have dedicated evaluation.

Test:

- Issue understanding.

- Retrieval accuracy.

- Planning quality.

- Code generation.

- Test generation.

- Failure analysis.

- Code review.

- Prompt injection resistance.


---

# 76. AI Evaluation Metrics

Track:

```text
Retrieval Accuracy
Plan Success Rate
Code Compilation Rate
Test Pass Rate
Self-Correction Success Rate
PR Success Rate
Hallucination Rate
Agent Failure Rate
Average Retry Count
```

---

# 77. Observability

## Logging

Structured logs shall include:

```text
requestId
userId
projectId
taskId
agentRunId
event
status
timestamp
```

Sensitive data must be excluded.

---

# 78. Metrics

Track:

- Request latency.

- API error rate.

- AI latency.

- Agent execution time.

- Retrieval latency.

- Queue size.

- Test execution time.

- Token usage.

- Retry count.

- PR creation success.

- Repository indexing time.


---

# 79. Monitoring

Future production monitoring may use:

```text
Prometheus
Grafana
OpenTelemetry
```

The architecture should remain compatible with these tools.

---

# 80. Performance Requirements

The platform should:

- Use asynchronous processing for long tasks.

- Cache repeated operations.

- Avoid unnecessary LLM calls.

- Limit RAG context size.

- Use pagination.

- Use database indexes.

- Use connection pooling.

- Incrementally index repositories when possible.


---

# 81. Database Performance

Required considerations:

- Index foreign keys.

- Index task status.

- Index repository IDs.

- Index user/project relationships.

- Use vector indexes for embeddings.

- Paginate large datasets.

- Avoid N+1 queries.

- Use connection pooling.


---

# 82. Reliability

The system must handle:

```text
LLM failure
GitHub API failure
Database failure
Redis failure
Network failure
Test failure
Agent failure
Timeout
Rate limit
```

For transient failures:

```text
Retry
 ↓
Exponential Backoff
 ↓
Retry Limit
 ↓
Failed State
```

---

# 83. Idempotency

Operations that can be retried should be designed to avoid duplicate effects.

Examples:

```text
Create branch
Create commit
Create PR
Start indexing job
```

The backend should detect duplicate requests where appropriate.

---

# 84. Task Cancellation

Users should be able to cancel long-running tasks.

Cancellation should:

- Update task status.

- Stop queued work where possible.

- Stop active AI workflows where possible.

- Terminate code execution containers.

- Prevent subsequent GitHub writes.


---

# 85. AI Cost Control

The system should track:

- Model used.

- Input tokens.

- Output tokens.

- Number of LLM calls.

- Estimated cost.


Controls should include:

- Maximum tokens.

- Maximum agent iterations.

- Maximum context size.

- Maximum task execution time.


---

# 86. Repository Indexing Optimization

Initial indexing:

```text
Entire Repository
```

Subsequent indexing:

```text
New Commit
   ↓
Detect Changed Files
   ↓
Re-index Changed Files
   ↓
Update Embeddings
```

The system should avoid unnecessarily re-embedding unchanged files.

---

# 87. Branch Consistency

Repository context must be associated with a specific:

```text
Repository
Branch
Commit SHA
```

Before generating changes, RepoPilot should verify that the repository state has not unexpectedly changed.

---

# 88. Concurrency

The system should prevent conflicting AI tasks from modifying the same repository state simultaneously.

Possible initial policy:

```text
One active write workflow per repository
```

Future versions may support more sophisticated conflict management.

---

# 89. Frontend State Management

The frontend must correctly represent:

```text
Task State
Indexing State
AI Execution State
Approval State
Test State
PR State
```

The UI must not assume success before the backend confirms it.

---

# 90. API Versioning

The API should be designed for future versioning.

Example:

```text
/api/v1/...
```

This allows future breaking changes without immediately breaking clients.

---

# 91. Project Folder Structure

Recommended monorepo structure:

```text
repopilot/
│
├── frontend/
│   ├── app/
│   ├── components/
│   ├── hooks/
│   ├── lib/
│   ├── services/
│   └── types/
│
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   └── java/
│   │   │       └── com/repopilot/
│   │   │           ├── auth/
│   │   │           ├── user/
│   │   │           ├── project/
│   │   │           ├── repository/
│   │   │           ├── github/
│   │   │           ├── task/
│   │   │           ├── ai/
│   │   │           ├── test/
│   │   │           ├── review/
│   │   │           ├── pullrequest/
│   │   │           ├── audit/
│   │   │           └── common/
│   │   └── test/
│   └── pom.xml
│
├── ai-service/
│   ├── app/
│   │   ├── agents/
│   │   ├── rag/
│   │   ├── embeddings/
│   │   ├── tools/
│   │   ├── workflows/
│   │   ├── evaluation/
│   │   └── api/
│   ├── tests/
│   └── requirements.txt
│
├── infrastructure/
│   ├── docker/
│   ├── github-actions/
│   └── aws/
│
├── docs/
│   ├── ARCHITECTURE.md
│   ├── API.md
│   ├── AI-ARCHITECTURE.md
│   └── SECURITY.md
│
├── docker-compose.yml
├── README.md
└── .gitignore
```

---

# 92. Backend Package Architecture

Recommended Spring Boot structure:

```text
com.repopilot
│
├── auth
│   ├── controller
│   ├── service
│   ├── repository
│   ├── entity
│   ├── dto
│   └── security
│
├── project
│
├── repository
│
├── github
│
├── task
│
├── ai
│
├── testing
│
├── review
│
├── pullrequest
│
├── audit
│
└── common
```

Each module should separate:

```text
Controller
Service
Repository
Entity
DTO
Mapper
```

where appropriate.

---

# 93. AI Service Structure

```text
ai-service/
│
├── app/
│   ├── api/
│   ├── agents/
│   │   ├── supervisor.py
│   │   ├── planner.py
│   │   ├── codebase.py
│   │   ├── coder.py
│   │   ├── tester.py
│   │   ├── reviewer.py
│   │   └── documentation.py
│   │
│   ├── workflows/
│   │   └── task_workflow.py
│   │
│   ├── rag/
│   │   ├── retriever.py
│   │   ├── chunker.py
│   │   └── reranker.py
│   │
│   ├── embeddings/
│   │
│   ├── tools/
│   │   ├── repository_tools.py
│   │   ├── search_tools.py
│   │   └── test_tools.py
│   │
│   ├── evaluation/
│   │
│   └── config/
│
└── tests/
```

---

# 94. Communication Contract

Spring Boot → AI Service requests must use structured schemas.

Example:

```json
{
  "taskId": "...",
  "repositoryId": "...",
  "issue": {
    "title": "Add JWT authentication",
    "description": "..."
  },
  "repositoryContext": {
    "branch": "main",
    "commitSha": "..."
  }
}
```

AI Service responses must be schema validated.

---

# 95. AI Model Abstraction

The AI layer should not tightly couple the application to one model provider.

Create an abstraction such as:

```text
LLMProvider
```

Possible implementations:

```text
OpenAIProvider
FutureProvider
```

Similarly:

```text
EmbeddingProvider
```

This allows future provider changes without rewriting the entire AI architecture.

---

# 96. Prompt Management

Prompts should be version controlled.

Example:

```text
prompts/
├── planner_v1.txt
├── coder_v1.txt
├── tester_v1.txt
├── reviewer_v1.txt
└── failure_analyzer_v1.txt
```

The system should record which prompt version was used for an AI execution.

---

# 97. Tool Calling

Agents shall interact with the repository through controlled tools.

Example tools:

```text
search_code
read_file
list_files
get_dependencies
get_tests
generate_patch
run_tests
```

Tools shall validate:

- Repository ID.

- File path.

- Permissions.

- Allowed operation.


---

# 98. Path Traversal Protection

File operations must prevent malicious paths such as:

```text
../../../../etc/passwd
```

All repository file paths must be validated and restricted to the repository workspace.

---

# 99. File Size Limits

The system should define maximum:

- Repository size.

- Individual file size.

- Task description size.

- AI context size.

- Generated patch size.


Large files should be handled through chunking rather than blindly sending them to the LLM.

---

# 100. Data Retention

The system should define retention policies for:

- AI execution logs.

- Task history.

- Repository metadata.

- Code chunks.

- Test results.

- Audit logs.


Sensitive data should not be retained unnecessarily.

---

# 101. Privacy

RepoPilot should ensure:

- Repository data is isolated by project.

- User credentials are protected.

- AI providers receive only required context.

- Secrets are filtered before AI processing.

- Sensitive files can be excluded from indexing.


---

# 102. Sensitive File Detection

The indexer should optionally detect and exclude files containing likely secrets.

Examples:

```text
.env
credentials
private keys
API keys
tokens
secrets
```

The system should never intentionally send secrets to an LLM.

---

# 103. Final End-to-End Technical Workflow

```text
┌──────────────────────────────────────────────┐
│                  USER                        │
└─────────────────────┬────────────────────────┘
                      │
                      ▼
                 Next.js UI
                      │
                      ▼
                Spring Boot
                      │
             ┌────────┴────────┐
             ▼                 ▼
          PostgreSQL          GitHub
             │
             ▼
        Create Project
             │
             ▼
       Repository Indexing
             │
             ▼
       File Scanner
             │
             ▼
       Code Chunking
             │
             ▼
        Embeddings
             │
             ▼
          pgvector
             │
             │
             ▼
        Developer Issue
             │
             ▼
       Task Created
             │
             ▼
          Redis Queue
             │
             ▼
       AI Service / LangGraph
             │
             ▼
        Issue Analyzer
             │
             ▼
        Codebase Agent
             │
             ▼
        Planner Agent
             │
             ▼
      Human Plan Approval
             │
             ▼
         Coding Agent
             │
             ▼
       Structured Patch
             │
             ▼
          Diff UI
             │
             ▼
       Test Generation
             │
             ▼
      Isolated Container
             │
             ▼
        Run Tests
             │
             ├───────────────┐
             │               │
           FAIL             PASS
             │               │
             ▼               ▼
       Failure Agent     Review Agent
             │               │
             ▼               ▼
        Coding Agent      Validation
             │               │
             └───────┐       │
                     │       │
                     ▼       ▼
                  Testing
                     │
                     ▼
             Human Approval
                     │
                     ▼
               Create Branch
                     │
                     ▼
                 Commit
                     │
                     ▼
                  Push
                     │
                     ▼
               Create PR
                     │
                     ▼
             CI/CD Validation
                     │
                     ▼
            Implementation Report
                     │
                     ▼
                    END
```

---

# 104. Development Milestones

## Milestone 1 — Foundation

```text
Spring Boot
PostgreSQL
Next.js
Authentication
Docker
```

## Milestone 2 — GitHub

```text
GitHub OAuth
Repository selection
Repository metadata
Repository file access
```

## Milestone 3 — Code Intelligence

```text
Repository indexing
Chunking
Embeddings
pgvector
Semantic search
Hybrid retrieval
```

## Milestone 4 — AI Planning

```text
AI service
LangGraph
Issue analyzer
Codebase agent
Planner agent
Human approval
```

## Milestone 5 — AI Coding

```text
Coding agent
Structured patches
Diff generation
Change explanations
```

## Milestone 6 — Testing

```text
Test agent
Docker code runner
Test execution
Failure analysis
Self-correction
```

## Milestone 7 — Review

```text
Code review agent
Security analysis
Confidence scoring
Evidence
```

## Milestone 8 — GitHub Delivery

```text
Branch creation
Commit
Push
Pull Request
PR report
```

## Milestone 9 — Production Engineering

```text
Redis
Background workers
SSE/WebSockets
Rate limiting
Audit logs
Observability
```

## Milestone 10 — Cloud

```text
AWS
CI/CD
Monitoring
Production deployment
```

---

# 105. Definition of Technical Completion

RepoPilot shall be considered technically complete when the platform can execute:

```text
1. Authenticate user
2. Connect GitHub
3. Select repository
4. Index repository
5. Generate embeddings
6. Store searchable code context
7. Accept development issue
8. Retrieve relevant code
9. Generate implementation plan
10. Obtain human approval
11. Generate structured code changes
12. Display diff
13. Generate tests
14. Execute tests safely
15. Analyze failures
16. Automatically attempt fixes
17. Review generated code
18. Perform security validation
19. Generate confidence/evidence
20. Obtain final human approval
21. Create Git branch
22. Commit changes
23. Push branch
24. Create Pull Request
25. Generate implementation report
26. Record audit events
27. Track complete execution history
```

---

# 106. Engineering Principles

RepoPilot development shall follow these principles:

1. **AI assists developers; it does not replace human approval.**

2. **Never blindly trust AI-generated code.**

3. **Repository content is untrusted data.**

4. **Use retrieval instead of guessing.**

5. **Keep AI changes small and explainable.**

6. **Validate AI output before execution.**

7. **Run generated code in isolation.**

8. **Use least-privilege agent tools.**

9. **Protect all credentials and secrets.**

10. **Design AI components as reusable engineering assets.**

11. **Prefer asynchronous processing for long-running workflows.**

12. **Make important operations observable and auditable.**

13. **Keep provider-specific AI code behind abstractions.**

14. **Design for incremental repository indexing.**

15. **Treat production readiness as a requirement, not an afterthought.**


---

# 107. Final Technical Stack

```text
┌────────────────────────────────────────────┐
│ FRONTEND                                   │
│ Next.js + TypeScript + Tailwind CSS        │
└─────────────────────┬──────────────────────┘
                      │
                      │ HTTPS / REST
                      ▼
┌────────────────────────────────────────────┐
│ BACKEND                                    │
│ Java + Spring Boot + Spring Security       │
└───────────────┬───────────────┬────────────┘
                │               │
                ▼               ▼
        PostgreSQL            Redis
                │
                ▼
             pgvector
                │
                │ Internal API / Jobs
                ▼
┌────────────────────────────────────────────┐
│ AI SERVICE                                 │
│ Python + FastAPI + LangGraph               │
│                                            │
│ RAG + Agents + LLM + Embeddings            │
└─────────────────────┬──────────────────────┘
                      │
                      ▼
               CODE RUNNER
                Docker
                      │
                      ▼
                  GitHub
                      │
                      ▼
                     PR

Infrastructure:
Docker
GitHub Actions
AWS

Observability:
Structured Logging
Prometheus
Grafana
OpenTelemetry

Security:
JWT
OAuth
RBAC
Rate Limiting
Secret Management
Prompt Injection Protection
Sandboxed Execution
```

---

# 108. Technical Priority

## P0 — Mandatory

```text
Authentication
GitHub OAuth
Repository integration
Repository indexing
RAG
PostgreSQL
pgvector
Spring Boot
Next.js
Python AI service
LangGraph
Planner Agent
Coding Agent
Testing Agent
Code Review Agent
Human approval
Code diff
Test execution
GitHub branch/commit/PR
Docker
```

## P1 — Important

```text
Redis
Background jobs
SSE/WebSockets
Self-correction
Confidence scoring
Audit logging
Prompt injection protection
Rate limiting
AI evaluation
Implementation reports
```

## P2 — Production Enhancement

```text
AWS
CI/CD
Prometheus
Grafana
OpenTelemetry
Multi-tenancy
Advanced concurrency
Advanced cost optimization
Multiple LLM providers
Multiple Git providers
```

---

# 109. Final Architecture Goal

The final RepoPilot system should demonstrate competence across:

```text
                    REPOPILOT
                        │
     ┌──────────────────┼──────────────────┐
     │                  │                  │
     ▼                  ▼                  ▼
FULL-STACK            AI/GENAI          CLOUD
     │                  │                  │
     ▼                  ▼                  ▼
Next.js              RAG               Docker
Spring Boot           Agents            AWS
REST APIs             LangGraph         CI/CD
PostgreSQL            LLM APIs          Redis
Authentication        Embeddings        Monitoring
     │                  │                  │
     └──────────────────┼──────────────────┘
                        ▼
                SOFTWARE ENGINEERING
                        │
        ┌───────────────┼────────────────┐
        ▼               ▼                ▼
      Testing         Security       GitHub
        │               │                │
        ▼               ▼                ▼
    Code Runner      RBAC/OAuth      Branches
    Test Analysis    Prompt Defense   Commits
    Self-Correction  Secrets          Pull Requests
```

**Target outcome:** RepoPilot should demonstrate that its developer can build a **production-oriented AI-native full-stack platform**, not merely integrate an LLM API.