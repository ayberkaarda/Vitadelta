"""JSON-ready representations of engine results.

Keys follow the property order of the contract, numbers are unrounded doubles and nothing
depends on dictionary or set iteration order, so equal inputs give byte-identical JSON.
"""

from typing import Any

from vitadelta_scoring.engine.model import (
    BreakdownRow,
    CompareResult,
    Cost,
    Flag,
    NutrientDeltaRow,
    ProductScore,
    ScoredProduct,
    ScoreResult,
)

Json = dict[str, Any]


def cost_json(cost: Cost) -> Json:
    return {
        "effective_price": cost.effective_price,
        "currency": cost.currency,
        "servings_per_container": cost.servings_per_container,
        "days_supply": cost.days_supply,
        "cost_per_day": cost.cost_per_day,
        "price_stale": cost.price_stale,
        "out_of_stock": cost.out_of_stock,
    }


def flag_json(flag: Flag) -> Json:
    return {"code": flag.code.value, "nutrient_codes": list(flag.nutrient_codes)}


def breakdown_json(row: BreakdownRow) -> Json:
    return {
        "code": row.code,
        "category": row.category.value,
        "amount_per_day": row.amount_per_day,
        "ul_amount_per_day": row.ul_amount_per_day,
        "unit": row.unit,
        "reference": row.reference,
        "reference_source": row.reference_source.value,
        "coverage_ratio": row.coverage_ratio,
        "utility": row.utility,
        "weight": row.weight,
        "contribution": row.contribution,
        "capped_at_ul": row.capped_at_ul,
        "capped_at_u_max": row.capped_at_u_max,
        "ul_below_reference": row.ul_below_reference,
        "below_presence_floor": row.below_presence_floor,
    }


def scored_product_json(item: ScoredProduct) -> Json:
    rows = {row.code: breakdown_json(row) for row in item.breakdown}
    return {
        "product_id": item.product_id,
        "rank": item.rank,
        "cost": cost_json(item.cost),
        "content_score": item.content_score,
        "content_score_display": item.content_score_display,
        "value_score": item.value_score,
        "value_score_display": item.value_score_display,
        "custom_score": item.custom_score,
        "flags": [flag_json(f) for f in item.flags],
        "breakdown": list(rows.values()),
        "top_contributors": [rows[row.code] for row in item.top_contributors],
    }


def score_result_json(result: ScoreResult) -> Json:
    meta = result.meta
    return {
        "items": [scored_product_json(item) for item in result.items],
        "meta": {
            "m_ref": meta.m_ref,
            "market_size": meta.market_size,
            "excluded": {
                "required_nutrients": meta.excluded.required_nutrients,
                "exceeds_ul": meta.excluded.exceeds_ul,
            },
        },
    }


def compared_product_json(score: ProductScore) -> Json:
    return {
        "product_id": score.product_id,
        "cost": cost_json(score.cost),
        "content_score": score.content_score,
        "value_score": score.value_score,
        "flags": [flag_json(f) for f in score.flags],
        "breakdown": [breakdown_json(row) for row in score.breakdown],
    }


def delta_row_json(row: NutrientDeltaRow) -> Json:
    return {
        "code": row.code,
        "category": row.category.value,
        "amount_A": row.amount_a,
        "amount_B": row.amount_b,
        "delta_amount": row.delta_amount,
        "unit": row.unit,
        "r_A": row.r_a,
        "r_B": row.r_b,
        "u_A": row.u_a,
        "u_B": row.u_b,
        "weight": row.weight,
        "delta_contribution": row.delta_contribution,
        "flags": [f.value for f in row.flags],
    }


def compare_result_json(result: CompareResult) -> Json:
    return {
        "a": compared_product_json(result.a),
        "b": compared_product_json(result.b),
        "swapped": result.swapped,
        "currency": result.currency,
        "delta_cost": result.delta_cost,
        "delta_content": result.delta_content,
        "m_ref": result.m_ref,
        "market_size": result.market_size,
        "marginal_rate": result.marginal_rate,
        "ratio": result.ratio,
        "verdict": result.verdict.value,
        "nutrient_deltas": [delta_row_json(row) for row in result.nutrient_deltas],
        "explanation": {"key": result.explanation.key, "params": dict(result.explanation.params)},
    }
