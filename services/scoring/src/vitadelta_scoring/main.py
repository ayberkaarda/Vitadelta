"""ASGI entrypoint of the scoring service."""

from typing import Literal

from fastapi import APIRouter, FastAPI
from pydantic import BaseModel, ConfigDict

from vitadelta_scoring.config import DEFAULT_CONFIG, EngineConfig


class HealthResponse(BaseModel):
    """Liveness status together with the active engine configuration."""

    model_config = ConfigDict(frozen=True)

    status: Literal["UP"]
    config: EngineConfig


router = APIRouter(prefix="/internal/v1")


@router.get("/health", operation_id="getHealth", tags=["health"])
def get_health() -> HealthResponse:
    """Report liveness and the engine configuration."""
    return HealthResponse(status="UP", config=DEFAULT_CONFIG)


app = FastAPI(
    title="Vitadelta Scoring API",
    version="0.1.0",
    openapi_url="/internal/v1/openapi.json",
    docs_url=None,
    redoc_url=None,
)
app.include_router(router)
