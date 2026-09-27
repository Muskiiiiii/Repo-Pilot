import os
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    APP_NAME: str = "RepoPilot AI Service"
    VERSION: str = "1.0.0"
    ENVIRONMENT: str = "development"

    # Database & Redis
    DATABASE_URL: str = "postgresql://repopilot_user:repopilot_password@localhost:5432/repopilot"
    REDIS_URL: str = "redis://localhost:6379/0"

    # LLM Settings
    LLM_API_KEY: str = "placeholder"
    LLM_MODEL: str = "gpt-4o"

    # Embedding Settings
    EMBEDDING_PROVIDER: str = "voyage"
    VOYAGE_API_KEY: str = "placeholder"
    EMBEDDING_MODEL: str = "voyage-code-4"
    EMBEDDING_DIMENSION: int = 1024
    EMBEDDING_MAX_RETRIES: int = 5
    EMBEDDING_INITIAL_BACKOFF_MS: int = 1000
    EMBEDDING_MAX_BACKOFF_MS: int = 32000
    EMBEDDING_BATCH_SIZE: int = 16
    EMBEDDING_MAX_BATCH_INPUTS: int = 16
    EMBEDDING_MAX_BATCH_TOKENS: int = 3500
    EMBEDDING_MAX_TOKENS_PER_MINUTE: int = 8000
    EMBEDDING_MAX_REQUESTS_PER_MINUTE: int = 2
    EMBEDDING_MAX_CHUNK_TOKENS: int = 2000
    EMBEDDING_MAX_CHUNK_CHARS: int = 6000

    # Workspace & Limits
    WORKSPACES_DIR: str = "/tmp/repopilot/workspaces"
    MAX_RETRY_ITERATIONS: int = 3

    # OpenTelemetry Settings
    OTEL_SERVICE_NAME: str = "repopilot-ai-service"
    OTEL_SAMPLING_RATIO: str = "1.0"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )

settings = Settings()
