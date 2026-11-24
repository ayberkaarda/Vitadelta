"""Performance budget (spec 6.12): 500 candidates x 40 nutrients scored in under 200 ms p95.

The load is generated deterministically from a fixed seed. The measured path is the whole
request through the service's ASGI application (middleware, raw body validation, scoring,
ranking and JSON encoding of the ~9 MB response), driven directly without a network or an
HTTP client library. ``VITADELTA_PERF_FACTOR`` scales the budget for slower
machines such as shared CI runners (for example ``VITADELTA_PERF_FACTOR=2``); it defaults
to 1, the developer-laptop budget of the specification.
"""

import asyncio
import json
import math
import os
import random
import time
from typing import Any

import pytest

from vitadelta_scoring.api.models import ScoreRequest
from vitadelta_scoring.config import DEFAULT_CONFIG
from vitadelta_scoring.engine import score
from vitadelta_scoring.engine.serialise import score_result_json
from vitadelta_scoring.main import app

SEED = 20261124
CANDIDATES = 500
NUTRIENTS = 40
WARMUP = 3
RUNS = 30
BUDGET_MS = 200.0
CATEGORIES = ["VITAMIN", "MINERAL", "COENZYME", "OTHER"]


def perf_factor() -> float:
    raw = os.environ.get("VITADELTA_PERF_FACTOR", "1")
    factor = float(raw)
    if not math.isfinite(factor) or factor < 1.0:
        raise ValueError(f"VITADELTA_PERF_FACTOR must be a number >= 1, got {raw!r}")
    return factor


def synthetic_load(seed: int = SEED) -> dict[str, Any]:
    rng = random.Random(seed)
    codes = [f"PERF_{i:02d}" for i in range(NUTRIENTS)]
    units = {code: rng.choice(["mg", "mcg"]) for code in codes}
    registry = [
        {
            "code": code,
            "category": CATEGORIES[i % 4],
            "canonical_unit": units[code],
            "presence_floor_per_day": rng.choice([None, 10.0]),
        }
        for i, code in enumerate(codes)
    ]
    references = [
        {"nutrient_code": code, "amount": rng.uniform(1.0, 500.0), "unit": units[code]}
        for code in codes[:28]
    ]
    upper_limits = [
        {
            "nutrient_code": code,
            "form_code": "ANY" if i % 3 else "FORM_A",
            "amount": rng.uniform(200.0, 2000.0),
            "unit": units[code],
            "basis": "TOTAL",
        }
        for i, code in enumerate(codes[:24])
    ]
    candidates: list[dict[str, Any]] = []
    for index in range(CANDIDATES):
        spd = rng.choice([0.5, 1.0, 1.5, 2.0])
        nutrients = []
        for code in codes:
            amount = rng.uniform(0.5, 800.0)
            nutrients.append(
                {
                    "code": code,
                    "amount_per_serving": amount,
                    "unit": units[code],
                    "ul_amount_per_serving": amount * 0.5 if rng.random() < 0.1 else None,
                    "form_code": rng.choice(["UNKNOWN", "FORM_A", "FORM_B"]),
                    "conversion_assumed": rng.random() < 0.05,
                }
            )
        candidates.append(
            {
                "product_id": f"perf-{index:04d}",
                "units_per_container": rng.choice([30, 60, 90, 120]),
                "units_per_serving": rng.choice([1, 2]),
                "servings_per_day": spd,
                "effective_price": round(rng.uniform(50.0, 2000.0), 2),
                "currency": "TRY",
                "price_stale": rng.random() < 0.1,
                "out_of_stock": False,
                "nutrients": nutrients,
            }
        )
    profile = [
        {
            "product_id": c["product_id"],
            "nutrients": [
                {
                    "code": n["code"],
                    "amount_per_day": n["amount_per_serving"] * c["servings_per_day"],
                }
                for n in c["nutrients"]
            ],
        }
        for c in candidates
    ]
    return {
        "nutrients": registry,
        "reference_values": references,
        "upper_limits": upper_limits,
        "weights": {
            "category_weights": {"VITAMIN": 1.0, "MINERAL": 1.0, "COENZYME": 0.8, "OTHER": 0.5},
            "overrides": {codes[0]: 2.0},
        },
        "ranking": {"mode": "VALUE", "lambda": None},
        "required_nutrients": [{"code": codes[1]}],
        "exclude_exceeding_ul": False,
        "market_profile": {"catalog_version": 7, "products": profile},
        "candidates": candidates,
    }


def p95(samples: list[float]) -> float:
    ordered = sorted(samples)
    return ordered[math.ceil(0.95 * len(ordered)) - 1]


def test_synthetic_load_is_deterministic() -> None:
    assert json.dumps(synthetic_load()) == json.dumps(synthetic_load())
    load = synthetic_load()
    assert len(load["candidates"]) == CANDIDATES
    assert {len(c["nutrients"]) for c in load["candidates"]} == {NUTRIENTS}


async def _call(body: bytes) -> tuple[int, bytes]:
    """One POST /internal/v1/score through the ASGI application."""
    pending = [{"type": "http.request", "body": body, "more_body": False}]
    status = 0
    chunks: list[bytes] = []

    async def receive() -> dict[str, Any]:
        return pending.pop(0) if pending else {"type": "http.disconnect"}

    async def send(message: dict[str, Any]) -> None:
        nonlocal status
        if message["type"] == "http.response.start":
            status = message["status"]
        elif message["type"] == "http.response.body":
            chunks.append(message.get("body", b""))

    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/internal/v1/score",
        "raw_path": b"/internal/v1/score",
        "query_string": b"",
        "root_path": "",
        "headers": [
            (b"content-type", b"application/json"),
            (b"content-length", str(len(body)).encode()),
        ],
        "client": ("127.0.0.1", 50000),
        "server": ("127.0.0.1", 8000),
    }
    await app(scope, receive, send)  # type: ignore[arg-type]
    return status, b"".join(chunks)


def _measure(body: bytes) -> list[float]:
    async def run_all() -> list[float]:
        samples = []
        for run in range(WARMUP + RUNS):
            started = time.perf_counter()
            status, payload = await _call(body)
            elapsed = (time.perf_counter() - started) * 1000.0
            assert status == 200, payload[:500]
            if run >= WARMUP:
                samples.append(elapsed)
        return samples

    return asyncio.run(run_all())


@pytest.mark.perf
def test_score_p95_within_budget(record_property: Any) -> None:
    samples = _measure(json.dumps(synthetic_load()).encode())
    measured = p95(samples)
    budget = BUDGET_MS * perf_factor()
    record_property("score_p95_ms", round(measured, 1))
    print(f"\nscore p95 over {RUNS} runs: {measured:.1f} ms (budget {budget:.0f} ms)")
    assert measured < budget, f"p95 {measured:.1f} ms exceeds the {budget:.0f} ms budget"


@pytest.mark.perf
def test_engine_alone_is_well_within_budget() -> None:
    request = ScoreRequest.model_validate(synthetic_load()).to_engine()
    samples = []
    for _ in range(10):
        started = time.perf_counter()
        score_result_json(score(request, DEFAULT_CONFIG))
        samples.append((time.perf_counter() - started) * 1000.0)
    print(f"\nengine-only p95: {p95(samples):.1f} ms")
    assert p95(samples) < BUDGET_MS * perf_factor()
