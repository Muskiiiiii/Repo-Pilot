import pytest
from httpx import AsyncClient, ASGITransport
from app.main import app
from app.workflow.state import EngineeringWorkflowState, WorkflowRunRequest, WorkflowResumeRequest
from app.workflow.service import WorkflowService
from app.workflow.graph import build_engineering_graph

@pytest.fixture
def anyio_backend():
    return 'asyncio'

@pytest.mark.asyncio
async def test_01_successful_workflow_execution():
    """Test 1: Full successful execution of the LangGraph engineering workflow."""
    req = WorkflowRunRequest(
        task_id="task-test-01-success",
        repository_id="repo-123",
        project_id="proj-123",
        issue_title="Add voting payload validation",
        issue_description="Ensure candidate ID is strictly positive in vote request.",
        file_contents={"src/voting.js": "function vote(id) { return true; }\n"},
        auto_approve=True,
        max_repair_attempts=3
    )
    resp = await WorkflowService.run(req)

    assert resp.task_id == "task-test-01-success"
    assert resp.workflow_status == "COMPLETED"
    assert resp.approval_status == "APPROVED"
    assert resp.is_published is False  # Never published directly by AI service
    assert resp.plan is not None
    assert len(resp.code_changes) > 0
    assert resp.validation_results["valid"] is True
    assert resp.test_results["passed"] is True
    assert resp.review_results is not None

@pytest.mark.asyncio
async def test_02_issue_understanding_fallback():
    """Test 2: Workflow handles edge case / fallback when issue understanding has unusual input."""
    req = WorkflowRunRequest(
        task_id="task-test-02-fallback",
        repository_id="repo-123",
        issue_title="Fix edge case",
        issue_description="Very sparse desc",
        file_contents={"src/app.js": "console.log('hi');\n"},
        auto_approve=True
    )
    resp = await WorkflowService.run(req)

    assert resp.task_id == "task-test-02-fallback"
    assert resp.workflow_status == "COMPLETED"
    assert resp.plan is not None

@pytest.mark.asyncio
async def test_03_missing_or_empty_repository_context():
    """Test 3: Empty codebase evidence chunks correctly flag ambiguity without crashing."""
    req = WorkflowRunRequest(
        task_id="task-test-03-empty-context",
        repository_id="repo-123",
        issue_title="Implement obscure feature with no matching files",
        issue_description="No code exists for this yet",
        file_contents={},
        evidence_chunks=[],
        auto_approve=True
    )
    resp = await WorkflowService.run(req)

    # Empty context causes planner to set is_ambiguous=True
    assert resp.plan is not None
    assert resp.plan.get("is_ambiguous") is True

@pytest.mark.asyncio
async def test_04_code_generation_security_rejection():
    """Test 4: Proposed changes containing leaked API keys are rejected by security policy."""
    req = WorkflowRunRequest(
        task_id="task-test-04-sec-reject",
        repository_id="repo-123",
        issue_title="Leaked key test",
        file_contents={"src/config.js": "const API_KEY = 'AKIA1234567890123456';\n"},
        evidence_chunks=[{
            "chunkId": "chunk-1",
            "filePath": "src/config.js",
            "contentSnippet": "const API_KEY = 'AKIA1234567890123456';",
            "similarityScore": 0.95
        }],
        auto_approve=True
    )
    resp = await WorkflowService.run(req)

    # Should detect secret in proposed content or fail validation
    assert resp.workflow_status in ["FAILED_SECURITY_POLICY", "VALIDATION_FAILED", "FAILED_VALIDATION"] or resp.validation_results.get("valid") is False

@pytest.mark.asyncio
async def test_05_successful_test_execution_advances_to_review():
    """Test 5: Passing tests bypass repair loop and advance directly to AI Code Review."""
    req = WorkflowRunRequest(
        task_id="task-test-05-direct-pass",
        repository_id="repo-123",
        issue_title="Clean feature implementation",
        file_contents={"src/clean.js": "function clean() { return 1; }\n"},
        test_runner_config={"force_failure": False},
        auto_approve=True
    )
    resp = await WorkflowService.run(req)

    assert resp.test_results["passed"] is True
    assert resp.repair_attempts == 0
    assert resp.review_results is not None
    assert resp.workflow_status == "COMPLETED"

@pytest.mark.asyncio
async def test_06_failed_tests_followed_by_successful_repair():
    """Test 6: Tests fail on attempt 0, get diagnosed and repaired, then pass on attempt 1."""
    req = WorkflowRunRequest(
        task_id="task-test-06-repair-success",
        repository_id="repo-123",
        issue_title="Repairable bug",
        file_contents={"src/calc.js": "function add(a, b) { return a - b; }\n"},
        test_runner_config={
            "fail_first_n_times": 1,
            "failure_message": "AssertionError: expected add(1, 2) to equal 3 but got -1"
        },
        auto_approve=True,
        max_repair_attempts=3
    )
    resp = await WorkflowService.run(req)

    assert resp.repair_attempts == 1
    assert resp.test_results["passed"] is True
    assert resp.workflow_status == "COMPLETED"

@pytest.mark.asyncio
async def test_07_repeated_test_failures_reach_repair_limit():
    """Test 7: Repeated test failures strictly respect max_repair_attempts limit."""
    req = WorkflowRunRequest(
        task_id="task-test-07-max-repairs",
        repository_id="repo-123",
        issue_title="Persistent failing bug",
        file_contents={"src/broken.js": "function broken() { throw new Error('fail'); }\n"},
        test_runner_config={
            "force_failure": True,
            "failure_message": "Error: Persistent failure that cannot pass"
        },
        max_repair_attempts=2,
        auto_approve=True
    )
    resp = await WorkflowService.run(req)

    assert resp.repair_attempts == 2
    assert resp.test_results["passed"] is False
    assert resp.workflow_status in ["FAILED_MAX_REPAIRS_REACHED", "FAILED"]

@pytest.mark.asyncio
async def test_08_non_repairable_failure_terminates_safely():
    """Test 8: Diagnosis recommendation DO_NOT_REPAIR halts workflow immediately without retry loops."""
    req = WorkflowRunRequest(
        task_id="task-test-08-non-repairable",
        repository_id="repo-123",
        issue_title="Hardware constraint failure",
        file_contents={"src/hw.js": "function hw() {}\n"},
        test_runner_config={
            "force_failure": True,
            "non_repairable": True,
            "failure_message": "Fatal: kernel unsupported architectural requirement"
        },
        max_repair_attempts=3,
        auto_approve=True
    )
    resp = await WorkflowService.run(req)

    assert resp.workflow_status in ["FAILED_NON_REPAIRABLE", "FAILED"]
    # Should not consume repair attempts when marked non-repairable
    assert resp.repair_attempts == 0

@pytest.mark.asyncio
async def test_09_test_infrastructure_error_handling():
    """Test 9: Test infrastructure errors (Docker timeout / OOM) do not get treated as code defects."""
    req = WorkflowRunRequest(
        task_id="task-test-09-infra-error",
        repository_id="repo-123",
        issue_title="Infra test",
        file_contents={"src/app.js": "function app() {}\n"},
        test_runner_config={"mock_infrastructure_error": True},
        max_repair_attempts=3,
        auto_approve=True
    )
    resp = await WorkflowService.run(req)

    assert resp.workflow_status in ["FAILED_TEST_INFRASTRUCTURE", "TEST_INFRASTRUCTURE_ERROR", "FAILED"]
    assert resp.test_results["is_infrastructure_error"] is True
    assert resp.repair_attempts == 0

@pytest.mark.asyncio
async def test_10_ai_code_review_execution():
    """Test 10: AI Code Review executes and populates structured findings."""
    req = WorkflowRunRequest(
        task_id="task-test-10-review",
        repository_id="repo-123",
        issue_title="Feature requiring review",
        file_contents={"src/service.js": "function process() { return true; }\n"},
        auto_approve=True
    )
    resp = await WorkflowService.run(req)

    assert resp.review_results is not None
    assert "summary" in resp.review_results
    assert "findings" in resp.review_results

@pytest.mark.asyncio
async def test_11_human_in_the_loop_pause_and_approval():
    """Test 11: Workflow pauses at approval boundary and resumes successfully on human approval."""
    # Step 1: Run workflow with auto_approve=False
    req = WorkflowRunRequest(
        task_id="task-test-11-hitl",
        repository_id="repo-123",
        issue_title="Feature requiring human approval",
        file_contents={"src/vote.js": "function vote() {}\n"},
        auto_approve=False
    )
    resp1 = await WorkflowService.run(req)

    assert resp1.workflow_status == "AWAITING_APPROVAL"
    assert resp1.approval_status == "PENDING"
    assert resp1.is_published is False

    # Step 2: Human approves via resume endpoint
    resume_req = WorkflowResumeRequest(
        task_id="task-test-11-hitl",
        approved=True,
        approver="lead_engineer@repopilot.io",
        reason="Verified changes and test assertions pass."
    )
    resp2 = await WorkflowService.resume(resume_req)

    assert resp2.workflow_status == "COMPLETED"
    assert resp2.approval_status == "APPROVED"
    assert resp2.is_published is False  # Safe publication boundary

@pytest.mark.asyncio
async def test_12_human_in_the_loop_rejection():
    """Test 12: Human rejection terminates workflow safely with REJECTED status."""
    req = WorkflowRunRequest(
        task_id="task-test-12-hitl-reject",
        repository_id="repo-123",
        issue_title="Feature that human reviewer rejects",
        file_contents={"src/vote.js": "function vote() {}\n"},
        auto_approve=False
    )
    resp1 = await WorkflowService.run(req)
    assert resp1.workflow_status == "AWAITING_APPROVAL"

    resume_req = WorkflowResumeRequest(
        task_id="task-test-12-hitl-reject",
        approved=False,
        approver="security_auditor@repopilot.io",
        reason="Security concerns with proposed changes."
    )
    resp2 = await WorkflowService.resume(resume_req)

    assert resp2.workflow_status == "REJECTED"
    assert resp2.approval_status == "REJECTED"
    assert resp2.is_published is False

@pytest.mark.asyncio
async def test_13_prevention_of_github_publication_without_approval():
    """Test 13: Under no circumstances is is_published set to True without approval."""
    req = WorkflowRunRequest(
        task_id="task-test-13-no-publish",
        repository_id="repo-123",
        issue_title="Feature checking publication prevention",
        file_contents={"src/test.js": "test\n"},
        auto_approve=False
    )
    resp = await WorkflowService.run(req)
    assert resp.is_published is False
    assert resp.workflow_status == "AWAITING_APPROVAL"

@pytest.mark.asyncio
async def test_14_path_traversal_validation_failure():
    """Test 14: Changes with path traversal file paths are blocked by validation."""
    from app.workflow.nodes import validate_changes_node

    state: EngineeringWorkflowState = {
        "task_id": "task-test-14-traversal",
        "code_changes": [
            {
                "file_path": "../../etc/shadow",
                "change_type": "MODIFY",
                "proposed_content": "malicious content"
            }
        ]
    }
    result = await validate_changes_node(state)
    assert result["validation_results"]["valid"] is False
    assert any("Path traversal" in err for err in result["validation_results"]["errors"])
    assert result["workflow_status"] == "VALIDATION_FAILED"

@pytest.mark.asyncio
async def test_15_api_endpoints_validation_and_status():
    """Test 15: FastAPI HTTP endpoints validate inputs and handle errors appropriately."""
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # Bad request: empty task_id
        bad_run = await client.post("/api/v1/workflow/run", json={"task_id": "", "issue_title": "test", "repository_id": "r1"})
        assert bad_run.status_code == 400

        # Bad request / validation failure: empty issue_title
        bad_run2 = await client.post("/api/v1/workflow/run", json={"task_id": "t1", "issue_title": "", "repository_id": "r1"})
        assert bad_run2.status_code in [400, 422]

        # Successful workflow run via API
        run_resp = await client.post("/api/v1/workflow/run", json={
            "task_id": "task-test-15-api",
            "repository_id": "repo-api-1",
            "issue_title": "API validation task",
            "issue_description": "Validate API routes",
            "file_contents": {"src/api.js": "function test() {}\n"},
            "auto_approve": True
        })
        assert run_resp.status_code == 200
        body = run_resp.json()
        assert body["task_id"] == "task-test-15-api"
        assert body["workflow_status"] == "COMPLETED"

        # Check status endpoint
        status_resp = await client.get("/api/v1/workflow/status/task-test-15-api")
        assert status_resp.status_code == 200
        assert status_resp.json()["task_id"] == "task-test-15-api"

        # Status for non-existent task
        not_found = await client.get("/api/v1/workflow/status/non-existent-task-id-xyz")
        assert not_found.status_code == 404
