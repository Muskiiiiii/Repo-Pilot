import logging
import time
from fastapi import APIRouter, HTTPException, status
from app.review.schemas import ReviewContextInput, ReviewResponse
from app.review.reviewer import perform_ai_review

logger = logging.getLogger("repopilot.ai.api.routes.review")

router = APIRouter(tags=["review"])

@router.post("/internal/ai/review", response_model=ReviewResponse)
@router.post("/api/v1/review", response_model=ReviewResponse)
async def handle_ai_review(request: ReviewContextInput):
    """Execute structured AI code review and security analysis on validated ReviewContext."""
    start_time = time.time()
    context_hash = request.reviewContextHash
    review_mode = request.reviewMode.value if request.reviewMode else "UNKNOWN"

    logger.info("Received AI review request: hash=%s, mode=%s, version=%s",
                context_hash, review_mode, request.contextVersion)

    try:
        response = await perform_ai_review(request)
        duration_ms = int((time.time() - start_time) * 1000)
        logger.info("Completed AI review request: hash=%s, status=SUCCESS, duration_ms=%d, findings=%d",
                    context_hash, duration_ms, len(response.findings))
        return response
    except HTTPException:
        duration_ms = int((time.time() - start_time) * 1000)
        logger.warning("AI review request failed with HTTP exception: hash=%s, duration_ms=%d",
                    context_hash, duration_ms)
        raise
    except Exception as e:
        duration_ms = int((time.time() - start_time) * 1000)
        logger.error("Unhandled error in AI review endpoint: hash=%s, duration_ms=%d, error=%s",
                     context_hash, duration_ms, str(e), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"AI_SERVICE_ERROR: Failed to perform AI review: {str(e)}"
        )
