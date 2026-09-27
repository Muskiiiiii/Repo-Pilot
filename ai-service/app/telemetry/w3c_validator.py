import re
from typing import Dict, List, Optional

TRACEPARENT_REGEX = re.compile(r'^([0-9a-fA-F]{2})-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$')
ALL_ZERO_TRACE_ID = "0" * 32
ALL_ZERO_PARENT_ID = "0" * 16

class W3CValidationResult:
    def __init__(
        self,
        is_valid: bool,
        trace_id: Optional[str] = None,
        parent_id: Optional[str] = None,
        trace_flags: Optional[str] = None,
        reason: Optional[str] = None
    ):
        self.is_valid = is_valid
        self.trace_id = trace_id
        self.parent_id = parent_id
        self.trace_flags = trace_flags
        self.reason = reason

def validate_w3c_traceparent(traceparent: str, duplicate_detected: bool = False) -> W3CValidationResult:
    if duplicate_detected:
        return W3CValidationResult(False, reason="Duplicate traceparent headers detected (header injection defense)")

    if not traceparent:
        return W3CValidationResult(False, reason="Missing traceparent header")

    # Header size cap (512 bytes)
    if len(traceparent.encode('utf-8')) > 512:
        return W3CValidationResult(False, reason="Oversized traceparent header (>512 bytes)")

    # Lowercase hex check: uppercase hex (A-F) is rejected
    if any(c in 'ABCDEF' for c in traceparent):
        return W3CValidationResult(False, reason="Uppercase hex characters rejected")

    match = TRACEPARENT_REGEX.match(traceparent)
    if not match:
        return W3CValidationResult(False, reason="Malformed traceparent format")

    version, trace_id, parent_id, trace_flags = match.groups()

    # Version 00 check: exact length must be 55 chars
    if version == "00" and len(traceparent) != 55:
        return W3CValidationResult(False, reason="Version 00 length != 55")

    # Future version: length < 55 is rejected
    if version != "00" and len(traceparent) < 55:
        return W3CValidationResult(False, reason="Unsupported future version length < 55")

    # All-zero trace-id check
    if trace_id == ALL_ZERO_TRACE_ID:
        return W3CValidationResult(False, reason="All-zero trace-id rejected")

    # All-zero parent-id check
    if parent_id == ALL_ZERO_PARENT_ID:
        return W3CValidationResult(False, reason="All-zero parent-id rejected")

    return W3CValidationResult(True, trace_id=trace_id, parent_id=parent_id, trace_flags=trace_flags)
