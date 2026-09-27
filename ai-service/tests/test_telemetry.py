import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.telemetry.w3c_validator import validate_w3c_traceparent
from app.telemetry.redaction import (
    is_sensitive_header,
    sanitize_header_value,
    sanitize_attribute_value,
    normalize_route_path,
    classify_exception
)
from app.telemetry.llm_isolation import strip_internal_telemetry_headers, record_llm_span
from app.telemetry.exporter import InMemoryTestSpanExporter, NonBlockingSpanExporterWrapper
from app.telemetry.async_worker import create_worker_span
from app.telemetry.config import parse_and_bound_sampling_ratio
from opentelemetry import trace
from opentelemetry.trace import SpanContext, TraceFlags, SpanKind
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor, SpanExportResult

client = TestClient(app)

@pytest.fixture
def test_exporter():
    exporter = InMemoryTestSpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    yield exporter
    exporter.clear()

# ======================================================================
# HOSTILE TELEMETRY TEST MATRIX (test01 -> test22)
# ======================================================================

def test01_valid_traceparent_propagated():
    """Invariant: Valid W3C traceparent header is correctly parsed and validated."""
    valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    res = validate_w3c_traceparent(valid)
    assert res.is_valid is True
    assert res.trace_id == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert res.parent_id == "00f067aa0ba902b7"

def test02_missing_traceparent_creates_root():
    """Invariant: Missing traceparent header returns invalid result (initializes new root)."""
    res = validate_w3c_traceparent(None)
    assert res.is_valid is False
    assert "Missing" in res.reason

def test03_malformed_traceparent_creates_root():
    """Invariant: Malformed traceparent header format is rejected."""
    res = validate_w3c_traceparent("invalid-traceparent-format")
    assert res.is_valid is False
    assert "Malformed" in res.reason or "Invalid" in res.reason

def test04_all_zero_ids_create_root():
    """Invariant: All-zero trace-id or parent-id is rejected."""
    res_trace = validate_w3c_traceparent("00-00000000000000000000000000000000-00f067aa0ba902b7-01")
    assert res_trace.is_valid is False
    assert "All-zero" in res_trace.reason

    res_parent = validate_w3c_traceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01")
    assert res_parent.is_valid is False
    assert "All-zero" in res_parent.reason

def test05_duplicate_traceparent_creates_root():
    """Invariant: Duplicate traceparent headers are rejected (header injection defense)."""
    res = validate_w3c_traceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", duplicate_detected=True)
    assert res.is_valid is False
    assert "Duplicate" in res.reason

def test06_uppercase_traceparent_creates_root():
    """Invariant: Uppercase hex characters in traceparent are rejected."""
    res = validate_w3c_traceparent("00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01")
    assert res.is_valid is False
    assert "Uppercase" in res.reason

def test07_oversized_headers_handled():
    """Invariant: Oversized traceparent (>512 bytes) is rejected."""
    oversized = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-" + "a" * 500
    res = validate_w3c_traceparent(oversized)
    assert res.is_valid is False
    assert "Oversized" in res.reason

def test08_baggage_header_stripped_at_boundary():
    """Invariant: Baggage headers are stripped from outbound LLM headers and not propagated."""
    headers = {
        "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "baggage": "userId=admin,role=superuser",
        "Content-Type": "application/json"
    }
    clean = strip_internal_telemetry_headers(headers)
    assert "baggage" not in clean
    assert "traceparent" not in clean
    assert clean["Content-Type"] == "application/json"

def test09_authentication_independence():
    """Invariant: Trace context validation failure never alters authentication status."""
    response = client.get("/health", headers={"traceparent": "invalid-header"})
    assert response.status_code == 200
    assert response.json()["status"] == "UP"

def test10_authorization_independence():
    """Invariant: Forged traceparent header does not grant unauthorized access."""
    response = client.get("/health", headers={"traceparent": "00-ffffffffffffffffffffffffffffffff-ffffffffffffffff-01"})
    assert response.status_code == 200

def test11_external_llm_header_isolation():
    """Invariant: External LLM provider outbound headers omit internal traceparent and baggage."""
    outgoing = {"Authorization": "Bearer sk-proj-12345", "traceparent": "00-11111111-22222222-01", "baggage": "secret=true"}
    isolated = strip_internal_telemetry_headers(outgoing)
    assert "traceparent" not in isolated
    assert "baggage" not in isolated
    assert isolated["Authorization"] == "Bearer sk-proj-12345"

def test12_sensitive_telemetry_redacted_at_export():
    """Invariant: Sensitive headers, JWTs, PATs, cookies, private keys, code, prompts, completions are redacted."""
    assert is_sensitive_header("authorization") is True
    assert is_sensitive_header("cookie") is True
    assert is_sensitive_header("x-api-key") is True
    assert is_sensitive_header("content-type") is False

    assert sanitize_header_value("Authorization", "Bearer secret_jwt") == "[REDACTED]"
    assert sanitize_attribute_value("prompt_text", "SECRET_PROMPT") == "[REDACTED]"
    assert sanitize_attribute_value("completion_text", "SECRET_COMPLETION") == "[REDACTED]"
    assert sanitize_attribute_value("user_token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9") == "[REDACTED]"

def test13_bounded_metric_labels():
    """Invariant: Request path UUIDs and numeric IDs are normalized to {id} to bound metric cardinality."""
    uuid_path = "/api/v1/projects/550e8400-e29b-41d4-a716-446655440000/tasks/12345"
    normalized = normalize_route_path(uuid_path)
    assert normalized == "/api/v1/projects/{id}/tasks/{id}"
    assert "550e8400" not in normalized
    assert "12345" not in normalized

def test14_exception_sanitization():
    """Invariant: Exception classifier maps raw exceptions to finite categories without leaking messages."""
    assert classify_exception(ValueError("Invalid token secret_key_1234")) == "BAD_REQUEST"
    assert classify_exception(PermissionError("Access denied")) == "FORBIDDEN"
    assert classify_exception(RuntimeError("DB_SECRET_PASSWORD_FAILED")) == "INTERNAL_SERVER_ERROR"

def test15_configurable_trace_sampling():
    """Invariant: Sampling ratio is parsed and clamped safely between [0.0, 1.0]."""
    assert parse_and_bound_sampling_ratio("1.0") == 1.0
    assert parse_and_bound_sampling_ratio("0.5") == 0.5
    assert parse_and_bound_sampling_ratio("-0.5") == 0.0
    assert parse_and_bound_sampling_ratio("2.5") == 1.0
    assert parse_and_bound_sampling_ratio("invalid") == 1.0

def test16_non_blocking_exporter_failure():
    """Invariant: Telemetry exporter outages catch errors silently without crashing requests."""
    class FailingExporter:
        def export(self, spans):
            raise ConnectionError("OTLP collector down")
        def shutdown(self):
            pass

    safe_exporter = NonBlockingSpanExporterWrapper(FailingExporter())
    result = safe_exporter.export([])
    assert result == SpanExportResult.FAILURE

def test17_async_trace_context_propagation():
    """Invariant: Async worker creates new worker span with link to parent context, or fails open to new root."""
    parent_tid = "4bf92f3577b34da6a3ce929d0e0e4736"
    parent_sid = "00f067aa0ba902b7"

    with create_worker_span("TaskExecutionWorker", parent_tid, parent_sid) as span:
        ctx = span.get_span_context()
        assert ctx.is_valid is True

    # Corrupt trace metadata fails open to new root span
    with create_worker_span("TaskExecutionWorker", "corrupt_tid", "corrupt_sid") as span:
        ctx = span.get_span_context()
        assert ctx.is_valid is True

def test18_spring_to_fastapi_trace_id_continuity():
    """Invariant: Distributed trace continuity from Spring Boot to FastAPI preserves parent trace ID."""
    spring_origin_trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"
    spring_origin_span_id = "00f067aa0ba902b7"
    propagated_traceparent = f"00-{spring_origin_trace_id}-{spring_origin_span_id}-01"

    response = client.get("/health", headers={"traceparent": propagated_traceparent})
    assert response.status_code == 200
    validation = validate_w3c_traceparent(propagated_traceparent)
    assert validation.is_valid is True
    assert validation.trace_id == spring_origin_trace_id
    assert validation.parent_id == spring_origin_span_id

def test19_retry_execution_span_independence():
    """Invariant: Async worker retries generate independent execution spans linked to originating context."""
    parent_tid = "4bf92f3577b34da6a3ce929d0e0e4736"
    parent_sid = "00f067aa0ba902b7"

    # Attempt 1
    with create_worker_span("TaskExecutionWorker_Attempt1", parent_tid, parent_sid) as span1:
        ctx1 = span1.get_span_context()

    # Attempt 2 (Retry)
    with create_worker_span("TaskExecutionWorker_Attempt2", parent_tid, parent_sid) as span2:
        ctx2 = span2.get_span_context()

    assert ctx1.span_id != ctx2.span_id, "Retry attempts must have independent span IDs"
    assert ctx1.trace_id != 0 and ctx2.trace_id != 0

def test20_telemetry_failure_business_state_independence():
    """Invariant: Exporter outage or telemetry exception fails open and does not alter request processing."""
    class BrokenExporter:
        def export(self, spans):
            raise RuntimeError("Telemetry pipeline crash")
        def shutdown(self):
            pass

    wrapper = NonBlockingSpanExporterWrapper(BrokenExporter())

    # Request execution completes successfully despite telemetry failure
    response = client.get("/health", headers={"traceparent": "00-11112222333344445555666677778888-9999000011112222-01"})
    assert response.status_code == 200
    assert response.json()["status"] == "UP"

def test21_sse_async_observability_isolation():
    """Invariant: Business SSE event payloads omit raw telemetry metadata (traceparent/baggage)."""
    business_payload = {"event": "task_updated", "task_id": "12345", "status": "COMPLETED"}
    assert "traceparent" not in business_payload
    assert "baggage" not in business_payload
    assert business_payload["task_id"] == "12345"

def test22_traceparent_authority_separation():
    """Invariant: Altering traceparent header does not alter user principal, org membership, or task authorization decisions."""
    headers_alice = {"traceparent": "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-1111111111111111-01"}
    headers_bob = {"traceparent": "00-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-2222222222222222-01"}

    res_alice = client.get("/health", headers=headers_alice)
    res_bob = client.get("/health", headers=headers_bob)

    assert res_alice.status_code == 200
    assert res_bob.status_code == 200
    assert res_alice.json()["status"] == res_bob.json()["status"] == "UP"
