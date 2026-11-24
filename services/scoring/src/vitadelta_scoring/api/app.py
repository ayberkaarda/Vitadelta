"""HTTP layer: routes, error handlers and the generated OpenAPI document."""

import json
from typing import Any

from fastapi import FastAPI, Request
from fastapi.openapi.utils import get_openapi
from fastapi.responses import Response
from pydantic import BaseModel, ValidationError
from pydantic.json_schema import models_json_schema
from pydantic_core import to_json
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException as StarletteHTTPException

from vitadelta_scoring.api.errors import (
    PROBLEM_MEDIA_TYPE,
    http_exception_handler,
    internal_problem,
    issues_from_pydantic,
    unsupported_media_type,
    validation_problem,
)
from vitadelta_scoring.api.models import (
    REQUEST_ID_PATTERN,
    CompareRequest,
    CompareResponse,
    HealthResponse,
    Problem,
    ScoreRequest,
    ScoreResponse,
)
from vitadelta_scoring.api.observability import REQUEST_ID_HEADER, request_id_var
from vitadelta_scoring.config import DEFAULT_CONFIG, EngineConfig
from vitadelta_scoring.engine import EngineValidationError, compare, score
from vitadelta_scoring.engine.serialise import compare_result_json, score_result_json

BASE_PATH = "/internal/v1"

_REQUEST_ID_PARAMETER = {
    "name": REQUEST_ID_HEADER,
    "in": "header",
    "required": False,
    "description": "Correlation id propagated by the core API. When absent or not matching "
    "the pattern, the service generates a new id.",
    "schema": {"type": "string", "pattern": REQUEST_ID_PATTERN},
}
_REQUEST_ID_RESPONSE_HEADER = {
    REQUEST_ID_HEADER: {
        "description": "Correlation id of this request (the accepted incoming id or a newly "
        "generated one).",
        "required": True,
        "schema": {"type": "string", "pattern": REQUEST_ID_PATTERN},
    }
}
_PROBLEM_REF = {"$ref": "#/components/schemas/Problem"}


def _problem_response_doc(description: str) -> dict[str, Any]:
    return {
        "model": Problem,
        "description": description,
        "headers": _REQUEST_ID_RESPONSE_HEADER,
        "content": {PROBLEM_MEDIA_TYPE: {"schema": _PROBLEM_REF}},
    }


def _responses(*, validation: bool) -> dict[int | str, dict[str, Any]]:
    responses: dict[int | str, dict[str, Any]] = {
        200: {"headers": _REQUEST_ID_RESPONSE_HEADER},
        500: _problem_response_doc("Unexpected server error (`internal_error`)."),
    }
    if validation:
        responses[422] = _problem_response_doc(
            "The request violates a structural or semantic rule (`validation_failed`)."
        )
    return responses


def _json(payload: dict[str, Any]) -> Response:
    """Compact, deterministic JSON: unrounded shortest round-trip doubles, contract key order.

    The engine never produces non-finite numbers (inputs that would overflow are rejected).
    """
    return Response(content=to_json(payload), media_type="application/json")


def _body_doc(model: type[BaseModel]) -> dict[str, Any]:
    return {
        "required": True,
        "content": {
            "application/json": {"schema": {"$ref": f"#/components/schemas/{model.__name__}"}}
        },
    }


def _parse[M: BaseModel](model: type[M], raw: bytes) -> M:
    """Validate the raw JSON body in one pass; structural errors become ``validation_failed``."""
    try:
        parsed = model.model_validate_json(raw)
    except ValidationError as exc:
        try:
            document = json.loads(raw)
        except ValueError:
            document = None
        raise EngineValidationError(
            issues_from_pydantic(exc.errors(include_url=False), document)
        ) from None
    if isinstance(parsed, ScoreRequest):
        issues = parsed.contract_issues()
        if issues:
            raise EngineValidationError(issues)
    return parsed


def _is_json(request: Request) -> bool:
    media_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    return media_type == "application/json"


def create_app(config: EngineConfig = DEFAULT_CONFIG) -> FastAPI:
    """The scoring API, served under ``/internal/v1`` by the entrypoint."""
    api = FastAPI(
        title="Vitadelta Scoring Service",
        version="1.0.0",
        servers=[{"url": BASE_PATH}],
        openapi_url="/openapi.json",
        docs_url=None,
        redoc_url=None,
        separate_input_output_schemas=False,
    )

    @api.exception_handler(Exception)
    async def _unexpected(request: Request, exc: Exception) -> Response:
        # The error itself is logged once, with its traceback, by the request middleware.
        del exc
        return internal_problem(request.url.path, request_id_var.get())

    api.add_exception_handler(StarletteHTTPException, http_exception_handler)

    @api.exception_handler(EngineValidationError)
    async def _engine_validation(request: Request, exc: EngineValidationError) -> Response:
        return validation_problem(exc.issues, request.url.path, request_id_var.get())

    @api.get(
        "/health",
        operation_id="getHealth",
        tags=["health"],
        summary="Liveness and engine configuration",
        response_model=HealthResponse,
        responses=_responses(validation=False),
        openapi_extra={"parameters": [_REQUEST_ID_PARAMETER]},
    )
    def get_health() -> Response:
        """Returns `UP` while the service can score requests, with the engine configuration."""
        return _json(HealthResponse(status="UP", config=config).model_dump(mode="json"))

    @api.post(
        "/score",
        operation_id="scoreCandidates",
        tags=["scoring"],
        summary="Score and rank a candidate set",
        response_model=ScoreResponse,
        responses=_responses(validation=True),
        openapi_extra={
            "parameters": [_REQUEST_ID_PARAMETER],
            "requestBody": _body_doc(ScoreRequest),
        },
    )
    async def score_candidates(request: Request) -> Response:
        """Normalise, resolve references, score, filter and rank the candidates."""
        if not _is_json(request):
            return unsupported_media_type(request.url.path, request_id_var.get())
        raw = await request.body()

        def work() -> Response:
            body = _parse(ScoreRequest, raw)
            return _json(score_result_json(score(body.to_engine(), config)))

        return await run_in_threadpool(work)

    @api.post(
        "/compare",
        operation_id="compareProducts",
        tags=["comparison"],
        summary="Pairwise marginal analysis",
        response_model=CompareResponse,
        responses=_responses(validation=True),
        openapi_extra={
            "parameters": [_REQUEST_ID_PARAMETER],
            "requestBody": _body_doc(CompareRequest),
        },
    )
    async def compare_products(request: Request) -> Response:
        """Order the pair, compute the deltas, the market rate and the verdict."""
        if not _is_json(request):
            return unsupported_media_type(request.url.path, request_id_var.get())
        raw = await request.body()

        def work() -> Response:
            body = _parse(CompareRequest, raw)
            return _json(compare_result_json(compare(body.to_engine(), config)))

        return await run_in_threadpool(work)

    def openapi() -> dict[str, Any]:
        if api.openapi_schema is None:
            document = get_openapi(
                title=api.title,
                version=api.version,
                openapi_version="3.1.0",
                routes=api.routes,
                servers=api.servers,
                separate_input_output_schemas=False,
            )
            _keep_only_problem_media_type(document)
            _add_request_schemas(document, (ScoreRequest, CompareRequest))
            api.openapi_schema = document
        return api.openapi_schema

    api.openapi = openapi  # type: ignore[method-assign]
    return api


def _add_request_schemas(document: dict[str, Any], models: tuple[type[BaseModel], ...]) -> None:
    """Request bodies are validated from raw JSON, so their schemas are added explicitly."""
    _, definitions = models_json_schema(
        [(model, "validation") for model in models],
        ref_template="#/components/schemas/{model}",
    )
    schemas = document.setdefault("components", {}).setdefault("schemas", {})
    for name, schema in definitions.get("$defs", {}).items():
        existing = schemas.setdefault(name, schema)
        if existing != schema:
            raise RuntimeError(f"conflicting component schema {name}")


def _keep_only_problem_media_type(document: dict[str, Any]) -> None:
    """Error responses are ``application/problem+json`` only."""
    for operations in document.get("paths", {}).values():
        for operation in operations.values():
            for status, response in operation.get("responses", {}).items():
                content = response.get("content", {})
                if not str(status).startswith("2") and PROBLEM_MEDIA_TYPE in content:
                    response["content"] = {PROBLEM_MEDIA_TYPE: content[PROBLEM_MEDIA_TYPE]}
