"""Semantic validation of engine requests.

Structural rules (types, ranges, patterns) are also enforced by the HTTP layer; they are
repeated here so that the engine is safe to call directly. Every violation is collected and
reported at once, in request traversal order.
"""

import math
from collections.abc import Iterable
from dataclasses import dataclass

from vitadelta_scoring.engine.model import (
    ANY_FORM,
    UNKNOWN_FORM,
    CandidateInput,
    CompareRequest,
    MarketProfileProductInput,
    NutrientCategory,
    RankingMode,
    RegistryNutrient,
    ScoreRequest,
    ScoringContext,
)

# Generous upper bound of a content score (weights <= 6, utilities <= u_max, a few hundred
# nutrients), used only to keep the value score finite.
MAX_CONTENT_SCORE = 1.0e6


@dataclass(frozen=True, slots=True)
class Issue:
    field: str
    code: str
    message: str


class EngineValidationError(ValueError):
    """The request violates a semantic rule; ``issues`` lists every violation."""

    def __init__(self, issues: Iterable[Issue]):
        self.issues = tuple(issues)
        super().__init__("; ".join(f"{i.field}: {i.code}" for i in self.issues))


class _Collector:
    def __init__(self) -> None:
        self.issues: list[Issue] = []

    def add(self, field: str, code: str, message: str) -> None:
        self.issues.append(Issue(field, code, message))

    def raise_if_any(self) -> None:
        if self.issues:
            raise EngineValidationError(self.issues)


def _finite_positive(value: float) -> bool:
    return math.isfinite(value) and value > 0.0


def _check_registry(nutrients: tuple[RegistryNutrient, ...], out: _Collector) -> None:
    if not nutrients:
        out.add("nutrients", "out_of_range", "The nutrient registry block must not be empty.")
    seen: set[str] = set()
    for i, nutrient in enumerate(nutrients):
        if nutrient.code in seen:
            out.add(
                f"nutrients[{i}].code",
                "duplicate_registry_code",
                f"Registry code {nutrient.code} appears more than once.",
            )
        seen.add(nutrient.code)
        floor = nutrient.presence_floor_per_day
        if floor is not None and not _finite_positive(floor):
            out.add(
                f"nutrients[{i}].presence_floor_per_day",
                "out_of_range",
                "The presence floor must be greater than 0 when set.",
            )


def _check_unit(
    registry: dict[str, RegistryNutrient], code: str, unit: str, field: str, out: _Collector
) -> None:
    canonical = registry[code].canonical_unit
    if unit != canonical:
        out.add(
            field,
            "unit_mismatch",
            f"Unit {unit} differs from the canonical unit {canonical} of {code}.",
        )


def _check_known(
    registry: dict[str, RegistryNutrient], code: str, field: str, out: _Collector
) -> bool:
    if code in registry:
        return True
    out.add(field, "unknown_nutrient_code", f"Nutrient code {code} is not in the registry block.")
    return False


def _check_context(context: ScoringContext, out: _Collector) -> dict[str, RegistryNutrient]:
    _check_registry(context.nutrients, out)
    registry = {n.code: n for n in context.nutrients}

    seen_refs: set[str] = set()
    for i, ref in enumerate(context.reference_values):
        path = f"reference_values[{i}]"
        if not _check_known(registry, ref.nutrient_code, f"{path}.nutrient_code", out):
            continue
        if ref.nutrient_code in seen_refs:
            out.add(
                f"{path}.nutrient_code",
                "duplicate_nutrient",
                f"Reference value for {ref.nutrient_code} appears more than once.",
            )
        seen_refs.add(ref.nutrient_code)
        if not _finite_positive(ref.amount):
            out.add(f"{path}.amount", "out_of_range", "The reference amount must be > 0.")
        _check_unit(registry, ref.nutrient_code, ref.unit, f"{path}.unit", out)

    seen_uls: set[tuple[str, str]] = set()
    for i, ul in enumerate(context.upper_limits):
        path = f"upper_limits[{i}]"
        if not _check_known(registry, ul.nutrient_code, f"{path}.nutrient_code", out):
            continue
        key = (ul.nutrient_code, ul.form_code)
        if key in seen_uls:
            out.add(
                f"{path}.form_code",
                "duplicate_nutrient",
                f"Upper limit for {ul.nutrient_code} / {ul.form_code} appears more than once.",
            )
        seen_uls.add(key)
        if ul.form_code == UNKNOWN_FORM:
            out.add(f"{path}.form_code", "invalid_format", "UNKNOWN is not a valid UL form.")
        exempt_parts = (ul.amount is None, ul.unit is None, ul.basis is None)
        if any(exempt_parts) and not all(exempt_parts):
            out.add(
                path,
                "invalid_format",
                "An exempt row has amount, unit and basis all null; a limit row has all three.",
            )
            continue
        if ul.amount is not None and ul.unit is not None:
            if not _finite_positive(ul.amount):
                out.add(f"{path}.amount", "out_of_range", "The upper limit must be > 0.")
            _check_unit(registry, ul.nutrient_code, ul.unit, f"{path}.unit", out)

    weights = context.weights
    for category in NutrientCategory:
        value = weights.category_weights.get(category)
        if value is None:
            out.add(
                f"weights.category_weights.{category.value}",
                "required",
                "Every category weight is required.",
            )
        elif not (math.isfinite(value) and 0.0 <= value <= 2.0):
            out.add(
                f"weights.category_weights.{category.value}",
                "out_of_range",
                "Category weights must be within [0, 2].",
            )
    for code, value in sorted(weights.overrides.items()):
        path = f"weights.overrides.{code}"
        _check_known(registry, code, path, out)
        if not (math.isfinite(value) and 0.0 <= value <= 3.0):
            out.add(path, "out_of_range", "Overrides must be within [0, 3].")
    if registry and all(
        weights.category_weights.get(n.category, 0.0) * weights.overrides.get(n.code, 1.0) == 0.0
        for n in registry.values()
    ):
        out.add("weights", "weights_all_zero", "The weights make every registry nutrient 0.")

    first_products: dict[str, MarketProfileProductInput] = {}
    for i, product in enumerate(context.market_profile.products):
        earlier = first_products.setdefault(product.product_id, product)
        if earlier is not product and _profile_key(earlier) != _profile_key(product):
            out.add(
                f"market_profile.products[{i}].product_id",
                "duplicate_product_id",
                f"Product {product.product_id} appears more than once with different content.",
            )
        seen_codes: set[str] = set()
        for j, nutrient in enumerate(product.nutrients):
            code = nutrient.code
            if code not in registry:
                _check_known(
                    registry, code, f"market_profile.products[{i}].nutrients[{j}].code", out
                )
            if code in seen_codes:
                out.add(
                    f"market_profile.products[{i}].nutrients[{j}].code",
                    "duplicate_nutrient",
                    f"Nutrient {code} appears more than once in the product.",
                )
            seen_codes.add(code)
            if not 0.0 < nutrient.amount_per_day < math.inf:
                out.add(
                    f"market_profile.products[{i}].nutrients[{j}].amount_per_day",
                    "out_of_range",
                    "Amounts must be > 0.",
                )
    return registry


def _candidate_key(c: CandidateInput) -> tuple[object, ...]:
    """Content of a candidate, independent of the order of its nutrients."""
    return (
        c.units_per_container,
        c.units_per_serving,
        c.servings_per_day,
        c.effective_price,
        c.currency,
        c.price_stale,
        c.out_of_stock,
        tuple(
            sorted(
                (
                    n.code,
                    n.amount_per_serving,
                    str(n.unit),
                    n.form_code,
                    n.conversion_assumed,
                    -1.0 if n.ul_amount_per_serving is None else n.ul_amount_per_serving,
                )
                for n in c.nutrients
            )
        ),
    )


def _profile_key(p: MarketProfileProductInput) -> tuple[tuple[str, float], ...]:
    return tuple(sorted((n.code, n.amount_per_day) for n in p.nutrients))


def _check_duplicates(members: Iterable[tuple[str, CandidateInput]], out: _Collector) -> None:
    """Exact duplicates are de-duplicated later; conflicting ones are rejected."""
    first: dict[str, CandidateInput] = {}
    for path, candidate in members:
        earlier = first.setdefault(candidate.product_id, candidate)
        if earlier is candidate:
            continue
        if _candidate_key(earlier) != _candidate_key(candidate):
            out.add(
                f"{path}.product_id",
                "duplicate_product_id",
                f"Product {candidate.product_id} appears more than once with different content.",
            )


def _check_candidate(
    candidate: CandidateInput,
    path: str,
    registry: dict[str, RegistryNutrient],
    currency: str,
    out: _Collector,
) -> None:
    if candidate.units_per_serving < 1:
        out.add(f"{path}.units_per_serving", "out_of_range", "units_per_serving must be >= 1.")
    if candidate.units_per_container < 1:
        out.add(f"{path}.units_per_container", "out_of_range", "units_per_container must be >= 1.")
    elif candidate.units_per_container < candidate.units_per_serving:
        out.add(
            f"{path}.units_per_container",
            "container_smaller_than_serving",
            "units_per_container must be >= units_per_serving.",
        )
    if not (_finite_positive(candidate.servings_per_day) and candidate.servings_per_day <= 99.99):
        out.add(
            f"{path}.servings_per_day", "out_of_range", "servings_per_day must be in (0, 99.99]."
        )
    if not _finite_positive(candidate.effective_price):
        out.add(f"{path}.effective_price", "out_of_range", "effective_price must be > 0.")
    if candidate.currency != currency:
        out.add(
            f"{path}.currency",
            "currency_mismatch",
            f"Currency {candidate.currency} differs from the request currency {currency}.",
        )
    # Hot loop: field paths are only formatted when a violation is recorded.
    seen: set[str] = set()
    for j, nutrient in enumerate(candidate.nutrients):
        code = nutrient.code
        entry = registry.get(code)
        if entry is None:
            _check_known(registry, code, f"{path}.nutrients[{j}].code", out)
        elif nutrient.unit != entry.canonical_unit:
            _check_unit(registry, code, nutrient.unit, f"{path}.nutrients[{j}].unit", out)
        if code in seen:
            out.add(
                f"{path}.nutrients[{j}].code",
                "duplicate_nutrient",
                f"Nutrient {code} appears more than once in the product.",
            )
        seen.add(code)
        amount = nutrient.amount_per_serving
        if not 0.0 < amount < math.inf:
            out.add(
                f"{path}.nutrients[{j}].amount_per_serving", "out_of_range", "Amounts must be > 0."
            )
        ul_amount = nutrient.ul_amount_per_serving
        if ul_amount is not None:
            if not 0.0 <= ul_amount < math.inf:
                out.add(
                    f"{path}.nutrients[{j}].ul_amount_per_serving", "out_of_range", "Must be >= 0."
                )
            elif ul_amount > amount:
                out.add(
                    f"{path}.nutrients[{j}].ul_amount_per_serving",
                    "ul_amount_exceeds_amount",
                    "ul_amount_per_serving must not exceed amount_per_serving.",
                )
        if nutrient.form_code == ANY_FORM:
            out.add(
                f"{path}.nutrients[{j}].form_code",
                "invalid_format",
                "ANY is not a valid product form.",
            )
    _check_finite_results(candidate, path, out)


def _check_finite_results(candidate: CandidateInput, path: str, out: _Collector) -> None:
    """Reject inputs whose derived daily values overflow double precision."""
    if (
        candidate.units_per_serving < 1
        or candidate.units_per_container < 1
        or not _finite_positive(candidate.servings_per_day)
        or not _finite_positive(candidate.effective_price)
    ):
        return
    try:
        days = (
            candidate.units_per_container / candidate.units_per_serving
        ) / candidate.servings_per_day
        cost_per_day = candidate.effective_price / days
        # V = C / cost_per_day must stay finite for every reachable C.
        supported = (
            math.isfinite(days)
            and _finite_positive(cost_per_day)
            and math.isfinite(MAX_CONTENT_SCORE / cost_per_day)
        )
    except OverflowError, ZeroDivisionError:
        supported = False
    if not supported:
        out.add(
            f"{path}.effective_price",
            "out_of_range",
            "cost_per_day is outside the supported range.",
        )
    servings_per_day = candidate.servings_per_day
    for j, nutrient in enumerate(candidate.nutrients):
        if not nutrient.amount_per_serving * servings_per_day < math.inf:
            out.add(
                f"{path}.nutrients[{j}].amount_per_serving",
                "out_of_range",
                "amount_per_day is not finite.",
            )


def validate_score_request(request: ScoreRequest) -> None:
    out = _Collector()
    registry = _check_context(request.context, out)
    ranking = request.ranking
    if ranking.lambda_ is not None:
        if ranking.mode is not RankingMode.CUSTOM:
            out.add("ranking.lambda", "lambda_requires_custom", "lambda is only valid for CUSTOM.")
        elif not (math.isfinite(ranking.lambda_) and 0.0 <= ranking.lambda_ <= 1.0):
            out.add("ranking.lambda", "out_of_range", "lambda must be within [0, 1].")
    seen_required: set[str] = set()
    for i, required in enumerate(request.required_nutrients):
        path = f"required_nutrients[{i}]"
        if not _check_known(registry, required.code, f"{path}.code", out):
            continue
        if required.code in seen_required:
            out.add(f"{path}.code", "duplicate_nutrient", f"{required.code} is required twice.")
        seen_required.add(required.code)
        if required.min_amount_per_day is not None:
            if not _finite_positive(required.min_amount_per_day):
                out.add(f"{path}.min_amount_per_day", "out_of_range", "Must be > 0.")
            if required.unit is None:
                out.add(f"{path}.unit", "required", "unit is required with min_amount_per_day.")
        if required.unit is not None:
            _check_unit(registry, required.code, required.unit, f"{path}.unit", out)
    currency = request.candidates[0].currency if request.candidates else ""
    for i, candidate in enumerate(request.candidates):
        _check_candidate(candidate, f"candidates[{i}]", registry, currency, out)
    _check_duplicates(((f"candidates[{i}]", c) for i, c in enumerate(request.candidates)), out)
    out.raise_if_any()


def validate_compare_request(request: CompareRequest) -> None:
    out = _Collector()
    registry = _check_context(request.context, out)
    currency = request.baseline.currency
    _check_candidate(request.baseline, "baseline", registry, currency, out)
    _check_candidate(request.candidate, "candidate", registry, currency, out)
    if request.baseline.product_id == request.candidate.product_id:
        out.add(
            "candidate.product_id",
            "same_product",
            "baseline and candidate must be different products.",
        )
    for i, candidate in enumerate(request.market):
        _check_candidate(candidate, f"market[{i}]", registry, currency, out)
    _check_duplicates(
        (
            ("baseline", request.baseline),
            *(
                [("candidate", request.candidate)]
                if request.candidate.product_id != request.baseline.product_id
                else []
            ),
            *((f"market[{i}]", c) for i, c in enumerate(request.market)),
        ),
        out,
    )
    out.raise_if_any()
