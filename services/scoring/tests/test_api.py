"""HTTP layer: validation problems, request ids, internal errors and structured logs."""

import asyncio
import io
import json
import logging
import re
from collections.abc import Callable
from typing import Any

import pytest
from fastapi.testclient import TestClient

from builders import (
    client,
    compare_payload,
    compare_request,
    gold_a,
    gold_b,
    score_payload,
    score_request,
)
from vitadelta_scoring.api import app as app_module
from vitadelta_scoring.api.errors import error_code, field_path
from vitadelta_scoring.api.observability import (
    JsonFormatter,
    RequestContextMiddleware,
    accept_request_id,
    configure_logging,
    logger,
)
from vitadelta_scoring.main import app

Json = dict[str, Any]
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")


def gt1_score() -> Json:
    return score_payload(score_request([gold_a(), gold_b()]))


def gt1_compare() -> Json:
    return compare_payload(compare_request(gold_a(), gold_b()))


def errors_of(path: str, payload: Any) -> list[tuple[str, str]]:
    response = client.post(f"/internal/v1/{path}", json=payload)
    assert response.status_code == 422, response.text
    assert response.headers["content-type"] == "application/problem+json"
    body = response.json()
    assert body["type"] == "https://vitadelta.dev/problems/validation_failed"
    assert body["title"] == "Validation failed"
    assert body["status"] == 422
    assert body["instance"] == f"/internal/v1/{path}"
    assert body["requestId"] == response.headers["x-request-id"]
    return [(e["field"], e["code"]) for e in body["errors"]]


Mutation = Callable[[Json], None]


def _set(path: list[str | int], value: Any) -> Mutation:
    def mutate(payload: Json) -> None:
        node: Any = payload
        for part in path[:-1]:
            node = node[part]
        node[path[-1]] = value

    return mutate


def _delete(path: list[str | int]) -> Mutation:
    def mutate(payload: Json) -> None:
        node: Any = payload
        for part in path[:-1]:
            node = node[part]
        del node[path[-1]]

    return mutate


def _append(path: list[str | int], value: Any) -> Mutation:
    def mutate(payload: Json) -> None:
        node: Any = payload
        for part in path:
            node = node[part]
        node.append(value)

    return mutate


def _registry_index(payload: Json, code: str) -> int:
    return next(i for i, n in enumerate(payload["nutrients"]) if n["code"] == code)


SCORE_CASES: list[tuple[str, Mutation, list[tuple[str, str]]]] = [
    (
        "effective price zero",
        _set(["candidates", 0, "effective_price"], 0),
        [("candidates[0].effective_price", "out_of_range")],
    ),
    (
        "effective price negative",
        _set(["candidates", 1, "effective_price"], -5.0),
        [("candidates[1].effective_price", "out_of_range")],
    ),
    (
        "unknown nutrient code",
        _set(["candidates", 0, "nutrients", 0, "code"], "VIT_X"),
        [("candidates[0].nutrients[0].code", "unknown_nutrient_code")],
    ),
    (
        "unknown override code",
        _set(["weights", "overrides"], {"NOT_IN_REGISTRY": 1.0}),
        [("weights.overrides.NOT_IN_REGISTRY", "unknown_nutrient_code")],
    ),
    (
        "unknown required code",
        _set(["required_nutrients"], [{"code": "NOPE"}]),
        [("required_nutrients[0].code", "unknown_nutrient_code")],
    ),
    (
        "missing field",
        _delete(["candidates", 0, "currency"]),
        [("candidates[0].currency", "required")],
    ),
    (
        "unknown field",
        _set(["candidates", 0, "colour"], "red"),
        [("candidates[0].colour", "unknown_field")],
    ),
    (
        "string for a number",
        _set(["candidates", 0, "servings_per_day"], "1"),
        [("candidates[0].servings_per_day", "invalid_type")],
    ),
    (
        "float for an integer",
        _set(["candidates", 0, "units_per_container"], 60.5),
        [("candidates[0].units_per_container", "invalid_type")],
    ),
    (
        "servings per day above the maximum",
        _set(["candidates", 0, "servings_per_day"], 100),
        [("candidates[0].servings_per_day", "out_of_range")],
    ),
    (
        "bad currency pattern",
        _set(["candidates", 0, "currency"], "try"),
        [("candidates[0].currency", "invalid_format")],
    ),
    (
        "currency mismatch",
        _set(["candidates", 1, "currency"], "EUR"),
        [("candidates[1].currency", "currency_mismatch")],
    ),
    (
        "container smaller than serving",
        _set(["candidates", 0, "units_per_serving"], 61),
        [("candidates[0].units_per_container", "container_smaller_than_serving")],
    ),
    (
        "ul amount above amount",
        _set(["candidates", 0, "nutrients", 0, "ul_amount_per_serving"], 301.0),
        [("candidates[0].nutrients[0].ul_amount_per_serving", "ul_amount_exceeds_amount")],
    ),
    (
        "duplicate candidate nutrient",
        _append(
            ["candidates", 0, "nutrients"],
            {
                "code": "VIT_C",
                "amount_per_serving": 1.0,
                "unit": "mg",
                "form_code": "UNKNOWN",
                "conversion_assumed": False,
            },
        ),
        [("candidates[0].nutrients[5].code", "duplicate_nutrient")],
    ),
    (
        "ANY as a product form",
        _set(["candidates", 0, "nutrients", 0, "form_code"], "ANY"),
        [("candidates[0].nutrients[0].form_code", "invalid_format")],
    ),
    (
        "lambda outside CUSTOM",
        _set(["ranking"], {"mode": "VALUE", "lambda": 0.5}),
        [("ranking.lambda", "lambda_requires_custom")],
    ),
    (
        "unknown ranking mode",
        _set(["ranking"], {"mode": "FASTEST"}),
        [("ranking.mode", "invalid_format")],
    ),
    (
        "required minimum without unit",
        _set(["required_nutrients"], [{"code": "ZINC", "min_amount_per_day": 5.0}]),
        [("required_nutrients[0].unit", "required")],
    ),
    (
        "required minimum with the wrong unit",
        _set(["required_nutrients"], [{"code": "ZINC", "min_amount_per_day": 5.0, "unit": "mcg"}]),
        [("required_nutrients[0].unit", "unit_mismatch")],
    ),
    (
        "category weight above 2",
        _set(["weights", "category_weights", "OTHER"], 2.5),
        [("weights.category_weights.OTHER", "out_of_range")],
    ),
    (
        "empty registry",
        _set(["nutrients"], []),
        [("nutrients", "out_of_range")],
    ),
    (
        "UNKNOWN as an upper-limit form",
        _set(["upper_limits", 0, "form_code"], "UNKNOWN"),
        [("upper_limits[0].form_code", "invalid_format")],
    ),
    (
        "half exempt upper-limit row",
        _set(["upper_limits", 0, "unit"], None),
        [("upper_limits[0]", "invalid_format")],
    ),
    (
        "reference unit mismatch",
        _set(["reference_values", 0, "unit"], "mcg"),
        [("reference_values[0].unit", "unit_mismatch")],
    ),
    (
        "market profile unknown code",
        _set(["market_profile", "products", 0, "nutrients", 0, "code"], "NOPE"),
        [("market_profile.products[0].nutrients[0].code", "unknown_nutrient_code")],
    ),
]


@pytest.mark.parametrize(
    ("name", "mutate", "expected"), SCORE_CASES, ids=[c[0] for c in SCORE_CASES]
)
def test_score_validation(name: str, mutate: Mutation, expected: list[tuple[str, str]]) -> None:
    del name
    payload = gt1_score()
    mutate(payload)
    assert errors_of("score", payload) == expected


def test_duplicate_registry_and_reference_rows() -> None:
    payload = gt1_score()
    payload["nutrients"].append(dict(payload["nutrients"][0]))
    payload["reference_values"].append(dict(payload["reference_values"][0]))
    payload["upper_limits"].append(dict(payload["upper_limits"][0]))
    found = errors_of("score", payload)
    assert (
        "nutrients[{}].code".format(len(payload["nutrients"]) - 1),
        "duplicate_registry_code",
    ) in found
    assert (
        f"reference_values[{len(payload['reference_values']) - 1}].nutrient_code",
        "duplicate_nutrient",
    ) in found
    assert (
        f"upper_limits[{len(payload['upper_limits']) - 1}].form_code",
        "duplicate_nutrient",
    ) in found


def test_every_violation_is_reported_at_once() -> None:
    payload = gt1_score()
    payload["candidates"][0]["nutrients"][0]["code"] = "VIT_X"
    payload["candidates"][1]["currency"] = "USD"
    assert errors_of("score", payload) == [
        ("candidates[0].nutrients[0].code", "unknown_nutrient_code"),
        ("candidates[1].currency", "currency_mismatch"),
    ]


def test_unit_mismatch_message_without_a_known_code() -> None:
    payload = gt1_score()
    payload["candidates"][0]["nutrients"][1]["code"] = "NOT_THERE"
    payload["candidates"][0]["nutrients"][1]["unit"] = "IU"
    response = client.post("/internal/v1/score", json=payload)
    assert response.json()["errors"] == [
        {
            "field": "candidates[0].nutrients[1].unit",
            "code": "unit_mismatch",
            "message": "Unit IU is not a canonical unit (mg, mcg).",
        }
    ]


def test_registry_unit_iu_is_invalid() -> None:
    payload = gt1_score()
    payload["nutrients"][_registry_index(payload, "VIT_D")]["canonical_unit"] = "IU"
    assert errors_of("score", payload) == [
        (f"nutrients[{_registry_index(payload, 'VIT_D')}].canonical_unit", "invalid_format")
    ]


def test_invalid_json_body() -> None:
    response = client.post(
        "/internal/v1/score", content=b"{not json", headers={"content-type": "application/json"}
    )
    assert response.status_code == 422
    assert [e["field"] for e in response.json()["errors"]] == ["body"]


def test_body_must_be_an_object() -> None:
    assert errors_of("score", [1, 2]) == [("body", "invalid_type")]


COMPARE_CASES: list[tuple[str, Mutation, list[tuple[str, str]]]] = [
    (
        "baseline price zero",
        _set(["baseline", "effective_price"], 0.0),
        [("baseline.effective_price", "out_of_range")],
    ),
    (
        "same product",
        _set(["candidate", "product_id"], "GOLD-A"),
        [("candidate.product_id", "same_product")],
    ),
    (
        "market currency mismatch",
        _set(["market", 1, "currency"], "USD"),
        [
            ("market[1].currency", "currency_mismatch"),
            ("market[1].product_id", "duplicate_product_id"),
        ],
    ),
    (
        "candidate unit mismatch",
        _set(["candidate", "nutrients", 1, "unit"], "mg"),
        [
            ("candidate.nutrients[1].unit", "unit_mismatch"),
            ("market[1].product_id", "duplicate_product_id"),
        ],
    ),
    (
        "weights all zero",
        _set(
            ["weights", "category_weights"],
            {"VITAMIN": 0, "MINERAL": 0, "COENZYME": 0, "OTHER": 0},
        ),
        [("weights", "weights_all_zero")],
    ),
    (
        "ranking is not part of compare",
        _set(["ranking"], {"mode": "VALUE"}),
        [("ranking", "unknown_field")],
    ),
]


@pytest.mark.parametrize(
    ("name", "mutate", "expected"), COMPARE_CASES, ids=[c[0] for c in COMPARE_CASES]
)
def test_compare_validation(name: str, mutate: Mutation, expected: list[tuple[str, str]]) -> None:
    del name
    payload = gt1_compare()
    mutate(payload)
    assert errors_of("compare", payload) == expected


def test_overflowing_inputs_are_rejected() -> None:
    payload = gt1_score()
    payload["candidates"][0]["nutrients"][0]["amount_per_serving"] = 1e308
    payload["candidates"][0]["servings_per_day"] = 99.0
    payload["candidates"][1]["effective_price"] = 1e-320
    assert errors_of("score", payload) == [
        ("candidates[0].nutrients[0].amount_per_serving", "out_of_range"),
        ("candidates[1].effective_price", "out_of_range"),
    ]


# --- Request ids ----------------------------------------------------------------------------


def test_valid_request_id_is_echoed_everywhere() -> None:
    rid = "7f3c9b2e-1d4a-4c8e-9f61-2b0d5a7e8c13"
    ok = client.post("/internal/v1/score", json=gt1_score(), headers={"X-Request-Id": rid})
    assert ok.headers["x-request-id"] == rid
    bad = gt1_score()
    bad["candidates"] = "x"
    failed = client.post("/internal/v1/score", json=bad, headers={"X-Request-Id": rid})
    assert failed.headers["x-request-id"] == rid
    assert failed.json()["requestId"] == rid


@pytest.mark.parametrize("incoming", [None, "", "has space", "a" * 65, "semi;colon", "ünï"])
def test_invalid_or_missing_request_id_is_replaced(incoming: str | None) -> None:
    headers = {} if incoming is None else {"X-Request-Id": incoming.encode("latin-1")}
    response = client.get("/internal/v1/health", headers=headers)
    assert UUID.match(response.headers["x-request-id"])
    assert accept_request_id(incoming) != incoming


def test_request_id_accepts_the_full_pattern() -> None:
    assert accept_request_id("A" * 64) == "A" * 64
    assert accept_request_id("abc-DEF-123") == "abc-DEF-123"


# --- Internal errors and logging ------------------------------------------------------------


def test_unexpected_error_is_a_problem_without_details(monkeypatch: pytest.MonkeyPatch) -> None:
    def explode(*_: object) -> None:
        raise RuntimeError("secret internal detail")

    monkeypatch.setattr(app_module, "score", explode)
    local = TestClient(app, raise_server_exceptions=False)
    response = local.post("/internal/v1/score", json=gt1_score(), headers={"X-Request-Id": "r-1"})
    assert response.status_code == 500
    assert response.headers["content-type"] == "application/problem+json"
    assert response.headers["x-request-id"] == "r-1"
    assert response.json() == {
        "type": "https://vitadelta.dev/problems/internal_error",
        "title": "Internal error",
        "status": 500,
        "detail": "An unexpected error occurred.",
        "instance": "/internal/v1/score",
        "requestId": "r-1",
    }
    assert "secret" not in response.text


def _capture() -> tuple[io.StringIO, logging.Handler]:
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())
    logger.addHandler(handler)
    return stream, handler


def test_every_log_line_is_json_with_the_request_id(monkeypatch: pytest.MonkeyPatch) -> None:
    stream, handler = _capture()
    level = logger.level
    logger.setLevel(logging.INFO)
    try:
        client.post("/internal/v1/score", json=gt1_score(), headers={"X-Request-Id": "log-1"})
        monkeypatch.setattr(app_module, "compare", lambda *_: 1 / 0)
        TestClient(app, raise_server_exceptions=False).post(
            "/internal/v1/compare", json=gt1_compare(), headers={"X-Request-Id": "log-2"}
        )
    finally:
        logger.removeHandler(handler)
        logger.setLevel(level)
    lines = [json.loads(line) for line in stream.getvalue().splitlines()]
    access = [entry for entry in lines if entry["message"] == "request"]
    assert [(e["request_id"], e["method"], e["path"], e["status"]) for e in access] == [
        ("log-1", "POST", "/internal/v1/score", 200),
        ("log-2", "POST", "/internal/v1/compare", 500),
    ]
    crash = next(entry for entry in lines if entry["message"] == "unhandled error")
    assert crash["request_id"] == "log-2"
    assert crash["level"] == "ERROR"
    assert "ZeroDivisionError" in crash["exception"]
    assert all(isinstance(e["duration_ms"], float) for e in access)


def test_configure_logging_is_idempotent() -> None:
    before = list(logger.handlers)
    configure_logging()
    configure_logging()
    assert logger.handlers == before
    assert logger.propagate is False


def test_non_http_scopes_pass_through() -> None:
    seen: list[str] = []

    async def inner(scope: Any, receive: Any, send: Any) -> None:
        seen.append(scope["type"])

    middleware = RequestContextMiddleware(inner)

    async def receive() -> Any:
        return {}

    async def send(message: Any) -> None:
        del message

    asyncio.run(middleware({"type": "lifespan"}, receive, send))
    assert seen == ["lifespan"]


def test_error_after_the_response_started_is_only_logged() -> None:
    async def half(scope: Any, receive: Any, send: Any) -> None:
        await send({"type": "http.response.start", "status": 200, "headers": []})
        raise RuntimeError("late")

    local = TestClient(RequestContextMiddleware(half), raise_server_exceptions=False)
    response = local.get("/anything")
    assert response.status_code == 200
    assert UUID.match(response.headers["x-request-id"])


def test_pydantic_error_mapping() -> None:
    assert error_code("missing") == "required"
    assert error_code("extra_forbidden") == "unknown_field"
    assert error_code("greater_than") == "out_of_range"
    assert error_code("float_type") == "invalid_type"
    assert error_code("int_from_float") == "invalid_type"
    assert error_code("string_pattern_mismatch") == "invalid_format"
    assert field_path(("body", "a", 0, "b", 1)) == "a[0].b[1]"
    assert field_path(("body",)) == "body"
    assert field_path((0, "x")) == "[0].x"


def test_docs_uis_are_disabled_and_unknown_paths_404() -> None:
    assert client.get("/docs").status_code == 404
    assert client.get("/internal/v1/docs").status_code == 404
    assert client.get("/internal/v1/redoc").status_code == 404
    assert client.get("/internal/v1/nothing").status_code == 404
