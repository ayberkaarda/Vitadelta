"""Data layer: regulatory reference data under data/reference (spec 5.3-5.7, 7.1, 11.1)."""

from collections import Counter
from decimal import Decimal, InvalidOperation

import datasets
import pytest
from datasets import CSV_HEADERS, NUTRIENT_REGISTRY, VALID_UNITS, reference_dir, reference_rows

CSV_FILES = sorted(CSV_HEADERS)
REFERENCE_TABLES = ["unit_conversions.csv", "upper_limits.csv"]


def nutrient_codes() -> set[str]:
    return {row["code"] for row in reference_rows("nutrients.csv")}


def form_rows() -> dict[str, datasets.Row]:
    return {row["code"]: row for row in reference_rows("forms.csv")}


def is_positive_decimal(text: str) -> bool:
    try:
        return Decimal(text) > 0
    except InvalidOperation:
        return False


# --- CSV shape -----------------------------------------------------------------------------


@pytest.mark.parametrize("name", CSV_FILES)
def test_csv_header_is_exact(name: str) -> None:
    header = datasets.read_records(reference_dir() / name)[0]
    assert header == CSV_HEADERS[name], f"{name}: unexpected header {header}"


@pytest.mark.parametrize("name", CSV_FILES)
def test_csv_rows_have_equal_column_count(name: str) -> None:
    records = datasets.read_records(reference_dir() / name)
    width = len(records[0])
    ragged = [(n, len(r)) for n, r in enumerate(records, start=1) if len(r) != width]
    assert len(records) > 1, f"{name}: no data rows"
    assert not ragged, f"{name}: records (line, fields) with != {width} fields: {ragged}"


@pytest.mark.parametrize("name", [*CSV_FILES, "weak_passwords.txt"])
def test_files_use_lf_line_endings_only(name: str) -> None:
    content = (reference_dir() / name).read_bytes()
    assert b"\r" not in content, f"{name}: contains CR characters"
    assert content.endswith(b"\n"), f"{name}: missing final line break"
    assert not content.startswith(b"\xef\xbb\xbf"), f"{name}: byte order mark"


@pytest.mark.parametrize("name", CSV_FILES)
def test_csv_has_no_empty_required_cells(name: str) -> None:
    optional = datasets.OPTIONAL_COLUMNS.get(name, frozenset())
    empty = [
        (line, column)
        for line, row in enumerate(datasets.read_rows(reference_dir() / name), start=2)
        for column, value in row.items()
        if column not in optional and value.strip() == ""
    ]
    assert not empty, f"{name}: empty required cells (line, column): {empty}"


# --- Nutrient registry ---------------------------------------------------------------------


def test_nutrient_codes_are_unique() -> None:
    counts = Counter(row["code"] for row in reference_rows("nutrients.csv"))
    assert [c for c, n in counts.items() if n > 1] == []


def test_nutrient_codes_are_exactly_the_38_registry_codes() -> None:
    codes = nutrient_codes()
    assert len(NUTRIENT_REGISTRY) == 38
    assert codes == set(NUTRIENT_REGISTRY), (
        f"missing {sorted(set(NUTRIENT_REGISTRY) - codes)}, "
        f"unexpected {sorted(codes - set(NUTRIENT_REGISTRY))}"
    )


def test_nutrient_category_and_canonical_unit_match_the_registry() -> None:
    wrong = [
        (row["code"], row["category"], row["canonical_unit"])
        for row in reference_rows("nutrients.csv")
        if NUTRIENT_REGISTRY.get(row["code"]) != (row["category"], row["canonical_unit"])
    ]
    assert not wrong, f"(code, category, unit) differing from spec 5.4: {wrong}"


def test_nutrient_sort_order_is_unique_integer() -> None:
    orders = [row["sort_order"] for row in reference_rows("nutrients.csv")]
    assert all(o.isdigit() for o in orders), f"non-integer sort_order in {orders}"
    assert len(set(orders)) == len(orders)


def test_nutrient_presence_floor_is_empty_or_positive() -> None:
    bad = [
        row["code"]
        for row in reference_rows("nutrients.csv")
        if row["presence_floor_per_day"] and not is_positive_decimal(row["presence_floor_per_day"])
    ]
    assert not bad, f"invalid presence_floor_per_day for {bad}"


# --- Forms ---------------------------------------------------------------------------------


def test_form_codes_are_unique_and_match_the_spec_list() -> None:
    codes = [row["code"] for row in reference_rows("forms.csv")]
    assert len(codes) == len(set(codes)), (
        f"duplicate form codes: {[c for c in codes if codes.count(c) > 1]}"
    )
    assert set(codes) == datasets.SPEC_FORM_CODES, (
        f"missing {sorted(datasets.SPEC_FORM_CODES - set(codes))}, "
        f"unexpected {sorted(set(codes) - datasets.SPEC_FORM_CODES)}"
    )


def test_form_rows_reference_known_nutrients() -> None:
    unknown = [
        (row["code"], row["nutrient_code"])
        for row in reference_rows("forms.csv")
        if row["nutrient_code"] not in nutrient_codes()
    ]
    assert not unknown, f"forms with unknown nutrient: {unknown}"


# --- Reference values ----------------------------------------------------------------------


def test_reference_sets_and_values_reference_known_sets_and_nutrients() -> None:
    sets = {row["code"] for row in reference_rows("reference_sets.csv")}
    assert sets == {"EU_NRV", "US_DV"}
    bad = [
        (line, row["reference_set_code"], row["nutrient_code"])
        for line, row in enumerate(reference_rows("reference_values.csv"), start=2)
        if row["reference_set_code"] not in sets or row["nutrient_code"] not in nutrient_codes()
    ]
    assert not bad, (
        f"reference_values rows with unknown set or nutrient (line, set, nutrient): {bad}"
    )


def test_reference_values_are_unique_per_set_and_nutrient() -> None:
    counts = Counter(
        (r["reference_set_code"], r["nutrient_code"])
        for r in reference_rows("reference_values.csv")
    )
    assert [k for k, n in counts.items() if n > 1] == []


def test_reference_values_have_positive_amount_and_canonical_unit() -> None:
    bad = []
    for line, row in enumerate(reference_rows("reference_values.csv"), start=2):
        canonical = NUTRIENT_REGISTRY[row["nutrient_code"]][1]
        if not is_positive_decimal(row["amount"]) or row["unit"] != canonical:
            bad.append((line, row["nutrient_code"], row["amount"], row["unit"], canonical))
    assert not bad, f"(line, nutrient, amount, unit, canonical unit): {bad}"


# --- Upper limits --------------------------------------------------------------------------


def test_ul_sets_are_efsa_and_iom() -> None:
    assert {row["code"] for row in reference_rows("ul_sets.csv")} == {"EFSA", "IOM"}


def test_upper_limits_reference_known_sets_nutrients_and_forms() -> None:
    sets = {row["code"] for row in reference_rows("ul_sets.csv")}
    forms = form_rows()
    bad = []
    for line, row in enumerate(reference_rows("upper_limits.csv"), start=2):
        form = row["form_code"]
        form_ok = form == "ANY" or (
            form in forms and forms[form]["nutrient_code"] == row["nutrient_code"]
        )
        if (
            row["ul_set_code"] not in sets
            or row["nutrient_code"] not in nutrient_codes()
            or not form_ok
        ):
            bad.append((line, row["ul_set_code"], row["nutrient_code"], form))
    assert not bad, f"upper_limits rows with unknown set, nutrient or form (line, ...): {bad}"


def test_upper_limits_are_unique_per_set_nutrient_and_form() -> None:
    counts = Counter(
        (r["ul_set_code"], r["nutrient_code"], r["form_code"])
        for r in reference_rows("upper_limits.csv")
    )
    assert [k for k, n in counts.items() if n > 1] == []


def test_upper_limits_units_are_valid_and_canonical() -> None:
    bad = [
        (line, row["nutrient_code"], row["unit"])
        for line, row in enumerate(reference_rows("upper_limits.csv"), start=2)
        if row["unit"] not in VALID_UNITS
        or row["unit"] != NUTRIENT_REGISTRY[row["nutrient_code"]][1]
    ]
    assert not bad, (
        f"upper_limits rows whose unit is not the canonical unit (line, nutrient, unit): {bad}"
    )


def test_upper_limits_exempt_rows_have_no_amount_and_no_basis_others_have_both() -> None:
    bad = []
    for line, row in enumerate(reference_rows("upper_limits.csv"), start=2):
        exempt = row["amount"] == ""
        if exempt:
            ok = row["basis"] == ""
        else:
            ok = is_positive_decimal(row["amount"]) and row["basis"] in {"TOTAL", "SUPPLEMENTAL"}
        if not ok:
            bad.append(
                (line, row["ul_set_code"], row["nutrient_code"], row["amount"], row["basis"])
            )
    assert not bad, f"upper_limits rows violating the amount/basis rule (line, ...): {bad}"


def test_every_upper_limit_row_has_a_citation_note() -> None:
    missing = [
        (line, row["ul_set_code"], row["nutrient_code"], row["form_code"])
        for line, row in enumerate(reference_rows("upper_limits.csv"), start=2)
        if len(row["note"].strip()) < 10
    ]
    assert not missing, f"upper_limits rows without a citation note (line, ...): {missing}"


# --- Unit conversions ----------------------------------------------------------------------


def test_unit_conversions_reference_known_nutrients_forms_and_units() -> None:
    forms = form_rows()
    bad = []
    for line, row in enumerate(reference_rows("unit_conversions.csv"), start=2):
        nutrient, form = row["nutrient_code"], row["form_code"]
        nutrient_ok = nutrient == "*" or nutrient in nutrient_codes()
        form_ok = form == "ANY" or (form in forms and forms[form]["nutrient_code"] == nutrient)
        units_ok = (
            row["from_unit"] in VALID_UNITS
            and row["to_unit"] in VALID_UNITS
            and row["from_unit"] != row["to_unit"]
        )
        if not (nutrient_ok and form_ok and units_ok and is_positive_decimal(row["factor"])):
            bad.append((line, nutrient, row["from_unit"], row["to_unit"], form, row["factor"]))
    assert not bad, f"invalid unit_conversions rows (line, ...): {bad}"


def test_unit_conversions_are_unique_and_generic_rows_use_any() -> None:
    rows = reference_rows("unit_conversions.csv")
    counts = Counter(
        (r["nutrient_code"], r["from_unit"], r["to_unit"], r["form_code"]) for r in rows
    )
    assert [k for k, n in counts.items() if n > 1] == []
    assert [r for r in rows if r["nutrient_code"] == "*" and r["form_code"] != "ANY"] == []


def test_iu_conversions_only_for_vitamins_a_d_e() -> None:
    iu_nutrients = {
        r["nutrient_code"]
        for r in reference_rows("unit_conversions.csv")
        if "IU" in (r["from_unit"], r["to_unit"])
    }
    assert iu_nutrients == {"VIT_A", "VIT_D", "VIT_E"}


# --- Form sentinel rule (spec 5.3) ---------------------------------------------------------


@pytest.mark.parametrize("name", REFERENCE_TABLES)
def test_reference_tables_never_have_an_empty_form_code(name: str) -> None:
    empty = [
        line
        for line, row in enumerate(reference_rows(name), start=2)
        if row["form_code"].strip() == ""
    ]
    assert not empty, f"{name}: empty form_code on lines {empty}"


@pytest.mark.parametrize("name", REFERENCE_TABLES)
def test_reference_tables_never_use_unknown_as_form(name: str) -> None:
    unknown = [
        line
        for line, row in enumerate(reference_rows(name), start=2)
        if row["form_code"] == "UNKNOWN"
    ]
    assert not unknown, f"{name}: UNKNOWN is not a valid reference form (lines {unknown})"


def test_form_registry_contains_no_sentinels() -> None:
    sentinels = [r["code"] for r in reference_rows("forms.csv") if r["code"] in {"ANY", "UNKNOWN"}]
    assert not sentinels, f"forms.csv must not list sentinels: {sentinels}"


# --- Countries -----------------------------------------------------------------------------


def test_countries_are_the_four_expected_and_reference_existing_sets() -> None:
    rows = {r["code"]: r for r in reference_rows("countries.csv")}
    assert {
        code: (r["currency"], r["reference_set_code"], r["ul_set_code"], r["default_locale"])
        for code, r in rows.items()
    } == {
        "TR": ("TRY", "EU_NRV", "EFSA", "tr"),
        "DE": ("EUR", "EU_NRV", "EFSA", "en"),
        "GB": ("GBP", "EU_NRV", "EFSA", "en"),
        "US": ("USD", "US_DV", "IOM", "en"),
    }
    reference_sets = {r["code"] for r in reference_rows("reference_sets.csv")}
    ul_sets = {r["code"] for r in reference_rows("ul_sets.csv")}
    assert {r["reference_set_code"] for r in rows.values()} <= reference_sets
    assert {r["ul_set_code"] for r in rows.values()} <= ul_sets


# --- Weak password list --------------------------------------------------------------------


def weak_passwords() -> list[str]:
    return (reference_dir() / "weak_passwords.txt").read_text(encoding="utf-8").split("\n")[:-1]


def test_weak_password_list_is_non_trivial() -> None:
    entries = weak_passwords()
    assert len(entries) >= 10_000, f"only {len(entries)} entries"


def test_weak_password_list_contains_well_known_passwords() -> None:
    entries = set(weak_passwords())
    expected = {"password", "123456", "qwerty", "12345678", "letmein", "iloveyou"}
    assert expected <= entries, f"missing {sorted(expected - entries)}"


def test_weak_password_list_has_no_blank_lines() -> None:
    blank = [n for n, line in enumerate(weak_passwords(), start=1) if line.strip() == ""]
    assert not blank, f"blank lines at {blank[:20]}"
