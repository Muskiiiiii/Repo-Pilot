import logging
from typing import Optional
from opentelemetry import trace
from opentelemetry.trace import SpanContext, TraceFlags, Link, SpanKind
from app.telemetry.config import get_tracer

logger = logging.getLogger("repopilot.ai.telemetry.worker")

def create_worker_span(
    name: str,
    parent_trace_id: Optional[str] = None,
    parent_span_id: Optional[str] = None,
    parent_trace_flags: Optional[str] = None
):
    tracer = get_tracer("repopilot.ai.worker")
    links = []

    if parent_trace_id and parent_span_id:
        try:
            tid_int = int(parent_trace_id, 16)
            sid_int = int(parent_span_id, 16)
            flags_int = int(parent_trace_flags or "01", 16)

            if tid_int != 0 and sid_int != 0:
                remote_context = SpanContext(
                    trace_id=tid_int,
                    span_id=sid_int,
                    is_remote=True,
                    trace_flags=TraceFlags(flags_int)
                )
                links.append(Link(remote_context))
        except Exception as e:
            logger.warning("Corrupt worker trace metadata ignored, creating fresh root worker span: %s", str(e))

    return tracer.start_as_current_span(
        name,
        kind=SpanKind.CONSUMER,
        links=links
    )
