"""Data layer: synthetic fixtures under data/fixtures (spec 5.8, 11.1)."""

import re
from collections import Counter
from datetime import timedelta
from decimal import Decimal

import datasets
import pytest
from datasets import (
    OFFER_COLUMNS,
    REFERENCE_NOW,
    ConversionTable,
    Product,
    Row,
    UlTable,
    available_countries,
    dec,
    fixtures_dir,
    in_stock,
    nutrient_map,
    reference_rows,
)
from jsonschema import Draft202012Validator

FICTIONAL_BRANDS = {
    "Fixture Labs",
    "Northline Nutrition",
    "Meridian Vitals",
    "Bosphorus Health",
    "Redwood Daily",
}
OFFER_FILES = ["offers.synthetic.csv", "offers.price-drop.synthetic.csv"]
CODE_PATTERN = re.compile(r"^[A-Z0-9][A-Z0-9-]{1,39}$")


def products() -> list[Product]:
    return datasets.load_products(fixtures_dir() / "products.synthetic.json")


def by_code() -> dict[str, Product]:
    return {p["code"]: p for p in products()}


def offers(name: str = "offers.synthetic.csv") -> tuple[Row, ...]:
    return datasets.read_rows(fixtures_dir() / name)


def conversions() -> ConversionTable:
    return ConversionTable(reference_rows("unit_conversions.csv"))


def country_currency() -> dict[str, str]:
    return {r["code"]: r["currency"] for r in reference_rows("countries.csv")}


def tr_offers(code: str) -> list[Row]:
    return [r for r in offers() if r["product_code"] == code and r["country_code"] == "TR"]


# --- Products document ---------------------------------------------------------------------


def test_format_checker_enforces_the_schema_formats() -> None:
    """Guards the schema test itself: date-time, date and uri formats must be checked."""
    schema = datasets.load_json(datasets.product_schema_path())
    validator = Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)
    definition = schema["$defs"]
    assert definition["offer"]["properties"]["checked_at"]["format"] == "date-time"
    for format_name in ("date-time", "date", "uri"):
        assert format_name in Draft202012Validator.FORMAT_CHECKER.checkers, format_name
    assert validator.is_valid({"schema_version": 1, "products": []}) is False
    checker = Draft202012Validator.FORMAT_CHECKER
    assert not checker.conforms("2026-13-45T25:61:61Z", "date-time")
    assert not checker.conforms("2026-02-30", "date")
    assert not checker.conforms("not a uri", "uri")


def test_products_document_is_valid_against_the_schema() -> None:
    schema = datasets.load_json(datasets.product_schema_path())
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)
    document = datasets.load_json(fixtures_dir() / "products.synthetic.json")
    errors = sorted(validator.iter_errors(document), key=lambda e: list(e.absolute_path))
    messages = [f"/{'/'.join(map(str, e.absolute_path))}: {e.message[:120]}" for e in errors]
    assert not messages, "schema violations:\n" + "\n".join(messages[:20])


def test_product_codes_are_unique_and_well_formed() -> None:
    codes = [p["code"] for p in products()]
    assert len(codes) >= 14, f"only {len(codes)} products, spec 5.8 requires at least 14"
    assert [c for c, n in Counter(codes).items() if n > 1] == []
    assert [c for c in codes if not CODE_PATTERN.match(c)] == []


def test_every_product_is_synthetic_with_a_fictional_brand() -> None:
    bad = [
        (p["code"], p["brand"], p.get("synthetic"))
        for p in products()
        if p.get("synthetic") is not True or p["brand"] not in FICTIONAL_BRANDS
    ]
    assert not bad, f"(code, brand, synthetic) violating the fictional-brand rule: {bad}"


def test_every_fixture_brand_name_is_used() -> None:
    assert {p["brand"] for p in products()} == FICTIONAL_BRANDS


def test_nutrient_codes_exist_and_are_unique_per_product() -> None:
    bad = []
    for p in products():
        codes = [n["nutrient_code"] for n in p["nutrients"]]
        unknown = sorted(set(codes) - set(datasets.NUTRIENT_REGISTRY))
        duplicates = [c for c, n in Counter(codes).items() if n > 1]
        if unknown or duplicates:
            bad.append((p["code"], unknown, duplicates))
    assert not bad, f"(product, unknown codes, duplicate codes): {bad}"


def test_form_codes_are_registered_for_their_nutrient_never_any() -> None:
    forms = {r["code"]: r["nutrient_code"] for r in reference_rows("forms.csv")}
    bad = []
    for p in products():
        for n in p["nutrients"]:
            form = n.get("form_code")
            if form is None or form == "UNKNOWN":
                continue
            if forms.get(form) != n["nutrient_code"]:
                bad.append((p["code"], n["nutrient_code"], form))
    assert not bad, f"(product, nutrient, form) not in forms.csv for that nutrient: {bad}"


def test_units_are_valid_and_iu_only_where_convertible() -> None:
    bad = []
    for p in products():
        for n in p["nutrients"]:
            unit = datasets.normalise_unit(n["unit"])
            code = n["nutrient_code"]
            if unit not in datasets.VALID_UNITS:
                bad.append((p["code"], code, n["unit"]))
            elif unit == "IU" and code not in {"VIT_A", "VIT_D", "VIT_E"}:
                bad.append((p["code"], code, "IU"))
            elif unit == "IU" and code == "VIT_A":
                retinol = {"RETINOL", "RETINYL_ACETATE", "RETINYL_PALMITATE"}
                if n.get("form_code") not in retinol:
                    bad.append((p["code"], code, "IU without a retinol form"))
    assert not bad, f"invalid units (product, nutrient, unit): {bad}"


def test_every_nutrient_amount_converts_to_the_canonical_unit() -> None:
    table = conversions()
    bad = []
    for p in products():
        for n in p["nutrients"]:
            canonical = datasets.NUTRIENT_REGISTRY[n["nutrient_code"]][1]
            form = n.get("form_code") or "UNKNOWN"
            unit = datasets.normalise_unit(n["unit"])
            if table.factor(n["nutrient_code"], form, unit, canonical) is None:
                bad.append((p["code"], n["nutrient_code"], unit))
    assert not bad, f"no unit conversion to the canonical unit for (product, nutrient, unit): {bad}"


def test_ul_amount_per_serving_does_not_exceed_the_amount() -> None:
    bad = [
        (p["code"], n["nutrient_code"])
        for p in products()
        for n in p["nutrients"]
        if n.get("ul_amount_per_serving") is not None
        and dec(n["ul_amount_per_serving"]) > dec(n["amount_per_serving"])
    ]
    assert not bad, f"ul_amount_per_serving above amount_per_serving: {bad}"


def test_units_per_container_covers_one_serving() -> None:
    bad = [p["code"] for p in products() if p["units_per_container"] < p["units_per_serving"]]
    assert not bad, f"units_per_container < units_per_serving: {bad}"


def test_availability_countries_exist_once_per_product() -> None:
    countries = set(country_currency())
    bad = []
    for p in products():
        listed = [a["country_code"] for a in p["availability"]]
        if len(listed) != len(set(listed)) or not set(listed) <= countries:
            bad.append((p["code"], listed))
    assert not bad, f"duplicate or unknown availability countries: {bad}"


def test_every_product_has_at_least_five_nutrients_except_zn40() -> None:
    thin = {p["code"]: len(p["nutrients"]) for p in products() if len(p["nutrients"]) < 5}
    assert set(thin) == {"ZN40"}, f"products with fewer than 5 nutrients: {thin}"


# --- Section 5.8 coverage ------------------------------------------------------------------


def test_golden_pair_is_available_in_tr_with_the_specified_best_prices() -> None:
    expected = {"GOLD-A": Decimal("800.00"), "GOLD-B": Decimal("900.00")}
    catalog = by_code()
    for code, price in expected.items():
        assert code in catalog, f"{code} missing"
        assert "TR" in available_countries(catalog[code])
        prices = [dec(r["price"]) for r in tr_offers(code) if in_stock(r)]
        assert prices, f"{code}: no in-stock TR offer"
        assert min(prices) == price, f"{code}: best in-stock TR price {min(prices)} != {price}"


def test_availability_counts() -> None:
    sets = [available_countries(p) for p in products()]
    only_tr = sum(1 for s in sets if s == {"TR"})
    only_us = sum(1 for s in sets if s == {"US"})
    de_and_gb = sum(1 for s in sets if s == {"DE", "GB"})
    everywhere = sum(1 for s in sets if s == {"TR", "DE", "GB", "US"})
    assert only_tr >= 3, f"AVAILABLE only in TR: {only_tr}"
    assert only_us >= 3, f"AVAILABLE only in US: {only_us}"
    assert de_and_gb >= 2, f"AVAILABLE in DE and GB only: {de_and_gb}"
    assert everywhere >= 2, f"AVAILABLE in all four countries: {everywhere}"


def test_at_least_two_products_are_not_sold_in_tr() -> None:
    not_sold = [p["code"] for p in products() if datasets.availability(p).get("TR") == "NOT_SOLD"]
    assert len(not_sold) >= 2, f"NOT_SOLD in TR: {not_sold}"


def test_at_least_two_tr_products_only_have_out_of_stock_tr_offers() -> None:
    only_out = [
        p["code"]
        for p in products()
        if "TR" in available_countries(p)
        and tr_offers(p["code"])
        and not any(in_stock(r) for r in tr_offers(p["code"]))
    ]
    assert len(only_out) >= 2, f"in-market TR products with only out-of-stock offers: {only_out}"


def test_an_in_stock_offer_is_older_than_thirty_days_at_the_reference_date() -> None:
    cutoff = REFERENCE_NOW - timedelta(days=30)
    stale = [
        (r["product_code"], r["country_code"], r["checked_at"])
        for r in offers()
        if in_stock(r) and datasets.parse_timestamp(r["checked_at"]) < cutoff
    ]
    assert stale, f"no in-stock offer checked before {cutoff.isoformat()}"


def test_zn40_is_zinc_40_mg_once_a_day_with_an_in_stock_tr_offer() -> None:
    zn40 = by_code()["ZN40"]
    zinc = nutrient_map(zn40)["ZINC"]
    assert (dec(zinc["amount_per_serving"]), zinc["unit"]) == (Decimal(40), "mg")
    assert dec(zn40["servings_per_day"]) == 1
    assert "TR" in available_countries(zn40)
    assert any(in_stock(r) for r in tr_offers("ZN40")), "ZN40 has no in-stock TR offer"


def test_exactly_three_tr_in_market_coq10_products_at_30_100_200_mg_per_day() -> None:
    table = conversions()
    in_market = [
        p for p in products() if "TR" in available_countries(p) and "COQ10" in nutrient_map(p)
    ]
    daily = sorted(datasets.daily_amount(p, "COQ10", table) for p in in_market)
    assert daily == [30, 100, 200], f"{[p['code'] for p in in_market]} -> {daily}"
    assert any("LUTEIN" in nutrient_map(p) for p in in_market), "no COQ10 product with LUTEIN"
    missing_offer = [
        p["code"] for p in in_market if not any(in_stock(r) for r in tr_offers(p["code"]))
    ]
    assert not missing_offer, f"COQ10 products without an in-stock TR offer: {missing_offer}"


def test_iu_usage_for_vitamin_d_and_vitamin_e_with_natural_form() -> None:
    d_iu = [p["code"] for p in products() if nutrient_map(p).get("VIT_D", {}).get("unit") == "IU"]
    e_iu = [
        p["code"]
        for p in products()
        if nutrient_map(p).get("VIT_E", {}).get("unit") == "IU"
        and nutrient_map(p)["VIT_E"].get("form_code") == "D_ALPHA_TOCOPHEROL"
    ]
    assert d_iu, "no product with VIT_D in IU"
    assert e_iu, "no product with VIT_E in IU and D_ALPHA_TOCOPHEROL"


def test_a_product_with_two_units_per_serving_and_two_servings_per_day() -> None:
    matches = [
        p["code"]
        for p in products()
        if p["units_per_serving"] == 2 and dec(p["servings_per_day"]) == 2
    ]
    assert matches, "no product with units_per_serving 2 and servings_per_day 2"


# --- UL caps -------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("country", "expected"),
    [("TR", {"ZN40"}), ("DE", set()), ("GB", set()), ("US", set())],
)
def test_only_zn40_in_tr_exceeds_an_upper_limit(country: str, expected: set[str]) -> None:
    ul_set = {r["code"]: r["ul_set_code"] for r in reference_rows("countries.csv")}[country]
    found = datasets.ul_exceeders(
        products(), country, ul_set, UlTable(reference_rows("upper_limits.csv")), conversions()
    )
    assert set(found) == expected, f"{country} ({ul_set}) fixtures above a UL: {found}"


# --- Offers files --------------------------------------------------------------------------


@pytest.mark.parametrize("name", OFFER_FILES)
def test_offers_csv_follows_the_column_spec(name: str) -> None:
    problems = datasets.offer_file_problems(
        fixtures_dir() / name,
        set(by_code()),
        country_currency(),
        lambda host: host.endswith(".example"),
    )
    assert not problems, f"{name}: " + "; ".join(problems[:20])


@pytest.mark.parametrize("name", OFFER_FILES)
def test_offers_natural_keys_are_unique(name: str) -> None:
    counts = Counter((r["product_code"], r["country_code"], r["retailer"]) for r in offers(name))
    assert [k for k, n in counts.items() if n > 1] == []


@pytest.mark.parametrize("name", OFFER_FILES)
def test_offer_countries_are_countries_the_product_has_an_availability_row_for(name: str) -> None:
    catalog = by_code()
    bad = [
        (r["product_code"], r["country_code"])
        for r in offers(name)
        if r["country_code"] not in datasets.availability(catalog[r["product_code"]])
    ]
    assert not bad, f"offers for a country without an availability row: {sorted(set(bad))}"


def test_price_drop_file_differs_in_exactly_the_two_intended_rows() -> None:
    base = offers("offers.synthetic.csv")
    drop = offers("offers.price-drop.synthetic.csv")
    assert len(base) == len(drop)
    changed = [(b, d) for b, d in zip(base, drop, strict=True) if b != d]
    assert len(changed) == 2, f"{len(changed)} rows differ"
    delta: dict[tuple[str, str, str], dict[str, tuple[str, str]]] = {}
    for before, after in changed:
        key = (before["product_code"], before["country_code"], before["retailer"])
        assert key == (after["product_code"], after["country_code"], after["retailer"])
        delta[key] = {c: (before[c], after[c]) for c in OFFER_COLUMNS if before[c] != after[c]}
    gold = ("GOLD-B", "TR", "Fixture Pharmacy")
    assert delta.pop(gold, None) == {"price": ("900.00", "846.00")}, "GOLD-B price drop missing"
    # Spec 5.8: one out-of-stock TR product comes back in stock, nothing else changes.
    (((product, country, _retailer), change),) = delta.items()
    assert country == "TR"
    assert change == {"in_stock": ("false", "true")}
    assert not any(in_stock(r) for r in tr_offers(product)), f"{product} was not out of stock"
