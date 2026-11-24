"""Regression tests for the review findings: overflow, duplicate ids, CUSTOM min-max,
the literal required-nutrient minimum, request-model conformance, routing problems and
boundary conditions."""

import math
from collections.abc import Callable
from dataclasses import replace
from typing import Any

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from builders import (
    EFSA,
    candidate,
    client,
    compare_payload,
    compare_request,
    context,
    gold_a,
    gold_b,
    profile,
    profile_product,
    score_payload,
    score_request,
)
from vitadelta_scoring.config import DEFAULT_CONFIG
from vitadelta_scoring.engine import EngineValidationError, compare, score
from vitadelta_scoring.engine.model import (
    Candidate,
    MarketProfile,
    RankingMode,
    RequiredNutrient,
)
from vitadelta_scoring.engine.numeric import median
from vitadelta_scoring.engine.scoring import Scorer
from vitadelta_scoring.engine.serialise import compare_result_json, score_result_json


def codes(call: Any) -> list[tuple[str, str]]:
    with pytest.raises(EngineValidationError) as caught:
        call()
    return [(i.field, i.code) for i in caught.value.issues]


def all_finite(value: Any) -> bool:
    if isinstance(value, float):
        return math.isfinite(value)
    if isinstance(value, dict):
        return all(all_finite(v) for v in value.values())
    if isinstance(value, (list, tuple)):
        return all(all_finite(v) for v in value)
    return True


# --- A. Overflow and non-finite values ------------------------------------------------------


def test_even_count_median_of_huge_values_is_finite() -> None:
    assert median([1e308, 1e308, 1e308, 1e308]) == 1e308
    assert median([1.5e308, 1.7e308]) == 1.6e308
    trio = [profile_product(f"P{i}", {"COQ10": 1e308}) for i in range(4)]
    ctx = context(market_profile=MarketProfile(1, tuple(trio)))
    assert Scorer(ctx, DEFAULT_CONFIG).reference("COQ10").amount == 1e308


def _overflowing_pair() -> tuple[Candidate, Candidate]:
    a = candidate("A", 1e308, {"VIT_C": 0.1})
    b = candidate("B", 1.7e308, {"VIT_C": 0.2})
    return a, b


def test_compare_rate_overflow_is_rejected() -> None:
    a, b = _overflowing_pair()
    found = codes(lambda: compare(compare_request(a, b), DEFAULT_CONFIG))
    assert {code for _, code in found} == {"value_out_of_range"}
    response = client.post("/internal/v1/compare", json=compare_payload(compare_request(a, b)))
    assert response.status_code == 422
    assert {e["code"] for e in response.json()["errors"]} == {"value_out_of_range"}


def test_market_rate_overflow_in_score_is_rejected() -> None:
    products = [candidate(f"P{i}", 1e308, {"VIT_C": 0.1 * (i + 1)}) for i in range(3)]
    found = codes(lambda: score(score_request(products), DEFAULT_CONFIG))
    assert found == [("meta.m_ref", "value_out_of_range")]


def test_coverage_ratio_overflow_is_rejected() -> None:
    product = candidate("P", 10.0, {"VIT_C": 1e300})
    ctx = context(refs={"VIT_C": 1e-300}, market_profile=profile(product))
    found = codes(lambda: score(score_request([product], ctx=ctx), DEFAULT_CONFIG))
    assert found == [("candidates[0].nutrients[0].amount_per_serving", "value_out_of_range")]


EXTREME = st.floats(min_value=1e-300, max_value=1e300, allow_nan=False, allow_infinity=False)


@st.composite
def extreme_candidates(draw: st.DrawFn, product_id: str) -> Candidate:
    nutrients = {
        code: draw(EXTREME)
        for code in draw(
            st.lists(st.sampled_from(["VIT_C", "ZINC", "COQ10", "LUTEIN"]), min_size=1, unique=True)
        )
    }
    return candidate(
        product_id,
        draw(EXTREME),
        nutrients,
        servings_per_day=draw(st.sampled_from([0.01, 1.0, 99.99])),
        units_per_container=draw(st.sampled_from([1, 60, 10**12])),
    )


@settings(max_examples=200, deadline=None)
@given(
    a=extreme_candidates("A"),
    b=extreme_candidates("B"),
    c=extreme_candidates("C"),
    ref=EXTREME,
)
def test_extreme_inputs_give_finite_outputs_or_422(
    a: Candidate, b: Candidate, c: Candidate, ref: float
) -> None:
    ctx = context(refs={"VIT_C": ref, "ZINC": 10.0}, market_profile=profile(a, b, c))
    runs: list[Callable[[], dict[str, Any]]] = [
        lambda: score_result_json(score(score_request([a, b, c], ctx=ctx), DEFAULT_CONFIG)),
        lambda: score_result_json(
            score(score_request([a, b, c], ctx=ctx, mode=RankingMode.CUSTOM), DEFAULT_CONFIG)
        ),
        lambda: compare_result_json(
            compare(compare_request(a, b, [a, b, c], ctx=ctx), DEFAULT_CONFIG)
        ),
    ]
    for run in runs:
        try:
            result: dict[str, Any] = run()
        except EngineValidationError:
            continue
        assert all_finite(result), result


# --- B. Duplicate ids -----------------------------------------------------------------------


def test_conflicting_duplicate_candidates_are_rejected_in_both_orders() -> None:
    cheap = replace(gold_a(), effective_price=1.0)
    for order in ([gold_a(), cheap], [cheap, gold_a()]):
        assert codes(lambda o=order: score(score_request(o), DEFAULT_CONFIG)) == [
            ("candidates[1].product_id", "duplicate_product_id")
        ]


def test_exact_duplicate_candidates_are_deduplicated() -> None:
    result = score(score_request([gold_a(), gold_a(), gold_b()]), DEFAULT_CONFIG)
    assert result.meta.market_size == 2


def test_conflicting_duplicates_in_market_and_profile_are_rejected() -> None:
    conflicting = replace(gold_a(), effective_price=1.0)
    for market in ([gold_a(), gold_b(), conflicting], [conflicting, gold_b(), gold_a()]):
        found = codes(
            lambda m=market: compare(compare_request(gold_a(), gold_b(), m), DEFAULT_CONFIG)
        )
        assert [c for _, c in found] == ["duplicate_product_id"]
    first = profile_product("Q", {"COQ10": 100.0})
    second = profile_product("Q", {"COQ10": 200.0})
    for products in ((first, second), (second, first)):
        ctx = context(market_profile=MarketProfile(1, products))
        assert codes(lambda c=ctx: score(score_request([gold_a()], ctx=c), DEFAULT_CONFIG)) == [
            ("market_profile.products[1].product_id", "duplicate_product_id")
        ]
    same = context(market_profile=MarketProfile(1, (first, first)))
    assert score(score_request([gold_a()], ctx=same), DEFAULT_CONFIG).meta.market_size == 1


# --- C. CUSTOM min-max uses exact equality --------------------------------------------------


def test_custom_minmax_is_not_epsilon_equal() -> None:
    cheaper = candidate("Z", 60.0, {"VIT_C": 80.0})
    dearer = candidate("A", 60.00000003, {"VIT_C": 80.0})
    result = score(
        score_request([dearer, cheaper], mode=RankingMode.CUSTOM, lambda_=0.0), DEFAULT_CONFIG
    )
    assert [i.product_id for i in result.items] == ["Z", "A"]
    assert [i.custom_score for i in result.items] == [1.0, 0.0]


# --- D. Literal required-nutrient minimum ---------------------------------------------------


def test_required_minimum_is_a_literal_comparison() -> None:
    product = candidate("P", 10.0, {"VIT_C": 0.7}, servings_per_day=3.0)
    assert 0.7 * 3.0 < 2.1
    request = score_request([product], required=[RequiredNutrient("VIT_C", 2.1, "mg")])
    result = score(request, DEFAULT_CONFIG)
    assert result.items == ()
    assert result.meta.excluded.required_nutrients == 1


# --- E. Request-model conformance -----------------------------------------------------------


def _score_status(mutate: Any) -> tuple[int, list[tuple[str, str]]]:
    payload = score_payload(score_request([gold_a(), gold_b()]))
    mutate(payload)
    response = client.post("/internal/v1/score", json=payload)
    body = response.json()
    return response.status_code, [(e["field"], e["code"]) for e in body.get("errors", [])]


def test_null_minimum_without_unit_and_null_unit_are_rejected() -> None:
    def null_min(p: dict[str, Any]) -> None:
        p["required_nutrients"] = [{"code": "ZINC", "min_amount_per_day": None}]

    def null_unit(p: dict[str, Any]) -> None:
        p["required_nutrients"] = [{"code": "ZINC", "unit": None}]

    def null_min_with_unit(p: dict[str, Any]) -> None:
        p["required_nutrients"] = [{"code": "ZINC", "min_amount_per_day": None, "unit": "mg"}]

    assert _score_status(null_min) == (422, [("required_nutrients[0].unit", "required")])
    assert _score_status(null_unit) == (422, [("required_nutrients[0].unit", "invalid_type")])
    assert _score_status(null_min_with_unit)[0] == 200


def test_json_integers_with_a_zero_fraction_are_integers() -> None:
    def whole(p: dict[str, Any]) -> None:
        p["candidates"][0]["units_per_container"] = 60.0
        p["candidates"][0]["units_per_serving"] = 1.0
        p["market_profile"]["catalog_version"] = 42.0

    def boolean(p: dict[str, Any]) -> None:
        p["candidates"][0]["units_per_serving"] = True

    assert _score_status(whole) == (200, [])
    assert _score_status(boolean) == (422, [("candidates[0].units_per_serving", "invalid_type")])


# --- F. Routing errors are problems ---------------------------------------------------------


@pytest.mark.parametrize(
    ("method", "path", "status", "code"),
    [
        ("GET", "/internal/v1/nothing", 404, "not_found"),
        ("GET", "/elsewhere", 404, "not_found"),
        ("GET", "/internal/v1/score", 405, "method_not_allowed"),
        ("DELETE", "/internal/v1/health", 405, "method_not_allowed"),
    ],
)
def test_routing_errors_are_problems(method: str, path: str, status: int, code: str) -> None:
    response = client.request(method, path, headers={"X-Request-Id": "route-1"})
    assert response.status_code == status
    assert response.headers["content-type"] == "application/problem+json"
    body = response.json()
    assert body["type"] == f"https://vitadelta.dev/problems/{code}"
    assert body["status"] == status
    assert body["instance"] == path
    assert body["requestId"] == "route-1"


@pytest.mark.parametrize("content_type", ["text/plain", "application/x-www-form-urlencoded", None])
def test_non_json_content_type_is_415(content_type: str | None) -> None:
    headers = {} if content_type is None else {"content-type": content_type}
    for path in ("/internal/v1/score", "/internal/v1/compare"):
        response = client.post(path, content=b"{}", headers=headers)
        assert response.status_code == 415
        assert response.headers["content-type"] == "application/problem+json"
        assert response.json()["type"] == "https://vitadelta.dev/problems/unsupported_media_type"


def test_json_content_type_with_parameters_is_accepted() -> None:
    import json

    body = json.dumps(score_payload(score_request([gold_a()]))).encode()
    response = client.post(
        "/internal/v1/score",
        content=body,
        headers={"content-type": "application/json; charset=utf-8"},
    )
    assert response.status_code == 200


# --- G. Boundaries --------------------------------------------------------------------------


def test_ul_relevant_amount_equal_to_the_ul_does_not_exceed() -> None:
    product = candidate("ZN25", 10.0, {"ZINC": 25.0})
    scored = Scorer(context(market_profile=profile(product)), DEFAULT_CONFIG).score(product)
    assert not scored.flags
    assert scored.breakdown[0].capped_at_ul is False


def test_r_a_exactly_one_is_diminishing() -> None:
    a = candidate("A", 60.0, {"VIT_C": 80.0})
    b = candidate("B", 90.0, {"VIT_C": 100.0})
    result = compare(compare_request(a, b), DEFAULT_CONFIG)
    assert result.nutrient_deltas[0].r_a == 1.0
    assert [f.value for f in result.nutrient_deltas[0].flags] == ["DIMINISHING"]


def test_ul_equal_to_the_reference_is_not_below_it() -> None:
    product = candidate("ZN10", 10.0, {"ZINC": 10.0})
    uls = [row for row in EFSA if row[0] != "ZINC"] + [("ZINC", "ANY", 10.0)]
    scored = Scorer(context(uls=uls, market_profile=profile(product)), DEFAULT_CONFIG).score(
        product
    )
    assert scored.breakdown[0].ul_below_reference is False
