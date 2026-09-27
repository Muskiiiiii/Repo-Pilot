import re
from typing import List

SECRET_PATTERNS: List[re.Pattern] = [
    re.compile(r"AKIA[0-9A-Z]{16}"),  # AWS Access Key ID
    re.compile(r"(?i)(?:aws_secret_access_key|aws_secret_key)\s*[:=]\s*[\"']?[a-zA-Z0-9/+=]{40}[\"']?"),  # AWS Secret Key
    re.compile(r"gh[pousr]_[a-zA-Z0-9]{30,40}"),  # GitHub Tokens
    re.compile(r"github_pat_[a-zA-Z0-9_]{30,90}"),  # GitHub PAT
    re.compile(r"xox[baprs]-[a-zA-Z0-9_-]{10,90}"),  # Slack Token
    re.compile(r"(?:sk|rk)_live_[a-zA-Z0-9]{24,}"),  # Stripe Key
    re.compile(r"eyJ[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+\.[a-zA-Z0-9_\-]+"),  # JWT
    re.compile(r"sk-[a-zA-Z0-9]{32,}"),  # OpenAI API Key
    re.compile(r"pa-[a-zA-Z0-9]{32,}"),  # Voyage API Key
    re.compile(r"-----BEGIN (?:RSA |EC |PGP |OPENSSH )?PRIVATE KEY-----"),  # Private Key
    re.compile(r"(?i)(?:api_key|apikey|secret_key|jwt_secret|password|token|access_token|private_key)\s*[:=]\s*[\"'][a-zA-Z0-9_\-+=/]{8,}[\"']")  # Generic Secret
]

def contains_secret(text: str) -> bool:
    if not text:
        return False
    for pattern in SECRET_PATTERNS:
        if pattern.search(text):
            return True
    return False

def redact_secrets(text: str) -> str:
    if not text:
        return text
    redacted = text
    for pattern in SECRET_PATTERNS:
        redacted = pattern.sub("[REDACTED_SECRET]", redacted)
    return redacted
