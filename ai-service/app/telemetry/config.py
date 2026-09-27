from typing import Any, Optional
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.sampling import TraceIdRatioBased, ALWAYS_ON, ALWAYS_OFF
from app.config import settings

def parse_and_bound_sampling_ratio(ratio_input: Any) -> float:
    if ratio_input is None:
        return 1.0
    try:
        val = float(str(ratio_input).strip())
        if val < 0.0:
            return 0.0
        if val > 1.0:
            return 1.0
        return val
    except (ValueError, TypeError):
        return 1.0

def get_bounded_sampler(ratio: float):
    if ratio >= 1.0:
        return ALWAYS_ON
    if ratio <= 0.0:
        return ALWAYS_OFF
    return TraceIdRatioBased(ratio)

_tracer_provider: Optional[TracerProvider] = None

def init_telemetry(sampling_ratio: Optional[float] = None) -> TracerProvider:
    global _tracer_provider
    if sampling_ratio is None:
        sampling_ratio = parse_and_bound_sampling_ratio(settings.OTEL_SAMPLING_RATIO)
    else:
        sampling_ratio = parse_and_bound_sampling_ratio(sampling_ratio)

    sampler = get_bounded_sampler(sampling_ratio)
    provider = TracerProvider(sampler=sampler)
    trace.set_tracer_provider(provider)
    _tracer_provider = provider
    return provider

def get_tracer(name: str = "repopilot.ai-service"):
    return trace.get_tracer(name)
