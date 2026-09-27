from typing import Dict, Any, Optional
from opentelemetry import trace
from opentelemetry.trace import SpanKind, StatusCode
from app.telemetry.config import get_tracer

INTERNAL_TELEMETRY_HEADERS = {
    "traceparent",
    "tracestate",
    "baggage",
    "x-b3-traceid",
    "x-b3-spanid",
    "x-b3-sampled",
}

def strip_internal_telemetry_headers(headers: Dict[str, str]) -> Dict[str, str]:
    if not headers:
        return {}
    clean_headers = {}
    for k, v in headers.items():
        if k.lower().strip() not in INTERNAL_TELEMETRY_HEADERS:
            clean_headers[k] = v
    return clean_headers

def record_llm_span(
    provider: str,
    model: str,
    operation: str,
    prompt_tokens: int = 0,
    completion_tokens: int = 0,
    error: Optional[Exception] = None
):
    tracer = get_tracer("repopilot.ai.llm")
    span_name = f"llm.{operation}"
    with tracer.start_as_current_span(span_name, kind=SpanKind.CLIENT) as span:
        # Bounded taxonomy attributes ONLY
        span.set_attribute("gen_ai.system", str(provider))
        span.set_attribute("gen_ai.request.model", str(model))
        span.set_attribute("gen_ai.operation", str(operation))
        if prompt_tokens > 0:
            span.set_attribute("gen_ai.usage.prompt_tokens", int(prompt_tokens))
        if completion_tokens > 0:
            span.set_attribute("gen_ai.usage.completion_tokens", int(completion_tokens))

        if error:
            span.set_status(StatusCode.ERROR, str(type(error).__name__))
        else:
            span.set_status(StatusCode.OK)
