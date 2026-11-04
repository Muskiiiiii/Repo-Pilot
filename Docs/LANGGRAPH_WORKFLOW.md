# RepoPilot LangGraph Workflow Orchestration

## 1. Overview & Architectural Motivation

RepoPilot is an agentic software engineering platform. Rather than executing isolated LLM calls sequentially or relying on hardcoded procedural scripts, RepoPilot uses **LangGraph** to construct a stateful, cyclic, and deterministic execution graph.

### Why LangGraph?
1. **Cyclic Failure-Repair Loops**: Software engineering requires self-correction. If tests or validation fail, the workflow loops through `diagnose_failure` -> `repair_code` -> `validate_changes` -> `execute_tests`, strictly bounded by a configurable retry limit.
2. **Deterministic State Transitions & Types**: A shared, strongly-typed state (`EngineeringWorkflowState`) ensures each node receives validated context and produces predictable updates.
3. **Checkpointed Pause-and-Resume**: Human-in-the-loop approval requires the workflow to pause safely, persist state via a checkpointer (`MemorySaver`), and resume seamlessly upon an authorized decision.
4. **Boundary Separation**: The AI service orchestrates the cognitive engineering loop, while the Java Spring Boot backend retains responsibility for repository persistence, workspace sandboxing, user authorization, and GitHub operations.

---

## 2. LangGraph Architecture Diagram

```mermaid
flowchart TD
    START([START]) --> UI[understand_issue]
    UI -->|Valid Analysis| RC[retrieve_context]
    UI -->|Analysis Failure| FIN[finalize_workflow]
    
    RC --> GP[generate_plan]
    GP -->|Valid Plan| GC[generate_code]
    GP -->|Planning Failure| FIN
    
    GC -->|Changes Proposed| VC[validate_changes]
    GC -->|Security/Gen Failure| FIN
    
    VC -->|Valid Changes| ET[execute_tests]
    VC -->|Path/Secret Violation| FIN
    
    ET -->|Tests Pass| REV[review_code]
    ET -->|Infra Error| FIN
    ET -->|Tests Fail & Attempts < Max| DF[diagnose_failure]
    ET -->|Tests Fail & Attempts >= Max| FIN
    
    DF -->|Repairable| REP[repair_code]
    DF -->|Non-Repairable (DO_NOT_REPAIR)| FIN
    
    REP -->|Bounded Increment| VC
    
    REV --> CA[check_approval]
    CA -->|Awaiting Approval / Approved| FIN
    FIN --> END_NODE([END])
```

---

## 3. Workflow State Schema (`EngineeringWorkflowState`)

Defined in `app/workflow/state.py`:

| Field | Type | Description |
|---|---|---|
| `task_id` | `str` | Correlation identifier for the task |
| `repository_id` | `str` | Target repository identifier |
| `project_id` | `Optional[str]` | Parent project UUID |
| `issue_title` | `str` | User/developer issue title |
| `issue_description` | `str` | Issue details and user requirements |
| `issue_analysis` | `Optional[Dict]` | Structured understanding output from `analyze_issue` |
| `search_queries` | `List[str]` | Semantic retrieval queries |
| `retrieved_context` | `List[Dict]` | Codebase evidence chunks with similarity scores |
| `implementation_plan` | `Optional[Dict]` | Evidence-backed plan with step-by-step strategy |
| `code_changes` | `List[Dict]` | Proposed file modifications (CREATE/MODIFY/DELETE) |
| `file_contents` | `Dict[str, str]` | In-memory file snapshots |
| `validation_results` | `Dict[str, Any]` | Path security & secret scan results |
| `test_results` | `Dict[str, Any]` | Execution exit code, stdout/stderr, pass/fail status |
| `diagnosis` | `Optional[Dict]` | Root cause analysis and repair recommendation |
| `repair_attempts` | `int` | Monotonically incremented retry counter |
| `max_repair_attempts` | `int` | Configurable repair ceiling (default: 3) |
| `repair_history` | `List[Dict]` | Audit log of applied patches and rationales |
| `review_results` | `Optional[Dict]` | AI review findings, risk scores, and compliance |
| `approval_status` | `str` | `NOT_REQUESTED`, `PENDING`, `APPROVED`, or `REJECTED` |
| `approval_metadata` | `Optional[Dict]` | Approver ID, timestamp, and review reason |
| `is_published` | `bool` | Strict boolean guard; `False` until backend publishes |
| `workflow_status` | `str` | Current workflow execution phase or terminal status |
| `execution_metadata` | `Dict[str, Any]` | Timing, engine version, and audit information |

---

## 4. Graph Nodes & Reused Project Components

All nodes live in `app/workflow/nodes.py` and reuse existing RepoPilot core services:

1. **`understand_issue`**:
   - Calls `analyze_issue(IssueUnderstandingRequest)` from `app.planning.understanding`.
   - Sanitizes untrusted user prompt text.
   - Outputs structured problem statement and vector search queries.

2. **`retrieve_context`**:
   - Uses pre-provided chunks or evaluates search queries against file context.
   - Synthesizes evidence chunks formatted for grounding the planner.

3. **`generate_plan`**:
   - Calls `generate_implementation_plan(ImplementationPlanRequest)` from `app.planning.planner`.
   - Incorporates codebase evidence chunks.
   - Detects ambiguity and calculates confidence score.

4. **`generate_code`**:
   - Calls `generate_code_changes(CodeGenerationRequest)` from `app.planning.generator`.
   - Reuses `scan_for_secrets` to reject code containing leaked credentials before propagation.

5. **`validate_changes`**:
   - Validates file paths to prevent directory traversal (`..` or root escaping).
   - Validates operation types (`MODIFY`, `CREATE`, `DELETE`, `REPLACE`).
   - Ensures proposed contents are non-empty.

6. **`execute_tests`**:
   - Evaluates test suite or test runner callback.
   - Differentiates code failures from test infrastructure errors (Docker timeout, OOM).

7. **`diagnose_failure`**:
   - Calls `handle_diagnose_failure(DiagnosisRequest)` from `app.api.routes.testing`.
   - Separates trusted control information from untrusted test logs and compiler outputs.
   - Produces root cause and `repairRecommendation`.

8. **`repair_code`**:
   - Increments `repair_attempts += 1`.
   - Calls `handle_generate_repair_proposal(ProposalRequest)` from `app.api.routes.testing`.
   - Applies search-and-replace edits to target code changes and logs audit history.
   - Loops back to `validate_changes` for re-verification.

9. **`review_code`**:
   - Calls `perform_ai_review(ReviewContextInput)` from `app.review.reviewer`.
   - Evaluates security findings, bug risks, and compliance policy.

10. **`check_approval`**:
    - Enforces human-in-the-loop approval.
    - If unapproved, pauses state and transitions to `AWAITING_APPROVAL`.

11. **`finalize_workflow`**:
    - Synthesizes accurate terminal status (`COMPLETED`, `AWAITING_APPROVAL`, `REJECTED`, `FAILED_MAX_REPAIRS_REACHED`, `FAILED_NON_REPAIRABLE`, `FAILED_TEST_INFRASTRUCTURE`, `FAILED_VALIDATION`).
    - Records completion timestamps and telemetry metadata.

---

## 5. Conditional Routing Rules

| Edge Transition | Condition | Next Node |
|---|---|---|
| `understand_issue` | Status is `FAILED_*` | `finalize_workflow` |
| `understand_issue` | Normal | `retrieve_context` |
| `generate_plan` | Status is `FAILED_*` | `finalize_workflow` |
| `generate_plan` | Normal | `generate_code` |
| `generate_code` | Status is `FAILED_*` | `finalize_workflow` |
| `generate_code` | Normal | `validate_changes` |
| `validate_changes` | `validation_results.valid == False` | `finalize_workflow` |
| `validate_changes` | `validation_results.valid == True` | `execute_tests` |
| `execute_tests` | `is_infrastructure_error == True` | `finalize_workflow` (`FAILED_TEST_INFRASTRUCTURE`) |
| `execute_tests` | `passed == True` | `review_code` |
| `execute_tests` | `passed == False` AND `attempts >= max_attempts` | `finalize_workflow` (`FAILED_MAX_REPAIRS_REACHED`) |
| `execute_tests` | `passed == False` AND `attempts < max_attempts` | `diagnose_failure` |
| `diagnose_failure` | `repairRecommendation == "DO_NOT_REPAIR"` | `finalize_workflow` (`FAILED_NON_REPAIRABLE`) |
| `diagnose_failure` | `repairRecommendation != "DO_NOT_REPAIR"` | `repair_code` |
| `repair_code` | Unconditional loop | `validate_changes` |
| `review_code` | Unconditional | `check_approval` |
| `check_approval` | Unconditional | `finalize_workflow` |
| `finalize_workflow` | Unconditional | `END` |

---

## 6. API Endpoints

### 1. `POST /api/v1/workflow/run`
Initiates a new LangGraph workflow run.
- **Request Body**: `WorkflowRunRequest` (task_id, repository_id, issue_title, issue_description, file_contents, etc.)
- **Response Body**: `WorkflowResponse`

### 2. `POST /api/v1/workflow/resume`
Resumes a paused workflow awaiting human approval.
- **Request Body**: `WorkflowResumeRequest` (`task_id`, `approved`, `approver`, `reason`)
- **Response**: `WorkflowResponse` (Status: `COMPLETED` if approved, `REJECTED` if denied)

### 3. `GET /api/v1/workflow/status/{task_id}`
Returns current checkpoint state and metadata for a task.

---

## 7. Java Backend Integration

The Java backend integrates with the LangGraph workflow via `AiServiceClient`:
- `executeWorkflow(Map<String, Object> payload)`: Calls `/api/v1/workflow/run`
- `resumeWorkflow(Map<String, Object> payload)`: Calls `/api/v1/workflow/resume`
- `getWorkflowStatus(String taskId)`: Calls `/api/v1/workflow/status/{task_id}`

All existing endpoints (`/api/v1/planning/*`, `/api/v1/testing/*`, `/internal/ai/*`) remain 100% backward-compatible.

---

## 8. Verification & Running Tests

### Running Unit & Integration Tests:
```powershell
# Set PYTHONPATH to the ai-service directory
$env:PYTHONPATH="."
.\.venv_win\Scripts\python.exe -m pytest tests/test_workflow.py -v
```

### Running All AI Service Tests:
```powershell
$env:PYTHONPATH="."
.\.venv_win\Scripts\python.exe -m pytest -v
```
All 126 tests (111 existing tests + 15 new LangGraph workflow tests) execute and pass with zero failures.
