import asyncio
import pytest
import time
from unittest.mock import AsyncMock, patch
import httpx
from app.retrieval.embeddings import VoyageEmbeddingProvider, EmbeddingPacer
import app.retrieval.embeddings as emb

@pytest.fixture(autouse=True)
def reset_pacer():
    emb._global_voyage_pacer = EmbeddingPacer(max_requests_per_minute=1000, max_tokens_per_minute=100000)
    yield
    emb._global_voyage_pacer = None

@pytest.mark.asyncio
async def test_voyage_single_embedding():
    provider = VoyageEmbeddingProvider(
        api_key="test_voyage_key",
        default_model="voyage-code-4",
        expected_dimension=1024,
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000
    )
    dummy_vector = [0.1] * 1024

    mock_response = AsyncMock()
    mock_response.status_code = 200
    mock_response.raise_for_status = lambda: None
    mock_response.json = lambda: {
        "object": "list",
        "data": [{"object": "embedding", "embedding": dummy_vector, "index": 0}],
        "model": "voyage-code-4"
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.return_value = mock_response
        res = await provider.generate_embeddings(["public class App {}"])
        assert len(res) == 1
        assert len(res[0]) == 1024

@pytest.mark.asyncio
async def test_voyage_batch_and_ordering():
    provider = VoyageEmbeddingProvider(
        api_key="test_voyage_key",
        default_model="voyage-code-4",
        expected_dimension=1024,
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000
    )
    vec0 = [0.1] * 1024
    vec1 = [0.2] * 1024

    # Intentionally return index 1 before index 0 to verify ordering preservation
    mock_response = AsyncMock()
    mock_response.status_code = 200
    mock_response.raise_for_status = lambda: None
    mock_response.json = lambda: {
        "object": "list",
        "data": [
            {"object": "embedding", "embedding": vec1, "index": 1},
            {"object": "embedding", "embedding": vec0, "index": 0}
        ],
        "model": "voyage-code-4"
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.return_value = mock_response
        res = await provider.generate_embeddings(["text0", "text1"])
        assert len(res) == 2
        assert res[0] == vec0
        assert res[1] == vec1

@pytest.mark.asyncio
async def test_voyage_wrong_dimension_rejected():
    provider = VoyageEmbeddingProvider(
        api_key="test_voyage_key",
        default_model="voyage-code-4",
        expected_dimension=1024,
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000
    )
    wrong_vec = [0.1] * 512

    mock_response = AsyncMock()
    mock_response.status_code = 200
    mock_response.raise_for_status = lambda: None
    mock_response.json = lambda: {
        "object": "list",
        "data": [{"object": "embedding", "embedding": wrong_vec, "index": 0}],
        "model": "voyage-code-4"
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.return_value = mock_response
        with pytest.raises(ValueError, match="Invalid embedding dimension"):
            await provider.generate_embeddings(["code chunk"])

@pytest.mark.asyncio
async def test_voyage_http_401_fast_fail():
    provider = VoyageEmbeddingProvider(
        api_key="secret_voyage_key_12345",
        default_model="voyage-code-4",
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000
    )
    request = httpx.Request("POST", "https://api.voyageai.com/v1/embeddings")
    response = httpx.Response(status_code=401, request=request)

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post, \
         patch("app.retrieval.embeddings.settings.EMBEDDING_MAX_RETRIES", 3):
        mock_post.side_effect = httpx.HTTPStatusError("401 Unauthorized", request=request, response=response)
        with pytest.raises(RuntimeError) as exc_info:
            await provider.generate_embeddings(["code chunk"])
        assert "401" in str(exc_info.value)
        # Verify it fast-failed on 1st attempt (no retries for 401)
        assert mock_post.call_count == 1

@pytest.mark.asyncio
async def test_voyage_http_429_retry_success():
    provider = VoyageEmbeddingProvider(
        api_key="secret_key",
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000,
        max_retries=3,
        initial_backoff_ms=10
    )
    request = httpx.Request("POST", "https://api.voyageai.com/v1/embeddings")
    response_429 = httpx.Response(status_code=429, headers={"Retry-After": "0.01"}, request=request)

    mock_200 = AsyncMock()
    mock_200.status_code = 200
    mock_200.raise_for_status = lambda: None
    mock_200.json = lambda: {
        "object": "list",
        "data": [{"object": "embedding", "embedding": [0.1] * 1024, "index": 0}],
        "model": "voyage-code-4"
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post, \
         patch("app.retrieval.embeddings.settings.EMBEDDING_MAX_RETRIES", 3), \
         patch("app.retrieval.embeddings.settings.EMBEDDING_INITIAL_BACKOFF_MS", 10):
        mock_post.side_effect = [
            httpx.HTTPStatusError("429 Rate Limit Exceeded", request=request, response=response_429),
            mock_200
        ]
        res = await provider.generate_embeddings(["code chunk"])
        assert len(res) == 1
        assert mock_post.call_count == 2

@pytest.mark.asyncio
async def test_voyage_http_5xx_handled():
    provider = VoyageEmbeddingProvider(
        api_key="secret_key",
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000,
        max_retries=2,
        initial_backoff_ms=1
    )
    request = httpx.Request("POST", "https://api.voyageai.com/v1/embeddings")
    response = httpx.Response(status_code=503, request=request)

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.side_effect = httpx.HTTPStatusError("503 Service Unavailable", request=request, response=response)
        with pytest.raises(RuntimeError) as exc_info:
            await provider.generate_embeddings(["code chunk"])
        assert "503" in str(exc_info.value)

@pytest.mark.asyncio
async def test_voyage_network_timeout():
    provider = VoyageEmbeddingProvider(
        api_key="secret_key",
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000,
        max_retries=2,
        initial_backoff_ms=1
    )
    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.side_effect = httpx.ConnectTimeout("Connection timed out")
        with pytest.raises(RuntimeError) as exc_info:
            await provider.generate_embeddings(["code chunk"])
        assert "connection failed" in str(exc_info.value).lower()

def test_get_embedding_provider_selection():
    from app.retrieval.embeddings import get_embedding_provider, MockEmbeddingProvider, VoyageEmbeddingProvider

    # Test explicit mock provider
    with patch("app.retrieval.embeddings.settings.EMBEDDING_PROVIDER", "mock"):
        p = get_embedding_provider()
        assert isinstance(p, MockEmbeddingProvider)

    # Test voyage provider with missing/placeholder key -> raises ValueError
    with patch("app.retrieval.embeddings.settings.EMBEDDING_PROVIDER", "voyage"), \
         patch("app.retrieval.embeddings.settings.VOYAGE_API_KEY", "placeholder"):
        with pytest.raises(ValueError, match="VOYAGE_API_KEY is missing or invalid"):
            get_embedding_provider()

    # Test voyage provider with valid key -> VoyageEmbeddingProvider
    with patch("app.retrieval.embeddings.settings.EMBEDDING_PROVIDER", "voyage"), \
         patch("app.retrieval.embeddings.settings.VOYAGE_API_KEY", "real_valid_key_12345"):
        p = get_embedding_provider()
        assert isinstance(p, VoyageEmbeddingProvider)

    # Test openai provider with missing/placeholder key -> raises ValueError
    with patch("app.retrieval.embeddings.settings.EMBEDDING_PROVIDER", "openai"), \
         patch("app.retrieval.embeddings.settings.LLM_API_KEY", "placeholder"):
        with pytest.raises(ValueError, match="LLM_API_KEY is missing or invalid"):
            get_embedding_provider()

    # Test invalid provider -> raises ValueError
    with patch("app.retrieval.embeddings.settings.EMBEDDING_PROVIDER", "unknown_provider"):
        with pytest.raises(ValueError, match="Unsupported EMBEDDING_PROVIDER"):
            get_embedding_provider()


def test_estimate_tokens():
    from app.retrieval.embeddings import estimate_tokens
    assert estimate_tokens("") == 0
    assert estimate_tokens("abc") == 1
    assert estimate_tokens("a" * 30) == 10
    # Conservative check
    code_snippet = "public class HelloWorld { public static void main(String[] args) {} }"
    assert estimate_tokens(code_snippet) == len(code_snippet) // 3 + (1 if len(code_snippet) % 3 != 0 else 0)


def test_token_aware_batch_creation():
    from app.retrieval.embeddings import create_token_aware_batches, estimate_tokens
    texts = ["a" * 30] * 10  # each text ~10 tokens
    # Set max_batch_size = 32, max_tokens_per_batch = 35 -> max 3 texts per batch
    batches = create_token_aware_batches(texts, max_batch_size=32, max_tokens_per_batch=35)
    assert len(batches) == 4
    assert len(batches[0]) == 3
    assert len(batches[1]) == 3
    assert len(batches[2]) == 3
    assert len(batches[3]) == 1

    # Verify every batch satisfies token budget
    for batch in batches:
        batch_tokens = sum(estimate_tokens(t) for t in batch)
        assert batch_tokens <= 35


def test_oversized_text_rejected_by_token_aware_batches():
    from app.retrieval.embeddings import create_token_aware_batches
    from unittest.mock import patch

    oversized_text = "x" * 7000  # Exceeds max_chunk_chars (6000) and max_chunk_tokens (2000)

    with pytest.raises(ValueError, match="exceeds chunk limits"):
        create_token_aware_batches([oversized_text], max_batch_size=32, max_tokens_per_batch=4000)


def test_token_aware_batches_preserves_exact_text_instances():
    from app.retrieval.embeddings import create_token_aware_batches
    texts = [f"code_snippet_{i}" for i in range(10)]
    batches = create_token_aware_batches(texts, max_batch_size=3, max_tokens_per_batch=4000)

    flattened = [t for b in batches for t in b]
    assert len(flattened) == 10
    assert flattened == texts


@pytest.mark.asyncio
async def test_retries_respect_global_pacer():
    from app.retrieval.embeddings import VoyageEmbeddingProvider, EmbeddingPacer

    mock_pacer = AsyncMock(spec=EmbeddingPacer)
    mock_pacer.acquire_budget = AsyncMock()

    provider = VoyageEmbeddingProvider(
        api_key="secret_key",
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000,
        max_retries=3,
        initial_backoff_ms=1,
        pacer=mock_pacer
    )
    request = httpx.Request("POST", "https://api.voyageai.com/v1/embeddings")
    response_429 = httpx.Response(status_code=429, headers={"Retry-After": "0.01"}, request=request)

    mock_200 = AsyncMock()
    mock_200.status_code = 200
    mock_200.raise_for_status = lambda: None
    mock_200.json = lambda: {
        "object": "list",
        "data": [{"object": "embedding", "embedding": [0.1] * 1024, "index": 0}],
        "model": "voyage-code-4"
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post, \
         patch("asyncio.sleep", new_callable=AsyncMock):
        mock_post.side_effect = [
            httpx.HTTPStatusError("429 Rate Limit Exceeded", request=request, response=response_429),
            mock_200
        ]
        res = await provider.generate_embeddings(["code chunk"])
        assert len(res) == 1
        assert mock_post.call_count == 2
        # CRITICAL ASSERTION: pacer.acquire_budget MUST be called on EVERY attempt (initial + retry)
        assert mock_pacer.acquire_budget.call_count == 2


@pytest.mark.asyncio
async def test_concurrent_api_calls_respect_global_limiter():
    from app.retrieval.embeddings import VoyageEmbeddingProvider, EmbeddingPacer

    shared_pacer = EmbeddingPacer(max_requests_per_minute=1000, max_tokens_per_minute=100000)
    provider1 = VoyageEmbeddingProvider(api_key="key1", pacer=shared_pacer, max_requests_per_minute=1000, max_tokens_per_minute=100000)
    provider2 = VoyageEmbeddingProvider(api_key="key2", pacer=shared_pacer, max_requests_per_minute=1000, max_tokens_per_minute=100000)

    mock_response = AsyncMock()
    mock_response.status_code = 200
    mock_response.raise_for_status = lambda: None
    mock_response.json = lambda: {
        "object": "list",
        "data": [{"object": "embedding", "embedding": [0.1] * 1024, "index": 0}],
        "model": "voyage-code-4"
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.return_value = mock_response

        # Execute two concurrent requests
        res1, res2 = await asyncio.gather(
            provider1.generate_embeddings(["chunk A"]),
            provider2.generate_embeddings(["chunk B"])
        )
        assert len(res1) == 1
        assert len(res2) == 1
        # Shared pacer recorded both requests
        assert len(shared_pacer.request_timestamps) == 2


@pytest.mark.asyncio
async def test_empty_input():
    provider = VoyageEmbeddingProvider(api_key="test_key", max_requests_per_minute=1000, max_tokens_per_minute=100000)
    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        res = await provider.generate_embeddings([])
        assert res == []
        assert mock_post.call_count == 0


@pytest.mark.asyncio
async def test_pacing_and_rpm_limiting():
    from app.retrieval.embeddings import EmbeddingPacer
    pacer = EmbeddingPacer(max_requests_per_minute=2, max_tokens_per_minute=100)

    # 1st request succeeds immediately
    await pacer.acquire_budget(30)
    assert len(pacer.request_timestamps) == 1

    curr_time = time.monotonic()

    async def mock_sleep_advance(delay):
        nonlocal curr_time
        curr_time += delay + 1.0

    with patch("time.monotonic", side_effect=lambda: curr_time), \
         patch("asyncio.sleep", side_effect=mock_sleep_advance):
        await pacer.acquire_budget(30)
        assert len(pacer.request_timestamps) == 2


@pytest.mark.asyncio
async def test_429_without_retry_after():
    provider = VoyageEmbeddingProvider(api_key="secret_key", max_requests_per_minute=1000, max_tokens_per_minute=100000)
    request = httpx.Request("POST", "https://api.voyageai.com/v1/embeddings")
    response_429_no_header = httpx.Response(status_code=429, request=request)

    mock_200 = AsyncMock()
    mock_200.status_code = 200
    mock_200.raise_for_status = lambda: None
    mock_200.json = lambda: {
        "object": "list",
        "data": [{"object": "embedding", "embedding": [0.1] * 1024, "index": 0}],
        "model": "voyage-code-4"
    }

    sleep_calls = []

    async def mock_sleep_track(delay):
        sleep_calls.append(delay)

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post, \
         patch("asyncio.sleep", side_effect=mock_sleep_track):
        mock_post.side_effect = [
            httpx.HTTPStatusError("429 Rate Limit Exceeded", request=request, response=response_429_no_header),
            mock_200
        ]
        res = await provider.generate_embeddings(["code chunk"])
        assert len(res) == 1
        assert mock_post.call_count == 2
        assert len(sleep_calls) >= 1


@pytest.mark.asyncio
async def test_voyage_payload_fields():
    provider = VoyageEmbeddingProvider(
        api_key="test_voyage_key",
        default_model="voyage-code-4",
        expected_dimension=1024,
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000
    )
    mock_response = AsyncMock()
    mock_response.status_code = 200
    mock_response.raise_for_status = lambda: None
    mock_response.json = lambda: {
        "object": "list",
        "data": [{"object": "embedding", "embedding": [0.1] * 1024, "index": 0}],
        "model": "voyage-code-4"
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.return_value = mock_response
        await provider.generate_embeddings(["class Test {}"])

        assert mock_post.call_count == 1
        _, kwargs = mock_post.call_args
        payload = kwargs.get("json", {})
        assert payload.get("model") == "voyage-code-4"
        assert payload.get("input_type") == "document"
        assert payload.get("output_dimension") == 1024


@pytest.mark.asyncio
async def test_voyage_payload_query_type():
    provider = VoyageEmbeddingProvider(
        api_key="test_voyage_key",
        default_model="voyage-code-4",
        expected_dimension=1024,
        max_requests_per_minute=1000,
        max_tokens_per_minute=100000
    )
    mock_response = AsyncMock()
    mock_response.status_code = 200
    mock_response.raise_for_status = lambda: None
    mock_response.json = lambda: {
        "object": "list",
        "data": [{"object": "embedding", "embedding": [0.2] * 1024, "index": 0}],
        "model": "voyage-code-4"
    }

    with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
        mock_post.return_value = mock_response
        res = await provider.generate_embeddings(["How does login work?"], input_type="query")

        assert len(res) == 1
        assert mock_post.call_count == 1
        _, kwargs = mock_post.call_args
        payload = kwargs.get("json", {})
        assert payload.get("input_type") == "query"


def test_configuration_loading():
    from app.retrieval.embeddings import get_embedding_provider, VoyageEmbeddingProvider
    with patch("app.retrieval.embeddings.settings.EMBEDDING_PROVIDER", "voyage"), \
         patch("app.retrieval.embeddings.settings.VOYAGE_API_KEY", "valid_key"), \
         patch("app.retrieval.embeddings.settings.EMBEDDING_BATCH_SIZE", 16), \
         patch("app.retrieval.embeddings.settings.EMBEDDING_MAX_TOKENS_PER_MINUTE", 6000), \
         patch("app.retrieval.embeddings.settings.EMBEDDING_MAX_REQUESTS_PER_MINUTE", 2):

        provider = get_embedding_provider()
        assert isinstance(provider, VoyageEmbeddingProvider)
        assert provider.batch_size == 16
        assert provider.max_tokens_per_minute == 6000
        assert provider.max_requests_per_minute == 2
