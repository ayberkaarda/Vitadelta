"""Builds scoring inputs for a country from the committed data files (read-only over ``data/``).

It reproduces what the core API sends to the scoring service: unit conversion to the
canonical unit (spec 5.5), the in-market rule (spec 7.2), effective prices under
``IN_STOCK_ONLY`` (spec 7.4, staleness against the fixed data-layer date), the market
profile (spec 6.3) and the country's reference and UL sets. It is used by the calibration
script and by the fixture market-rate tests; it is not part of the service.

``VITADELTA_DATA_ROOT`` redirects the data directory, like the data-layer tests.
"""

import csv
import json
import os
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from functools import cache
from pathlib import Path
from typing import Any

from vitadelta_scoring.engine.model import (
    ANY_FORM,
    UNKNOWN_FORM,
    Candidate,
    CandidateNutrient,
    MarketProfile,
    MarketProfileNutrient,
    MarketProfileProduct,
    NutrientCategory,
    ReferenceValue,
    RegistryNutrient,
    ScoringContext,
    UpperLimit,
    Weights,
)

# Fixed "now" of the data layer (the same date the data-layer tests use); no wall clock.
REFERENCE_NOW = datetime(2026, 11, 24, tzinfo=UTC)
PRICE_STALE_AFTER = timedelta(days=30)
UNIT_ALIASES = {"µg": "mcg", "μg": "mcg", "ug": "mcg", "iu": "IU"}
DEFAULT_CATEGORY_WEIGHTS = {
    NutrientCategory.VITAMIN: 1.0,
    NutrientCategory.MINERAL: 1.0,
    NutrientCategory.COENZYME: 0.8,
    NutrientCategory.OTHER: 0.5,
}

SYNTHETIC_PRODUCTS = ("fixtures", "products.synthetic.json")
SYNTHETIC_OFFERS = ("fixtures", "offers.synthetic.csv")
PRICE_DROP_OFFERS = ("fixtures", "offers.price-drop.synthetic.csv")
REAL_PRODUCTS = ("real", "products.dsld.json")
REAL_OFFERS = ("real", "offers.us-demo.synthetic.csv")

Row = dict[str, str]


def data_root() -> Path:
    override = os.environ.get("VITADELTA_DATA_ROOT")
    if override is not None:
        return Path(override)
    return Path(__file__).resolve().parents[3] / "data"


@cache
def _csv(path: Path) -> tuple[Row, ...]:
    with path.open(encoding="utf-8", newline="") as handle:
        return tuple(csv.DictReader(handle))


@cache
def _json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def reference_rows(name: str) -> tuple[Row, ...]:
    return _csv(data_root() / "reference" / name)


def _path(parts: tuple[str, str]) -> Path:
    return data_root().joinpath(*parts)


@dataclass(frozen=True, slots=True)
class Country:
    code: str
    currency: str
    reference_set: str
    ul_set: str


def country(code: str) -> Country:
    for row in reference_rows("countries.csv"):
        if row["code"] == code:
            return Country(code, row["currency"], row["reference_set_code"], row["ul_set_code"])
    raise KeyError(code)


def registry() -> tuple[RegistryNutrient, ...]:
    return tuple(
        RegistryNutrient(
            code=row["code"],
            category=NutrientCategory(row["category"]),
            canonical_unit=row["canonical_unit"],
            presence_floor_per_day=(
                float(row["presence_floor_per_day"]) if row["presence_floor_per_day"] else None
            ),
        )
        for row in reference_rows("nutrients.csv")
    )


def canonical_units() -> dict[str, str]:
    return {n.code: n.canonical_unit for n in registry()}


class Conversions:
    """Spec 5.5: specific form, then the nutrient's ANY row, then the generic ``*`` row.

    ``assumed`` is true when the nutrient has form-specific factors for the unit pair but
    none matches the product form, so the ANY row's assumed form was used.
    """

    def __init__(self, rows: Iterable[Row]):
        self._factors = {
            (r["nutrient_code"], r["from_unit"], r["to_unit"], r["form_code"]): float(r["factor"])
            for r in rows
        }

    def factor(self, code: str, form: str, from_unit: str, to_unit: str) -> tuple[float, bool]:
        if from_unit == to_unit:
            return 1.0, False
        if form not in (UNKNOWN_FORM, ANY_FORM):
            specific = self._factors.get((code, from_unit, to_unit, form))
            if specific is not None:
                return specific, False
        nutrient_any = self._factors.get((code, from_unit, to_unit, ANY_FORM))
        if nutrient_any is not None:
            has_forms = any(
                key[0] == code and key[1] == from_unit and key[2] == to_unit and key[3] != ANY_FORM
                for key in self._factors
            )
            return nutrient_any, has_forms
        generic = self._factors.get(("*", from_unit, to_unit, ANY_FORM))
        if generic is None:
            raise ValueError(f"no conversion {from_unit} -> {to_unit} for {code} ({form})")
        return generic, False


def products(source: tuple[str, str]) -> list[dict[str, Any]]:
    loaded: list[dict[str, Any]] = _json(_path(source))["products"]
    return loaded


def available_in(product: dict[str, Any], country_code: str) -> bool:
    return any(
        a["country_code"] == country_code and a["status"] == "AVAILABLE"
        for a in product["availability"]
    )


def candidate_nutrients(
    product: dict[str, Any], conversions: Conversions, units: dict[str, str]
) -> tuple[CandidateNutrient, ...]:
    nutrients = []
    for entry in product["nutrients"]:
        code = entry["nutrient_code"]
        form = entry.get("form_code") or UNKNOWN_FORM
        unit = UNIT_ALIASES.get(entry["unit"], entry["unit"])
        factor, assumed = conversions.factor(code, form, unit, units[code])
        ul_amount = entry.get("ul_amount_per_serving")
        nutrients.append(
            CandidateNutrient(
                code=code,
                amount_per_serving=float(entry["amount_per_serving"]) * factor,
                unit=units[code],
                form_code=form,
                conversion_assumed=assumed,
                ul_amount_per_serving=None if ul_amount is None else float(ul_amount) * factor,
            )
        )
    return tuple(nutrients)


def best_in_stock_offer(offers: Iterable[Row], product_code: str, country_code: str) -> Row | None:
    """Spec 7.4 ``IN_STOCK_ONLY``: lowest price, then most recent ``checked_at``, then retailer."""
    eligible = [
        o
        for o in offers
        if o["product_code"] == product_code
        and o["country_code"] == country_code
        and o["in_stock"] == "true"
    ]
    if not eligible:
        return None
    return min(
        eligible,
        key=lambda o: (
            float(o["price"]),
            -datetime.fromisoformat(o["checked_at"]).timestamp(),
            o["retailer"],
        ),
    )


@dataclass(frozen=True, slots=True)
class Market:
    """One country's scoring inputs: the context and the in-stock candidate set."""

    country: Country
    context: ScoringContext
    candidates: tuple[Candidate, ...]

    def by_id(self) -> dict[str, Candidate]:
        return {c.product_id: c for c in self.candidates}


def build_market(
    country_code: str,
    product_sources: Iterable[tuple[str, str]] = (SYNTHETIC_PRODUCTS,),
    offer_sources: Iterable[tuple[str, str]] = (SYNTHETIC_OFFERS,),
    weights: Weights | None = None,
) -> Market:
    target = country(country_code)
    conversions = Conversions(reference_rows("unit_conversions.csv"))
    units = canonical_units()
    in_market = [
        p for source in product_sources for p in products(source) if available_in(p, country_code)
    ]
    offers = [o for source in offer_sources for o in _csv(_path(source))]

    profile = []
    candidates = []
    for product in in_market:
        nutrients = candidate_nutrients(product, conversions, units)
        servings_per_day = float(product["servings_per_day"])
        profile.append(
            MarketProfileProduct(
                product["code"],
                tuple(
                    MarketProfileNutrient(n.code, n.amount_per_serving * servings_per_day)
                    for n in nutrients
                ),
            )
        )
        offer = best_in_stock_offer(offers, product["code"], country_code)
        if offer is None:
            continue
        checked_at = datetime.fromisoformat(offer["checked_at"])
        candidates.append(
            Candidate(
                product_id=product["code"],
                units_per_container=int(product["units_per_container"]),
                units_per_serving=int(product["units_per_serving"]),
                servings_per_day=servings_per_day,
                effective_price=float(offer["price"]),
                currency=offer["currency"],
                price_stale=checked_at < REFERENCE_NOW - PRICE_STALE_AFTER,
                out_of_stock=False,
                nutrients=nutrients,
            )
        )

    references = tuple(
        ReferenceValue(r["nutrient_code"], float(r["amount"]), r["unit"])
        for r in reference_rows("reference_values.csv")
        if r["reference_set_code"] == target.reference_set
    )
    upper_limits = tuple(
        UpperLimit(r["nutrient_code"], r["form_code"], float(r["amount"]), r["unit"], r["basis"])
        if r["amount"]
        else UpperLimit(r["nutrient_code"], r["form_code"], None, None, None)
        for r in reference_rows("upper_limits.csv")
        if r["ul_set_code"] == target.ul_set
    )
    context = ScoringContext(
        nutrients=registry(),
        reference_values=references,
        upper_limits=upper_limits,
        weights=weights or Weights(dict(DEFAULT_CATEGORY_WEIGHTS)),
        market_profile=MarketProfile(catalog_version=1, products=tuple(profile)),
    )
    return Market(target, context, tuple(sorted(candidates, key=lambda c: c.product_id)))
