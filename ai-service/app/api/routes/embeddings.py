import logging
from typing import List, Optional
from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

from app.config import settings
from app.retrieval.embeddings import get_embedding_provider

logger = logging.getLogger("repopilot.ai.api.embeddings")

router = APIRouter(tags=["Embeddings"])

class EmbeddingRequest(BaseModel):
    texts: List[str] = Field(..., description="List of code or text chunks to embed", min_length=1)
    model: Optional[str] = Field(default=None, description="Optional embedding model override")
    input_type: Optional[str] = Field(default="document", description="Embedding input type ('document' or 'query')")

class EmbeddingResponse(BaseModel):
    embeddings: List[List[float]] = Field(..., description="List of generated float vector embeddings")
    model: str = Field(..., description="Model name used for embeddings")
    dimensions: int = Field(..., description="Vector dimensionality")

@router.post("/internal/ai/embeddings", response_model=EmbeddingResponse)
@router.post("/api/v1/embeddings", response_model=EmbeddingResponse)
async def create_embeddings(request: EmbeddingRequest):
    if not request.texts:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="texts array cannot be empty")

    target_model = request.model or settings.EMBEDDING_MODEL
    input_type = request.input_type or "document"
    try:
        provider = get_embedding_provider()
        embeddings = await provider.generate_embeddings(request.texts, model=target_model, input_type=input_type)
        dimensions = len(embeddings[0]) if embeddings else settings.EMBEDDING_DIMENSION
        return EmbeddingResponse(
            embeddings=embeddings,
            model=target_model,
            dimensions=dimensions
        )
    except Exception as e:
        logger.error("Error generating embeddings: %s", str(e), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to generate embeddings: {str(e)}"
        )
