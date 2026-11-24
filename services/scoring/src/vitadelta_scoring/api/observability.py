"""Request ids (spec 9.3) and structured JSON logging."""

import json
import logging
import re
import sys
import time
import uuid
from contextvars import ContextVar
from datetime import UTC, datetime
from typing import Any

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from vitadelta_scoring.api.errors import internal_problem

REQUEST_ID_HEADER = "X-Request-Id"
_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9-]{1,64}$")
_HEADER_KEY = REQUEST_ID_HEADER.lower().encode("latin-1")

request_id_var: ContextVar[str] = ContextVar("request_id", default="-")

LOGGER_NAME = "vitadelta_scoring"
logger = logging.getLogger(LOGGER_NAME)

_STANDARD_RECORD_FIELDS = frozenset(vars(logging.makeLogRecord({})).keys()) | {"message"}


def accept_request_id(value: str | None) -> str:
    """The incoming id when it matches ``^[A-Za-z0-9-]{1,64}$``, otherwise a new UUID."""
    if value is not None and _REQUEST_ID_RE.fullmatch(value):
        return value
    return str(uuid.uuid4())


class JsonFormatter(logging.Formatter):
    """One JSON object per line with the request id of the current request."""

    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "request_id": getattr(record, "request_id", None) or request_id_var.get(),
        }
        for key, value in vars(record).items():
            if key not in _STANDARD_RECORD_FIELDS and key not in entry:
                entry[key] = value
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False, default=str)


def configure_logging(level: int = logging.INFO) -> None:
    """Attach the JSON handler to the service logger once."""
    if any(getattr(h, "_vitadelta_json", False) for h in logger.handlers):
        return
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    handler._vitadelta_json = True  # type: ignore[attr-defined]
    logger.addHandler(handler)
    logger.setLevel(level)
    logger.propagate = False


class RequestContextMiddleware:
    """Assigns the request id, echoes it, logs one line per request and maps crashes to 500."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        incoming = None
        for key, value in scope.get("headers", []):
            if key == _HEADER_KEY:
                incoming = value.decode("latin-1")
                break
        request_id = accept_request_id(incoming)
        token = request_id_var.set(request_id)
        started = time.perf_counter()
        state = {"status": 500, "sent": False}

        async def send_with_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                state["status"] = message["status"]
                state["sent"] = True
                headers = [
                    (k, v) for k, v in message.get("headers", []) if k.lower() != _HEADER_KEY
                ]
                headers.append((_HEADER_KEY, request_id.encode("latin-1")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_with_id)
        except Exception:
            logger.exception("unhandled error")
            if not state["sent"]:
                response = internal_problem(scope.get("path", ""), request_id)
                await response(scope, receive, send_with_id)
        finally:
            logger.info(
                "request",
                extra={
                    "method": scope.get("method"),
                    "path": scope.get("path"),
                    "status": state["status"],
                    "duration_ms": round((time.perf_counter() - started) * 1000.0, 3),
                },
            )
            request_id_var.reset(token)
