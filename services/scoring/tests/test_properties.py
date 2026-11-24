"""Property-based tests (spec 6.4, 6.5, 6.6, 6.8, 6.12, 11.1)."""

import json
import math
import random
from dataclasses import replace

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from builders import (
    REGISTRY,
    candidate,
    compare_request,
    context,
    gold_a,
    gold_b,
    profile,
    score_request,
    weights,
)
from vitadelta_scoring.config import DEFAULT_CONFIG, EngineConfig
from vitadelta_scoring.engine import compare, score
from vitadelta_scoring.engine.model import (
    Candidate,
    CandidateNutrient,
    RankingMode,
    ScoringContext,
)
from vitadelta_scoring.engine.scoring import Scorer
from vitadelta_scoring.engine.serialise import compare_result_json, score_result_json
from vitadelta_scoring.engine.utility import ceiling_ratio, utility

BETA = DEFAULT_CONFIG.diminishing_beta
U_MAX = DEFAULT_CONFIG.utility_ceiling

ratios = st.floats(min_value=0.0, max_value=1e6, allow_nan=False, allow_infinity=False)
betas = st.floats(min_value=0.01, max_value=2.0)
ceilings = st.floats(min_value=1.0, max_value=5.0)


def u(r: float, beta: float = BETA, u_max: float = U_MAX) -> float:
    return utility(r, beta, u_max)[0]


# --- Utility curve --------------------------------------------------------------------------


def test_utility_anchor_points() -> None:
    assert u(0.0) == 0.0
    assert u(1.0) == 1.0
    assert u(0.5) == 0.5
    assert ceiling_ratio(BETA, U_MAX) == pytest.approx(4.17, abs=0.01)
    assert utility(ceiling_ratio(BETA, U_MAX) * 1.001, BETA, U_MAX) == (U_MAX, True)


@given(beta=betas, u_max=ceilings)
def test_utility_is_continuous_at_one(beta: float, u_max: float) -> None:
    below = u(1.0 - 1e-12, beta, u_max)
    above = u(1.0 + 1e-12, beta, u_max)
    assert abs(below - 1.0) < 1e-9
    assert abs(above - 1.0) < 1e-9


@given(r1=ratios, r2=ratios, beta=betas, u_max=ceilings)
def test_utility_is_non_decreasing(r1: float, r2: float, beta: float, u_max: float) -> None:
    low, high = sorted((r1, r2))
    assert u(low, beta, u_max) <= u(high, beta, u_max)


@given(r=ratios, beta=betas, u_max=ceilings)
def test_utility_never_exceeds_the_ceiling(r: float, beta: float, u_max: float) -> None:
    value, capped = utility(r, beta, u_max)
    assert 0.0 <= value <= u_max
    assert capped == (r > 1.0 and 1.0 + beta * math.log(r) > u_max)


@given(
    r1=st.floats(min_value=1.0, max_value=1e4),
    r2=st.floats(min_value=1.0, max_value=1e4),
    t=st.floats(min_value=0.0, max_value=1.0),
    beta=betas,
    u_max=ceilings,
)
def test_utility_is_concave_above_one(
    r1: float, r2: float, t: float, beta: float, u_max: float
) -> None:
    mid = t * r1 + (1.0 - t) * r2
    chord = t * u(r1, beta, u_max) + (1.0 - t) * u(r2, beta, u_max)
    assert u(mid, beta, u_max) >= chord - 1e-12


@given(r=st.floats(min_value=0.0, max_value=1.0))
def test_utility_is_linear_up_to_one(r: float) -> None:
    assert u(r) == r


# --- Monotonicity scoped to the modified product --------------------------------------------

AMOUNTS = st.floats(min_value=0.01, max_value=5000.0, allow_nan=False)
CODES = sorted(REGISTRY)


@st.composite
def products(draw: st.DrawFn) -> Candidate:
    codes = draw(st.lists(st.sampled_from(CODES), min_size=1, max_size=8, unique=True))
    nutrients = {code: draw(AMOUNTS) for code in codes}
    price = draw(st.floats(min_value=1.0, max_value=5000.0))
    spd = draw(st.sampled_from([0.5, 1.0, 1.5, 2.0, 3.0]))
    return candidate("P", price, nutrients, servings_per_day=spd)


def _fixed_context(*products_in_market: Candidate) -> ScoringContext:
    return context(market_profile=profile(*products_in_market))


@settings(max_examples=150, deadline=None)
@given(product=products(), extra_code=st.sampled_from(CODES), amount=AMOUNTS)
def test_adding_a_positively_weighted_nutrient_never_lowers_c(
    product: Candidate, extra_code: str, amount: float
) -> None:
    if any(n.code == extra_code for n in product.nutrients):
        return
    ctx = _fixed_context(product)
    scorer = Scorer(ctx, DEFAULT_CONFIG)
    assert scorer.weight(extra_code) > 0
    unit = REGISTRY[extra_code][1]
    extended = replace(
        product, nutrients=(*product.nutrients, CandidateNutrient(extra_code, amount, unit))
    )
    assert scorer.score(extended).content_score >= scorer.score(product).content_score


@settings(max_examples=150, deadline=None)
@given(product=products(), factor=st.floats(min_value=1.0, max_value=100.0))
def test_raising_the_price_never_raises_v(product: Candidate, factor: float) -> None:
    scorer = Scorer(_fixed_context(product), DEFAULT_CONFIG)
    dearer = replace(product, effective_price=product.effective_price * factor)
    assert scorer.score(dearer).value_score <= scorer.score(product).value_score


@settings(max_examples=100, deadline=None)
@given(product=products(), code=st.sampled_from(CODES), factor=st.floats(1.0, 10.0))
def test_more_of_a_nutrient_never_lowers_c(product: Candidate, code: str, factor: float) -> None:
    scorer = Scorer(_fixed_context(product), DEFAULT_CONFIG)
    scaled = replace(
        product,
        nutrients=tuple(
            replace(n, amount_per_serving=n.amount_per_serving * factor) if n.code == code else n
            for n in product.nutrients
        ),
    )
    assert scorer.score(scaled).content_score >= scorer.score(product).content_score - 1e-12


# --- Compare order independence -------------------------------------------------------------


@settings(max_examples=100, deadline=None)
@given(first=products(), second=products(), third=products())
def test_compare_is_independent_of_request_order(
    first: Candidate, second: Candidate, third: Candidate
) -> None:
    a = replace(first, product_id="A-1")
    b = replace(second, product_id="B-2")
    c = replace(third, product_id="C-3")
    ctx = _fixed_context(a, b, c)
    forward = compare_result_json(
        compare(compare_request(a, b, [a, b, c], ctx=ctx), DEFAULT_CONFIG)
    )
    backward = compare_result_json(
        compare(compare_request(b, a, [c, b, a], ctx=ctx), DEFAULT_CONFIG)
    )
    assert forward["swapped"] is not backward["swapped"]
    assert {**forward, "swapped": None} == {**backward, "swapped": None}
    assert forward["delta_cost"] >= 0
    if forward["delta_cost"] == 0:
        assert forward["delta_content"] >= 0


# --- Determinism ----------------------------------------------------------------------------


def _market(seed: int, count: int) -> list[Candidate]:
    rng = random.Random(seed)
    items = []
    for index in range(count):
        codes = rng.sample(CODES, rng.randint(1, 6))
        items.append(
            candidate(
                f"P-{index:03d}",
                # Duplicate prices and contents provoke every tie-break level.
                rng.choice([100.0, 150.0, 200.0]),
                {code: rng.choice([5.0, 50.0, 500.0]) for code in codes},
                servings_per_day=rng.choice([1.0, 2.0]),
            )
        )
    return items


@pytest.mark.parametrize("mode", list(RankingMode))
def test_shuffled_input_gives_identical_bytes(mode: RankingMode) -> None:
    market = _market(7, 60)
    ctx = _fixed_context(*market)
    lambda_ = 0.3 if mode is RankingMode.CUSTOM else None

    def run(order: list[Candidate]) -> bytes:
        request = score_request(order, ctx=ctx, mode=mode, lambda_=lambda_)
        return json.dumps(score_result_json(score(request, DEFAULT_CONFIG))).encode()

    reference = run(market)
    shuffler = random.Random(11)
    for _ in range(5):
        shuffled = list(market)
        shuffler.shuffle(shuffled)
        assert run(shuffled) == reference
    ranks = [i["rank"] for i in json.loads(reference)["items"]]
    assert ranks == list(range(1, len(market) + 1))


def test_ties_fall_through_to_content_then_product_id() -> None:
    cheap_rich = candidate("B", 100.0, {"VIT_C": 80.0, "ZINC": 10.0})
    cheap_poor = candidate("A", 100.0, {"VIT_C": 80.0})
    twin = replace(cheap_poor, product_id="C")
    result = score(
        score_request([twin, cheap_poor, cheap_rich], mode=RankingMode.PRICE), DEFAULT_CONFIG
    )
    assert [i.product_id for i in result.items] == ["B", "A", "C"]


def test_noise_below_ten_decimals_is_a_tie() -> None:
    base = candidate("B", 100.0, {"VIT_C": 80.0})
    noisy = replace(base, product_id="A", effective_price=100.0 + 1e-12)
    result = score(score_request([base, noisy], mode=RankingMode.PRICE), DEFAULT_CONFIG)
    assert [i.product_id for i in result.items] == ["A", "B"]


def test_equal_inputs_give_byte_identical_compare_json() -> None:
    first = json.dumps(
        compare_result_json(compare(compare_request(gold_a(), gold_b()), DEFAULT_CONFIG))
    )
    second = json.dumps(
        compare_result_json(compare(compare_request(gold_a(), gold_b()), DEFAULT_CONFIG))
    )
    assert first == second


# --- Fractional servings and configuration --------------------------------------------------


@pytest.mark.parametrize("spd", [0.5, 1.5, 2.25, 0.33])
def test_fractional_servings_per_day(spd: float) -> None:
    product = candidate(
        "F",
        120.0,
        {"VIT_C": 80.0},
        units_per_container=90,
        units_per_serving=2,
        servings_per_day=spd,
    )
    scored = Scorer(_fixed_context(product), DEFAULT_CONFIG).score(product)
    assert scored.cost.servings_per_container == 45.0
    assert scored.cost.days_supply == pytest.approx(45.0 / spd)
    assert scored.cost.cost_per_day == pytest.approx(120.0 * spd / 45.0)
    assert scored.breakdown[0].amount_per_day == pytest.approx(80.0 * spd)
    assert scored.breakdown[0].coverage_ratio == pytest.approx(spd)


def test_configuration_changes_the_curve() -> None:
    flat = EngineConfig(utility_ceiling=1.0)
    scorer = Scorer(_fixed_context(gold_a()), flat)
    assert all(r.utility <= 1.0 for r in scorer.score(gold_a()).breakdown)
    with pytest.raises(ValueError, match="verdict_marginal_max_ratio"):
        EngineConfig(verdict_worth_it_max_ratio=2.0, verdict_marginal_max_ratio=1.5)


def test_weights_override_scales_a_contribution() -> None:
    ctx = context(market_profile=profile(gold_a()), weight=weights({"VIT_C": 2.0}))
    scored = Scorer(ctx, DEFAULT_CONFIG).score(gold_a())
    vit_c = next(r for r in scored.breakdown if r.code == "VIT_C")
    assert vit_c.weight == 2.0
    assert vit_c.contribution == pytest.approx(2.0 * 1.4626, abs=0.001)
