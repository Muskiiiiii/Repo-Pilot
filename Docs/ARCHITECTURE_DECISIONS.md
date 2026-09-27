# RepoPilot — Architecture Decision Records (ADR)

This document records key architectural decisions made for the **RepoPilot** platform, including technology selection, design rationale, alternatives considered, and trade-offs.

---

## ADR-001: Modular Dual-Backend Architecture (Spring Boot + FastAPI)

* **Technology**: Java (Spring Boot 3) for Application Backend, Python (FastAPI + LangGraph) for AI Service
* **Reason**: Spring Boot offers robust enterprise capabilities for security (Spring Security), relational data management (Spring Data JPA), REST APIs, transaction management, and RBAC. Python is the industry standard for LLM orchestration, agentic frameworks (LangGraph), chunking, and vector operations.
* **Alternatives Considered**:
  - Monolithic Python application (Django / FastAPI doing everything)
  - Monolithic Java application with Java LLM libraries (LangChain4j)
* **Why Selected**: Splitting application concerns (Java) from AI agent orchestration (Python) provides clean service boundaries, allows independent scaling, and leverages the best tools in each ecosystem.

---

## ADR-002: PostgreSQL + pgvector for Relational and Vector Storage

* **Technology**: PostgreSQL 16 with `pgvector` extension
* **Reason**: RepoPilot requires storing standard relational data (Users, Projects, Repositories, Tasks, Pull Requests, Audit Logs) as well as vector embeddings for codebase RAG. Using `pgvector` allows vector search directly alongside metadata filtering within a single ACID-compliant database.
* **Alternatives Considered**:
  - Standalone Vector DBs (Qdrant, Pinecone, Weaviate, Milvus) + PostgreSQL
* **Why Selected**: Using `pgvector` eliminates multi-database synchronization overhead, reduces deployment complexity, and allows single-query hybrid/metadata-filtered vector search without sacrificing relational constraints.

---

## ADR-003: Multi-Agent Orchestration using LangGraph

* **Technology**: LangGraph (Python)
* **Reason**: RepoPilot workflows involve multi-step, stateful agent loops with branching logic, retries (e.g. up to 3 automated test-fix attempts), human approval checkpoints, and specialized agent roles (Supervisor, Codebase, Planner, Coder, Tester, Reviewer).
* **Alternatives Considered**:
  - Simple linear LLM prompts
  - AutoGen / CrewAI / custom Python scripts
* **Why Selected**: LangGraph provides explicit state management, cyclic graph support, built-in checkpointing/persistence, human-in-the-loop validation, and fine-grained agent tool permission control.

---

## ADR-004: Ephemeral Docker Sandbox for Code Execution & Testing

* **Technology**: Docker Engine (isolated container runner)
* **Reason**: AI-generated code is probabilistic and untrusted. Executing generated code or unit tests directly on the application host poses severe security risks (arbitrary code execution, secret leakage, resource exhaustion).
* **Alternatives Considered**:
  - Host execution (running `mvn test` / `pytest` directly on backend server)
  - WebAssembly (Wasm) runtimes
* **Why Selected**: Docker containers provide strong process, filesystem, and network isolation, with configurable CPU/memory limits, timeouts, and automatic cleanup after execution.

---

## ADR-005: Capability-Based AI Agent Tool Permissions

* **Technology**: Custom Tool Permission Wrappers in AI Service
* **Reason**: Repository contents are untrusted input that may contain prompt injection attacks. Giving all agents unrestricted tool capabilities risks accidental or malicious write/push operations.
* **Alternatives Considered**:
  - Open agent tool access (giving all agents access to all tools)
* **Why Selected**: Capability-based scoping ensures that Planner agents can only read/search code, Coding agents can only generate patches in isolated workspaces, and GitHub write operations require explicit human approval gates.

---

## ADR-006: Server-Sent Events (SSE) for Real-Time Workflow Progress

* **Technology**: Server-Sent Events (SSE) via Spring Boot and Next.js
* **Reason**: AI engineering tasks take time (analyzing, planning, coding, testing, reviewing). Users need real-time progress feedback without needing to manually refresh or implement aggressive client polling.
* **Alternatives Considered**:
  - WebSockets
  - Short HTTP Polling
* **Why Selected**: SSE is lightweight, unidirectional (server-to-client), operates over standard HTTP/HTTPS, automatically handles reconnection, and fits REST architecture cleanly.

---

## ADR-007: Project Access Control, Hierarchy & Soft Deletion Data Isolation

* **Technology**: Custom Spring Security Guard (`ProjectAuthorizationService`) + Soft Delete (`ProjectStatus.DELETED`)
* **Reason**: Multi-user workspace security requires strict project-level data isolation and role-based authorization hierarchy (`OWNER` > `ADMIN` > `MEMBER` > `VIEWER`). Additionally, deleting projects must not break historical compliance or audit logs (`audit_logs.project_id` foreign key).
* **Alternatives Considered**:
  - Hard SQL deletion (`DELETE FROM projects`) with cascading foreign keys
  - Global system admin permission flag without per-project membership mapping
* **Why Selected**: Soft deletion updates project status to `DELETED` and automatically sets `deleted_at`, preserving audit history while excluding the project from normal user queries. Hierarchical numerical permissions (`OWNER` = 4, `ADMIN` = 3, `MEMBER` = 2, `VIEWER` = 1) enable fine-grained action guards (`ADMIN` for member/settings modification, `OWNER` for deletion).

---

## ADR-008: AES-256 GCM Encrypted GitHub OAuth Token Storage & Zero Exposure Boundaries

* **Technology**: `TokenEncryptionService` (AES-256-GCM with 128-bit authentication tag & 12-byte IV)
* **Reason**: GitHub access tokens grant access to user codebases. Storing plaintext OAuth tokens in database tables risks catastrophic secret leakage in DB dumps. Exposing tokens to the frontend or LLMs creates dangerous injection attack vectors.
* **Alternatives Considered**:
  - Plaintext database column storage
  - Hash-only storage (incompatible because tokens must be decrypted to make authenticated GitHub REST API calls)
* **Why Selected**: AES-256 GCM provides authenticated encryption at rest. Raw OAuth tokens are decrypted in-memory strictly when making backend-to-GitHub API requests, and are NEVER returned in REST DTOs, NEVER logged, and NEVER passed to the AI Service / LLM prompts.
