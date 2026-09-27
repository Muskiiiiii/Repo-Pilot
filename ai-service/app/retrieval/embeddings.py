import asyncio
from abc import ABC, abstractmethod
import hashlib
import logging
import math
import random
import time
from typing import List, Optional, Tuple
import httpx

from app.config import settings

logger = logging.getLogger("repopilot.ai.embeddings")

def estimate_tokens(text: str) -> int:
    """Conservatively estimate token count for code/text.
    Code typically averages ~2.5 to 3.5 characters per token.
    Using 3.0 chars/token (minimum 1 token for non-empty text) provides a conservative estimate.
    """
    if not text:
        return 0
    return max(1, math.ceil(len(text) / 3.0))


def create_token_aware_batches(
    texts: List[str],
    max_batch_size: int = 32,
    max_tokens_per_batch: int = 4000
) -> List[List[str]]:
    """Group complete input texts into sub-batches bounded by item count and estimated token count.

    Raises ValueError if any single text exceeds token or character limits, enforcing
    the invariant that texts must be bounded prior to entering the embedding pipeline.
    """
    if not texts:
        return []

    max_chunk_tokens = getattr(settings, "EMBEDDING_MAX_CHUNK_TOKENS", 2000)
    max_chunk_chars = getattr(settings, "EMBEDDING_MAX_CHUNK_CHARS", 6000)

    for idx, text in enumerate(texts):
        tokens = estimate_tokens(text)
        if tokens > max_chunk_tokens or len(text) > max_chunk_chars:
            raise ValueError(
                f"Input text at index {idx} exceeds chunk limits ({tokens} tokens / {len(text)} chars vs limit {max_chunk_tokens} tokens / {max_chunk_chars} chars)"
            )

    batches: List[List[str]] = []
    current_batch: List[str] = []
    current_tokens = 0

    for text in texts:
        tokens = estimate_tokens(text)
        if len(current_batch) >= max_batch_size or (current_tokens + tokens) > max_tokens_per_batch:
            if current_batch:
                batches.append(current_batch)
            current_batch = [text]
            current_tokens = tokens
        else:
            current_batch.append(text)
            current_tokens += tokens

    if current_batch:
        batches.append(current_batch)

    return batches


class EmbeddingPacer:
    """Sliding-window rate limiter and pacer supporting a reservation/commit/release model for requests and tokens."""

    def __init__(self, max_requests_per_minute: int = 2, max_tokens_per_minute: int = 8000):
        self.max_rpm = max_requests_per_minute
        self.max_tpm = max_tokens_per_minute
        self.events: List[dict] = []  # items: {"id": str, "timestamp": float, "tokens": int, "committed": bool}
        self.request_timestamps: List[float] = []
        self.token_events: List[Tuple[float, int]] = []
        self._lock = asyncio.Lock()
        self._counter = 0

    def _sync_legacy_attributes(self):
        self.request_timestamps = [ev["timestamp"] for ev in self.events]
        self.token_events = [(ev["timestamp"], ev["tokens"]) for ev in self.events]

    async def reserve_budget(self, estimated_tokens: int) -> str:
        """Wait if necessary to ensure RPM, TPM, and inter-request pacing are respected, then reserve budget."""
        async with self._lock:
            while True:
                now = time.monotonic()
                cutoff = now - 60.0

                # Prune events older than 60 seconds
                self.events = [ev for ev in self.events if ev["timestamp"] > cutoff]
                self._sync_legacy_attributes()

                active_rpm = len(self.events)
                active_tpm = sum(ev["tokens"] for ev in self.events)

                # Check RPM limit
                rpm_wait = 0.0
                if self.max_rpm > 0 and active_rpm >= self.max_rpm:
                    oldest_ts = self.events[0]["timestamp"]
                    rpm_wait = max(0.0, 60.0 - (now - oldest_ts) + 0.05)

                # Check inter-request pacing interval (spread requests evenly)
                pacing_wait = 0.0
                if self.max_rpm > 0 and self.events:
                    min_interval = 60.0 / self.max_rpm
                    last_ts = self.events[-1]["timestamp"]
                    time_since_last = now - last_ts
                    if time_since_last < min_interval:
                        pacing_wait = min_interval - time_since_last

                # Check TPM limit
                tpm_wait = 0.0
                if self.max_tpm > 0 and (active_tpm + estimated_tokens) > self.max_tpm and self.events:
                    accumulated = active_tpm
                    for ev in self.events:
                        accumulated -= ev["tokens"]
                        if (accumulated + estimated_tokens) <= self.max_tpm:
                            tpm_wait = max(0.0, 60.0 - (now - ev["timestamp"]) + 0.05)
                            break
                    if tpm_wait == 0.0:
                        tpm_wait = max(0.0, 60.0 - (now - self.events[0]["timestamp"]) + 0.05)

                max_wait = max(rpm_wait, pacing_wait, tpm_wait)
                if max_wait <= 0.01:
                    now_actual = time.monotonic()
                    self._counter += 1
                    res_id = f"res_{self._counter}_{now_actual}"
                    self.events.append({
                        "id": res_id,
                        "timestamp": now_actual,
                        "tokens": estimated_tokens,
                        "committed": False
                    })
                    self._sync_legacy_attributes()
                    return res_id

                logger.info(
                    "Pacing Voyage API request (RPM: %d/%d, TPM: %d/%d, estimated: %d tokens). Sleeping for %.2fs",
                    active_rpm, self.max_rpm, active_tpm, self.max_tpm, estimated_tokens, max_wait
                )
                await asyncio.sleep(max_wait)

    async def commit_usage(self, reservation_id: str, actual_tokens: int):
        """Commit actual token usage for a successful request."""
        async with self._lock:
            for ev in self.events:
                if ev["id"] == reservation_id:
                    ev["tokens"] = actual_tokens
                    ev["committed"] = True
                    break
            self._sync_legacy_attributes()

    async def release_reservation(self, reservation_id: str):
        """Release reservation for a request rejected with 429 without consuming provider tokens."""
        async with self._lock:
            self.events = [ev for ev in self.events if ev["id"] != reservation_id]
            self._sync_legacy_attributes()

    async def acquire_budget(self, estimated_tokens: int) -> str:
        """Wait if necessary to ensure RPM, TPM, and inter-request pacing are respected, then reserve budget."""
        return await self.reserve_budget(estimated_tokens)


_global_voyage_pacer: Optional[EmbeddingPacer] = None


def get_voyage_pacer(max_rpm: int = 2, max_tpm: int = 8000) -> EmbeddingPacer:
    global _global_voyage_pacer
    if _global_voyage_pacer is None or _global_voyage_pacer.max_rpm != max_rpm or _global_voyage_pacer.max_tpm != max_tpm:
        _global_voyage_pacer = EmbeddingPacer(max_requests_per_minute=max_rpm, max_tokens_per_minute=max_tpm)
    return _global_voyage_pacer


class EmbeddingProvider(ABC):
    @abstractmethod
    async def generate_embeddings(self, texts: List[str], model: Optional[str] = None, input_type: str = "document") -> List[List[float]]:
        pass


class MockEmbeddingProvider(EmbeddingProvider):
    """Deterministic mock embedding provider for development and testing."""

    def __init__(self, dimensions: int = 1024):
        self.dimensions = dimensions

    async def generate_embeddings(self, texts: List[str], model: Optional[str] = None, input_type: str = "document") -> List[List[float]]:
        logger.info("MockEmbeddingProvider generating embeddings for %d texts (dims=%d, type=%s)", len(texts), self.dimensions, input_type)
        results = []
        for text in texts:
            seed = hashlib.sha256((input_type + ":" + text).encode("utf-8")).digest()
            raw_vec = []
            for i in range(self.dimensions):
                byte_val = seed[i % len(seed)]
                val = (byte_val / 255.0) * 2.0 - 1.0
                raw_vec.append(val)
            norm = math.sqrt(sum(v * v for v in raw_vec))
            normed_vec = [v / norm for v in raw_vec] if norm > 0 else raw_vec
            results.append(normed_vec)
        return results


class VoyageEmbeddingProvider(EmbeddingProvider):
    """Voyage AI API implementation for embeddings (voyage-code-4) with token-aware batching and production-grade rate limiting."""

    def __init__(
        self,
        api_key: str,
        default_model: str = "voyage-code-4",
        expected_dimension: int = 1024,
        max_retries: Optional[int] = None,
        initial_backoff_ms: Optional[int] = None,
        max_backoff_ms: Optional[int] = None,
        batch_size: Optional[int] = None,
        max_tokens_per_minute: Optional[int] = None,
        max_requests_per_minute: Optional[int] = None,
        pacer: Optional[EmbeddingPacer] = None
    ):
        self.api_key = api_key
        self.default_model = default_model
        self.expected_dimension = expected_dimension
        self.max_retries = max_retries if max_retries is not None else getattr(settings, "EMBEDDING_MAX_RETRIES", 5)
        init_ms = initial_backoff_ms if initial_backoff_ms is not None else getattr(settings, "EMBEDDING_INITIAL_BACKOFF_MS", 1000)
        self.initial_backoff_sec = init_ms / 1000.0
        max_ms = max_backoff_ms if max_backoff_ms is not None else getattr(settings, "EMBEDDING_MAX_BACKOFF_MS", 32000)
        self.max_backoff_sec = max_ms / 1000.0
        self.batch_size = batch_size if batch_size is not None else getattr(settings, "EMBEDDING_BATCH_SIZE", 16)
        self.max_tokens_per_minute = max_tokens_per_minute if max_tokens_per_minute is not None else getattr(settings, "EMBEDDING_MAX_TOKENS_PER_MINUTE", 8000)
        self.max_requests_per_minute = max_requests_per_minute if max_requests_per_minute is not None else getattr(settings, "EMBEDDING_MAX_REQUESTS_PER_MINUTE", 2)
        max_batch_tokens_cfg = getattr(settings, "EMBEDDING_MAX_BATCH_TOKENS", 3500)
        self.max_tokens_per_request = min(max_batch_tokens_cfg, self.max_tokens_per_minute // max(1, self.max_requests_per_minute))
        self.pacer = pacer if pacer is not None else get_voyage_pacer(max_rpm=self.max_requests_per_minute, max_tpm=self.max_tokens_per_minute)

    async def generate_embeddings(self, texts: List[str], model: Optional[str] = None, input_type: str = "document") -> List[List[float]]:
        if not texts:
            return []

        target_model = model or self.default_model

        sub_batches = create_token_aware_batches(
            texts=texts,
            max_batch_size=self.batch_size,
            max_tokens_per_batch=self.max_tokens_per_request
        )

        all_embeddings: List[List[float]] = []
        url = "https://api.voyageai.com/v1/embeddings"
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        for batch_idx, sub_batch in enumerate(sub_batches):
            batch_tokens = sum(estimate_tokens(t) for t in sub_batch)
            logger.info(
                "VoyageEmbeddingProvider dispatching sub-batch %d/%d (%d texts, ~%d estimated tokens, type=%s) using model %s",
                batch_idx + 1, len(sub_batches), len(sub_batch), batch_tokens, input_type, target_model
            )

            payload = {
                "input": sub_batch,
                "model": target_model,
                "input_type": input_type,
                "output_dimension": self.expected_dimension
            }

            data = None
            for attempt in range(self.max_retries + 1):
                # BEFORE EVERY ATTEMPT (initial & retry), reserve local pacing budget
                reservation_id = await self.pacer.acquire_budget(batch_tokens)

                try:
                    async with httpx.AsyncClient(timeout=60.0) as client:
                        response = await client.post(url, json=payload, headers=headers)
                        response.raise_for_status()
                        data = response.json()

                        # Parse actual token usage from Voyage API response if available
                        actual_tokens = batch_tokens
                        if isinstance(data, dict) and "usage" in data and isinstance(data["usage"], dict):
                            actual_tokens = data["usage"].get("total_tokens", batch_tokens)

                        # Commit actual token usage to pacer
                        await self.pacer.commit_usage(reservation_id, actual_tokens)
                        break

                except httpx.HTTPStatusError as e:
                    # Release reservation on HTTP error so failed request does not double-count in local budget
                    await self.pacer.release_reservation(reservation_id)

                    status_code = e.response.status_code
                    if status_code in (400, 401, 403):
                        logger.error("Voyage API non-retryable HTTP error %d for model %s", status_code, target_model)
                        raise RuntimeError(f"Voyage AI API HTTP error {status_code}") from None

                    if attempt >= self.max_retries:
                        logger.error("Voyage API max retries (%d) exceeded for HTTP status %d", self.max_retries, status_code)
                        raise RuntimeError(f"Voyage AI API HTTP error {status_code}") from None

                    retry_after_header = e.response.headers.get("Retry-After")
                    retry_after_sec = None
                    if retry_after_header:
                        try:
                            retry_after_sec = float(retry_after_header)
                        except ValueError:
                            pass

                    calculated_backoff = min(self.initial_backoff_sec * (2 ** attempt), self.max_backoff_sec)
                    jitter = random.uniform(0, 0.1 * calculated_backoff)

                    if retry_after_sec is not None:
                        backoff_sec = max(retry_after_sec, calculated_backoff + jitter)
                    else:
                        backoff_sec = calculated_backoff + jitter

                    logger.warning(
                        "Voyage API HTTP %d (attempt %d/%d). Sleeping backoff %.2fs before re-acquiring pacer budget for retry",
                        status_code, attempt + 1, self.max_retries, backoff_sec
                    )
                    await asyncio.sleep(backoff_sec)

                except Exception as e:
                    await self.pacer.release_reservation(reservation_id)
                    if attempt >= self.max_retries:
                        logger.error("Voyage API connection error max retries (%d) exceeded: %s", self.max_retries, str(e))
                        raise RuntimeError("Voyage AI API connection failed") from None

                    calculated_backoff = min(self.initial_backoff_sec * (2 ** attempt), self.max_backoff_sec)
                    jitter = random.uniform(0, 0.1 * calculated_backoff)
                    backoff_sec = calculated_backoff + jitter
                    logger.warning(
                        "Voyage API connection error on attempt %d/%d: %s. Sleeping %.2fs before retry",
                        attempt + 1, self.max_retries, str(e), backoff_sec
                    )
                    await asyncio.sleep(backoff_sec)

            raw_items = data.get("data", [])
            if len(raw_items) != len(sub_batch):
                logger.error("Voyage API returned %d embeddings for %d input texts", len(raw_items), len(sub_batch))
                raise ValueError(f"Voyage API return count mismatch: expected {len(sub_batch)}, got {len(raw_items)}")

            sorted_items = sorted(raw_items, key=lambda x: x.get("index", 0))
            sub_embeddings = [item["embedding"] for item in sorted_items]

            for idx, vec in enumerate(sub_embeddings):
                if len(vec) != self.expected_dimension:
                    logger.error("Voyage API returned dimension %d at index %d, expected %d", len(vec), idx, self.expected_dimension)
                    raise ValueError(f"Invalid embedding dimension: expected {self.expected_dimension}, got {len(vec)}")

            all_embeddings.extend(sub_embeddings)

        if len(all_embeddings) != len(texts):
            logger.error("Voyage API returned %d total embeddings for %d input texts", len(all_embeddings), len(texts))
            raise ValueError(f"Voyage API total return count mismatch: expected {len(texts)}, got {len(all_embeddings)}")

        return all_embeddings


class OpenAIEmbeddingProvider(EmbeddingProvider):
    """OpenAI API implementation for embeddings."""

    def __init__(self, api_key: str, default_model: str = "text-embedding-3-small", expected_dimension: int = 1536):
        self.api_key = api_key
        self.default_model = default_model
        self.expected_dimension = expected_dimension

    async def generate_embeddings(self, texts: List[str], model: Optional[str] = None) -> List[List[float]]:
        if not texts:
            return []

        target_model = model or self.default_model
        logger.info("OpenAIEmbeddingProvider requesting embeddings for %d texts using model %s", len(texts), target_model)

        url = "https://api.openai.com/v1/embeddings"
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "input": texts,
            "model": target_model
        }
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                response = await client.post(url, json=payload, headers=headers)
                response.raise_for_status()
                data = response.json()
        except httpx.HTTPStatusError as e:
            logger.error("OpenAI API returned HTTP error %s for model %s", e.response.status_code, target_model)
            raise RuntimeError(f"OpenAI API HTTP error {e.response.status_code}") from None
        except Exception as e:
            logger.error("OpenAI API connection error: %s", str(e))
            raise RuntimeError("OpenAI API connection failed") from None

        raw_items = data.get("data", [])
        if len(raw_items) != len(texts):
            raise ValueError(f"OpenAI API return count mismatch: expected {len(texts)}, got {len(raw_items)}")

        sorted_items = sorted(raw_items, key=lambda x: x.get("index", 0))
        embeddings = [item["embedding"] for item in sorted_items]

        for idx, vec in enumerate(embeddings):
            if len(vec) != self.expected_dimension:
                raise ValueError(f"Invalid embedding dimension: expected {self.expected_dimension}, got {len(vec)}")

        return embeddings


def get_embedding_provider() -> EmbeddingProvider:
    provider = (settings.EMBEDDING_PROVIDER or "voyage").strip().lower()

    if provider == "mock":
        logger.info("Explicitly using MockEmbeddingProvider (dims=%d)", settings.EMBEDDING_DIMENSION)
        return MockEmbeddingProvider(dimensions=settings.EMBEDDING_DIMENSION)

    if provider == "openai":
        if not settings.LLM_API_KEY or settings.LLM_API_KEY == "placeholder":
            raise ValueError("EMBEDDING_PROVIDER is set to 'openai' but LLM_API_KEY is missing or invalid.")
        return OpenAIEmbeddingProvider(
            api_key=settings.LLM_API_KEY,
            default_model="text-embedding-3-small",
            expected_dimension=1536
        )

    if provider == "voyage":
        if not settings.VOYAGE_API_KEY or settings.VOYAGE_API_KEY == "placeholder":
            raise ValueError("EMBEDDING_PROVIDER is set to 'voyage' but VOYAGE_API_KEY is missing or invalid.")
        return VoyageEmbeddingProvider(
            api_key=settings.VOYAGE_API_KEY,
            default_model=settings.EMBEDDING_MODEL,
            expected_dimension=settings.EMBEDDING_DIMENSION,
            max_retries=settings.EMBEDDING_MAX_RETRIES,
            initial_backoff_ms=settings.EMBEDDING_INITIAL_BACKOFF_MS,
            max_backoff_ms=settings.EMBEDDING_MAX_BACKOFF_MS,
            batch_size=settings.EMBEDDING_BATCH_SIZE,
            max_tokens_per_minute=settings.EMBEDDING_MAX_TOKENS_PER_MINUTE,
            max_requests_per_minute=settings.EMBEDDING_MAX_REQUESTS_PER_MINUTE
        )

    raise ValueError(f"Unsupported EMBEDDING_PROVIDER '{provider}'. Supported providers are 'voyage', 'openai', and 'mock'.")
