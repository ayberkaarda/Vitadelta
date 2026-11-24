"""Engine rules beyond the golden tests: UL matching, flags, filters, market rate, verdicts."""

import math
from dataclasses import replace

import pytest

from builders import (
    EFSA,
    EU_NRV_EXTENDED,
    IOM,
    candidate,
    compare_request,
    context,
    gold_a,
    gold_b,
    profile,
    profile_product,
    score_request,
    weights,
)
from vitadelta_scoring.config import DEFAULT_CONFIG, EngineConfig
from vitadelta_scoring.engine import compare, score
from vitadelta_scoring.engine.compare import order_pair, verdict_for
from vitadelta_scoring.engine.model import (
    BreakdownRow,
    Candidate,
    CandidateNutrient,
    FlagCode,
    MarketProfile,
    NutrientCategory,
    ProductScore,
    RequiredNutrient,
    ScoringContext,
    UpperLimit,
    Verdict,
)
from vitadelta_scoring.engine.numeric import eq, is_positive, median, snap_zero, sort_key
from vitadelta_scoring.engine.references import UlMatcher, UlMatchKind, market_amounts
from vitadelta_scoring.engine.scoring import Scorer

CONFIG = DEFAULT_CONFIG


def scored(product: Candidate, ctx: ScoringContext | None = None) -> ProductScore:
    return Scorer(ctx or context(market_profile=profile(product)), CONFIG).score(product)


def breakdown_row(product: ProductScore, code: str) -> BreakdownRow:
    return next(r for r in product.breakdown if r.code == code)


def flags(product: ProductScore) -> dict[str, tuple[str, ...]]:
    return {f.code.value: f.nutrient_codes for f in product.flags}


# --- Numeric helpers ------------------------------------------------------------------------


def test_numeric_helpers() -> None:
    assert eq(1.4949999999999999, 1.495, 1e-9)
    assert not eq(1.0, 1.0001, 1e-9)
    assert eq(1e12, 1e12 + 100, 1e-9)
    assert not is_positive(1e-10, 1e-9)
    assert is_positive(1e-8, 1e-9)
    assert snap_zero(-1e-12, 1e-9) == 0.0
    assert snap_zero(0.5, 1e-9) == 0.5
    assert median([3.0, 1.0, 2.0]) == 2.0
    assert median([30.0, 100.0, 200.0, 300.0]) == 150.0
    assert sort_key(0.1 + 0.2) == sort_key(0.3)


# --- UL matching rule (spec 5.7) ------------------------------------------------------------


@pytest.mark.parametrize(
    ("rows", "code", "form", "kind", "amount"),
    [
        (EFSA, "VIT_B3", "NICOTINAMIDE", UlMatchKind.LIMIT, 900.0),
        (EFSA, "VIT_B3", "UNKNOWN", UlMatchKind.FORM_DEPENDENT, None),
        (EFSA, "VIT_B3", "INOSITOL_HEXANICOTINATE", UlMatchKind.FORM_DEPENDENT, None),
        (IOM, "VIT_B3", "NICOTINAMIDE", UlMatchKind.LIMIT, 35.0),
        (IOM, "VIT_B3", "UNKNOWN", UlMatchKind.LIMIT, 35.0),
        (EFSA, "ZINC", "ZINC_GLUCONATE", UlMatchKind.LIMIT, 25.0),
        (EFSA, "VIT_A", "BETA_CAROTENE", UlMatchKind.EXEMPT, None),
        (EFSA, "VIT_A", "UNKNOWN", UlMatchKind.FORM_DEPENDENT, None),
        (EFSA, "VIT_C", "UNKNOWN", UlMatchKind.NONE, None),
        (IOM, "VIT_B9", "METHYLFOLATE", UlMatchKind.FORM_DEPENDENT, None),
        (
            [("OMEGA3_EPA", "TRIGLYCERIDE", 5000.0)],
            "OMEGA3_EPA",
            "UNKNOWN",
            UlMatchKind.FORM_DEPENDENT,
            None,
        ),
        (
            [("OMEGA3_EPA", "TRIGLYCERIDE", 5000.0)],
            "OMEGA3_EPA",
            "TRIGLYCERIDE",
            UlMatchKind.LIMIT,
            5000.0,
        ),
    ],
)
def test_ul_matching_rule(
    rows: list[tuple[str, str, float | None]],
    code: str,
    form: str,
    kind: UlMatchKind,
    amount: float | None,
) -> None:
    ctx = context(uls=rows)
    match = UlMatcher(ctx.upper_limits).match(code, form)
    assert (match.kind, match.amount) == (kind, amount)


def test_ul_form_dependent_for_any_set_code() -> None:
    """The rule does not depend on the set: a custom set behaves like EFSA or IOM."""
    custom = [("OMEGA3_EPA", "TRIGLYCERIDE", 5000.0), ("OMEGA3_EPA", "ETHYL_ESTER", None)]
    product = candidate("P", 10.0, {"OMEGA3_EPA": 500.0, "VIT_C": 80.0})
    result = scored(product, context(uls=custom, market_profile=profile(product)))
    assert flags(result)["UL_FORM_DEPENDENT"] == ("OMEGA3_EPA",)
    exempt = replace(
        product, nutrients=(CandidateNutrient("OMEGA3_EPA", 9000.0, "mg", "ETHYL_ESTER"),)
    )
    assert flags(scored(exempt, context(uls=custom))) == {
        "PRESENCE_ONLY_NUTRIENTS": ("OMEGA3_EPA",)
    }


def test_exempt_row_never_flags() -> None:
    product = candidate("P", 10.0, {"VIT_A": (90000.0, "BETA_CAROTENE")})
    result = scored(product, context(refs=EU_NRV_EXTENDED))
    assert "EXCEEDS_UL" not in flags(result)
    assert "UL_FORM_DEPENDENT" not in flags(result)
    assert breakdown_row(result, "VIT_A").capped_at_u_max is True


# --- EXCEEDS_UL for every reference source --------------------------------------------------


def test_exceeds_ul_for_presence_only_nutrient() -> None:
    product = candidate("SE", 10.0, {"SELENIUM": 300.0, "VIT_C": 80.0})
    result = scored(product)
    selenium = breakdown_row(result, "SELENIUM")
    assert selenium.reference_source.value == "PRESENCE_ONLY"
    assert selenium.utility == 0.5
    assert selenium.capped_at_ul is False
    assert selenium.coverage_ratio is None
    assert flags(result)["EXCEEDS_UL"] == ("SELENIUM",)
    assert flags(result)["PRESENCE_ONLY_NUTRIENTS"] == ("SELENIUM",)
    excluded = score(score_request([product, gold_a()], exclude_exceeding_ul=True), CONFIG)
    assert [i.product_id for i in excluded.items] == ["GOLD-A"]
    assert excluded.meta.excluded.exceeds_ul == 1


def test_exceeds_ul_for_market_median_nutrient() -> None:
    trio = [candidate(f"B6-{mg}", 50.0, {"VIT_B6": float(mg), "VIT_C": 80.0}) for mg in (2, 10, 20)]
    ctx = context(market_profile=profile(*trio))
    result = Scorer(ctx, CONFIG).score(trio[2])
    b6 = breakdown_row(result, "VIT_B6")
    assert (b6.reference_source.value, b6.reference) == ("MARKET_MEDIAN", 10.0)
    assert b6.capped_at_ul is True
    assert b6.coverage_ratio == pytest.approx(1.2)
    assert flags(result)["EXCEEDS_UL"] == ("VIT_B6",)
    assert flags(result)["MARKET_MEDIAN_REFERENCE_USED"] == ("VIT_B6",)


def test_ul_below_reference_magnesium_and_nicotinic_acid() -> None:
    product = candidate("UL-LOW", 10.0, {"MAGNESIUM": 300.0, "VIT_B3": (15.0, "NICOTINIC_ACID")})
    result = scored(product, context(refs=EU_NRV_EXTENDED, market_profile=profile(product)))
    magnesium = breakdown_row(result, "MAGNESIUM")
    assert magnesium.ul_below_reference is True
    assert magnesium.capped_at_ul is True
    assert magnesium.coverage_ratio == pytest.approx(250.0 / 375.0)
    assert magnesium.utility < 1.0
    niacin = breakdown_row(result, "VIT_B3")
    assert niacin.ul_below_reference is True
    assert niacin.coverage_ratio == pytest.approx(10.0 / 16.0)
    assert flags(result)["EXCEEDS_UL"] == ("MAGNESIUM", "VIT_B3")
    below_limit = scored(candidate("OK", 10.0, {"MAGNESIUM": 200.0}))
    assert breakdown_row(below_limit, "MAGNESIUM").ul_below_reference is True
    assert "EXCEEDS_UL" not in flags(below_limit)


def test_ul_amount_per_serving_drives_the_flag_and_the_cap() -> None:
    def vitamin_a(ul_amount: float) -> Candidate:
        return replace(
            candidate("A", 10.0, {}, servings_per_day=1.0),
            nutrients=(CandidateNutrient("VIT_A", 4000.0, "mcg", "RETINOL", False, ul_amount),),
        )

    ctx = context(refs=EU_NRV_EXTENDED)
    safe = Scorer(ctx, CONFIG).score(vitamin_a(2000.0))
    assert "EXCEEDS_UL" not in flags(safe)
    assert breakdown_row(safe, "VIT_A").ul_amount_per_day == 2000.0
    over = Scorer(ctx, CONFIG).score(vitamin_a(3500.0))
    row_a = breakdown_row(over, "VIT_A")
    assert flags(over)["EXCEEDS_UL"] == ("VIT_A",)
    assert row_a.capped_at_ul is True
    assert row_a.coverage_ratio == pytest.approx(3500.0 / 800.0)
    zero = Scorer(ctx, CONFIG).score(vitamin_a(0.0))
    assert breakdown_row(zero, "VIT_A").ul_amount_per_day == 0.0
    assert "EXCEEDS_UL" not in flags(zero)


# --- Product flags --------------------------------------------------------------------------


def test_conversion_assumed_passes_through() -> None:
    product = replace(
        candidate("E", 10.0, {"VIT_C": 80.0}),
        nutrients=(
            CandidateNutrient("VIT_E", 13.4, "mg", "UNKNOWN", conversion_assumed=True),
            CandidateNutrient("VIT_C", 80.0, "mg"),
        ),
    )
    result = scored(product, context(refs=EU_NRV_EXTENDED))
    assert flags(result)["CONVERSION_ASSUMED"] == ("VIT_E",)


def test_flags_are_in_enum_order_with_sorted_codes() -> None:
    product = replace(
        candidate("F", 10.0, {}, price_stale=True, out_of_stock=True),
        nutrients=(
            CandidateNutrient("ZINC", 40.0, "mg", conversion_assumed=True),
            CandidateNutrient("SELENIUM", 300.0, "mcg"),
            CandidateNutrient("VIT_B3", 10.0, "mg"),
            CandidateNutrient("LUTEIN", 0.1, "mg"),
        ),
    )
    ctx = context(floors={"LUTEIN": 10.0}, market_profile=profile(product))
    result = scored(product, ctx)
    assert [f.code for f in result.flags] == [
        FlagCode.EXCEEDS_UL,
        FlagCode.UL_FORM_DEPENDENT,
        FlagCode.CONVERSION_ASSUMED,
        FlagCode.PRICE_STALE,
        FlagCode.OUT_OF_STOCK,
        FlagCode.PRESENCE_ONLY_NUTRIENTS,
        FlagCode.BELOW_PRESENCE_FLOOR,
    ]
    assert flags(result)["EXCEEDS_UL"] == ("SELENIUM", "ZINC")
    assert flags(result)["PRESENCE_ONLY_NUTRIENTS"] == ("LUTEIN", "SELENIUM", "VIT_B3")
    assert flags(result)["PRICE_STALE"] == ()


def test_out_of_stock_implies_price_stale_flag() -> None:
    result = scored(candidate("O", 10.0, {"VIT_C": 80.0}, out_of_stock=True))
    assert flags(result) == {"PRICE_STALE": (), "OUT_OF_STOCK": ()}
    assert result.cost.price_stale is False


def test_presence_floor_boundary_is_inclusive() -> None:
    product = candidate("L", 10.0, {"LUTEIN": 1.0})
    result = scored(product, context(floors={"LUTEIN": 10.0}))
    assert breakdown_row(result, "LUTEIN").utility == 0.5


# --- Score: filters, meta, display, top contributors ----------------------------------------


def test_required_nutrients_filter_and_counts() -> None:
    zn40 = candidate("ZN40", 300.0, {"ZINC": 40.0})
    no_zinc = candidate("C-ONLY", 100.0, {"VIT_C": 500.0})
    low_zinc = candidate("ZN5", 100.0, {"ZINC": 5.0, "VIT_C": 80.0})
    request = score_request(
        [gold_a(), zn40, no_zinc, low_zinc],
        required=[RequiredNutrient("ZINC", 10.0, "mg")],
        exclude_exceeding_ul=True,
    )
    result = score(request, CONFIG)
    assert [i.product_id for i in result.items] == ["GOLD-A"]
    assert result.meta.excluded.required_nutrients == 2
    assert result.meta.excluded.exceeds_ul == 1
    assert result.meta.market_size == 4
    assert result.meta.m_ref is not None
    plain = score(score_request([gold_a(), no_zinc], required=[RequiredNutrient("ZINC")]), CONFIG)
    assert [i.product_id for i in plain.items] == ["GOLD-A"]


def test_candidates_are_deduplicated_and_m_ref_is_a_median() -> None:
    a, b = gold_a(), gold_b()
    third = candidate("GOLD-C", 1000.0, {"VIT_C": 400.0, "ZINC": 10.0})
    result = score(score_request([a, b, third, gold_a()]), CONFIG)
    assert result.meta.market_size == 3
    assert next(i for i in result.items if i.product_id == "GOLD-A").cost.effective_price == 800.0
    rates = sorted(i.cost.cost_per_day / i.content_score for i in result.items)
    assert result.meta.m_ref == rates[1]


def test_empty_candidate_set() -> None:
    result = score(score_request([]), CONFIG)
    assert result.items == ()
    assert result.meta.m_ref is None
    assert result.meta.market_size == 0


def test_top_contributors_are_the_five_largest() -> None:
    rich = candidate(
        "R",
        10.0,
        {
            "VIT_C": 80.0,
            "VIT_D": 5.0,
            "VIT_B12": 2.5,
            "ZINC": 10.0,
            "MAGNESIUM": 30.0,
            "LUTEIN": 5.0,
        },
    )
    result = score(score_request([rich]), CONFIG).items[0]
    assert [r.code for r in result.top_contributors] == [
        "VIT_B12",
        "VIT_C",
        "VIT_D",
        "ZINC",
        "LUTEIN",
    ]
    assert len(result.breakdown) == 6


def test_all_zero_content_display_guards() -> None:
    ctx = context(weight=weights({"VIT_C": 0.0}))
    zeros = [candidate(f"Z{i}", 10.0 * (i + 1), {"VIT_C": 80.0}) for i in range(3)]
    result = score(score_request(zeros, ctx=ctx), CONFIG)
    assert {(i.content_score_display, i.value_score_display) for i in result.items} == {(0.0, 0.0)}
    assert result.meta.m_ref is None


def test_custom_mode_all_equal_minmax_is_one() -> None:
    twins = [candidate(pid, 100.0, {"VIT_C": 80.0}) for pid in ("T-2", "T-1")]
    from vitadelta_scoring.engine.model import RankingMode

    result = score(score_request(twins, mode=RankingMode.CUSTOM, lambda_=0.25), CONFIG)
    assert [(i.product_id, i.custom_score) for i in result.items] == [("T-1", 1.0), ("T-2", 1.0)]


# --- Compare --------------------------------------------------------------------------------


def _market_of(*rates: tuple[str, float]) -> list[Candidate]:
    """Products whose cost per day / C equals each given rate (C = 1, VIT_C at 100 %)."""
    return [
        candidate(pid, rate * 60.0, {"VIT_C": 80.0}, units_per_container=60) for pid, rate in rates
    ]


def test_compare_m_ref_median_with_three_or_more_products() -> None:
    a = candidate("A", 60.0, {"VIT_C": 80.0})  # cost 1, C 1 -> rate 1
    b = candidate("B", 120.0, {"VIT_C": 80.0, "ZINC": 10.0})  # cost 2, C 2 -> rate 1
    others = _market_of(("M-3", 3.0), ("M-4", 4.0), ("M-5", 5.0))
    result = compare(compare_request(a, b, others), CONFIG)
    assert result.market_size == 5
    assert result.m_ref == 3.0
    assert result.marginal_rate == pytest.approx(1.0)
    assert result.ratio == pytest.approx(1.0 / 3.0)
    assert result.verdict is Verdict.WORTH_IT
    assert result.explanation.key == "explain.verdict.worth_it"


def test_compare_adds_missing_pair_members_and_deduplicates() -> None:
    market = [gold_a(), gold_a()]
    result = compare(compare_request(gold_a(), gold_b(), market), CONFIG)
    assert result.market_size == 2
    assert result.a.cost.effective_price == 800.0


def test_compare_marginal_verdict() -> None:
    a = candidate("A", 60.0, {"VIT_C": 80.0})
    b = candidate("B", 60.0 + 60.0 * 1.2, {"VIT_C": 80.0, "ZINC": 10.0})
    result = compare(compare_request(a, b), CONFIG)
    assert result.ratio == pytest.approx(1.2)
    assert result.verdict is Verdict.MARGINAL


def test_compare_more_expensive_and_poorer_is_not_worth_it() -> None:
    a = candidate("A", 60.0, {"VIT_C": 80.0, "ZINC": 10.0})
    b = candidate("B", 120.0, {"VIT_C": 80.0})
    result = compare(compare_request(b, a), CONFIG)
    assert result.a.product_id == "A"
    assert result.delta_content < 0
    assert result.verdict is Verdict.NOT_WORTH_IT
    assert result.ratio is None
    assert result.m_ref is not None
    assert result.explanation.params["mRef"] == result.m_ref
    assert "ratio" not in result.explanation.params


def test_compare_zero_content_a_uses_the_market_median_when_available() -> None:
    ctx = context(weight=weights({"VIT_C": 0.0}), market_profile=MarketProfile(1, ()))
    z = candidate("Z", 60.0, {"VIT_C": 80.0})
    y = candidate("Y", 120.0, {"VIT_C": 80.0, "ZINC": 10.0})
    others = [candidate(f"M{i}", 60.0 * i, {"ZINC": 10.0}) for i in (1, 2, 3)]
    result = compare(compare_request(z, y, [z, y, *others], ctx=ctx), CONFIG)
    assert result.m_ref == 2.0
    assert result.verdict is Verdict.WORTH_IT


def test_compare_both_zero_content_equal_cost_is_equivalent() -> None:
    ctx = context(weight=weights({"VIT_C": 0.0}))
    z1, z2 = (candidate(pid, 60.0, {"VIT_C": 80.0}) for pid in ("Z1", "Z2"))
    result = compare(compare_request(z2, z1, ctx=ctx), CONFIG)
    assert (result.a.product_id, result.swapped) == ("Z1", True)
    assert result.verdict is Verdict.EQUIVALENT
    assert result.m_ref is None


def test_delta_rows_flags() -> None:
    a = candidate("A", 60.0, {"VIT_C": 80.0, "ZINC": 20.0, "LUTEIN": 5.0})
    b = candidate("B", 90.0, {"VIT_C": 40.0, "ZINC": 30.0, "VIT_D": 5.0, "COQ10": 50.0})
    result = compare(compare_request(a, b), CONFIG)
    rows = {r.code: r for r in result.nutrient_deltas}
    assert [r.code for r in result.nutrient_deltas] == sorted(rows)
    assert [f.value for f in rows["VIT_D"].flags] == ["NEW_IN_B"]
    assert rows["VIT_D"].r_a == 0.0
    assert [f.value for f in rows["LUTEIN"].flags] == ["REMOVED_IN_B"]
    assert rows["LUTEIN"].r_a is None
    assert rows["LUTEIN"].r_b is None
    assert rows["LUTEIN"].amount_b == 0.0
    assert [f.value for f in rows["COQ10"].flags] == ["NEW_IN_B"]
    assert [f.value for f in rows["ZINC"].flags] == ["DIMINISHING", "ABOVE_UL_NO_CREDIT"]
    assert rows["VIT_C"].flags == ()
    assert rows["VIT_C"].delta_amount == -40.0
    assert rows["ZINC"].delta_contribution == pytest.approx(0.35 * math.log(1.25))
    assert rows["COQ10"].weight == 0.8
    assert rows["COQ10"].category is NutrientCategory.COENZYME


# --- Verdict table and ordering -------------------------------------------------------------


@pytest.mark.parametrize(
    ("delta_cost", "delta_content", "m_ref", "verdict"),
    [
        (0.0, 0.0, 1.0, Verdict.EQUIVALENT),
        (0.0, 0.5, None, Verdict.DOMINATES),
        (1.0, 0.0, 1.0, Verdict.NOT_WORTH_IT),
        (1.0, -0.5, 1.0, Verdict.NOT_WORTH_IT),
        (1.0, 1e-12, 1.0, Verdict.NOT_WORTH_IT),
        (1.0, 0.5, None, Verdict.INSUFFICIENT_DATA),
        (1.0, 1.0, 1.0, Verdict.WORTH_IT),
        (1.5, 1.0, 1.0, Verdict.MARGINAL),
        (1.5000001, 1.0, 1.0, Verdict.NOT_WORTH_IT),
    ],
)
def test_verdict_table(
    delta_cost: float, delta_content: float, m_ref: float | None, verdict: Verdict
) -> None:
    assert verdict_for(delta_cost, delta_content, m_ref, CONFIG)[0] is verdict


def test_verdict_thresholds_are_configurable() -> None:
    strict = EngineConfig(verdict_worth_it_max_ratio=0.5, verdict_marginal_max_ratio=0.8)
    assert verdict_for(1.0, 1.0, 1.0, strict)[0] is Verdict.NOT_WORTH_IT
    assert verdict_for(0.7, 1.0, 1.0, strict)[0] is Verdict.MARGINAL


def test_order_pair_rules() -> None:
    a = scored(candidate("B-1", 100.0, {"VIT_C": 80.0}))
    b = scored(candidate("A-1", 100.0, {"VIT_C": 80.0}))
    cheap = scored(candidate("Z-9", 50.0, {"VIT_C": 80.0}))
    assert order_pair(a, b, 1e-9)[0].product_id == "A-1"
    assert order_pair(a, cheap, 1e-9)[0].product_id == "Z-9"
    assert order_pair(cheap, a, 1e-9)[0].product_id == "Z-9"


def test_market_profile_counts_each_product_once() -> None:
    p = profile_product("P", {"COQ10": 100.0})
    duplicate_code = MarketProfile(
        1,
        (
            p,
            p,
            replace(p, product_id="Q", nutrients=(*p.nutrients, *p.nutrients)),
        ),
    )
    assert market_amounts(duplicate_code) == {"COQ10": [100.0, 100.0]}


def test_upper_limit_rows_keep_their_basis() -> None:
    ctx = context(uls=EFSA)
    assert all(isinstance(u, UpperLimit) for u in ctx.upper_limits)
    assert {u.basis for u in ctx.upper_limits} == {None, "TOTAL"}
