"""Product scoring, pre-scoring filters, display normalisation and ranking (spec 6.2-6.7, 6.9)."""

import math
from collections.abc import Iterable, Sequence
from operator import attrgetter

from vitadelta_scoring.config import EngineConfig
from vitadelta_scoring.engine.model import (
    BreakdownRow,
    CandidateInput,
    CandidateNutrientInput,
    Cost,
    ExcludedCounts,
    Flag,
    FlagCode,
    ProductScore,
    RankingMode,
    ReferenceSource,
    RegistryNutrient,
    RequiredNutrient,
    ScoredProduct,
    ScoreMeta,
    ScoreRequest,
    ScoreResult,
    ScoringContext,
)
from vitadelta_scoring.engine.numeric import is_positive, median, require_finite, sort_key
from vitadelta_scoring.engine.references import (
    ReferenceResolver,
    ResolvedReference,
    UlMatcher,
    UlMatchKind,
)
from vitadelta_scoring.engine.utility import is_below_presence_floor, presence_utility, utility
from vitadelta_scoring.engine.validation import validate_score_request

_BY_CODE = attrgetter("code")
DEFAULT_LAMBDA = 0.5
TOP_CONTRIBUTORS = 5
MARKET_RATE_MIN_PRODUCTS = 3


def normalise_cost(candidate: CandidateInput) -> Cost:
    """Spec 6.2: servings per container, days of supply and cost per day."""
    servings_per_container = candidate.units_per_container / candidate.units_per_serving
    days_supply = servings_per_container / candidate.servings_per_day
    return Cost(
        effective_price=float(candidate.effective_price),
        currency=candidate.currency,
        servings_per_container=servings_per_container,
        days_supply=days_supply,
        cost_per_day=candidate.effective_price / days_supply,
        price_stale=candidate.price_stale,
        out_of_stock=candidate.out_of_stock,
    )


def dedupe(candidates: Iterable[CandidateInput]) -> list[CandidateInput]:
    """De-duplicate by ``product_id``; the first occurrence wins."""
    seen: set[str] = set()
    unique: list[CandidateInput] = []
    for candidate in candidates:
        if candidate.product_id not in seen:
            seen.add(candidate.product_id)
            unique.append(candidate)
    return unique


class Scorer:
    """Scores candidates against the references, limits and weights of one request."""

    def __init__(self, context: ScoringContext, config: EngineConfig):
        self.config = config
        self.registry = {n.code: n for n in context.nutrients}
        self.resolver = ReferenceResolver(context.reference_values, context.market_profile)
        self.matcher = UlMatcher(context.upper_limits)
        category_weights = context.weights.category_weights
        overrides = context.weights.overrides
        self._weights = {
            code: category_weights[n.category] * overrides.get(code, 1.0)
            for code, n in self.registry.items()
        }
        self._plans: dict[str, tuple[RegistryNutrient, ResolvedReference, float]] = {}
        self._beta = config.diminishing_beta
        self._u_max = config.utility_ceiling

    def weight(self, code: str) -> float:
        return self._weights[code]

    def reference(self, code: str) -> ResolvedReference:
        return self.resolver.resolve(code)

    def _plan(self, code: str) -> tuple[RegistryNutrient, ResolvedReference, float]:
        plan = self._plans.get(code)
        if plan is None:
            plan = (self.registry[code], self.resolver.resolve(code), self._weights[code])
            self._plans[code] = plan
        return plan

    def row(self, nutrient: CandidateNutrientInput, servings_per_day: float) -> BreakdownRow:
        """One breakdown row: reference, UL cap, coverage ratio, utility and contribution."""
        code = nutrient.code
        registry, reference, weight = self._plan(code)
        amount_per_day = nutrient.amount_per_serving * servings_per_day
        ul_amount = nutrient.ul_amount_per_serving
        ul_amount_per_day = amount_per_day if ul_amount is None else ul_amount * servings_per_day
        match = self.matcher.match(code, nutrient.form_code)
        upper_limit = match.amount if match.kind is UlMatchKind.LIMIT else None
        exceeds_ul = upper_limit is not None and ul_amount_per_day > upper_limit
        ref_amount = reference.amount

        if ref_amount is None:
            floor = registry.presence_floor_per_day
            below_floor = is_below_presence_floor(amount_per_day, floor)
            u = presence_utility(amount_per_day, floor)
            coverage: float | None = None
            capped_at_ul = False
            capped_at_u_max = False
            ul_below_reference = False
        else:
            below_floor = False
            creditable = amount_per_day
            capped_at_ul = False
            if exceeds_ul and upper_limit is not None:
                creditable = min(amount_per_day, upper_limit + (amount_per_day - ul_amount_per_day))
                capped_at_ul = creditable < amount_per_day
            coverage = creditable / ref_amount
            u, capped_at_u_max = utility(coverage, self._beta, self._u_max)
            ul_below_reference = upper_limit is not None and upper_limit < ref_amount

        # Positional construction (hot path); the order is the field order of BreakdownRow.
        return BreakdownRow(
            code,
            registry.category,
            amount_per_day,
            ul_amount_per_day,
            registry.canonical_unit,
            ref_amount,
            reference.source,
            coverage,
            u,
            weight,
            weight * u,
            capped_at_ul,
            capped_at_u_max,
            ul_below_reference,
            below_floor,
            upper_limit,
            exceeds_ul,
            match.kind is UlMatchKind.FORM_DEPENDENT,
            nutrient.conversion_assumed,
        )

    def score(self, candidate: CandidateInput) -> ProductScore:
        """Sections 6.2 to 6.6 and the product flags of 6.9 for one candidate."""
        cost = normalise_cost(candidate)
        rows = sorted(
            (self.row(n, candidate.servings_per_day) for n in candidate.nutrients),
            key=_BY_CODE,
        )
        content = math.fsum(row.contribution for row in rows)
        return ProductScore(
            product_id=candidate.product_id,
            cost=cost,
            content_score=content,
            value_score=content / cost.cost_per_day,
            flags=product_flags(candidate, rows),
            breakdown=tuple(rows),
        )


def product_flags(candidate: CandidateInput, rows: Sequence[BreakdownRow]) -> tuple[Flag, ...]:
    """Product-level flags in enum order, each with its sorted nutrient codes (spec 6.9).

    ``rows`` are sorted by code, so every code list is sorted too.
    """
    exceeds: list[str] = []
    form_dependent: list[str] = []
    assumed: list[str] = []
    median_used: list[str] = []
    presence_only: list[str] = []
    below_floor: list[str] = []
    for row in rows:
        code = row.code
        if row.exceeds_ul:
            exceeds.append(code)
        if row.ul_form_dependent:
            form_dependent.append(code)
        if row.conversion_assumed:
            assumed.append(code)
        source = row.reference_source
        if source is ReferenceSource.MARKET_MEDIAN:
            median_used.append(code)
        elif source is ReferenceSource.PRESENCE_ONLY:
            presence_only.append(code)
            if row.below_presence_floor:
                below_floor.append(code)
    flags: list[Flag] = []
    for flag_code, codes in (
        (FlagCode.EXCEEDS_UL, exceeds),
        (FlagCode.UL_FORM_DEPENDENT, form_dependent),
        (FlagCode.CONVERSION_ASSUMED, assumed),
    ):
        if codes:
            flags.append(Flag(flag_code, tuple(codes)))
    if candidate.price_stale or candidate.out_of_stock:
        flags.append(Flag(FlagCode.PRICE_STALE, ()))
    if candidate.out_of_stock:
        flags.append(Flag(FlagCode.OUT_OF_STOCK, ()))
    for flag_code, codes in (
        (FlagCode.MARKET_MEDIAN_REFERENCE_USED, median_used),
        (FlagCode.PRESENCE_ONLY_NUTRIENTS, presence_only),
        (FlagCode.BELOW_PRESENCE_FLOOR, below_floor),
    ):
        if codes:
            flags.append(Flag(flag_code, tuple(codes)))
    return tuple(flags)


def market_rate(
    scores: Iterable[ProductScore], epsilon: float, field: str = "m_ref"
) -> float | None:
    """Median of ``cost_per_day / C`` over products with ``C > 0``; None below 3 products.

    A median that is not representable as a finite double is rejected (``value_out_of_range``).
    """
    rates = [
        s.cost.cost_per_day / s.content_score
        for s in scores
        if is_positive(s.content_score, epsilon)
    ]
    if len(rates) < MARKET_RATE_MIN_PRODUCTS:
        return None
    return require_finite(median(rates), field, "The market rate")


def check_finite_rows(candidate: CandidateInput, scored: ProductScore, path: str) -> None:
    """Reject coverage ratios that overflow (huge amount against a tiny reference)."""
    for row in scored.breakdown:
        if row.coverage_ratio is not None and not math.isfinite(row.coverage_ratio):
            index = next(i for i, n in enumerate(candidate.nutrients) if n.code == row.code)
            require_finite(
                row.coverage_ratio,
                f"{path}.nutrients[{index}].amount_per_serving",
                "The coverage ratio",
            )


def _meets_requirement(score: ProductScore, required: RequiredNutrient) -> bool:
    for row in score.breakdown:
        if row.code == required.code:
            minimum = required.min_amount_per_day
            if minimum is None:
                return row.amount_per_day > 0.0
            return row.amount_per_day >= minimum
    return False


def _minmax(values: Sequence[float]) -> list[float]:
    """Min-max scaling; 1 for every value only when all values are exactly equal."""
    low, high = min(values), max(values)
    if low == high:
        return [1.0] * len(values)
    span = high - low
    return [(v - low) / span for v in values]


def _display(value: float, maximum: float) -> float:
    """``100 * value / max`` over the surviving set, 0 for everyone when ``max = 0``."""
    return 100.0 * (value / maximum) if maximum > 0.0 else 0.0


def _top_contributors(rows: tuple[BreakdownRow, ...]) -> tuple[BreakdownRow, ...]:
    ordered = sorted(rows, key=lambda row: (-row.contribution, row.code))
    return tuple(ordered[:TOP_CONTRIBUTORS])


def rank(
    scores: Sequence[ProductScore],
    mode: RankingMode,
    lambda_: float | None,
    epsilon: float,
) -> tuple[ScoredProduct, ...]:
    """Display normalisation and ranking over the surviving set (spec 6.5-6.7)."""
    if not scores:
        return ()
    max_content = max(s.content_score for s in scores)
    max_value = max(s.value_score for s in scores)
    custom: list[float | None] = [None] * len(scores)
    if mode is RankingMode.CUSTOM:
        weight = DEFAULT_LAMBDA if lambda_ is None else lambda_
        content_scaled = _minmax([s.content_score for s in scores])
        cost_scaled = _minmax([-s.cost.cost_per_day for s in scores])
        custom = [
            min(1.0, max(0.0, weight * c + (1.0 - weight) * p))
            for c, p in zip(content_scaled, cost_scaled, strict=True)
        ]

    def key(index: int) -> tuple[float, float, str]:
        s = scores[index]
        content = sort_key(s.content_score)
        if mode is RankingMode.PRICE:
            return (sort_key(s.cost.cost_per_day), -content, s.product_id)
        if mode is RankingMode.RICHNESS:
            return (-content, sort_key(s.cost.cost_per_day), s.product_id)
        if mode is RankingMode.CUSTOM:
            custom_score = custom[index]
            assert custom_score is not None
            return (-sort_key(custom_score), -content, s.product_id)
        return (-sort_key(s.value_score), -content, s.product_id)

    order = sorted(range(len(scores)), key=key)
    ranked: list[ScoredProduct] = []
    for position, index in enumerate(order, start=1):
        s = scores[index]
        ranked.append(
            ScoredProduct(
                product_id=s.product_id,
                rank=position,
                cost=s.cost,
                content_score=s.content_score,
                content_score_display=_display(s.content_score, max_content),
                value_score=s.value_score,
                value_score_display=_display(s.value_score, max_value),
                custom_score=custom[index],
                flags=s.flags,
                breakdown=s.breakdown,
                top_contributors=_top_contributors(s.breakdown),
            )
        )
    return tuple(ranked)


def score(request: ScoreRequest, config: EngineConfig) -> ScoreResult:
    """``POST /score``: score, filter and rank a candidate set."""
    validate_score_request(request)
    epsilon = config.comparison_epsilon
    scorer = Scorer(request.context, config)
    candidates = dedupe(request.candidates)
    scores = [scorer.score(c) for c in candidates]
    positions: dict[str, int] = {}
    for index, original in enumerate(request.candidates):
        positions.setdefault(original.product_id, index)
    for candidate, scored in zip(candidates, scores, strict=True):
        check_finite_rows(candidate, scored, f"candidates[{positions[candidate.product_id]}]")
    m_ref = market_rate(scores, epsilon, "meta.m_ref")

    survivors = [
        s for s in scores if all(_meets_requirement(s, req) for req in request.required_nutrients)
    ]
    excluded_required = len(scores) - len(survivors)
    excluded_ul = 0
    if request.exclude_exceeding_ul:
        kept = [s for s in survivors if not s.has_flag(FlagCode.EXCEEDS_UL)]
        excluded_ul = len(survivors) - len(kept)
        survivors = kept

    items = rank(survivors, request.ranking.mode, request.ranking.lambda_, epsilon)
    return ScoreResult(
        items=items,
        meta=ScoreMeta(
            m_ref=m_ref,
            market_size=len(candidates),
            excluded=ExcludedCounts(required_nutrients=excluded_required, exceeds_ul=excluded_ul),
        ),
    )
