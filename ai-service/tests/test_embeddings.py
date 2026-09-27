from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

from unittest.mock import patch

def test_create_embeddings_mock():
    payload = {
        "texts": [
            "public class AuthService { public boolean login() { return true; } }",
            "def test_login(): assert True"
        ]
    }
    with patch("app.api.routes.embeddings.settings.EMBEDDING_PROVIDER", "mock"):
        response = client.post("/internal/ai/embeddings", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert "embeddings" in data
        assert len(data["embeddings"]) == 2
        assert len(data["embeddings"][0]) == 1024
        assert len(data["embeddings"][1]) == 1024
        assert data["model"] == "voyage-code-4"

def test_create_embeddings_empty_texts():
    payload = {"texts": []}
    response = client.post("/internal/ai/embeddings", json=payload)
    assert response.status_code == 422 or response.status_code == 400
