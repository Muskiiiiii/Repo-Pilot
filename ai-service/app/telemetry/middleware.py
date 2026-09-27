import logging
from typing import Dict, Optional
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response
from opentelemetry import trace
from opentelemetry.trace import SpanKind, StatusCode, SpanContext, TraceFlags
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

from app.telemetry.config import get_tracer
from app.telemetry.w3c_validator import validate_w3c_traceparent
from app.telemetry.redaction import (
    normalize_route_path,
    is_sensitive_header,
    sanitize_header_value,
    classify_exception
)

logger = logging.getLogger("repopilot.ai.telemetry.middleware")

# Context var for MDC trace correlation
import contextvars
_mdc_context: contextvars.ContextVar[Dict[str, str]] = contextvars.ContextVar("mdc_context", default={})

def get_mdc_context() -> Dict[str, str]:
    return _mdc_context.get()

class W3CTraceContextMiddleware(BaseHTTPMiddleware):
    """FastAPI / Starlette Middleware enforcing W3C Trace Context Validation, Baggage Stripping, and Redaction."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        raw_headers = request.headers

        # Check for duplicate traceparent headers (HTTP header injection defense)
        traceparent_values = raw_headers.getlist("traceparent") if hasattr(raw_headers, "getlist") else []
        duplicate_traceparent = len(traceparent_values) > 1

        traceparent_header = raw_headers.get("traceparent")
        tracestate_header = raw_headers.get("tracestate")

        # Check tracestate size cap (512 bytes)
        if tracestate_header and len(tracestate_header.encode("utf-8")) > 512:
            tracestate_header = None

        # Validate traceparent
        validation = validate_w3c_traceparent(traceparent_header, duplicate_detected=duplicate_traceparent)

        tracer = get_tracer("repopilot.ai-service")
        route_template = normalize_route_path(request.url.path)
        span_name = f"HTTP {request.method} {route_template}"

        parent_context = None
        if validation.is_valid and validation.trace_id and validation.parent_id:
            try:
                tid_int = int(validation.trace_id, 16)
                sid_int = int(validation.parent_id, 16)
                flags_int = int(validation.trace_flags or "01", 16)
                sc = SpanContext(
                    trace_id=tid_int,
                    span_id=sid_int,
                    is_remote=True,
                    trace_flags=TraceFlags(flags_int)
                )
                parent_context = trace.set_span_in_context(trace.NonRecordingSpan(sc))
            except Exception as e:
                logger.warning("Failed to construct remote span context, defaulting to local root: %s", str(e))
                parent_context = None

        # Start span (fresh root if parent_context is None)
        span = tracer.start_span(
            span_name,
            context=parent_context,
            kind=SpanKind.SERVER
        )

        span.set_attribute("http.method", request.method)
        span.set_attribute("http.route", route_template)
        span.set_attribute("http.target", route_template)

        # Sanitize and attach non-sensitive headers
        for name, value in raw_headers.items():
            if name.lower() in ("traceparent", "tracestate", "baggage"):
                continue
            sanitized_val = sanitize_header_value(name, value)
            span.set_attribute(f"http.header.{name.lower()}", sanitized_val)

        # Set MDC trace context
        span_ctx = span.get_span_context()
        trace_id_hex = format(span_ctx.trace_id, "032x") if span_ctx.trace_id else ""
        span_id_hex = format(span_ctx.span_id, "016x") if span_ctx.span_id else ""

        token = _mdc_context.set({
            "trace_id": trace_id_hex,
            "span_id": span_id_hex
        })

        try:
            with trace.use_span(span, end_on_exit=True):
                response = await call_next(request)
                span.set_attribute("http.status_code", response.status_code)
                if response.status_code >= 500:
                    span.set_status(StatusCode.ERROR)
                else:
                    span.set_status(StatusCode.OK)
                return response
        except Exception as exc:
            span.set_attribute("http.status_code", 500)
            error_cat = classify_exception(exc)
            span.set_attribute("error.type", error_cat)
            span.set_status(StatusCode.ERROR)
            raise exc
        finally:
            _mdc_context.reset(token)
