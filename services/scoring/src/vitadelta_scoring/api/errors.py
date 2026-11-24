"""RFC 9457 problem responses (spec 9.3)."""

import json
from collections.abc import Iterable, Sequence
from http import HTTPStatus
from typing import Any

from fastapi.responses import Response
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.requests import Request

from vitadelta_scoring.engine import Issue

PROBLEM_MEDIA_TYPE = "application/problem+json"
PROBLEM_TYPE_PREFIX = "https://vitadelta.dev/problems/"

_MISSING = frozenset({"missing", "missing_argument"})
_UNKNOWN_FIELD = frozenset({"extra_forbidden", "unexpected_keyword_argument"})
_OUT_OF_RANGE = frozenset(
    {
        "greater_than",
        "greater_than_equal",
        "less_than",
        "less_than_equal",
        "too_short",
        "too_long",
        "finite_number",
        "multiple_of",
    }
)


def error_code(pydantic_type: str) -> str:
    """Map a Pydantic error type onto the contract's ``errors[].code`` vocabulary."""
    if pydantic_type in _MISSING:
        return "required"
    if pydantic_type in _UNKNOWN_FIELD:
        return "unknown_field"
    if pydantic_type in _OUT_OF_RANGE:
        return "out_of_range"
    if pydantic_type.endswith(("_type", "_parsing")) or pydantic_type.startswith("int_from"):
        return "invalid_type"
    return "invalid_format"


def field_path(location: Sequence[int | str]) -> str:
    """``("body", "candidates", 0, "unit")`` -> ``candidates[0].unit``."""
    parts = list(location)
    if parts and parts[0] == "body":
        parts = parts[1:]
    path = ""
    for part in parts:
        if isinstance(part, int):
            path += f"[{part}]"
        else:
            path += f".{part}" if path else str(part)
    return path or "body"


def _at(document: Any, location: Sequence[int | str]) -> Any:
    """The value at ``location`` inside the request body, or None."""
    node = document
    for part in location:
        if isinstance(node, dict) and isinstance(part, str):
            node = node.get(part)
        elif isinstance(node, list) and isinstance(part, int) and 0 <= part < len(node):
            node = node[part]
        else:
            return None
    return node


def _unit_mismatch(error: Any, location: tuple[int | str, ...], body: Any) -> Issue | None:
    """A non-canonical ``unit`` (for example IU) is reported as ``unit_mismatch``."""
    if error.get("type") != "enum" or not location or location[-1] != "unit":
        return None
    unit = error.get("input")
    if not isinstance(unit, str):
        return None
    inner = location[1:] if location[0] == "body" else location
    owner = _at(body, inner[:-1])
    code = None
    if isinstance(owner, dict):
        code = owner.get("code", owner.get("nutrient_code"))
    canonical = None
    registry = body.get("nutrients") if isinstance(body, dict) else None
    if isinstance(code, str) and isinstance(registry, list):
        for entry in registry:
            if isinstance(entry, dict) and entry.get("code") == code:
                canonical = entry.get("canonical_unit")
                break
    if isinstance(code, str) and isinstance(canonical, str):
        message = f"Unit {unit} differs from the canonical unit {canonical} of {code}."
    else:
        message = f"Unit {unit} is not a canonical unit (mg, mcg)."
    return Issue(field_path(location), "unit_mismatch", message)


def issues_from_pydantic(errors: Iterable[Any], body: Any = None) -> list[Issue]:
    issues = []
    for error in errors:
        location = tuple(error.get("loc", ()))
        error_type = str(error.get("type", ""))
        if error_type == "json_invalid":
            location = ("body",)
        mismatch = _unit_mismatch(error, location, body)
        if mismatch is not None:
            issues.append(mismatch)
            continue
        issues.append(
            Issue(field_path(location), error_code(error_type), str(error.get("msg", "")))
        )
    return issues


def problem_response(
    *,
    code: str,
    title: str,
    status: int,
    detail: str,
    instance: str,
    request_id: str,
    issues: Sequence[Issue] | None = None,
) -> Response:
    body: dict[str, Any] = {
        "type": PROBLEM_TYPE_PREFIX + code,
        "title": title,
        "status": status,
        "detail": detail,
        "instance": instance,
        "requestId": request_id,
    }
    if issues is not None:
        body["errors"] = [
            {"field": issue.field, "code": issue.code, "message": issue.message} for issue in issues
        ]
    return Response(
        content=json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        status_code=status,
        media_type=PROBLEM_MEDIA_TYPE,
    )


def validation_problem(issues: Sequence[Issue], instance: str, request_id: str) -> Response:
    return problem_response(
        code="validation_failed",
        title="Validation failed",
        status=422,
        detail="The request contains invalid values.",
        instance=instance,
        request_id=request_id,
        issues=issues,
    )


def internal_problem(instance: str, request_id: str) -> Response:
    return problem_response(
        code="internal_error",
        title="Internal error",
        status=500,
        detail="An unexpected error occurred.",
        instance=instance,
        request_id=request_id,
    )


def unsupported_media_type(instance: str, request_id: str) -> Response:
    return problem_response(
        code="unsupported_media_type",
        title="Unsupported media type",
        status=415,
        detail="The request body must be application/json.",
        instance=instance,
        request_id=request_id,
    )


_HTTP_CODES = {404: "not_found", 405: "method_not_allowed"}


async def http_exception_handler(request: Request, exc: Exception) -> Response:
    """Framework HTTP errors (unknown path, wrong method, ...) as problem documents."""
    from vitadelta_scoring.api.observability import request_id_var

    status = exc.status_code if isinstance(exc, StarletteHTTPException) else 500
    phrase = HTTPStatus(status).phrase
    code = _HTTP_CODES.get(status, phrase.lower().replace(" ", "_").replace("-", "_"))
    response = problem_response(
        code=code,
        title=phrase,
        status=status,
        detail=f"{phrase}.",
        instance=request.url.path,
        request_id=request_id_var.get(),
    )
    headers = getattr(exc, "headers", None) or {}
    for name, value in headers.items():
        response.headers[name] = value
    return response
