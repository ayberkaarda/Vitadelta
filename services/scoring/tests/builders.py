"""Builders for engine requests and their HTTP payloads, shared by the scoring tests.

The reference and UL subsets below are the ones the golden tests of spec 6.11 name.
"""

import json
import logging
from collections.abc import Iterable, Mapping
from dataclasses import replace
from typing import Any

from fastapi.testclient import TestClient

from vitadelta_scoring.config import DEFAULT_CONFIG, EngineConfig
from vitadelta_scoring.engine import compare, score
from vitadelta_scoring.engine.model import (
    Candidate,
    CandidateInput,
    CandidateNutrient,
    CompareRequest,
    MarketProfile,
    MarketProfileNutrient,
    MarketProfileProduct,
    NutrientCategory,
    Ranking,
    RankingMode,
    ReferenceValue,
    RegistryNutrient,
    RequiredNutrient,
    ScoreRequest,
    ScoringContext,
    UpperLimit,
    Weights,
)
from vitadelta_scoring.engine.serialise import compare_result_json, score_result_json
from vitadelta_scoring.main import app

Json = dict[str, Any]

# code -> (category, canonical unit), the registry of spec 5.4.
REGISTRY: dict[str, tuple[NutrientCategory, str]] = {
    **{
        code: (NutrientCategory.VITAMIN, unit)
        for code, unit in {
            "VIT_A": "mcg",
            "VIT_C": "mg",
            "VIT_D": "mcg",
            "VIT_E": "mg",
            "VIT_B3": "mg",
            "VIT_B6": "mg",
            "VIT_B9": "mcg",
            "VIT_B12": "mcg",
        }.items()
    },
    **{
        code: (NutrientCategory.MINERAL, unit)
        for code, unit in {
            "MAGNESIUM": "mg",
            "ZINC": "mg",
            "IRON": "mg",
            "SELENIUM": "mcg",
        }.items()
    },
    "COQ10": (NutrientCategory.COENZYME, "mg"),
    "ALPHA_LIPOIC_ACID": (NutrientCategory.COENZYME, "mg"),
    "LUTEIN": (NutrientCategory.OTHER, "mg"),
    "OMEGA3_EPA": (NutrientCategory.OTHER, "mg"),
}

EU_NRV = {"VIT_C": 80.0, "VIT_D": 5.0, "VIT_B12": 2.5, "ZINC": 10.0, "MAGNESIUM": 375.0}
EU_NRV_EXTENDED = {**EU_NRV, "VIT_B3": 16.0, "VIT_B9": 200.0, "VIT_A": 800.0, "VIT_E": 12.0}
US_DV = {"VIT_D": 20.0, "VIT_B3": 16.0, "VIT_B9": 400.0, "VIT_C": 90.0, "ZINC": 11.0}

# (nutrient, form, amount or None for exempt)
EFSA: list[tuple[str, str, float | None]] = [
    ("VIT_A", "RETINOL", 3000.0),
    ("VIT_A", "BETA_CAROTENE", None),
    ("VIT_D", "ANY", 100.0),
    ("VIT_E", "ANY", 300.0),
    ("VIT_B6", "ANY", 12.0),
    ("VIT_B9", "FOLIC_ACID", 1000.0),
    ("VIT_B9", "METHYLFOLATE", 1000.0),
    ("VIT_B3", "NICOTINIC_ACID", 10.0),
    ("VIT_B3", "NICOTINAMIDE", 900.0),
    ("MAGNESIUM", "ANY", 250.0),
    ("ZINC", "ANY", 25.0),
    ("SELENIUM", "ANY", 255.0),
]
IOM: list[tuple[str, str, float | None]] = [
    ("VIT_A", "RETINOL", 3000.0),
    ("VIT_A", "BETA_CAROTENE", None),
    ("VIT_D", "ANY", 100.0),
    ("VIT_C", "ANY", 2000.0),
    ("VIT_B9", "FOLIC_ACID", 1000.0),
    ("VIT_B3", "ANY", 35.0),
    ("MAGNESIUM", "ANY", 350.0),
    ("ZINC", "ANY", 40.0),
    ("IRON", "ANY", 45.0),
]

DEFAULT_CATEGORY_WEIGHTS = {
    NutrientCategory.VITAMIN: 1.0,
    NutrientCategory.MINERAL: 1.0,
    NutrientCategory.COENZYME: 0.8,
    NutrientCategory.OTHER: 0.5,
}

NutrientSpec = float | tuple[float, str] | CandidateNutrient


def registry(
    codes: Iterable[str] | None = None, floors: Mapping[str, float | None] | None = None
) -> tuple[RegistryNutrient, ...]:
    chosen = sorted(REGISTRY if codes is None else set(codes))
    floors = floors or {}
    return tuple(
        RegistryNutrient(code, REGISTRY[code][0], REGISTRY[code][1], floors.get(code))
        for code in chosen
    )


def references(values: Mapping[str, float]) -> tuple[ReferenceValue, ...]:
    return tuple(ReferenceValue(code, amount, REGISTRY[code][1]) for code, amount in values.items())


def upper_limits(rows: Iterable[tuple[str, str, float | None]]) -> tuple[UpperLimit, ...]:
    result = []
    for code, form, amount in rows:
        if amount is None:
            result.append(UpperLimit(code, form, None, None, None))
        else:
            result.append(UpperLimit(code, form, amount, REGISTRY[code][1], "TOTAL"))
    return tuple(result)


def weights(
    overrides: Mapping[str, float] | None = None,
    category_weights: Mapping[NutrientCategory, float] | None = None,
) -> Weights:
    return Weights(
        category_weights=dict(category_weights or DEFAULT_CATEGORY_WEIGHTS),
        overrides=dict(overrides or {}),
    )


def nutrient(code: str, spec: NutrientSpec) -> CandidateNutrient:
    if isinstance(spec, CandidateNutrient):
        return spec
    if isinstance(spec, tuple):
        amount, form = spec
        return CandidateNutrient(code, amount, REGISTRY[code][1], form_code=form)
    return CandidateNutrient(code, float(spec), REGISTRY[code][1])


def candidate(
    product_id: str,
    price: float,
    nutrients: Mapping[str, NutrientSpec],
    *,
    units_per_container: int = 60,
    units_per_serving: int = 1,
    servings_per_day: float = 1.0,
    currency: str = "TRY",
    price_stale: bool = False,
    out_of_stock: bool = False,
) -> Candidate:
    return Candidate(
        product_id=product_id,
        units_per_container=units_per_container,
        units_per_serving=units_per_serving,
        servings_per_day=servings_per_day,
        effective_price=price,
        currency=currency,
        price_stale=price_stale,
        out_of_stock=out_of_stock,
        nutrients=tuple(nutrient(code, spec) for code, spec in nutrients.items()),
    )


def profile(*candidates: Candidate, extra: Iterable[MarketProfileProduct] = ()) -> MarketProfile:
    """Market profile with the daily amounts of the given candidates (plus extra products)."""
    products = [
        MarketProfileProduct(
            c.product_id,
            tuple(
                MarketProfileNutrient(n.code, n.amount_per_serving * c.servings_per_day)
                for n in c.nutrients
            ),
        )
        for c in candidates
    ]
    return MarketProfile(catalog_version=1, products=(*products, *extra))


def profile_product(product_id: str, amounts: Mapping[str, float]) -> MarketProfileProduct:
    return MarketProfileProduct(
        product_id, tuple(MarketProfileNutrient(code, a) for code, a in amounts.items())
    )


def context(
    *,
    refs: Mapping[str, float] = EU_NRV,
    uls: Iterable[tuple[str, str, float | None]] = EFSA,
    market_profile: MarketProfile | None = None,
    weight: Weights | None = None,
    codes: Iterable[str] | None = None,
    floors: Mapping[str, float | None] | None = None,
) -> ScoringContext:
    return ScoringContext(
        nutrients=registry(codes, floors),
        reference_values=references(refs),
        upper_limits=upper_limits(uls),
        weights=weight or weights(),
        market_profile=market_profile or MarketProfile(catalog_version=1, products=()),
    )


def score_request(
    candidates: Iterable[Candidate],
    *,
    ctx: ScoringContext | None = None,
    mode: RankingMode = RankingMode.VALUE,
    lambda_: float | None = None,
    required: Iterable[RequiredNutrient] = (),
    exclude_exceeding_ul: bool = False,
) -> ScoreRequest:
    items = tuple(candidates)
    return ScoreRequest(
        context=ctx or context(market_profile=profile(*items)),
        ranking=Ranking(mode, lambda_),
        required_nutrients=tuple(required),
        exclude_exceeding_ul=exclude_exceeding_ul,
        candidates=items,
    )


def compare_request(
    baseline: Candidate,
    other: Candidate,
    market: Iterable[Candidate] | None = None,
    *,
    ctx: ScoringContext | None = None,
) -> CompareRequest:
    members = (baseline, other) if market is None else tuple(market)
    return CompareRequest(
        context=ctx or context(market_profile=profile(*members)),
        baseline=baseline,
        candidate=other,
        market=members,
    )


# --- GT-1 -----------------------------------------------------------------------------------

GT1_SHARED: dict[str, NutrientSpec] = {
    "VIT_D": 10.0,
    "VIT_B12": 2.5,
    "ZINC": 10.0,
    "MAGNESIUM": 100.0,
}


def gold_a() -> Candidate:
    return candidate("GOLD-A", 800.0, {"VIT_C": 300.0, **GT1_SHARED})


def gold_b() -> Candidate:
    return candidate("GOLD-B", 900.0, {"VIT_C": 350.0, **GT1_SHARED})


def with_price(c: Candidate, price: float) -> Candidate:
    return replace(c, effective_price=price)


# --- JSON payloads (HTTP layer) ---------------------------------------------------------------


def context_json(ctx: ScoringContext) -> Json:
    return {
        "nutrients": [
            {
                "code": n.code,
                "category": n.category.value,
                "canonical_unit": n.canonical_unit,
                "presence_floor_per_day": n.presence_floor_per_day,
            }
            for n in ctx.nutrients
        ],
        "reference_values": [
            {"nutrient_code": r.nutrient_code, "amount": r.amount, "unit": r.unit}
            for r in ctx.reference_values
        ],
        "upper_limits": [
            {
                "nutrient_code": u.nutrient_code,
                "form_code": u.form_code,
                "amount": u.amount,
                "unit": u.unit,
                "basis": u.basis,
            }
            for u in ctx.upper_limits
        ],
        "weights": {
            "category_weights": {c.value: w for c, w in ctx.weights.category_weights.items()},
            "overrides": dict(ctx.weights.overrides),
        },
        "market_profile": {
            "catalog_version": ctx.market_profile.catalog_version,
            "products": [
                {
                    "product_id": p.product_id,
                    "nutrients": [
                        {"code": n.code, "amount_per_day": n.amount_per_day} for n in p.nutrients
                    ],
                }
                for p in ctx.market_profile.products
            ],
        },
    }


def candidate_json(c: CandidateInput) -> Json:
    return {
        "product_id": c.product_id,
        "units_per_container": c.units_per_container,
        "units_per_serving": c.units_per_serving,
        "servings_per_day": c.servings_per_day,
        "effective_price": c.effective_price,
        "currency": c.currency,
        "price_stale": c.price_stale,
        "out_of_stock": c.out_of_stock,
        "nutrients": [
            {
                "code": n.code,
                "amount_per_serving": n.amount_per_serving,
                "unit": n.unit,
                "ul_amount_per_serving": n.ul_amount_per_serving,
                "form_code": n.form_code,
                "conversion_assumed": n.conversion_assumed,
            }
            for n in c.nutrients
        ],
    }


def score_payload(request: ScoreRequest) -> Json:
    return {
        **context_json(request.context),
        "ranking": {"mode": request.ranking.mode.value, "lambda": request.ranking.lambda_},
        "required_nutrients": [
            {"code": r.code, "min_amount_per_day": r.min_amount_per_day, "unit": r.unit}
            if r.unit is not None
            else {"code": r.code, "min_amount_per_day": r.min_amount_per_day}
            for r in request.required_nutrients
        ],
        "exclude_exceeding_ul": request.exclude_exceeding_ul,
        "candidates": [candidate_json(c) for c in request.candidates],
    }


def compare_payload(request: CompareRequest) -> Json:
    return {
        **context_json(request.context),
        "baseline": candidate_json(request.baseline),
        "candidate": candidate_json(request.candidate),
        "market": [candidate_json(c) for c in request.market],
    }


# --- Runners: the same scenario through the engine or through HTTP ---------------------------

logging.getLogger("vitadelta_scoring").setLevel(logging.WARNING)
client = TestClient(app)


def run_score(request: ScoreRequest, via: str, config: EngineConfig = DEFAULT_CONFIG) -> Json:
    if via == "engine":
        result: Json = json.loads(json.dumps(score_result_json(score(request, config))))
        return result
    response = client.post("/internal/v1/score", json=score_payload(request))
    assert response.status_code == 200, response.text
    body: Json = response.json()
    return body


def run_compare(request: CompareRequest, via: str, config: EngineConfig = DEFAULT_CONFIG) -> Json:
    if via == "engine":
        result: Json = json.loads(json.dumps(compare_result_json(compare(request, config))))
        return result
    response = client.post("/internal/v1/compare", json=compare_payload(request))
    assert response.status_code == 200, response.text
    body: Json = response.json()
    return body


def item(result: Json, product_id: str) -> Json:
    found: Json = next(i for i in result["items"] if i["product_id"] == product_id)
    return found


def row(product: Json, code: str) -> Json:
    found: Json = next(r for r in product["breakdown"] if r["code"] == code)
    return found


def flag_codes(product: Json) -> dict[str, list[str]]:
    return {f["code"]: f["nutrient_codes"] for f in product["flags"]}


VIAS = ["engine", "http"]
