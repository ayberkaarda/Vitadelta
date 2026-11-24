"""Input and output value types of the scoring engine.

The engine never sees the HTTP layer. High-volume inputs (candidates, their nutrients and
the market profile) are declared as read-only protocols, so any object with these
attributes can be scored without a copy; the dataclasses below are the engine's own
implementation of them. All values are treated as immutable. Every amount is a float in
the nutrient's canonical unit and every price is in the country's native currency.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Protocol


class NutrientCategory(StrEnum):
    VITAMIN = "VITAMIN"
    MINERAL = "MINERAL"
    COENZYME = "COENZYME"
    OTHER = "OTHER"


class RankingMode(StrEnum):
    PRICE = "PRICE"
    RICHNESS = "RICHNESS"
    VALUE = "VALUE"
    CUSTOM = "CUSTOM"


class ReferenceSource(StrEnum):
    REGULATORY = "REGULATORY"
    MARKET_MEDIAN = "MARKET_MEDIAN"
    PRESENCE_ONLY = "PRESENCE_ONLY"


class FlagCode(StrEnum):
    """Product-level flags; the declaration order is the output order."""

    EXCEEDS_UL = "EXCEEDS_UL"
    UL_FORM_DEPENDENT = "UL_FORM_DEPENDENT"
    CONVERSION_ASSUMED = "CONVERSION_ASSUMED"
    PRICE_STALE = "PRICE_STALE"
    OUT_OF_STOCK = "OUT_OF_STOCK"
    MARKET_MEDIAN_REFERENCE_USED = "MARKET_MEDIAN_REFERENCE_USED"
    PRESENCE_ONLY_NUTRIENTS = "PRESENCE_ONLY_NUTRIENTS"
    BELOW_PRESENCE_FLOOR = "BELOW_PRESENCE_FLOOR"


class DeltaRowFlag(StrEnum):
    """Per-nutrient delta row flags; the declaration order is the output order."""

    NEW_IN_B = "NEW_IN_B"
    REMOVED_IN_B = "REMOVED_IN_B"
    DIMINISHING = "DIMINISHING"
    ABOVE_CEILING_NO_CREDIT = "ABOVE_CEILING_NO_CREDIT"
    ABOVE_UL_NO_CREDIT = "ABOVE_UL_NO_CREDIT"


class Verdict(StrEnum):
    WORTH_IT = "WORTH_IT"
    MARGINAL = "MARGINAL"
    NOT_WORTH_IT = "NOT_WORTH_IT"
    DOMINATES = "DOMINATES"
    EQUIVALENT = "EQUIVALENT"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"


UNKNOWN_FORM = "UNKNOWN"
ANY_FORM = "ANY"


class CandidateNutrientInput(Protocol):
    @property
    def code(self) -> str: ...
    @property
    def amount_per_serving(self) -> float: ...
    @property
    def unit(self) -> str: ...
    @property
    def form_code(self) -> str: ...
    @property
    def conversion_assumed(self) -> bool: ...
    @property
    def ul_amount_per_serving(self) -> float | None: ...


class CandidateInput(Protocol):
    @property
    def product_id(self) -> str: ...
    @property
    def units_per_container(self) -> int: ...
    @property
    def units_per_serving(self) -> int: ...
    @property
    def servings_per_day(self) -> float: ...
    @property
    def effective_price(self) -> float: ...
    @property
    def currency(self) -> str: ...
    @property
    def price_stale(self) -> bool: ...
    @property
    def out_of_stock(self) -> bool: ...
    @property
    def nutrients(self) -> Sequence[CandidateNutrientInput]: ...


class MarketProfileNutrientInput(Protocol):
    @property
    def code(self) -> str: ...
    @property
    def amount_per_day(self) -> float: ...


class MarketProfileProductInput(Protocol):
    @property
    def product_id(self) -> str: ...
    @property
    def nutrients(self) -> Sequence[MarketProfileNutrientInput]: ...


# --- Inputs ---------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class RegistryNutrient:
    code: str
    category: NutrientCategory
    canonical_unit: str
    presence_floor_per_day: float | None


@dataclass(frozen=True, slots=True)
class ReferenceValue:
    nutrient_code: str
    amount: float
    unit: str


@dataclass(frozen=True, slots=True)
class UpperLimit:
    """One UL row; ``amount is None`` marks an exempt row (no UL for that form)."""

    nutrient_code: str
    form_code: str
    amount: float | None
    unit: str | None
    basis: str | None


@dataclass(frozen=True, slots=True)
class Weights:
    category_weights: Mapping[NutrientCategory, float]
    overrides: Mapping[str, float] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class Ranking:
    mode: RankingMode = RankingMode.VALUE
    lambda_: float | None = None


@dataclass(frozen=True, slots=True)
class RequiredNutrient:
    code: str
    min_amount_per_day: float | None = None
    unit: str | None = None


@dataclass(slots=True)
class MarketProfileNutrient:
    code: str
    amount_per_day: float


@dataclass(frozen=True, slots=True)
class MarketProfileProduct:
    product_id: str
    nutrients: tuple[MarketProfileNutrient, ...]


@dataclass(frozen=True, slots=True)
class MarketProfile:
    catalog_version: int
    products: Sequence[MarketProfileProductInput]


@dataclass(slots=True)
class CandidateNutrient:
    code: str
    amount_per_serving: float
    unit: str
    form_code: str = UNKNOWN_FORM
    conversion_assumed: bool = False
    ul_amount_per_serving: float | None = None


@dataclass(frozen=True, slots=True)
class Candidate:
    product_id: str
    units_per_container: int
    units_per_serving: int
    servings_per_day: float
    effective_price: float
    currency: str
    price_stale: bool
    out_of_stock: bool
    nutrients: tuple[CandidateNutrient, ...]


@dataclass(frozen=True, slots=True)
class ScoringContext:
    """Everything that is shared by all candidates of one request."""

    nutrients: tuple[RegistryNutrient, ...]
    reference_values: tuple[ReferenceValue, ...]
    upper_limits: tuple[UpperLimit, ...]
    weights: Weights
    market_profile: MarketProfile


@dataclass(frozen=True, slots=True)
class ScoreRequest:
    context: ScoringContext
    ranking: Ranking
    required_nutrients: tuple[RequiredNutrient, ...]
    exclude_exceeding_ul: bool
    candidates: Sequence[CandidateInput]


@dataclass(frozen=True, slots=True)
class CompareRequest:
    context: ScoringContext
    baseline: CandidateInput
    candidate: CandidateInput
    market: Sequence[CandidateInput]


# --- Outputs --------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Cost:
    effective_price: float
    currency: str
    servings_per_container: float
    days_supply: float
    cost_per_day: float
    price_stale: bool
    out_of_stock: bool


@dataclass(frozen=True, slots=True)
class Flag:
    code: FlagCode
    nutrient_codes: tuple[str, ...]


@dataclass(slots=True)
class BreakdownRow:
    code: str
    category: NutrientCategory
    amount_per_day: float
    ul_amount_per_day: float
    unit: str
    reference: float | None
    reference_source: ReferenceSource
    coverage_ratio: float | None
    utility: float
    weight: float
    contribution: float
    capped_at_ul: bool
    capped_at_u_max: bool
    ul_below_reference: bool
    below_presence_floor: bool
    # Engine-internal facts used by the pairwise analysis; not serialised.
    upper_limit: float | None
    exceeds_ul: bool
    ul_form_dependent: bool
    conversion_assumed: bool


@dataclass(frozen=True, slots=True)
class ProductScore:
    """A candidate scored with sections 6.2 to 6.6 (no ranking yet)."""

    product_id: str
    cost: Cost
    content_score: float
    value_score: float
    flags: tuple[Flag, ...]
    breakdown: tuple[BreakdownRow, ...]

    def has_flag(self, code: FlagCode) -> bool:
        return any(flag.code is code for flag in self.flags)


@dataclass(frozen=True, slots=True)
class ScoredProduct:
    product_id: str
    rank: int
    cost: Cost
    content_score: float
    content_score_display: float
    value_score: float
    value_score_display: float
    custom_score: float | None
    flags: tuple[Flag, ...]
    breakdown: tuple[BreakdownRow, ...]
    top_contributors: tuple[BreakdownRow, ...]


@dataclass(frozen=True, slots=True)
class ExcludedCounts:
    required_nutrients: int
    exceeds_ul: int


@dataclass(frozen=True, slots=True)
class ScoreMeta:
    m_ref: float | None
    market_size: int
    excluded: ExcludedCounts


@dataclass(frozen=True, slots=True)
class ScoreResult:
    items: tuple[ScoredProduct, ...]
    meta: ScoreMeta


@dataclass(frozen=True, slots=True)
class NutrientDeltaRow:
    code: str
    category: NutrientCategory
    amount_a: float
    amount_b: float
    delta_amount: float
    unit: str
    r_a: float | None
    r_b: float | None
    u_a: float
    u_b: float
    weight: float
    delta_contribution: float
    flags: tuple[DeltaRowFlag, ...]


@dataclass(frozen=True, slots=True)
class Explanation:
    key: str
    params: Mapping[str, str | float]


@dataclass(frozen=True, slots=True)
class CompareResult:
    a: ProductScore
    b: ProductScore
    swapped: bool
    currency: str
    delta_cost: float
    delta_content: float
    m_ref: float | None
    market_size: int
    marginal_rate: float | None
    ratio: float | None
    verdict: Verdict
    nutrient_deltas: tuple[NutrientDeltaRow, ...]
    explanation: Explanation
