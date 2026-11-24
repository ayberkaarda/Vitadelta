"""Pairwise marginal analysis (spec 6.8, 6.8a)."""

from dataclasses import dataclass

from vitadelta_scoring.config import EngineConfig
from vitadelta_scoring.engine.model import (
    BreakdownRow,
    CandidateInput,
    CompareRequest,
    CompareResult,
    DeltaRowFlag,
    Explanation,
    NutrientDeltaRow,
    ProductScore,
    Verdict,
)
from vitadelta_scoring.engine.numeric import eq, is_positive, require_finite
from vitadelta_scoring.engine.scoring import Scorer, check_finite_rows, dedupe, market_rate
from vitadelta_scoring.engine.validation import validate_compare_request

EXPLANATION_KEYS = {
    Verdict.WORTH_IT: "explain.verdict.worth_it",
    Verdict.MARGINAL: "explain.verdict.marginal",
    Verdict.NOT_WORTH_IT: "explain.verdict.not_worth_it",
    Verdict.DOMINATES: "explain.verdict.dominates",
    Verdict.EQUIVALENT: "explain.verdict.equivalent",
    Verdict.INSUFFICIENT_DATA: "explain.verdict.insufficient_data",
}


def order_pair(
    x: ProductScore, y: ProductScore, epsilon: float
) -> tuple[ProductScore, ProductScore]:
    """A = cheaper by cost per day; equal cost: lower C; equal C: lower product id."""
    cost_x, cost_y = x.cost.cost_per_day, y.cost.cost_per_day
    if not eq(cost_x, cost_y, epsilon):
        return (x, y) if cost_x < cost_y else (y, x)
    if not eq(x.content_score, y.content_score, epsilon):
        return (x, y) if x.content_score < y.content_score else (y, x)
    return (x, y) if x.product_id < y.product_id else (y, x)


def verdict_for(
    delta_cost: float,
    delta_content: float,
    m_ref: float | None,
    config: EngineConfig,
) -> tuple[Verdict, float | None, float | None]:
    """Verdict table, first matching row wins. Returns ``(verdict, marginal_rate, ratio)``.

    ``delta_cost`` and ``delta_content`` are already snapped to exactly 0 when they equal 0
    under the epsilon rule.
    """
    epsilon = config.comparison_epsilon
    content_up = is_positive(delta_content, epsilon)
    if delta_cost == 0.0:
        if delta_content == 0.0:
            return Verdict.EQUIVALENT, None, None
        if content_up:
            return Verdict.DOMINATES, None, None
    if not content_up:
        return Verdict.NOT_WORTH_IT, None, None
    if m_ref is None:
        return Verdict.INSUFFICIENT_DATA, None, None
    marginal_rate = require_finite(delta_cost / delta_content, "marginal_rate", "The marginal rate")
    ratio = require_finite(marginal_rate / m_ref, "ratio", "The ratio")
    if ratio <= config.verdict_worth_it_max_ratio:
        return Verdict.WORTH_IT, marginal_rate, ratio
    if ratio <= config.verdict_marginal_max_ratio:
        return Verdict.MARGINAL, marginal_rate, ratio
    return Verdict.NOT_WORTH_IT, marginal_rate, ratio


def _delta_flags(
    row_a: BreakdownRow | None,
    row_b: BreakdownRow | None,
    delta_amount: float,
    r_a: float | None,
) -> tuple[DeltaRowFlag, ...]:
    if delta_amount == 0.0:
        return ()
    flags: list[DeltaRowFlag] = []
    if row_a is None and row_b is not None:
        flags.append(DeltaRowFlag.NEW_IN_B)
    if row_a is not None and row_b is None:
        flags.append(DeltaRowFlag.REMOVED_IN_B)
    if delta_amount > 0.0:
        if r_a is not None and r_a >= 1.0:
            flags.append(DeltaRowFlag.DIMINISHING)
        if row_b is not None and row_b.capped_at_u_max:
            flags.append(DeltaRowFlag.ABOVE_CEILING_NO_CREDIT)
        if row_b is not None and row_b.exceeds_ul:
            flags.append(DeltaRowFlag.ABOVE_UL_NO_CREDIT)
    return tuple(flags)


def nutrient_deltas(
    a: ProductScore, b: ProductScore, scorer: Scorer, epsilon: float
) -> tuple[NutrientDeltaRow, ...]:
    """Per-nutrient delta rows over the union of nutrients in A and B, sorted by code."""
    rows_a = {row.code: row for row in a.breakdown}
    rows_b = {row.code: row for row in b.breakdown}
    deltas: list[NutrientDeltaRow] = []
    for code in sorted(rows_a.keys() | rows_b.keys()):
        row_a, row_b = rows_a.get(code), rows_b.get(code)
        registry = scorer.registry[code]
        absent_ratio = None if scorer.reference(code).amount is None else 0.0
        amount_a = row_a.amount_per_day if row_a is not None else 0.0
        amount_b = row_b.amount_per_day if row_b is not None else 0.0
        r_a = row_a.coverage_ratio if row_a is not None else absent_ratio
        r_b = row_b.coverage_ratio if row_b is not None else absent_ratio
        u_a = row_a.utility if row_a is not None else 0.0
        u_b = row_b.utility if row_b is not None else 0.0
        delta_amount = 0.0 if eq(amount_a, amount_b, epsilon) else amount_b - amount_a
        weight = scorer.weight(code)
        deltas.append(
            NutrientDeltaRow(
                code=code,
                category=registry.category,
                amount_a=amount_a,
                amount_b=amount_b,
                delta_amount=delta_amount,
                unit=registry.canonical_unit,
                r_a=r_a,
                r_b=r_b,
                u_a=u_a,
                u_b=u_b,
                weight=weight,
                delta_contribution=weight * (u_b - u_a),
                flags=_delta_flags(row_a, row_b, delta_amount, r_a),
            )
        )
    return tuple(deltas)


@dataclass(frozen=True, slots=True)
class PairOutcome:
    a: ProductScore
    b: ProductScore
    delta_cost: float
    delta_content: float
    m_ref: float | None
    verdict: Verdict
    marginal_rate: float | None
    ratio: float | None


def pair_outcome(
    x: ProductScore,
    y: ProductScore,
    market_m_ref: float | None,
    config: EngineConfig,
) -> PairOutcome:
    """Steps 2 to 5 of spec 6.8 for two scored products and the market's median rate.

    ``market_m_ref`` is the median of ``cost_per_day / C`` over the market (None below 3
    products with ``C > 0``); the fallback to A's own rate is applied here.
    """
    epsilon = config.comparison_epsilon
    a, b = order_pair(x, y, epsilon)
    cost_a, cost_b = a.cost.cost_per_day, b.cost.cost_per_day
    delta_cost = 0.0 if eq(cost_a, cost_b, epsilon) else cost_b - cost_a
    content_a, content_b = a.content_score, b.content_score
    delta_content = 0.0 if eq(content_a, content_b, epsilon) else content_b - content_a
    m_ref = market_m_ref
    if m_ref is None and is_positive(content_a, epsilon):
        m_ref = require_finite(cost_a / content_a, "m_ref", "The market rate")
    verdict, marginal_rate, ratio = verdict_for(delta_cost, delta_content, m_ref, config)
    return PairOutcome(a, b, delta_cost, delta_content, m_ref, verdict, marginal_rate, ratio)


def _market_path(request: CompareRequest, member: CandidateInput) -> str:
    index = next(i for i, c in enumerate(request.market) if c.product_id == member.product_id)
    return f"market[{index}]"


def compare(request: CompareRequest, config: EngineConfig) -> CompareResult:
    """``POST /compare``: order the pair, compute deltas, the market rate and the verdict."""
    validate_compare_request(request)
    epsilon = config.comparison_epsilon
    scorer = Scorer(request.context, config)
    market = dedupe((request.baseline, request.candidate, *request.market))
    scores = {c.product_id: scorer.score(c) for c in market}
    for index, member in enumerate(market):
        path = ("baseline", "candidate")[index] if index < 2 else _market_path(request, member)
        check_finite_rows(member, scores[member.product_id], path)
    baseline = scores[request.baseline.product_id]
    candidate = scores[request.candidate.product_id]
    outcome = pair_outcome(baseline, candidate, market_rate(scores.values(), epsilon), config)
    a, b = outcome.a, outcome.b
    delta_cost, delta_content, m_ref = outcome.delta_cost, outcome.delta_content, outcome.m_ref
    verdict, marginal_rate, ratio = outcome.verdict, outcome.marginal_rate, outcome.ratio
    params: dict[str, str | float] = {
        "currency": a.cost.currency,
        "deltaCostPerDay": delta_cost,
        "deltaContent": delta_content,
    }
    for name, value in (("marginalRate", marginal_rate), ("mRef", m_ref), ("ratio", ratio)):
        if value is not None:
            params[name] = value

    return CompareResult(
        a=a,
        b=b,
        swapped=a.product_id != request.baseline.product_id,
        currency=a.cost.currency,
        delta_cost=delta_cost,
        delta_content=delta_content,
        m_ref=m_ref,
        market_size=len(market),
        marginal_rate=marginal_rate,
        ratio=ratio,
        verdict=verdict,
        nutrient_deltas=nutrient_deltas(a, b, scorer, epsilon),
        explanation=Explanation(key=EXPLANATION_KEYS[verdict], params=params),
    )
