"""Pydantic models mirroring ``contracts/scoring-api.openapi.yaml``.

Request models validate structure (types, ranges, patterns, unknown fields); the semantic
rules live in the engine. Response models document the payload; responses are produced by
the engine serialiser and checked against the contract in the test suite.
"""

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field
from pydantic.json_schema import SkipJsonSchema, WithJsonSchema
from pydantic_core import PydanticCustomError

from vitadelta_scoring.config import EngineConfig
from vitadelta_scoring.engine import Issue
from vitadelta_scoring.engine import model as engine
from vitadelta_scoring.engine.model import (
    DeltaRowFlag,
    FlagCode,
    NutrientCategory,
    RankingMode,
    ReferenceSource,
    Verdict,
)

CODE_PATTERN = r"^[A-Z][A-Z0-9_]{0,39}$"
REQUEST_ID_PATTERN = r"^[A-Za-z0-9-]{1,64}$"
ERROR_CODE_PATTERN = r"^[a-z][a-z0-9_]{0,63}$"

NutrientCode = Annotated[str, Field(pattern=CODE_PATTERN)]
ProductId = Annotated[str, Field(min_length=1, max_length=64)]
CurrencyCode = Annotated[str, Field(pattern=r"^[A-Z]{3}$")]
Money = Field(json_schema_extra={"x-money": True})


class CanonicalUnit(StrEnum):
    mg = "mg"
    mcg = "mcg"


class UlBasis(StrEnum):
    TOTAL = "TOTAL"
    SUPPLEMENTAL = "SUPPLEMENTAL"


# Request models are strict (no string-to-number or number-to-bool coercion); enum fields
# accept their string values.
Category = Annotated[NutrientCategory, Field(strict=False)]
Unit = Annotated[CanonicalUnit, Field(strict=False)]
Basis = Annotated[UlBasis, Field(strict=False)]
Mode = Annotated[RankingMode, Field(strict=False)]


def _json_integer(value: object) -> object:
    """JSON Schema integers include numbers with a zero fraction (``60.0``).

    Used before the strict integer check, so strings, booleans and ``60.5`` stay invalid.
    """
    if isinstance(value, float):
        if value.is_integer():
            return int(value)
        raise PydanticCustomError("int_type", "Input should be a valid integer")
    return value


class _Request(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False, frozen=True)


class _Response(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)


# --- Requests -------------------------------------------------------------------------------


class RegistryNutrient(_Request):
    code: NutrientCode
    category: Category
    canonical_unit: Unit
    presence_floor_per_day: Annotated[float, Field(gt=0)] | None

    def to_engine(self) -> engine.RegistryNutrient:
        return engine.RegistryNutrient(
            code=self.code,
            category=self.category,
            canonical_unit=self.canonical_unit.value,
            presence_floor_per_day=self.presence_floor_per_day,
        )


class ReferenceValue(_Request):
    nutrient_code: NutrientCode
    amount: Annotated[float, Field(gt=0)]
    unit: Unit

    def to_engine(self) -> engine.ReferenceValue:
        return engine.ReferenceValue(self.nutrient_code, self.amount, self.unit.value)


class UpperLimit(_Request):
    nutrient_code: NutrientCode
    form_code: Annotated[
        str, Field(pattern=CODE_PATTERN, json_schema_extra={"not": {"const": "UNKNOWN"}})
    ]
    amount: Annotated[float, Field(gt=0)] | None
    unit: Unit | None
    basis: Basis | None

    def to_engine(self) -> engine.UpperLimit:
        return engine.UpperLimit(
            nutrient_code=self.nutrient_code,
            form_code=self.form_code,
            amount=self.amount,
            unit=None if self.unit is None else self.unit.value,
            basis=None if self.basis is None else self.basis.value,
        )


CategoryWeight = Annotated[float, Field(ge=0, le=2)]


class CategoryWeights(_Request):
    VITAMIN: CategoryWeight
    MINERAL: CategoryWeight
    COENZYME: CategoryWeight
    OTHER: CategoryWeight


class Weights(_Request):
    category_weights: CategoryWeights
    overrides: Annotated[
        dict[NutrientCode, Annotated[float, Field(ge=0, le=3)]],
        WithJsonSchema(
            {
                "type": "object",
                "propertyNames": {"type": "string", "pattern": CODE_PATTERN},
                "additionalProperties": {"type": "number", "minimum": 0, "maximum": 3},
            }
        ),
    ] = Field(default_factory=dict)

    def to_engine(self) -> engine.Weights:
        cw = self.category_weights
        return engine.Weights(
            category_weights={
                NutrientCategory.VITAMIN: cw.VITAMIN,
                NutrientCategory.MINERAL: cw.MINERAL,
                NutrientCategory.COENZYME: cw.COENZYME,
                NutrientCategory.OTHER: cw.OTHER,
            },
            overrides=dict(self.overrides),
        )


class Ranking(_Request):
    mode: Mode
    lambda_: Annotated[float, Field(ge=0, le=1)] | None = Field(default=None, alias="lambda")

    def to_engine(self) -> engine.Ranking:
        return engine.Ranking(mode=self.mode, lambda_=self.lambda_)


class RequiredNutrient(_Request):
    model_config = ConfigDict(
        json_schema_extra={"dependentRequired": {"min_amount_per_day": ["unit"]}}
    )

    code: NutrientCode
    min_amount_per_day: Annotated[float, Field(gt=0)] | None = None
    unit: Unit | SkipJsonSchema[None] = None

    def to_engine(self) -> engine.RequiredNutrient:
        return engine.RequiredNutrient(
            code=self.code,
            min_amount_per_day=self.min_amount_per_day,
            unit=None if self.unit is None else self.unit.value,
        )


class MarketProfileNutrient(_Request):
    code: NutrientCode
    amount_per_day: Annotated[float, Field(gt=0)]


class MarketProfileProduct(_Request):
    product_id: ProductId
    nutrients: list[MarketProfileNutrient]


class MarketProfile(_Request):
    catalog_version: Annotated[
        int,
        Field(ge=0, json_schema_extra={"format": "int64"}),
        BeforeValidator(_json_integer),
    ]
    products: list[MarketProfileProduct]

    def to_engine(self) -> engine.MarketProfile:
        # The validated products satisfy the engine's read-only input protocol as they are.
        return engine.MarketProfile(catalog_version=self.catalog_version, products=self.products)


class CandidateNutrient(_Request):
    code: NutrientCode
    amount_per_serving: Annotated[float, Field(gt=0)]
    unit: Unit
    ul_amount_per_serving: Annotated[float, Field(ge=0)] | None = None
    form_code: Annotated[
        str, Field(pattern=CODE_PATTERN, json_schema_extra={"not": {"const": "ANY"}})
    ]
    conversion_assumed: bool


class Candidate(_Request):
    product_id: ProductId
    units_per_container: Annotated[int, Field(ge=1), BeforeValidator(_json_integer)]
    units_per_serving: Annotated[int, Field(ge=1), BeforeValidator(_json_integer)]
    servings_per_day: Annotated[float, Field(gt=0, le=99.99)]
    effective_price: Annotated[float, Field(gt=0)]
    currency: CurrencyCode
    price_stale: bool
    out_of_stock: bool
    nutrients: list[CandidateNutrient]


class _ContextRequest(_Request):
    nutrients: Annotated[list[RegistryNutrient], Field(min_length=1)]
    reference_values: list[ReferenceValue]
    upper_limits: list[UpperLimit]
    weights: Weights
    market_profile: MarketProfile

    def context(self) -> engine.ScoringContext:
        return engine.ScoringContext(
            nutrients=tuple(n.to_engine() for n in self.nutrients),
            reference_values=tuple(r.to_engine() for r in self.reference_values),
            upper_limits=tuple(u.to_engine() for u in self.upper_limits),
            weights=self.weights.to_engine(),
            market_profile=self.market_profile.to_engine(),
        )


class ScoreRequest(_ContextRequest):
    ranking: Ranking
    required_nutrients: list[RequiredNutrient]
    exclude_exceeding_ul: bool
    candidates: list[Candidate]

    def contract_issues(self) -> list[Issue]:
        """Rules of the contract that the field types alone cannot express.

        ``required_nutrients[].unit`` is never null, and ``min_amount_per_day`` (even when
        null) requires ``unit`` (``dependentRequired``).
        """
        issues = []
        for i, required in enumerate(self.required_nutrients):
            fields = required.model_fields_set
            path = f"required_nutrients[{i}].unit"
            if "unit" in fields and required.unit is None:
                issues.append(Issue(path, "invalid_type", "unit must not be null."))
            elif "min_amount_per_day" in fields and "unit" not in fields:
                issues.append(Issue(path, "required", "unit is required with min_amount_per_day."))
        return issues

    def to_engine(self) -> engine.ScoreRequest:
        return engine.ScoreRequest(
            context=self.context(),
            ranking=self.ranking.to_engine(),
            required_nutrients=tuple(r.to_engine() for r in self.required_nutrients),
            exclude_exceeding_ul=self.exclude_exceeding_ul,
            candidates=self.candidates,
        )


class CompareRequest(_ContextRequest):
    baseline: Candidate
    candidate: Candidate
    market: list[Candidate]

    def to_engine(self) -> engine.CompareRequest:
        return engine.CompareRequest(
            context=self.context(),
            baseline=self.baseline,
            candidate=self.candidate,
            market=self.market,
        )


# --- Responses ------------------------------------------------------------------------------


class HealthResponse(_Response):
    """Liveness status together with the active engine configuration."""

    status: Literal["UP"]
    config: EngineConfig


class Cost(_Response):
    effective_price: Annotated[float, Money]
    currency: CurrencyCode
    servings_per_container: float
    days_supply: float
    cost_per_day: Annotated[float, Money]
    price_stale: bool
    out_of_stock: bool


class Flag(_Response):
    code: FlagCode
    nutrient_codes: Annotated[list[NutrientCode], Field(json_schema_extra={"uniqueItems": True})]


NonNegative = Annotated[float, Field(ge=0)]


class BreakdownRow(_Response):
    code: NutrientCode
    category: NutrientCategory
    amount_per_day: float
    ul_amount_per_day: float
    unit: CanonicalUnit
    reference: float | None
    reference_source: ReferenceSource
    coverage_ratio: float | None
    utility: NonNegative
    weight: NonNegative
    contribution: float
    capped_at_ul: bool
    capped_at_u_max: bool
    ul_below_reference: bool
    below_presence_floor: bool


class ScoredProduct(_Response):
    product_id: ProductId
    rank: Annotated[int, Field(ge=1)]
    cost: Cost
    content_score: NonNegative
    content_score_display: Annotated[float, Field(ge=0, le=100)]
    value_score: NonNegative
    value_score_display: Annotated[float, Field(ge=0, le=100)]
    custom_score: Annotated[float, Field(ge=0, le=1)] | None
    flags: list[Flag]
    breakdown: list[BreakdownRow]
    top_contributors: Annotated[list[BreakdownRow], Field(max_length=5)]


class ExcludedCounts(_Response):
    required_nutrients: Annotated[int, Field(ge=0)]
    exceeds_ul: Annotated[int, Field(ge=0)]


class ScoreMeta(_Response):
    m_ref: Annotated[float | None, Money]
    market_size: Annotated[int, Field(ge=0)]
    excluded: ExcludedCounts


class ScoreResponse(_Response):
    items: list[ScoredProduct]
    meta: ScoreMeta


class ComparedProduct(_Response):
    product_id: ProductId
    cost: Cost
    content_score: NonNegative
    value_score: NonNegative
    flags: list[Flag]
    breakdown: list[BreakdownRow]


class NutrientDeltaRow(_Response):
    code: NutrientCode
    category: NutrientCategory
    amount_a: Annotated[NonNegative, Field(alias="amount_A")]
    amount_b: Annotated[NonNegative, Field(alias="amount_B")]
    delta_amount: float
    unit: CanonicalUnit
    r_a: Annotated[float | None, Field(alias="r_A")]
    r_b: Annotated[float | None, Field(alias="r_B")]
    u_a: Annotated[NonNegative, Field(alias="u_A")]
    u_b: Annotated[NonNegative, Field(alias="u_B")]
    weight: NonNegative
    delta_contribution: float
    flags: Annotated[list[DeltaRowFlag], Field(json_schema_extra={"uniqueItems": True})]


OptionalMoney = Annotated[float | SkipJsonSchema[None], Money]
OptionalNumber = float | SkipJsonSchema[None]


class ExplanationParams(_Response):
    currency: CurrencyCode
    delta_cost_per_day: Annotated[OptionalMoney, Field(alias="deltaCostPerDay")] = None
    delta_content: Annotated[OptionalNumber, Field(alias="deltaContent")] = None
    marginal_rate: Annotated[OptionalMoney, Field(alias="marginalRate")] = None
    m_ref: Annotated[OptionalMoney, Field(alias="mRef")] = None
    ratio: OptionalNumber = None


class Explanation(_Response):
    key: Literal[
        "explain.verdict.worth_it",
        "explain.verdict.marginal",
        "explain.verdict.not_worth_it",
        "explain.verdict.dominates",
        "explain.verdict.equivalent",
        "explain.verdict.insufficient_data",
    ]
    params: ExplanationParams


class CompareResponse(_Response):
    a: ComparedProduct
    b: ComparedProduct
    swapped: bool
    currency: CurrencyCode
    delta_cost: Annotated[float, Field(ge=0), Money]
    delta_content: float
    m_ref: Annotated[float | None, Money]
    market_size: Annotated[int, Field(ge=2)]
    marginal_rate: Annotated[float | None, Money]
    ratio: float | None
    verdict: Verdict
    nutrient_deltas: list[NutrientDeltaRow]
    explanation: Explanation


class ValidationError(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    field: str
    code: Annotated[str, Field(pattern=ERROR_CODE_PATTERN)]
    message: str


class Problem(BaseModel):
    """RFC 9457 problem details."""

    model_config = ConfigDict(frozen=True)

    type: Annotated[str, Field(json_schema_extra={"format": "uri"})]
    title: str
    status: Annotated[int, Field(ge=400, le=599)]
    detail: str | SkipJsonSchema[None] = None
    instance: Annotated[
        str | SkipJsonSchema[None], Field(json_schema_extra={"format": "uri-reference"})
    ] = None
    requestId: Annotated[str, Field(pattern=REQUEST_ID_PATTERN)]  # noqa: N815 (contract name)
    errors: list[ValidationError] | SkipJsonSchema[None] = None
