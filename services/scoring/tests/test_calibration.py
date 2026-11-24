"""The verdict calibration script (spec 6.8, Phase 2) and its committed result."""

from itertools import combinations
from pathlib import Path

import pytest

import calibrate_verdicts as calibration
from fixture_market import build_market
from vitadelta_scoring.config import DEFAULT_CONFIG
from vitadelta_scoring.engine import compare
from vitadelta_scoring.engine.model import CompareRequest


def test_committed_result_is_up_to_date() -> None:
    assert calibration.main(["--check"]) == 0


def test_check_reports_stale_files(tmp_path: Path) -> None:
    assert calibration.main(["--check", "--output", str(tmp_path)]) == 1
    assert calibration.main(["--output", str(tmp_path)]) == 0
    assert calibration.main(["--check", "--output", str(tmp_path)]) == 0
    (tmp_path / calibration.MARKDOWN_NAME).write_text("edited", encoding="utf-8")
    assert calibration.main(["--check", "--output", str(tmp_path)]) == 1


def test_output_is_deterministic() -> None:
    assert calibration.render(calibration.calibrate()) == calibration.render(
        calibration.calibrate()
    )


def test_variants_move_one_parameter_by_twenty_percent() -> None:
    variants = calibration.variants()
    assert list(variants) == [
        "default",
        "worth_it -20%",
        "worth_it +20%",
        "marginal -20%",
        "marginal +20%",
        "u_max -20%",
        "u_max +20%",
    ]
    assert variants["worth_it -20%"].verdict_worth_it_max_ratio == 0.8
    assert variants["marginal +20%"].verdict_marginal_max_ratio == pytest.approx(1.8)
    assert variants["u_max -20%"].utility_ceiling == pytest.approx(1.2)
    assert variants["u_max +20%"].diminishing_beta == DEFAULT_CONFIG.diminishing_beta


def test_pair_results_equal_the_compare_endpoint_logic() -> None:
    market = build_market("TR")
    _, pairs = calibration.evaluate(market, DEFAULT_CONFIG)
    by_id = market.by_id()
    ids = [c.product_id for c in market.candidates]
    assert len(pairs) == len(list(combinations(ids, 2)))
    for pair, (x, y) in zip(pairs, combinations(ids, 2), strict=True):
        result = compare(
            CompareRequest(market.context, by_id[x], by_id[y], market.candidates), DEFAULT_CONFIG
        )
        assert (pair.a, pair.b, pair.verdict, pair.ratio) == (
            result.a.product_id,
            result.b.product_id,
            result.verdict.value,
            result.ratio,
        )


def test_report_covers_every_country_and_the_real_label_set() -> None:
    report = calibration.calibrate()
    keys = [s["key"] for s in report["sets"]]
    assert keys == ["TR", "TR-price-drop", "DE", "GB", "US", "US-with-real-labels"]
    for market_set in report["sets"]:
        assert abs(sum(market_set["verdict_shares"].values()) - 1.0) < 1e-12
        assert sum(market_set["verdict_counts"].values()) == market_set["pairs"]
    assert report["proposal"]["gold_pair_stable"] is True


def test_within_band_rule() -> None:
    assert calibration._within_band("worth_it -20%", 0.9)
    assert not calibration._within_band("worth_it +20%", 1.3)
    assert calibration._within_band("marginal +20%", 1.7)
    assert not calibration._within_band("marginal -20%", None)


def test_quantiles_of_small_samples() -> None:
    assert calibration._quantiles([]) is None
    assert calibration._quantiles([2.0]) == {"min": 2.0, "median": 2.0, "max": 2.0}
