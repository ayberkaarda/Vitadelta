"""Offline tests of the vitamin A and folate UL splits, on hand-made test samples (not real labels)."""

import copy
import json
import sys
from collections import Counter
from decimal import Decimal
from pathlib import Path

import pytest

TESTS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS_DIR.parent))

import dsld_snapshot as converter  # noqa: E402

SAMPLES = json.loads((TESTS_DIR / "label-samples.test.json").read_text(encoding="utf-8"), parse_float=Decimal)


def convert_case(case: str) -> dict:
    label = copy.deepcopy(SAMPLES["base_label"])
    label["ingredientRows"] = copy.deepcopy(SAMPLES["filler_rows"]) + copy.deepcopy(SAMPLES["cases"][case])
    maps = converter.Maps(converter.SCRIPT_DIR)
    product, _, _ = converter.LabelConverter(maps, Counter(), Counter()).convert(label, "TABLET")
    return product


def nutrient(product: dict, code: str) -> dict:
    return next(item for item in product["nutrients"] if item["nutrient_code"] == code)


def test_vitamin_a_preformed_subtotal_with_unstated_rest_keeps_full_amount():
    vit_a = nutrient(convert_case("vitamin_a_preformed_subtotal_rest_unstated"), "VIT_A")
    assert vit_a["amount_per_serving"] == 900
    assert "ul_amount_per_serving" not in vit_a


def test_vitamin_a_preformed_and_beta_carotene_both_stated():
    vit_a = nutrient(convert_case("vitamin_a_preformed_and_beta_carotene_stated"), "VIT_A")
    assert vit_a["amount_per_serving"] == 900
    assert vit_a["ul_amount_per_serving"] == 300
    assert vit_a["form_code"] == "RETINOL"


def test_vitamin_a_beta_carotene_part_stated_reduces_by_that_part():
    vit_a = nutrient(convert_case("vitamin_a_beta_carotene_part_stated"), "VIT_A")
    assert vit_a["ul_amount_per_serving"] == 300
    assert vit_a["form_code"] == "RETINYL_ACETATE"


@pytest.mark.parametrize("case", ["folate_dfe_total_with_folic_acid_in_mg", "folate_dfe_total_with_folic_acid_in_mcg"])
def test_folate_split_is_converted_to_the_total_unit(case):
    folate = nutrient(convert_case(case), "VIT_B9")
    assert folate["amount_per_serving"] == 667
    assert folate["unit"] == "mcg"
    assert folate["ul_amount_per_serving"] == 400
    assert folate["form_code"] == "FOLIC_ACID"
