"""Fixture market-rate bound of spec 5.8, computed with the real engine over the real files.

The median of ``cost_per_day / C`` over all TR products with in-stock offers (default
weights, EU_NRV / EFSA) must be at most 10.00 TRY per point per day, so that GOLD-A vs
GOLD-B stays NOT_WORTH_IT (ratio > 1.5) in the e2e flows, before and after the price-drop
file.
"""

import pytest

from fixture_market import (
    PRICE_DROP_OFFERS,
    REAL_OFFERS,
    REAL_PRODUCTS,
    SYNTHETIC_OFFERS,
    SYNTHETIC_PRODUCTS,
    Conversions,
    build_market,
    reference_rows,
)
from vitadelta_scoring.config import DEFAULT_CONFIG
from vitadelta_scoring.engine import compare, score
from vitadelta_scoring.engine.model import CompareRequest, Ranking, ScoreRequest, Verdict
from vitadelta_scoring.engine.scoring import Scorer, market_rate

MARKET_RATE_BOUND = 10.00


def test_tr_market_rate_is_at_most_ten() -> None:
    market = build_market("TR")
    assert market.country.reference_set == "EU_NRV"
    assert market.country.ul_set == "EFSA"
    scorer = Scorer(market.context, DEFAULT_CONFIG)
    rate = market_rate((scorer.score(c) for c in market.candidates), 1e-9)
    assert rate is not None
    assert rate <= MARKET_RATE_BOUND, f"TR market rate {rate:.4f} > {MARKET_RATE_BOUND}"
    result = score(
        ScoreRequest(market.context, Ranking(), (), False, market.candidates), DEFAULT_CONFIG
    )
    assert result.meta.m_ref == rate


@pytest.mark.parametrize(
    ("offers", "gold_b_price"), [(SYNTHETIC_OFFERS, 900.0), (PRICE_DROP_OFFERS, 846.0)]
)
def test_golden_pair_stays_not_worth_it(offers: tuple[str, str], gold_b_price: float) -> None:
    market = build_market("TR", (SYNTHETIC_PRODUCTS,), (offers,))
    by_id = market.by_id()
    assert by_id["GOLD-A"].effective_price == 800.0
    assert by_id["GOLD-B"].effective_price == gold_b_price
    result = compare(
        CompareRequest(market.context, by_id["GOLD-A"], by_id["GOLD-B"], market.candidates),
        DEFAULT_CONFIG,
    )
    assert result.swapped is False
    assert result.m_ref is not None and result.m_ref <= MARKET_RATE_BOUND
    assert result.ratio is not None and result.ratio > DEFAULT_CONFIG.verdict_marginal_max_ratio
    assert result.verdict is Verdict.NOT_WORTH_IT


@pytest.mark.parametrize(("country", "expected"), [("TR", 1), ("DE", 0), ("GB", 0), ("US", 0)])
def test_exceeds_ul_exclusions_per_country(country: str, expected: int) -> None:
    market = build_market(country)
    request = ScoreRequest(market.context, Ranking(), (), True, market.candidates)
    assert score(request, DEFAULT_CONFIG).meta.excluded.exceeds_ul == expected


def test_market_median_reference_for_coq10_in_tr() -> None:
    market = build_market("TR")
    scorer = Scorer(market.context, DEFAULT_CONFIG)
    reference = scorer.reference("COQ10")
    assert (reference.source.value, reference.amount) == ("MARKET_MEDIAN", 100.0)


def test_real_label_snapshot_scores_without_errors() -> None:
    market = build_market(
        "US", (SYNTHETIC_PRODUCTS, REAL_PRODUCTS), (SYNTHETIC_OFFERS, REAL_OFFERS)
    )
    assert len(market.candidates) > 80
    result = score(
        ScoreRequest(market.context, Ranking(), (), False, market.candidates), DEFAULT_CONFIG
    )
    assert len(result.items) == len(market.candidates)
    assert result.meta.m_ref is not None


def test_unit_conversions_follow_the_reference_table() -> None:
    table = Conversions(reference_rows("unit_conversions.csv"))
    assert table.factor("VIT_E", "UNKNOWN", "IU", "mg") == (0.67, True)
    assert table.factor("VIT_E", "D_ALPHA_TOCOPHEROL", "IU", "mg") == (0.67, False)
    assert table.factor("VIT_E", "DL_ALPHA_TOCOPHEROL", "IU", "mg") == (0.45, False)
    assert table.factor("VIT_D", "UNKNOWN", "IU", "mcg") == (0.025, False)
    assert table.factor("CALCIUM", "UNKNOWN", "g", "mg") == (1000.0, False)
    assert table.factor("ZINC", "UNKNOWN", "mg", "mg") == (1.0, False)
    with pytest.raises(ValueError, match="no conversion"):
        table.factor("VIT_A", "BETA_CAROTENE", "IU", "mcg")


def test_conversion_assumed_reaches_the_product_flags() -> None:
    market = build_market(
        "US", (SYNTHETIC_PRODUCTS, REAL_PRODUCTS), (SYNTHETIC_OFFERS, REAL_OFFERS)
    )
    assumed = {
        c.product_id for c in market.candidates if any(n.conversion_assumed for n in c.nutrients)
    }
    result = score(
        ScoreRequest(market.context, Ranking(), (), False, market.candidates), DEFAULT_CONFIG
    )
    flagged = {
        i.product_id
        for i in result.items
        if any(f.code.value == "CONVERSION_ASSUMED" for f in i.flags)
    }
    assert flagged == assumed
