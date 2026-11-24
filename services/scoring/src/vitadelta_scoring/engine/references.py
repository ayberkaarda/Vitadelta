"""Reference resolution (spec 6.3) and the UL matching rule (spec 5.7)."""

from collections import defaultdict
from dataclasses import dataclass
from enum import Enum

from vitadelta_scoring.engine.model import (
    ANY_FORM,
    UNKNOWN_FORM,
    MarketProfile,
    ReferenceSource,
    ReferenceValue,
    UpperLimit,
)
from vitadelta_scoring.engine.numeric import median

MARKET_MEDIAN_MIN_PRODUCTS = 3


@dataclass(frozen=True, slots=True)
class ResolvedReference:
    source: ReferenceSource
    amount: float | None


class UlMatchKind(Enum):
    LIMIT = "LIMIT"
    EXEMPT = "EXEMPT"
    FORM_DEPENDENT = "FORM_DEPENDENT"
    NONE = "NONE"


@dataclass(frozen=True, slots=True)
class UlMatch:
    kind: UlMatchKind
    amount: float | None = None


_EXEMPT = UlMatch(UlMatchKind.EXEMPT)
_FORM_DEPENDENT = UlMatch(UlMatchKind.FORM_DEPENDENT)
_NO_UL = UlMatch(UlMatchKind.NONE)
_PRESENCE_ONLY = ResolvedReference(ReferenceSource.PRESENCE_ONLY, None)


def market_amounts(profile: MarketProfile) -> dict[str, list[float]]:
    """Daily amounts per nutrient over the profile, each product counted once.

    Products are de-duplicated by ``product_id`` (first occurrence wins) and a nutrient is
    counted at most once per product.
    """
    amounts: dict[str, list[float]] = defaultdict(list)
    seen_products: set[str] = set()
    for product in profile.products:
        if product.product_id in seen_products:
            continue
        seen_products.add(product.product_id)
        seen_codes: set[str] = set()
        for nutrient in product.nutrients:
            if nutrient.code in seen_codes or nutrient.amount_per_day <= 0.0:
                continue
            seen_codes.add(nutrient.code)
            amounts[nutrient.code].append(nutrient.amount_per_day)
    return amounts


class ReferenceResolver:
    """Resolves the reference of each nutrient once per request."""

    def __init__(self, reference_values: tuple[ReferenceValue, ...], profile: MarketProfile):
        self._regulatory = {row.nutrient_code: row.amount for row in reference_values}
        self._market = market_amounts(profile)
        self._cache: dict[str, ResolvedReference] = {}

    def resolve(self, code: str) -> ResolvedReference:
        cached = self._cache.get(code)
        if cached is not None:
            return cached
        resolved = self._resolve(code)
        self._cache[code] = resolved
        return resolved

    def _resolve(self, code: str) -> ResolvedReference:
        regulatory = self._regulatory.get(code)
        if regulatory is not None:
            return ResolvedReference(ReferenceSource.REGULATORY, regulatory)
        amounts = self._market.get(code, [])
        if len(amounts) >= MARKET_MEDIAN_MIN_PRODUCTS:
            return ResolvedReference(ReferenceSource.MARKET_MEDIAN, median(amounts))
        return _PRESENCE_ONLY


class UlMatcher:
    """The UL matching rule, one rule for every UL set.

    1. If the product form is not ``UNKNOWN``, look up ``(nutrient, form)``.
    2. Else, or if absent, look up ``(nutrient, ANY)``.
    3. If still absent and the set has a form-specific row for the nutrient, no UL applies
       and the product gets ``UL_FORM_DEPENDENT``.
    4. Otherwise no UL applies. A matched exempt row means "no UL" without the flag.
    """

    def __init__(self, upper_limits: tuple[UpperLimit, ...]):
        self._rows = {(row.nutrient_code, row.form_code): row for row in upper_limits}
        self._form_specific = {
            row.nutrient_code for row in upper_limits if row.form_code != ANY_FORM
        }
        self._cache: dict[tuple[str, str], UlMatch] = {}

    def match(self, code: str, form_code: str) -> UlMatch:
        key = (code, form_code)
        cached = self._cache.get(key)
        if cached is None:
            cached = self._match(code, form_code)
            self._cache[key] = cached
        return cached

    def _match(self, code: str, form_code: str) -> UlMatch:
        row = None
        if form_code != UNKNOWN_FORM:
            row = self._rows.get((code, form_code))
        if row is None:
            row = self._rows.get((code, ANY_FORM))
        if row is not None:
            return _EXEMPT if row.amount is None else UlMatch(UlMatchKind.LIMIT, row.amount)
        if code in self._form_specific:
            return _FORM_DEPENDENT
        return _NO_UL
