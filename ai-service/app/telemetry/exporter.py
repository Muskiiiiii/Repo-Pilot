import logging
from typing import Sequence, List, Optional
from opentelemetry.sdk.trace.export import SpanExporter, SpanExportResult
from opentelemetry.sdk.trace import ReadableSpan

logger = logging.getLogger("repopilot.ai.telemetry.exporter")

class InMemoryTestSpanExporter(SpanExporter):
    """In-memory span exporter for unit/hostile test assertions."""

    def __init__(self):
        self._finished_spans: List[ReadableSpan] = []

    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        self._finished_spans.extend(spans)
        return SpanExportResult.SUCCESS

    def get_finished_spans(self) -> List[ReadableSpan]:
        return list(self._finished_spans)

    def clear(self):
        self._finished_spans.clear()

    def shutdown(self):
        self.clear()

class NonBlockingSpanExporterWrapper(SpanExporter):
    """Safe wrapper ensuring export failures never block or raise exceptions."""

    def __init__(self, delegate: SpanExporter):
        self.delegate = delegate

    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        try:
            return self.delegate.export(spans)
        except Exception as e:
            logger.warning("Telemetry export failed (silently caught): %s", str(e))
            return SpanExportResult.FAILURE

    def shutdown(self):
        try:
            self.delegate.shutdown()
        except Exception:
            pass
