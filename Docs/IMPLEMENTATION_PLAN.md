# RepoPilot — Implementation Plan

This document defines the comprehensive master implementation plan for building **RepoPilot**, an AI-powered software engineering platform.

---

## 1. Project Architecture

RepoPilot follows a modern modular service-oriented architecture:

```text
┌──────────────────────────────────────────────────────────┐
│                    Frontend Application                  │
│                  Next.js 14+ / TypeScript                │
└────────────────────────────┬─────────────────────────────┘
                             │ REST / SSE
                             ▼
┌──────────────────────────────────────────────────────────┐
│                     Backend API                          │
│             Java 17+ / Spring Boot 3+                    │
│                                                          │
│ Auth | Projects | GitHub | Tasks | Approvals | Reports  │
└───────────────┬──────────────────────┬───────────────────┘
                │                      │
                ▼                      ▼
┌───────────────────────┐    ┌─────────────────────────────┐
│     PostgreSQL 16     │    │       Redis 7 / Queue       │
│       + pgvector      │    │   Background Job System     │
└───────────────────────┘    └──────────────┬──────────────┘
                                            │
                                            ▼
                               ┌─────────────────────────────┐
                               │        AI Service           │
                               │       Python 3.11+ / FastAPI│
                               │                             │
                               │ LangGraph Agent Workflow    │
                               │ RAG / Vector & Hybrid Search│
                               │ LLM Integration             │
                               │ Code Generation             │
                               │ Testing / Review Agents     │
                               └──────────────┬──────────────┘
                                              │
                               ┌──────────────┼──────────────┐
                               ▼              ▼              ▼
                          GitHub API      LLM API       Docker Sandbox
```

---

## 2. Development Phases & Dependencies

```text
Phase 1: Project Foundation (Next.js + Spring Boot + FastAPI + Postgres/pgvector + Redis + Docker Compose) [COMPLETED]
   │
   ▼
Phase 2: Authentication & User Management (JWT + RBAC + User Endpoints + Auth Guard UI) [COMPLETED]
   │
   ▼
Phase 3: Database Schema & Migrations (Liquibase / Flyway migrations + JPA Entities for all core domain tables) [COMPLETED]
   │
   ▼
Phase 4: Project Management & Workspace (Projects CRUD + Project Dashboard + Project Authorization) [COMPLETED]
   │
   ▼
Phase 5: GitHub Integration (OAuth + Repositories API + Branch/Commit/File API) [COMPLETED]
   │
   ▼
Phase 6: Repository Indexing Pipeline (Scanner + Chunking + Embeddings + pgvector storing)
   │
   ▼
Phase 7: Codebase RAG Engine (Semantic + Keyword Hybrid Search + Metadata Filtering)
   │
   ▼
Phase 8: Multi-Agent Architecture (LangGraph + Supervisor + Issue Analyzer + Planner Agent)
   │
   ▼
Phase 9: Structured Code Generation (Coding Agent + Structured JSON Patches + Diff UI)
   │
   ▼
Phase 10: Human Approval Workflow (Plan Approval + Code Change Approval Gates)
   │
   ▼
Phase 11: Docker Test Sandbox (Isolated Container Runner + Resource Limits)
   │
   ▼
Phase 12: Automated Testing & Self-Correction (Test Agent + Fail Analysis + Max 3 Retries Loop)
   │
   ▼
Phase 13: AI Code Review & Security Analysis (Review Agent + Vulnerability Check + Confidence Scoring)
   │
   ▼
Phase 14: GitHub Delivery Engine (Branch Creation + Commit + Push + Idempotent PR Creation)
   │
   ▼
Phase 15: Implementation Report & Audit Trail (Explainable Task Report + Audit Logging + SSE Streaming)
```

---

## 3. Implementation Order by Component

### 3.1 Database Implementation Order
1. `users` & `audit_logs`
2. `projects` & `project_members`
3. `repositories`, `repository_branches`, `repository_files`
4. `code_chunks` & `embeddings` (with pgvector extension)
5. `tasks` & `task_executions`
6. `agent_runs`
7. `plans` & `plan_steps`
8. `code_changes`
9. `test_runs` & `test_results`
10. `code_reviews`
11. `pull_requests`

### 3.2 Backend Implementation Order (Spring Boot)
1. Spring Boot Foundation & Package Structure (`auth`, `user`, `project`, `repository`, `github`, `task`, `ai`, `testing`, `review`, `pullrequest`, `audit`, `common`)
2. Database Connectivity & Liquibase/Flyway Migration Scripts
3. Security Layer (Spring Security, JWT Filter, Password Hashing, CORS, Exception Handler)
4. User & Auth REST Endpoints (`/api/v1/auth/*`, `/api/v1/users/*`)
5. Project REST Endpoints (`/api/v1/projects/*`)
6. GitHub Service & OAuth REST Endpoints (`/api/v1/github/*`)
7. Repository Indexing Trigger & Event Handlers
8. Task Management REST Endpoints (`/api/v1/tasks/*`)
9. Integration with AI Service Client (WebClient / RestClient to FastAPI)
10. SSE (Server-Sent Events) Progress Event Emitter
11. Plan & Code Change Approval API Endpoints
12. Pull Request & Implementation Report Endpoints

### 3.3 AI-Service Implementation Order (FastAPI + LangGraph)
1. FastAPI App Setup, Config, Health Endpoint
2. LLM Provider Abstraction (`LLMProvider`, `EmbeddingProvider`)
3. Code Chunking & Embeddings Engine
4. Vector Search (pgvector) & Keyword Search Hybrid Retriever
5. LangGraph State Schema (`TaskState`)
6. Issue Analyzer Node
7. Codebase Agent Node
8. Planner Agent Node
9. Coding Agent Node (Structured Patches output)
10. Testing Agent Node & Docker Sandbox Integration
11. Failure Analysis & Self-Correction Retries (Max 3)
12. Code Review Agent & Security Scanner Node
13. Implementation Report Generator Node
14. Internal REST API Routes (`/internal/ai/*`)

### 3.4 Frontend Implementation Order (Next.js)
1. Next.js 14 App Router, TypeScript, Tailwind CSS Setup
2. Design System Foundation, Colors (Dark/Light theme support), JetBrains Mono / Inter Fonts
3. Application Shell (Top Navigation, Sidebar, Breadcrumbs, Responsive Layout)
4. Auth Pages (`/login`, `/register`) & Auth Store (Zustand)
5. Dashboard Screen (`/dashboard`)
6. Projects Screens (`/projects`, `/projects/new`, `/projects/[id]`)
7. GitHub Connection & Repository Selector Modal
8. Task Creation & Task List View (`/projects/[id]/tasks`)
9. Task Workspace Workspace View (`/projects/[id]/tasks/[taskId]`):
   - Timeline & Real-time Progress (SSE)
   - Issue Understanding Panel
   - Plan Review & Approval UI
   - File Diff Viewer (Split/Unified view with syntax highlighting)
   - Test Results UI & Failure Analysis View
   - AI Code Review Panel & Confidence Badge
   - Final Approval & PR Creation UI
   - Implementation Report Page
10. Global AI Runs & Audit Trail Screens

---

## 4. Testing Strategy

- **Backend Testing**:
  - Unit Tests: JUnit 5 + Mockito for Service logic, Utility methods, and DTO Mappers.
  - Repository Tests: `@DataJpaTest` with PostgreSQL test containers or standard PostgreSQL.
  - Controller Tests: `@WebMvcTest` for request validation, authorization, and error formatting.
- **AI Service Testing**:
  - Unit Tests: Pytest for schema parsers, prompt templates, and tool invocation bounds.
  - Mock Retrieval & LLM Tests: Test agent graph routing with mock LLM outputs.
- **Frontend Testing**:
  - Component Tests: React Testing Library.
  - End-to-End Tests: Critical workflow validation (Login -> Create Project -> Create Task -> Plan Review -> Diff Review -> PR creation).

---

## 5. Infrastructure & Environment Configuration

### Required External Services / Tools
- PostgreSQL 16 (with `pgvector` extension)
- Redis 7
- Docker Engine (for running application containers and test sandboxes)
- GitHub OAuth App (for repository integration)
- LLM API Provider (OpenAI API / Gemini API / compatible model provider)

### Environment Variables (.env)
```ini
# Application Ports
FRONTEND_PORT=3000
BACKEND_PORT=8080
AI_SERVICE_PORT=8000

# Database
POSTGRES_HOST=postgres
POSTGRES_PORT=5432
POSTGRES_DB=repopilot
POSTGRES_USER=repopilot_user
POSTGRES_PASSWORD=repopilot_secure_pass

# Redis
REDIS_HOST=redis
REDIS_PORT=6379

# Security
JWT_SECRET=super_secret_jwt_key_at_least_256_bits_long_repopilot
JWT_EXPIRATION_MS=86400000

# GitHub Integration
GITHUB_CLIENT_ID=your_github_client_id
GITHUB_CLIENT_SECRET=your_github_client_secret
GITHUB_REDIRECT_URI=http://localhost:3000/api/auth/callback/github

# AI & LLM Services
LLM_API_KEY=your_llm_api_key
LLM_MODEL=gpt-4o
EMBEDDING_MODEL=text-embedding-3-small
AI_SERVICE_URL=http://ai-service:8000
```

---

## 6. How to Run the Project

### Local Docker Compose Workflow
```bash
# Build and launch all services
docker-compose up --build -d

# Check service health
curl http://localhost:8080/actuator/health  # Backend
curl http://localhost:8000/health           # AI Service
curl http://localhost:3000                  # Frontend
```

---

## 7. Definition of Done (DoD) per Phase

Each phase is considered COMPLETE when:
1. **Implementation**: All features of the phase are coded according to specification.
2. **Validation**: Inputs and outputs are strictly validated (JSON Schema / DTO Validation).
3. **Tests**: Unit & Integration tests exist and pass cleanly.
4. **Security**: Authorization and token protection controls are enforced.
5. **UI Integration**: Frontend cleanly reflects backend state without fake/hardcoded data.
6. **Documentation**: Any schema or architectural addition is documented.
