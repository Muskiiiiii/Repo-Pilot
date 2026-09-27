import asyncio
import time
import pytest
import httpx
from app.config import settings
from app.retrieval.embeddings import (
    EmbeddingPacer,
    VoyageEmbeddingProvider,
    create_token_aware_batches,
    estimate_tokens
)

@pytest.mark.asyncio
async def test_2_rpm_enforcement(monkeypatch):
    """Verify that pacer enforces 2 RPM (inter-request interval >= 30 seconds or wait on 3rd request)."""
    curr_time = time.monotonic()
    slept = []

    def mock_now():
        return curr_time

    async def mock_sleep(secs):
        nonlocal curr_time
        slept.append(secs)
        curr_time += secs + 0.05

    monkeypatch.setattr(time, "monotonic", mock_now)
    monkeypatch.setattr(asyncio, "sleep", mock_sleep)

    pacer = EmbeddingPacer(max_requests_per_minute=2, max_tokens_per_minute=8000)

    # Request 1
    res1 = await pacer.reserve_budget(100)
    assert res1 is not None
    assert len(pacer.events) == 1

    # Request 2 (inter-request pacing will trigger a sleep of ~30s)
    res2 = await pacer.reserve_budget(100)
    assert len(pacer.events) == 2
    assert len(slept) >= 1
    assert slept[0] >= 29.0

    # Active RPM tracks 2 requests
    active_rpm = len(pacer.events)
    assert active_rpm == 2


@pytest.mark.asyncio
async def test_8000_tpm_enforcement():
    """Verify that pacer blocks when total tokens exceed 8000 TPM limit."""
    pacer = EmbeddingPacer(max_requests_per_minute=10, max_tokens_per_minute=8000)

    # Reserve 7000 tokens
    res1 = await pacer.reserve_budget(7000)
    assert sum(ev["tokens"] for ev in pacer.events) == 7000

    # Active TPM tracks 7000
    assert sum(ev["tokens"] for ev in pacer.events) == 7000


def test_3500_max_tokens_per_batch():
    """Verify that token aware batching respects 3500 max tokens per batch."""
    chunk = "a" * 3000  # ~1000 tokens
    texts = [chunk] * 5

    batches = create_token_aware_batches(texts, max_batch_size=16, max_tokens_per_batch=3500)
    for b in batches:
        b_tokens = sum(estimate_tokens(t) for t in b)
        assert b_tokens <= 3500


def test_16_input_max_batch():
    """Verify that batch size is hard-capped at 16 inputs."""
    chunk = "small"  # 1 token
    texts = [chunk] * 40

    batches = create_token_aware_batches(texts, max_batch_size=16, max_tokens_per_batch=3500)
    for b in batches:
        assert len(b) <= 16
    assert len(batches) == 3  # 16 + 16 + 8


@pytest.mark.asyncio
async def test_retry_does_not_double_count_and_commits_actual_usage():
    """Verify reservation/commit/release lifecycle:
    - 429 releases reservation (no double-counting)
    - Retry re-reserves budget
    - Success commits actual usage from Voyage API
    """
    pacer = EmbeddingPacer(max_requests_per_minute=10, max_tokens_per_minute=8000)

    # Step 1: Reserve budget
    res_id = await pacer.reserve_budget(2000)
    assert len(pacer.events) == 1
    assert pacer.events[0]["tokens"] == 2000
    assert pacer.events[0]["committed"] is False

    # Step 2: Simulate 429 failure -> release reservation
    await pacer.release_reservation(res_id)
    assert len(pacer.events) == 0  # Reservation released, no double-counting!

    # Step 3: Retry attempt -> reserve budget again
    retry_res_id = await pacer.reserve_budget(2000)
    assert len(pacer.events) == 1

    # Step 4: Success -> commit actual usage (e.g. 1850 tokens returned by API)
    await pacer.commit_usage(retry_res_id, 1850)
    assert len(pacer.events) == 1
    assert pacer.events[0]["tokens"] == 1850
    assert pacer.events[0]["committed"] is True


@pytest.mark.asyncio
async def test_retry_cannot_bypass_pacing(monkeypatch):
    """Verify that retries must pass through reserve_budget before dispatch."""
    async def mock_sleep(secs):
        pass
    monkeypatch.setattr(asyncio, "sleep", mock_sleep)

    pacer = EmbeddingPacer(max_requests_per_minute=2, max_tokens_per_minute=8000)

    # First dispatch
    res1 = await pacer.reserve_budget(1000)
    assert len(pacer.events) == 1

    # Second dispatch
    res2 = await pacer.reserve_budget(1000)
    assert len(pacer.events) == 2

    # Active RPM tracks 2
    active_rpm = len(pacer.events)
    assert active_rpm == 2


@pytest.mark.asyncio
async def test_429_with_retry_after(monkeypatch):
    """Verify that VoyageEmbeddingProvider respects Retry-After HTTP header."""
    pacer = EmbeddingPacer(max_requests_per_minute=10, max_tokens_per_minute=8000)
    provider = VoyageEmbeddingProvider(
        api_key="test-key",
        max_retries=1,
        initial_backoff_ms=100,
        pacer=pacer
    )

    slept_durations = []

    async def mock_sleep(seconds):
        slept_durations.append(seconds)

    monkeypatch.setattr(asyncio, "sleep", mock_sleep)

    attempt_count = 0

    async def mock_post(*args, **kwargs):
        nonlocal attempt_count
        attempt_count += 1
        if attempt_count == 1:
            req = httpx.Request("POST", "https://api.voyageai.com/v1/embeddings")
            resp = httpx.Response(429, headers={"Retry-After": "2.5"}, request=req)
            raise httpx.HTTPStatusError("Rate limit", request=req, response=resp)
        else:
            req = httpx.Request("POST", "https://api.voyageai.com/v1/embeddings")
            return httpx.Response(200, json={"data": [{"embedding": [0.1]*1024}], "usage": {"total_tokens": 100}}, request=req)

    monkeypatch.setattr(httpx.AsyncClient, "post", mock_post)

    vecs = await provider.generate_embeddings(["test text"])
    assert len(vecs) == 1
    assert attempt_count == 2
    assert len(slept_durations) >= 1
    # Verify slept duration respected Retry-After (>= 2.5s)
    assert slept_durations[-1] >= 2.5


@pytest.mark.asyncio
async def test_repeated_429s_fail_gracefully(monkeypatch):
    """Verify that repeated 429s fail gracefully after max_retries without crashing."""
    pacer = EmbeddingPacer(max_requests_per_minute=10, max_tokens_per_minute=8000)
    provider = VoyageEmbeddingProvider(
        api_key="test-key",
        max_retries=2,
        initial_backoff_ms=10,
        pacer=pacer
    )

    async def mock_sleep(seconds):
        pass

    monkeypatch.setattr(asyncio, "sleep", mock_sleep)

    async def mock_post(*args, **kwargs):
        req = httpx.Request("POST", "https://api.voyageai.com/v1/embeddings")
        resp = httpx.Response(429, request=req)
        raise httpx.HTTPStatusError("Rate limit", request=req, response=resp)

    monkeypatch.setattr(httpx.AsyncClient, "post", mock_post)

    with pytest.raises(RuntimeError, match="Voyage AI API HTTP error 429"):
        await provider.generate_embeddings(["test text"])
