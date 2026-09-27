# RepoPilot — AI-Powered Software Engineering Platform

RepoPilot is an AI-powered software engineering platform that understands a GitHub codebase, converts a developer's issue into an implementation plan, generates structured code changes, executes tests in an isolated environment, reviews changes, and creates GitHub Pull Requests with an explainable implementation report.

---

## 🚀 Key Features

- **Codebase Indexing & Understanding**: Clones, filters, parses, and embeds codebases into PostgreSQL with `pgvector`.
- **Hybrid RAG Retrieval**: Combines semantic vector search with keyword symbol filtering for precise context.
- **Multi-Agent Orchestration**: Powered by LangGraph agents (Supervisor, Codebase, Planner, Coder, Tester, Reviewer).
- **Human-in-the-Loop Approval**: Explicit approval gates before code execution or GitHub write operations.
- **Automated Testing & Self-Correction**: Isolated Docker sandbox execution with up to 3 automated retry attempts on test failures.
- **Explainable Implementation Reports**: Transparent audit logs, confidence scoring, evidence citations, and PR delivery.

---

## 🏗 System Architecture

```text
Frontend (Next.js + TypeScript + Tailwind CSS)
       │
       ▼ REST / SSE
Backend (Java + Spring Boot + Spring Security)
       ├── PostgreSQL 16 + pgvector (Relational & Vector DB)
       └── Redis 7 (Caching & Job Queue)
       │
       ▼ REST / Internal Queue
AI Service (Python + FastAPI + LangGraph + Docker Sandbox)
       └── LLM / Embeddings Provider
```

---

## 🛠 Local Quickstart

### Prerequisites
- Docker Engine & Docker Compose
- Java 17 or 21 (for local backend development)
- Node.js 18+ (for local frontend development)
- Python 3.11+ (for local AI service development)

### Running with Docker Compose

1. Clone the repository and copy environment variables:
```bash
cp .env.example .env
```

2. Start all services:
```bash
make up
# or
docker-compose up --build -d
```

3. Service Endpoints:
- **Frontend App**: http://localhost:3000
- **Backend API**: http://localhost:8080 (`/actuator/health`)
- **AI Service**: http://localhost:8000 (`/health`)

---

## 🔑 GitHub OAuth Setup

To enable real GitHub integration locally:
1. Go to **GitHub Settings → Developer Settings → OAuth Apps → New OAuth App**.
2. Set **Application Name**: `RepoPilot Local`
3. Set **Homepage URL**: `http://localhost:3000`
4. Set **Authorization Callback URL**: `http://localhost:3000/api/auth/callback/github`
5. Copy the generated **Client ID** and **Client Secret** into your local `.env` file:
   ```ini
   GITHUB_CLIENT_ID=your_client_id
   GITHUB_CLIENT_SECRET=your_client_secret
   GITHUB_REDIRECT_URI=http://localhost:3000/api/auth/callback/github
   GITHUB_ENCRYPTION_KEY=YourCustom32ByteAESEncryptionKeyHere!
   ```

---

## 📁 Repository Structure

```text
RepoPilot/
├── docs/                     # Specifications, Architecture, & Implementation Plan
│   ├── 01-product-requirements.md
│   ├── 02-technical-requirements.md
│   ├── 03-ui-ux-design.md
│   ├── 04-system-architecture.md
│   ├── IMPLEMENTATION_PLAN.md
│   └── ARCHITECTURE_DECISIONS.md
├── frontend/                 # Next.js 14 + TypeScript + Tailwind CSS UI
├── backend/                  # Spring Boot 3 Java Backend
├── ai-service/               # Python FastAPI + LangGraph AI Service
├── infrastructure/           # Docker Compose & Deployment Scripts
├── docker-compose.yml
├── .env.example
├── Makefile
└── README.md
```

---

## 📝 License

Distributed under the MIT License.
