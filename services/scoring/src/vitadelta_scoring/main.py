"""ASGI entrypoint of the scoring service (``vitadelta_scoring.main:app``)."""

import gc

from starlette.applications import Starlette
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware import Middleware
from starlette.routing import Mount

from vitadelta_scoring.api.app import BASE_PATH, create_app
from vitadelta_scoring.api.errors import http_exception_handler
from vitadelta_scoring.api.observability import RequestContextMiddleware, configure_logging

# One large /score request allocates a few hundred thousand short-lived objects that are
# all freed when the response is sent. With the default young-generation threshold (2000)
# the collector runs dozens of times per request, which costs about a third of the p95
# latency budget (spec 6.12) without reclaiming anything; a larger threshold keeps the
# collector out of the request path. The older generations keep their defaults.
YOUNG_GENERATION_THRESHOLD = 50_000

_, _older, _oldest = gc.get_threshold()
gc.set_threshold(YOUNG_GENERATION_THRESHOLD, _older, _oldest)

configure_logging()

api = create_app()

app = Starlette(
    routes=[Mount(BASE_PATH, app=api)],
    middleware=[Middleware(RequestContextMiddleware)],
    exception_handlers={StarletteHTTPException: http_exception_handler},
)
