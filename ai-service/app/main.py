import logging
from datetime import datetime
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import settings
from app.api.routes.embeddings import router as embeddings_router
from app.api.routes.planning import router as planning_router
from app.api.routes.testing import router as testing_router
from app.api.routes.review import router as review_router
from app.api.routes.report import router as report_router
from app.api.routes.workflow import router as workflow_router

# Configure structured logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s (%(filename)s:%(lineno)d) - %(message)s"
)
logger = logging.getLogger("repopilot.ai-service")

from app.telemetry.config import init_telemetry
from app.telemetry.middleware import W3CTraceContextMiddleware

# Initialize OpenTelemetry infrastructure
init_telemetry()

app = FastAPI(
    title=settings.APP_NAME,
    version=settings.VERSION,
    description="RepoPilot AI Service — LangGraph & Codebase RAG Engine"
)

app.include_router(embeddings_router)
app.include_router(planning_router)
app.include_router(testing_router)
app.include_router(review_router)
app.include_router(report_router)
app.include_router(workflow_router)

# Add Telemetry Middleware & CORS
app.add_middleware(W3CTraceContextMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    logger.error("Unhandled Exception on %s: %s", request.url.path, str(exc), exc_info=True)
    return JSONResponse(
        status_code=500,
        content={
            "status": 500,
            "code": "AI_SERVICE_ERROR",
            "message": "Internal AI Service Error",
            "timestamp": datetime.utcnow().isoformat()
        }
    )

@app.get("/health")
@app.get("/api/v1/health")
async def health_check():
    return {
        "status": "UP",
        "service": "repopilot-ai-service",
        "timestamp": datetime.utcnow().isoformat(),
        "version": settings.VERSION,
        "environment": settings.ENVIRONMENT
    }

@app.get("/")
async def root():
    return {"message": "RepoPilot AI Service is running", "docs": "/docs"}
