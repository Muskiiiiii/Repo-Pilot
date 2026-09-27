import re
from typing import Any, Dict, Set

SENSITIVE_HEADERS: Set[str] = {
    "authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "x-github-pat",
    "x-auth-token",
    "proxy-authorization",
}

UUID_PATTERN = re.compile(r'/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}')
NUMERIC_ID_PATTERN = re.compile(r'/\d+(?=/|$)')

def is_sensitive_header(header_name: str) -> bool:
    if not header_name:
        return False
    return header_name.lower().strip() in SENSITIVE_HEADERS

def sanitize_header_value(header_name: str, value: str) -> str:
    if is_sensitive_header(header_name):
        return "[REDACTED]"
    return value

def contains_sensitive_payload(val_str: str) -> bool:
    if not val_str:
        return False
    val_lower = val_str.lower()
    if "bearer " in val_lower or "eyjhbgci" in val_lower:
        return True
    if "session_id=" in val_lower or "sk-proj-" in val_lower or "ghp_" in val_lower:
        return True
    if "-----begin private key-----" in val_lower or "-----begin rsa private key-----" in val_lower:
        return True
    return False

def sanitize_attribute_value(key: str, value: Any) -> Any:
    if value is None:
        return None
    key_lower = key.lower()
    if any(s in key_lower for s in ["auth", "token", "cookie", "key", "password", "secret", "pat", "prompt", "completion", "code", "diff"]):
        return "[REDACTED]"
    val_str = str(value)
    if contains_sensitive_payload(val_str):
        return "[REDACTED]"
    return value

def normalize_route_path(path: str) -> str:
    if not path:
        return "/"
    normalized = UUID_PATTERN.sub('/{id}', path)
    normalized = NUMERIC_ID_PATTERN.sub('/{id}', normalized)
    return normalized

def classify_exception(exc: Exception) -> str:
    if exc is None:
        return "NONE"
    exc_type = type(exc).__name__
    if exc_type in ("AuthenticationError", "UnauthorizedError"):
        return "UNAUTHORIZED"
    if exc_type in ("PermissionError", "ForbiddenError", "AccessDeniedError"):
        return "FORBIDDEN"
    if exc_type in ("ValueError", "KeyError", "HTTPException", "RequestValidationError"):
        return "BAD_REQUEST"
    if exc_type in ("TimeoutError", "AsyncTimeoutError", "TimeoutException"):
        return "TIMED_OUT"
    return "INTERNAL_SERVER_ERROR"
