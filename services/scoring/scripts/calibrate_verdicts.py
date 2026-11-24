"""Verdict calibration over the fixture market (spec 6.8, Phase 2).

For every country it compares all same-country pairs of products that have an in-stock
offer (``IN_STOCK_ONLY``, default weights, the country's reference and UL sets), with the
market of the country as ``market[]``. It reports the ratio distribution, the share of each
verdict and every pair whose verdict flips when ``verdict_worth_it_max_ratio``,
``verdict_marginal_max_ratio`` or ``utility_ceiling`` move by +-20 %. The synthetic
fixtures are evaluated per country; the US is evaluated a second time together with the
real label snapshot and its demo prices.

The output is deterministic. Run from ``services/scoring``::

    uv run python scripts/calibrate_verdicts.py           # writes calibration/
    uv run python scripts/calibrate_verdicts.py --check   # fails if calibration/ is stale

The defaults are not changed by this script; changing them is an owner decision.
"""

import argparse
import json
import statistics
import sys
from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from itertools import combinations
from pathlib import Path
from typing import Any

from fixture_market import (
    PRICE_DROP_OFFERS,
    REAL_OFFERS,
    REAL_PRODUCTS,
    SYNTHETIC_OFFERS,
    SYNTHETIC_PRODUCTS,
    Market,
    build_market,
)
from vitadelta_scoring.config import DEFAULT_CONFIG, EngineConfig
from vitadelta_scoring.engine.compare import pair_outcome
from vitadelta_scoring.engine.model import Ranking, ScoreRequest, Verdict
from vitadelta_scoring.engine.scoring import Scorer, market_rate
from vitadelta_scoring.engine.validation import validate_score_request

OUTPUT_DIR = Path(__file__).resolve().parents[1] / "calibration"
JSON_NAME = "verdict-calibration.json"
MARKDOWN_NAME = "verdict-calibration.md"
CHANGE = 0.2
VERDICT_ORDER = [v.value for v in Verdict]
# Largest share of pairs whose verdict may change when u_max moves by +-20 % before the
# ceiling is flagged for review.
MAX_U_MAX_FLIP_SHARE = 0.05
FLIP_LIST_LIMIT = 40


@dataclass(frozen=True, slots=True)
class MarketSet:
    key: str
    label: str
    country: str
    products: tuple[tuple[str, str], ...]
    offers: tuple[tuple[str, str], ...]


SETS = (
    MarketSet("TR", "TR synthetic fixtures", "TR", (SYNTHETIC_PRODUCTS,), (SYNTHETIC_OFFERS,)),
    MarketSet(
        "TR-price-drop",
        "TR synthetic fixtures after the price-drop file",
        "TR",
        (SYNTHETIC_PRODUCTS,),
        (PRICE_DROP_OFFERS,),
    ),
    MarketSet("DE", "DE synthetic fixtures", "DE", (SYNTHETIC_PRODUCTS,), (SYNTHETIC_OFFERS,)),
    MarketSet("GB", "GB synthetic fixtures", "GB", (SYNTHETIC_PRODUCTS,), (SYNTHETIC_OFFERS,)),
    MarketSet("US", "US synthetic fixtures", "US", (SYNTHETIC_PRODUCTS,), (SYNTHETIC_OFFERS,)),
    MarketSet(
        "US-with-real-labels",
        "US synthetic fixtures plus the real label snapshot (demo prices)",
        "US",
        (SYNTHETIC_PRODUCTS, REAL_PRODUCTS),
        (SYNTHETIC_OFFERS, REAL_OFFERS),
    ),
)


def variants(base: EngineConfig = DEFAULT_CONFIG) -> dict[str, EngineConfig]:
    def scaled(field: str, factor: float) -> EngineConfig:
        return base.model_copy(update={field: getattr(base, field) * factor})

    result = {"default": base}
    for field, short in (
        ("verdict_worth_it_max_ratio", "worth_it"),
        ("verdict_marginal_max_ratio", "marginal"),
        ("utility_ceiling", "u_max"),
    ):
        for factor, sign in ((1.0 - CHANGE, "-20%"), (1.0 + CHANGE, "+20%")):
            config = EngineConfig.model_validate(scaled(field, factor).model_dump())
            result[f"{short} {sign}"] = config
    return result


@dataclass(frozen=True, slots=True)
class PairResult:
    a: str
    b: str
    verdict: str
    ratio: float | None


def evaluate(market: Market, config: EngineConfig) -> tuple[float | None, list[PairResult]]:
    """Every same-country pair with spec 6.8 (``market[]`` = the country's candidate set)."""
    validate_score_request(ScoreRequest(market.context, Ranking(), (), False, market.candidates))
    scorer = Scorer(market.context, config)
    scores = [scorer.score(c) for c in market.candidates]
    m_ref = market_rate(scores, config.comparison_epsilon)
    pairs = []
    for x, y in combinations(scores, 2):
        outcome = pair_outcome(x, y, m_ref, config)
        pairs.append(
            PairResult(
                outcome.a.product_id, outcome.b.product_id, outcome.verdict.value, outcome.ratio
            )
        )
    return m_ref, pairs


def _quantiles(values: Sequence[float]) -> dict[str, float] | None:
    if not values:
        return None
    ordered = sorted(values)
    summary = {"min": ordered[0]}
    if len(ordered) >= 2:
        deciles = statistics.quantiles(ordered, n=10, method="inclusive")
        quartiles = statistics.quantiles(ordered, n=4, method="inclusive")
        summary.update(
            {
                "p10": deciles[0],
                "p25": quartiles[0],
                "median": quartiles[1],
                "p75": quartiles[2],
                "p90": deciles[8],
            }
        )
    else:
        summary["median"] = ordered[0]
    summary["max"] = ordered[-1]
    return summary


def _within_band(variant: str, ratio: float | None) -> bool:
    """A threshold flip must come from a ratio within +-20 % of the threshold that moved."""
    threshold = (
        DEFAULT_CONFIG.verdict_worth_it_max_ratio
        if variant.startswith("worth_it")
        else DEFAULT_CONFIG.verdict_marginal_max_ratio
    )
    return ratio is not None and (1 - CHANGE) * threshold <= ratio <= (1 + CHANGE) * threshold


def proposal(report: dict[str, Any]) -> dict[str, Any]:
    """Keep the defaults when the e2e golden pair is stable, threshold flips are confined to
    the +-20 % band around the moved threshold, and the ceiling has a small effect."""
    gold_stable = all(
        set(s["gold_pair"]["verdicts_under_variants"].values()) == {"NOT_WORTH_IT"}
        for s in report["sets"]
        if s["gold_pair"] is not None
    )
    within_band = all(s["threshold_flips_within_band"] for s in report["sets"])
    shares = [(name, share) for s in report["sets"] for name, share in s["flip_shares"].items()]
    u_max_share = max(share for name, share in shares if name.startswith("u_max"))
    threshold_share = max(share for name, share in shares if not name.startswith("u_max"))
    return {
        "keep_defaults": gold_stable and within_band and u_max_share <= MAX_U_MAX_FLIP_SHARE,
        "gold_pair_stable": gold_stable,
        "threshold_flips_within_band": within_band,
        "max_threshold_flip_share": threshold_share,
        "max_u_max_flip_share": u_max_share,
        "max_u_max_flip_share_limit": MAX_U_MAX_FLIP_SHARE,
    }


def calibrate() -> dict[str, Any]:
    configs = variants()
    report: dict[str, Any] = {
        "description": "Verdict calibration over the fixture market (spec 6.8).",
        "defaults": DEFAULT_CONFIG.model_dump(),
        "change": CHANGE,
        "variants": {name: c.model_dump() for name, c in configs.items()},
        "sets": [],
    }
    for market_set in SETS:
        market = build_market(market_set.country, market_set.products, market_set.offers)
        results = {name: evaluate(market, config) for name, config in configs.items()}
        m_ref, base_pairs = results["default"]
        ratios = [p.ratio for p in base_pairs if p.ratio is not None]
        counts = Counter(p.verdict for p in base_pairs)
        total = len(base_pairs)
        flips: dict[str, list[dict[str, Any]]] = {}
        for name, (_, pairs) in results.items():
            if name == "default":
                continue
            changed = []
            for before, after in zip(base_pairs, pairs, strict=True):
                if before.verdict != after.verdict:
                    changed.append(
                        {
                            "a": after.a,
                            "b": after.b,
                            "from": before.verdict,
                            "to": after.verdict,
                            "ratio_default": before.ratio,
                            "ratio_variant": after.ratio,
                        }
                    )
            flips[name] = changed
        gold = next(
            (p for p in base_pairs if {p.a, p.b} == {"GOLD-A", "GOLD-B"}),
            None,
        )
        report["sets"].append(
            {
                "key": market_set.key,
                "label": market_set.label,
                "country": market_set.country,
                "currency": market.country.currency,
                "products": len(market.candidates),
                "pairs": total,
                "m_ref": m_ref,
                "ratio_distribution": _quantiles(ratios),
                "verdict_counts": {v: counts.get(v, 0) for v in VERDICT_ORDER},
                "verdict_shares": {
                    v: (counts.get(v, 0) / total if total else 0.0) for v in VERDICT_ORDER
                },
                "flips": flips,
                "flipped_pairs": sorted(
                    {f"{f['a']} / {f['b']}" for changes in flips.values() for f in changes}
                ),
                "flip_shares": {
                    name: (len(changes) / total if total else 0.0)
                    for name, changes in flips.items()
                },
                "threshold_flips_within_band": all(
                    _within_band(name, f["ratio_default"])
                    for name, changes in flips.items()
                    if not name.startswith("u_max")
                    for f in changes
                ),
                "gold_pair": None
                if gold is None
                else {
                    "verdict": gold.verdict,
                    "ratio": gold.ratio,
                    "verdicts_under_variants": {
                        name: next(p.verdict for p in pairs if {p.a, p.b} == {"GOLD-A", "GOLD-B"})
                        for name, (_, pairs) in results.items()
                    },
                },
            }
        )
    report["proposal"] = proposal(report)
    return report


def _fmt(value: float | None, digits: int = 4) -> str:
    return "n/a" if value is None else f"{value:.{digits}f}"


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Verdict calibration over the fixture market",
        "",
        "Produced with `scripts/calibrate_verdicts.py` (deterministic; do not edit by hand).",
        "Every same-country pair of products with an in-stock offer is compared with the",
        "country's in-stock candidate set as `market[]`, default weights and the country's",
        "reference and UL sets. Variants move one parameter by +-20 % at a time.",
        "",
        "Defaults: " + ", ".join(f"`{k} = {v}`" for k, v in report["defaults"].items()) + ".",
        "",
        "## Verdict shares (defaults)",
        "",
        "| set | products | pairs | m_ref | " + " | ".join(VERDICT_ORDER) + " |",
        "|---|---|---|---|" + "---|" * len(VERDICT_ORDER),
    ]
    for s in report["sets"]:
        shares = " | ".join(
            f"{s['verdict_counts'][v]} ({100 * s['verdict_shares'][v]:.1f} %)"
            for v in VERDICT_ORDER
        )
        lines.append(
            f"| {s['key']} | {s['products']} | {s['pairs']} | {_fmt(s['m_ref'])} | {shares} |"
        )
    lines += [
        "",
        "## Ratio distribution (pairs with a ratio)",
        "",
        "| set | min | p10 | p25 | median | p75 | p90 | max |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for s in report["sets"]:
        d = s["ratio_distribution"] or {}
        cells = " | ".join(
            _fmt(d.get(k)) for k in ("min", "p10", "p25", "median", "p75", "p90", "max")
        )
        lines.append(f"| {s['key']} | {cells} |")
    variant_names = [k for k in report["variants"] if k != "default"]
    lines += [
        "",
        "## Verdict flips under +-20 % changes",
        "",
        "Flipped pairs per variant (share of all pairs of the set).",
        "",
        "| set | " + " | ".join(variant_names) + " |",
        "|---|" + "---|" * len(variant_names),
    ]
    for s in report["sets"]:
        cells = " | ".join(
            f"{len(s['flips'][n])} ({100 * s['flip_shares'][n]:.1f} %)" for n in variant_names
        )
        lines.append(f"| {s['key']} | {cells} |")
    lines += ["", "### Flipped pairs", ""]
    for s in report["sets"]:
        entries = [(n, f) for n in variant_names for f in s["flips"][n]]
        if not entries:
            lines.append(f"- {s['key']}: none")
            continue
        if len(entries) > FLIP_LIST_LIMIT:
            lines.append(
                f"- {s['key']}: {len(entries)} flips over {len(s['flipped_pairs'])} of "
                f"{s['pairs']} pairs; every flip is listed in `{JSON_NAME}`."
            )
            continue
        lines.append(f"- {s['key']}:")
        for name, f in entries:
            lines.append(
                f"  - {name}: {f['a']} vs {f['b']}: {f['from']} -> {f['to']} "
                f"(ratio {_fmt(f['ratio_default'])} -> {_fmt(f['ratio_variant'])})"
            )
    lines += ["", "## Golden pair (GOLD-A vs GOLD-B)", ""]
    for s in report["sets"]:
        gold = s["gold_pair"]
        if gold is None:
            continue
        stable = set(gold["verdicts_under_variants"].values())
        lines.append(
            f"- {s['key']}: {gold['verdict']} at ratio {_fmt(gold['ratio'])}; "
            f"verdicts under all variants: {', '.join(sorted(stable))}"
        )
    decision = report["proposal"]
    yes_no = {True: "yes", False: "no"}
    lines += [
        "",
        "## Proposal",
        "",
        "- Golden pair NOT_WORTH_IT under every variant, before and after the price drop: "
        f"{yes_no[decision['gold_pair_stable']]}.",
        "- Every threshold flip comes from a pair whose default ratio lies within +-20 % of "
        f"the threshold that moved: {yes_no[decision['threshold_flips_within_band']]}.",
        "- Largest share of pairs flipped by one threshold variant: "
        f"{100 * decision['max_threshold_flip_share']:.1f} %.",
        "- Largest share of pairs flipped by a +-20 % change of `u_max`: "
        f"{100 * decision['max_u_max_flip_share']:.1f} % (review limit "
        f"{100 * decision['max_u_max_flip_share_limit']:.0f} %).",
        "",
        "Result: "
        + (
            "keep the defaults (`verdict_worth_it_max_ratio = 1.0`, "
            "`verdict_marginal_max_ratio = 1.5`, `utility_ceiling = 1.5`)."
            if decision["keep_defaults"]
            else "review the defaults; at least one criterion above is not met."
        )
        + " Changing them is an owner decision.",
        "",
    ]
    return "\n".join(lines)


def render(report: dict[str, Any]) -> tuple[str, str]:
    return json.dumps(report, indent=2, sort_keys=False) + "\n", markdown(report)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    parser.add_argument(
        "--check", action="store_true", help="fail if the committed files are stale"
    )
    parser.add_argument("--output", type=Path, default=OUTPUT_DIR)
    args = parser.parse_args(argv)
    json_text, md_text = render(calibrate())
    targets = {args.output / JSON_NAME: json_text, args.output / MARKDOWN_NAME: md_text}
    if args.check:
        stale = [
            str(path.name)
            for path, text in targets.items()
            if not path.is_file() or path.read_text(encoding="utf-8") != text
        ]
        if stale:
            print("stale calibration files: " + ", ".join(stale), file=sys.stderr)
            return 1
        print("calibration files are up to date")
        return 0
    args.output.mkdir(parents=True, exist_ok=True)
    for path, text in targets.items():
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote {path.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
