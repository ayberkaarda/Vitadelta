"""Golden tests GT-1 .. GT-9 of spec 6.11, through the engine and through the HTTP layer.

Tolerances: +-0.02 on scores, +-0.1 on the ratio. Every scenario runs twice (``via``).
"""

from dataclasses import replace

import pytest

from builders import (
    EFSA,
    EU_NRV,
    IOM,
    US_DV,
    VIAS,
    candidate,
    client,
    compare_request,
    context,
    flag_codes,
    gold_a,
    gold_b,
    item,
    profile,
    row,
    run_compare,
    run_score,
    score_payload,
    score_request,
    weights,
)
from vitadelta_scoring.engine.model import Candidate, NutrientCategory, RankingMode

SCORE = 0.02
RATIO = 0.1
pytestmark = pytest.mark.parametrize("via", VIAS)


def approx(value: float, tolerance: float = SCORE) -> object:
    return pytest.approx(value, abs=tolerance)


# --- GT-1 -----------------------------------------------------------------------------------


def test_gt1_scores(via: str) -> None:
    result = run_score(score_request([gold_a(), gold_b()]), via)
    a, b = item(result, "GOLD-A"), item(result, "GOLD-B")
    assert a["cost"]["cost_per_day"] == approx(13.3333)
    assert b["cost"]["cost_per_day"] == approx(15.0)
    expected_u = {"VIT_D": 1.2426, "VIT_B12": 1.0, "ZINC": 1.0, "MAGNESIUM": 0.2667}
    for product in (a, b):
        for code, u in expected_u.items():
            assert row(product, code)["utility"] == approx(u)
    assert row(a, "VIT_C")["utility"] == approx(1.4626)
    assert row(b, "VIT_C")["utility"] == approx(1.5)
    assert a["content_score"] == approx(4.9719)
    assert b["content_score"] == approx(5.0093)
    assert a["value_score"] == approx(0.3729)
    assert b["value_score"] == approx(0.3340)
    capped = [
        (p["product_id"], r["code"]) for p in (a, b) for r in p["breakdown"] if r["capped_at_u_max"]
    ]
    assert capped == [("GOLD-B", "VIT_C")]
    assert [i["product_id"] for i in result["items"]] == ["GOLD-A", "GOLD-B"]
    assert [i["rank"] for i in result["items"]] == [1, 2]


def test_gt1_uncapped_vitamin_c_would_be_above_the_ceiling(via: str) -> None:
    import math

    assert 1 + 0.35 * math.log(350 / 80) == approx(1.5166)
    result = run_score(score_request([gold_b()]), via)
    vit_c = row(item(result, "GOLD-B"), "VIT_C")
    assert vit_c["coverage_ratio"] == approx(4.375)
    assert vit_c["utility"] == 1.5


@pytest.mark.parametrize(
    ("mode", "order"),
    [
        (RankingMode.VALUE, ["GOLD-A", "GOLD-B"]),
        (RankingMode.RICHNESS, ["GOLD-B", "GOLD-A"]),
        (RankingMode.PRICE, ["GOLD-A", "GOLD-B"]),
    ],
)
def test_gt1_ranking_modes(via: str, mode: RankingMode, order: list[str]) -> None:
    result = run_score(score_request([gold_b(), gold_a()], mode=mode), via)
    assert [i["product_id"] for i in result["items"]] == order


def test_gt1_compare(via: str) -> None:
    result = run_compare(compare_request(gold_a(), gold_b()), via)
    assert result["a"]["product_id"] == "GOLD-A"
    assert result["b"]["product_id"] == "GOLD-B"
    assert result["market_size"] == 2
    assert result["m_ref"] == approx(13.3333 / 4.9719, 0.001)
    assert result["m_ref"] == approx(2.6817)
    assert result["delta_cost"] == approx(1.6667)
    assert result["delta_content"] == approx(0.0374, 0.001)
    assert result["marginal_rate"] == approx(44.58, 1.0)
    assert result["ratio"] == approx(16.62, RATIO)
    assert result["verdict"] == "NOT_WORTH_IT"
    assert result["swapped"] is False
    assert result["currency"] == "TRY"
    flags = {r["code"]: r["flags"] for r in result["nutrient_deltas"]}
    assert flags == {
        "MAGNESIUM": [],
        "VIT_B12": [],
        "VIT_C": ["DIMINISHING", "ABOVE_CEILING_NO_CREDIT"],
        "VIT_D": [],
        "ZINC": [],
    }
    for code in ("MAGNESIUM", "VIT_B12", "VIT_D", "ZINC"):
        delta = next(r for r in result["nutrient_deltas"] if r["code"] == code)
        assert delta["delta_amount"] == 0
    explanation = result["explanation"]
    assert explanation["key"] == "explain.verdict.not_worth_it"
    assert explanation["params"]["currency"] == "TRY"
    assert explanation["params"]["deltaCostPerDay"] == approx(1.6667)
    assert explanation["params"]["ratio"] == approx(16.6237, RATIO)


def test_gt1_swapped_request_returns_the_same_payload(via: str) -> None:
    forward = run_compare(compare_request(gold_a(), gold_b()), via)
    backward = run_compare(compare_request(gold_b(), gold_a(), [gold_b(), gold_a()]), via)
    assert backward["swapped"] is True
    assert {**backward, "swapped": False} == forward


# --- GT-2 -----------------------------------------------------------------------------------


def test_gt2_upper_limit_cap(via: str) -> None:
    zn40 = candidate("ZN40", 300.0, {"ZINC": 40.0})
    result = run_score(score_request([zn40, gold_a()]), via)
    zinc = row(item(result, "ZN40"), "ZINC")
    assert zinc["coverage_ratio"] == approx(2.5)
    assert zinc["utility"] == approx(1.3207)
    assert zinc["capped_at_ul"] is True
    assert zinc["capped_at_u_max"] is False
    assert flag_codes(item(result, "ZN40"))["EXCEEDS_UL"] == ["ZINC"]

    excluded = run_score(score_request([zn40, gold_a()], exclude_exceeding_ul=True), via)
    assert [i["product_id"] for i in excluded["items"]] == ["GOLD-A"]
    assert excluded["meta"]["excluded"] == {"required_nutrients": 0, "exceeds_ul": 1}
    assert excluded["meta"]["market_size"] == 2


# --- GT-3 -----------------------------------------------------------------------------------


def _coq10(product_id: str, mg: float, *, out_of_stock: bool = False) -> Candidate:
    return candidate(
        product_id,
        500.0,
        {"VIT_C": 80.0, "COQ10": mg},
        out_of_stock=out_of_stock,
        price_stale=out_of_stock,
    )


def test_gt3_market_median_reference(via: str) -> None:
    trio = [_coq10("Q30", 30.0), _coq10("Q100", 100.0), _coq10("Q200", 200.0)]
    result = run_score(score_request(trio), via)
    q200, q30 = row(item(result, "Q200"), "COQ10"), row(item(result, "Q30"), "COQ10")
    for r in (q200, q30):
        assert r["reference_source"] == "MARKET_MEDIAN"
        assert r["reference"] == 100.0
    assert q200["coverage_ratio"] == approx(2.0)
    assert q200["utility"] == approx(1.2426)
    assert q200["weight"] == approx(0.8)
    assert q200["contribution"] == approx(0.9941)
    assert q30["utility"] == approx(0.3)
    assert q30["contribution"] == approx(0.24)
    assert flag_codes(item(result, "Q200"))["MARKET_MEDIAN_REFERENCE_USED"] == ["COQ10"]


def test_gt3_two_products_fall_back_to_presence_only(via: str) -> None:
    pair = [_coq10("Q30", 30.0), _coq10("Q200", 200.0)]
    result = run_score(score_request(pair), via)
    for product_id in ("Q30", "Q200"):
        r = row(item(result, product_id), "COQ10")
        assert r["reference_source"] == "PRESENCE_ONLY"
        assert r["reference"] is None
        assert r["coverage_ratio"] is None
        assert r["contribution"] == approx(0.4)
        assert flag_codes(item(result, product_id))["PRESENCE_ONLY_NUTRIENTS"] == ["COQ10"]


def test_gt3_presence_floor(via: str) -> None:
    pair = [_coq10("Q30", 30.0), _coq10("Q200", 200.0)]
    ctx = context(market_profile=profile(*pair), floors={"COQ10": 400.0})
    result = run_score(score_request(pair, ctx=ctx), via)
    q30 = row(item(result, "Q30"), "COQ10")
    assert q30["contribution"] == 0.0
    assert q30["below_presence_floor"] is True
    assert flag_codes(item(result, "Q30"))["BELOW_PRESENCE_FLOOR"] == ["COQ10"]
    assert row(item(result, "Q200"), "COQ10")["contribution"] == approx(0.4)
    assert "BELOW_PRESENCE_FLOOR" not in flag_codes(item(result, "Q200"))


def test_gt3_median_ignores_the_stock_filter(via: str) -> None:
    in_stock = [_coq10("Q30", 30.0), _coq10("Q100", 100.0)]
    out_of_stock = _coq10("Q200", 200.0, out_of_stock=True)
    ctx = context(market_profile=profile(*in_stock, out_of_stock))
    only_in_stock = run_score(score_request(in_stock, ctx=ctx), via)
    including = run_score(score_request([*in_stock, out_of_stock], ctx=ctx), via)
    for result in (only_in_stock, including):
        r = row(item(result, "Q30"), "COQ10")
        assert (r["reference_source"], r["reference"]) == ("MARKET_MEDIAN", 100.0)
    assert item(only_in_stock, "Q30")["breakdown"] == item(including, "Q30")["breakdown"]
    assert {"PRICE_STALE", "OUT_OF_STOCK"} <= set(flag_codes(item(including, "Q200")))


# --- GT-4 -----------------------------------------------------------------------------------


def test_gt4_equivalent_and_dominates_in_both_orders(via: str) -> None:
    x = candidate("P-1", 500.0, {"VIT_C": 100.0, "ZINC": 10.0})
    twin = replace(x, product_id="P-2")
    richer = candidate("P-3", 500.0, {"VIT_C": 100.0, "ZINC": 10.0, "VIT_D": 5.0})

    for first, second, verdict, a_id in (
        (x, twin, "EQUIVALENT", "P-1"),
        (x, richer, "DOMINATES", "P-1"),
    ):
        forward = run_compare(compare_request(first, second), via)
        backward = run_compare(compare_request(second, first), via)
        for result in (forward, backward):
            assert result["verdict"] == verdict
            assert result["a"]["product_id"] == a_id
            assert result["delta_cost"] == 0
            assert result["marginal_rate"] is None
            assert result["ratio"] is None
        assert forward["swapped"] is not backward["swapped"]
    dominates = run_compare(compare_request(richer, x), via)
    assert dominates["delta_content"] > 0
    assert dominates["explanation"]["key"] == "explain.verdict.dominates"


def test_gt4_floating_point_equal_cost(via: str) -> None:
    x = candidate("X", 29.90, {"VIT_C": 100.0}, units_per_container=30, servings_per_day=1.5)
    y = candidate("Y", 89.70, {"VIT_C": 300.0}, units_per_container=30, servings_per_day=0.5)
    assert 29.90 / (30 / 1.5) == 1.4949999999999999
    assert 89.70 / (30 / 0.5) == 1.495
    result = run_compare(compare_request(x, y), via)
    assert result["a"]["cost"]["cost_per_day"] != result["b"]["cost"]["cost_per_day"]
    assert result["verdict"] == "EQUIVALENT"
    assert result["delta_cost"] == 0
    assert result["delta_content"] == 0
    assert result["explanation"]["key"] == "explain.verdict.equivalent"


# --- GT-5 -----------------------------------------------------------------------------------


def test_gt5_even_count_median(via: str) -> None:
    amounts = {"Q30": 30.0, "Q100": 100.0, "Q200": 200.0, "Q300": 300.0}
    products = [_coq10(pid, mg) for pid, mg in amounts.items()]
    result = run_score(score_request(products), via)
    expected = {
        "Q30": (0.2, 0.16),
        "Q100": (0.6667, 0.5333),
        "Q200": (1.1007, 0.8806),
        "Q300": (1.2426, 0.9941),
    }
    for product_id, (u, contribution) in expected.items():
        r = row(item(result, product_id), "COQ10")
        assert r["reference"] == 150.0
        assert r["utility"] == approx(u)
        assert r["contribution"] == approx(contribution)


# --- GT-6 -----------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("lambda_", "first", "scores"),
    [(0.5, "GOLD-B", (0.5, 0.5)), (1.0, "GOLD-B", (0.0, 1.0)), (0.0, "GOLD-A", (1.0, 0.0))],
)
def test_gt6_custom_mode(via: str, lambda_: float, first: str, scores: tuple[float, float]) -> None:
    request = score_request([gold_a(), gold_b()], mode=RankingMode.CUSTOM, lambda_=lambda_)
    result = run_score(request, via)
    assert result["items"][0]["product_id"] == first
    assert item(result, "GOLD-A")["custom_score"] == approx(scores[0])
    assert item(result, "GOLD-B")["custom_score"] == approx(scores[1])


def test_gt6_custom_mode_defaults_lambda_to_one_half(via: str) -> None:
    result = run_score(score_request([gold_a(), gold_b()], mode=RankingMode.CUSTOM), via)
    assert [i["custom_score"] for i in result["items"]] == [0.5, 0.5]
    assert result["items"][0]["product_id"] == "GOLD-B"


def test_gt6_non_custom_modes_have_no_custom_score(via: str) -> None:
    result = run_score(score_request([gold_a(), gold_b()]), via)
    assert [i["custom_score"] for i in result["items"]] == [None, None]


# --- GT-7 -----------------------------------------------------------------------------------


def _gt7_product() -> Candidate:
    return candidate(
        "US-1",
        20.0,
        {"VIT_D": 25.0, "VIT_B3": (50.0, "NICOTINAMIDE"), "VIT_B9": (400.0, "METHYLFOLATE")},
        currency="USD",
    )


def test_gt7_us_dv_and_iom(via: str) -> None:
    product = _gt7_product()
    ctx = context(refs=US_DV, uls=IOM, market_profile=profile(product))
    result = run_score(score_request([product], ctx=ctx), via)
    scored = item(result, "US-1")
    assert row(scored, "VIT_D")["coverage_ratio"] == approx(1.25)
    assert row(scored, "VIT_D")["utility"] == approx(1.0781)
    niacin = row(scored, "VIT_B3")
    assert niacin["coverage_ratio"] == approx(2.1875)
    assert niacin["utility"] == approx(1.2740)
    assert niacin["capped_at_ul"] is True
    assert row(scored, "VIT_B9")["utility"] == approx(1.0)
    assert scored["content_score"] == approx(3.3521)
    flags = flag_codes(scored)
    assert flags["EXCEEDS_UL"] == ["VIT_B3"]
    assert flags["UL_FORM_DEPENDENT"] == ["VIT_B9"]


def test_gt7_same_product_under_efsa(via: str) -> None:
    product = _gt7_product()
    ctx = context(refs=US_DV, uls=EFSA, market_profile=profile(product))
    result = run_score(score_request([product], ctx=ctx), via)
    flags = flag_codes(item(result, "US-1"))
    assert "UL_FORM_DEPENDENT" not in flags
    assert "EXCEEDS_UL" not in flags


# --- GT-8 -----------------------------------------------------------------------------------


def _gt8_pair() -> tuple[Candidate, Candidate]:
    z = candidate("Z", 800.0, {"VIT_C": 100.0})
    y = candidate("Y", 900.0, {"VIT_C": 100.0, "ZINC": 10.0})
    return z, y


def test_gt8_zero_content_score(via: str) -> None:
    z, y = _gt8_pair()
    ctx = context(market_profile=profile(z, y), weight=weights({"VIT_C": 0.0}))
    result = run_score(score_request([z, y], ctx=ctx), via)
    scored_z, scored_y = item(result, "Z"), item(result, "Y")
    assert scored_z["content_score"] == 0.0
    assert scored_y["content_score"] == approx(1.0)
    assert (scored_z["content_score_display"], scored_y["content_score_display"]) == (0.0, 100.0)
    assert scored_z["value_score"] == 0.0
    assert scored_y["value_score"] == approx(0.0667)
    assert result["meta"]["m_ref"] is None
    vit_c = row(scored_z, "VIT_C")
    assert (vit_c["weight"], vit_c["contribution"]) == (0.0, 0.0)


def test_gt8_compare_is_insufficient_data(via: str) -> None:
    z, y = _gt8_pair()
    ctx = context(market_profile=profile(z, y), weight=weights({"VIT_C": 0.0}))
    result = run_compare(compare_request(y, z, ctx=ctx), via)
    assert result["a"]["product_id"] == "Z"
    assert result["swapped"] is True
    assert result["delta_cost"] == approx(1.6667)
    assert result["delta_content"] == approx(1.0)
    assert result["m_ref"] is None
    assert result["ratio"] is None
    assert result["marginal_rate"] is None
    assert result["verdict"] == "INSUFFICIENT_DATA"
    assert result["explanation"] == {
        "key": "explain.verdict.insufficient_data",
        "params": {
            "currency": "TRY",
            "deltaCostPerDay": result["delta_cost"],
            "deltaContent": result["delta_content"],
        },
    }


def test_gt8_all_zero_weights_are_rejected(via: str) -> None:
    z, y = _gt8_pair()
    zero = {c: 0.0 for c in NutrientCategory}
    ctx = context(market_profile=profile(z, y), weight=weights(category_weights=zero))
    request = score_request([z, y], ctx=ctx)
    if via == "engine":
        from vitadelta_scoring.config import DEFAULT_CONFIG
        from vitadelta_scoring.engine import EngineValidationError, score

        with pytest.raises(EngineValidationError) as caught:
            score(request, DEFAULT_CONFIG)
        assert [i.code for i in caught.value.issues] == ["weights_all_zero"]
        return
    response = client.post("/internal/v1/score", json=score_payload(request))
    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"
    body = response.json()
    assert body["type"] == "https://vitadelta.dev/problems/validation_failed"
    assert body["errors"] == [
        {
            "field": "weights",
            "code": "weights_all_zero",
            "message": "The weights make every registry nutrient 0.",
        }
    ]


# --- GT-9 -----------------------------------------------------------------------------------


def test_gt9_unit_mismatch(via: str) -> None:
    good = candidate("D-1", 100.0, {"VIT_C": 80.0, "VIT_D": 10.0})
    request = score_request([good])
    if via == "engine":
        from vitadelta_scoring.config import DEFAULT_CONFIG
        from vitadelta_scoring.engine import EngineValidationError, score
        from vitadelta_scoring.engine.model import CandidateNutrient

        bad = replace(good, nutrients=(good.nutrients[0], CandidateNutrient("VIT_D", 400.0, "IU")))
        with pytest.raises(EngineValidationError) as caught:
            score(replace(request, candidates=(bad,)), DEFAULT_CONFIG)
        assert [(i.field, i.code) for i in caught.value.issues] == [
            ("candidates[0].nutrients[1].unit", "unit_mismatch")
        ]
        return
    for unit in ("IU", "mg"):
        payload = score_payload(request)
        payload["candidates"][0]["nutrients"][1]["unit"] = unit
        response = client.post("/internal/v1/score", json=payload)
        assert response.status_code == 422
        assert response.json()["errors"] == [
            {
                "field": "candidates[0].nutrients[1].unit",
                "code": "unit_mismatch",
                "message": f"Unit {unit} differs from the canonical unit mcg of VIT_D.",
            }
        ]


def test_gt_reference_set_is_eu_nrv(via: str) -> None:
    del via
    assert EU_NRV == {
        "VIT_C": 80.0,
        "VIT_D": 5.0,
        "VIT_B12": 2.5,
        "ZINC": 10.0,
        "MAGNESIUM": 375.0,
    }
