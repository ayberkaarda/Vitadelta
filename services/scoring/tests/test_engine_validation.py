"""Semantic validation when the engine is called directly (without the HTTP layer)."""

import math
from dataclasses import replace
from typing import Any

import pytest
from fastapi.testclient import TestClient

from builders import candidate, compare_request, context, gold_a, gold_b, score_request, weights
from vitadelta_scoring.api.app import _add_request_schemas
from vitadelta_scoring.api.errors import issues_from_pydantic
from vitadelta_scoring.api.models import ScoreRequest as ScoreRequestModel
from vitadelta_scoring.api.observability import RequestContextMiddleware
from vitadelta_scoring.config import DEFAULT_CONFIG
from vitadelta_scoring.engine import EngineValidationError, compare, score
from vitadelta_scoring.engine.model import (
    CandidateNutrient,
    NutrientCategory,
    Ranking,
    RankingMode,
    ReferenceValue,
    RegistryNutrient,
    RequiredNutrient,
    UpperLimit,
    Weights,
)


def issues(call: Any) -> list[tuple[str, str]]:
    with pytest.raises(EngineValidationError) as caught:
        call()
    return [(i.field, i.code) for i in caught.value.issues]


def score_issues(**changes: Any) -> list[tuple[str, str]]:
    request = score_request([gold_a(), gold_b()])
    ctx_changes = {
        k: v
        for k, v in changes.items()
        if k in ("nutrients", "reference_values", "upper_limits", "weights")
    }
    request = replace(request, context=replace(request.context, **ctx_changes))
    other = {k: v for k, v in changes.items() if k not in ctx_changes}
    request = replace(request, **other)
    return issues(lambda: score(request, DEFAULT_CONFIG))


def test_registry_rules() -> None:
    assert score_issues(nutrients=())[0] == ("nutrients", "out_of_range")
    bad_floor = (
        *context().nutrients,
        RegistryNutrient("INOSITOL", NutrientCategory.OTHER, "mg", -1.0),
    )
    index = len(bad_floor) - 1
    assert (f"nutrients[{index}].presence_floor_per_day", "out_of_range") in score_issues(
        nutrients=bad_floor
    )


def test_reference_and_limit_amounts() -> None:
    refs = (ReferenceValue("VIT_C", 0.0, "mg"), ReferenceValue("NOPE", 1.0, "mg"))
    assert score_issues(reference_values=refs) == [
        ("reference_values[0].amount", "out_of_range"),
        ("reference_values[1].nutrient_code", "unknown_nutrient_code"),
    ]
    limits = (
        UpperLimit("ZINC", "ANY", math.nan, "mg", "TOTAL"),
        UpperLimit("NOPE", "ANY", 1.0, "mg", "TOTAL"),
    )
    assert score_issues(upper_limits=limits) == [
        ("upper_limits[0].amount", "out_of_range"),
        ("upper_limits[1].nutrient_code", "unknown_nutrient_code"),
    ]


def test_weight_rules() -> None:
    missing = Weights({NutrientCategory.VITAMIN: 1.0}, {})
    found = score_issues(weights=missing)
    assert ("weights.category_weights.MINERAL", "required") in found
    assert score_issues(weights=weights({"VIT_C": 3.5})) == [
        ("weights.overrides.VIT_C", "out_of_range")
    ]


def test_candidate_ranges() -> None:
    bad = replace(
        gold_a(),
        units_per_serving=0,
        units_per_container=0,
        servings_per_day=0.0,
        effective_price=-1.0,
    )
    assert issues(lambda: score(score_request([bad], ctx=context()), DEFAULT_CONFIG)) == [
        ("candidates[0].units_per_serving", "out_of_range"),
        ("candidates[0].units_per_container", "out_of_range"),
        ("candidates[0].servings_per_day", "out_of_range"),
        ("candidates[0].effective_price", "out_of_range"),
    ]


def test_candidate_nutrient_ranges() -> None:
    bad = replace(
        gold_a(),
        nutrients=(
            CandidateNutrient("VIT_C", 0.0, "mg"),
            CandidateNutrient("ZINC", 10.0, "mg", ul_amount_per_serving=-1.0),
        ),
    )
    assert issues(lambda: score(score_request([bad], ctx=context()), DEFAULT_CONFIG)) == [
        ("candidates[0].nutrients[0].amount_per_serving", "out_of_range"),
        ("candidates[0].nutrients[1].ul_amount_per_serving", "out_of_range"),
    ]


def test_ranking_and_required_rules() -> None:
    assert score_issues(ranking=Ranking(RankingMode.CUSTOM, 1.5)) == [
        ("ranking.lambda", "out_of_range")
    ]
    required = (RequiredNutrient("ZINC"), RequiredNutrient("ZINC", -1.0, "mg"))
    assert score_issues(required_nutrients=required) == [
        ("required_nutrients[1].code", "duplicate_nutrient"),
        ("required_nutrients[1].min_amount_per_day", "out_of_range"),
    ]


def test_huge_container_is_out_of_range() -> None:
    huge = replace(gold_a(), units_per_container=10**400, units_per_serving=1)
    assert ("candidates[0].effective_price", "out_of_range") in issues(
        lambda: score(score_request([huge]), DEFAULT_CONFIG)
    )


def test_compare_validates_every_member() -> None:
    bad = replace(gold_b(), currency="EUR")
    request = compare_request(gold_a(), gold_b(), [gold_a(), bad])
    assert issues(lambda: compare(request, DEFAULT_CONFIG)) == [
        ("market[1].currency", "currency_mismatch"),
        ("market[1].product_id", "duplicate_product_id"),
    ]


def test_error_message_lists_the_issues() -> None:
    with pytest.raises(
        EngineValidationError, match=r"candidates\[0\]\.effective_price: out_of_range"
    ):
        score(score_request([replace(gold_a(), effective_price=0.0)]), DEFAULT_CONFIG)


# --- HTTP-layer helpers ---------------------------------------------------------------------


def test_unit_mismatch_lookup_tolerates_odd_shapes() -> None:
    errors = [
        {
            "type": "enum",
            "loc": ("candidates", 5, "nutrients", 0, "unit"),
            "input": "IU",
            "msg": "m",
        },
        {"type": "enum", "loc": ("candidates", "x", "unit"), "input": "IU", "msg": "m"},
        {"type": "enum", "loc": ("candidates", 0, "unit"), "input": 7, "msg": "bad"},
    ]
    found = issues_from_pydantic(errors, {"candidates": [{"code": "VIT_D"}], "nutrients": "x"})
    assert [(i.field, i.code) for i in found] == [
        ("candidates[5].nutrients[0].unit", "unit_mismatch"),
        ("candidates.x.unit", "unit_mismatch"),
        ("candidates[0].unit", "invalid_format"),
    ]
    assert found[0].message == "Unit IU is not a canonical unit (mg, mcg)."


def test_request_schemas_must_not_conflict() -> None:
    document = {"components": {"schemas": {"Candidate": {"type": "string"}}}}
    with pytest.raises(RuntimeError, match="conflicting component schema Candidate"):
        _add_request_schemas(document, (ScoreRequestModel,))


def test_crash_before_the_response_is_a_problem() -> None:
    async def broken(scope: Any, receive: Any, send: Any) -> None:
        raise RuntimeError("boom")

    local = TestClient(RequestContextMiddleware(broken), raise_server_exceptions=False)
    response = local.get("/internal/v1/anything", headers={"X-Request-Id": "crash-1"})
    assert response.status_code == 500
    assert response.json()["requestId"] == "crash-1"
    assert response.json()["instance"] == "/internal/v1/anything"


def test_mixed_currency_in_score_candidates() -> None:
    usd = candidate("U", 10.0, {"VIT_C": 80.0}, currency="USD")
    assert issues(lambda: score(score_request([gold_a(), usd]), DEFAULT_CONFIG)) == [
        ("candidates[1].currency", "currency_mismatch")
    ]
