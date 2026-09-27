import logging
import time
from fastapi import APIRouter, HTTPException, status
from app.report.schemas import ReportSynthesisInput, ReportSynthesisOutput
from app.report.synthesizer import perform_ai_synthesis

logger = logging.getLogger("repopilot.ai.api.routes.report")

router = APIRouter(tags=["report"])

@router.post("/internal/ai/synthesize-report", response_model=ReportSynthesisOutput)
@router.post("/api/v1/report/synthesize", response_model=ReportSynthesisOutput)
async def handle_report_synthesis(request: ReportSynthesisInput):
    """Execute AI explanation synthesis on validated ReportSynthesisInput."""
    start_time = time.time()
    report_id = request.implementationReportId
    context_hash = request.sourceContextHash

    logger.info("Received AI report synthesis request: reportId=%s, hash=%s", report_id, context_hash)

    try:
        response = await perform_ai_synthesis(request)
        duration_ms = int((time.time() - start_time) * 1000)
        logger.info("Completed AI report synthesis request: reportId=%s, duration_ms=%d", report_id, duration_ms)
        return response
    except HTTPException:
        duration_ms = int((time.time() - start_time) * 1000)
        logger.warning("AI report synthesis failed with HTTP exception: reportId=%s, duration_ms=%d", report_id, duration_ms)
        raise
    except Exception as e:
        duration_ms = int((time.time() - start_time) * 1000)
        logger.error("Unhandled error in AI report synthesis endpoint: reportId=%s, duration_ms=%d, error=%s",
                     report_id, duration_ms, str(e), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"AI_SERVICE_ERROR: Failed to perform AI synthesis: {str(e)}"
        )
