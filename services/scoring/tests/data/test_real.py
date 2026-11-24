"""Data layer: real label snapshot under data/real (spec 5.8 "Real label data", 11.1)."""

from collections import Counter
from datetime import date

import datasets
from datasets import ConversionTable, Product, Row, real_dir, reference_rows
from jsonschema import Draft202012Validator

DEMO_RETAILERS = {"Demo Store", "Demo Market"}
EXAMPLE_DOMAINS = ("example.com", "example.org", "example.net")


def real_products() -> list[Product]:
    return datasets.load_products(real_dir() / "products.dsld.json")


def fixture_codes() -> set[str]:
    return {
        p["code"]
        for p in datasets.load_products(datasets.fixtures_dir() / "products.synthetic.json")
    }


def demo_offers() -> tuple[Row, ...]:
    return datasets.read_rows(real_dir() / "offers.us-demo.synthetic.csv")


def test_real_products_document_is_valid_against_the_schema() -> None:
    schema = datasets.load_json(datasets.product_schema_path())
    validator = Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)
    document = datasets.load_json(real_dir() / "products.dsld.json")
    errors = sorted(validator.iter_errors(document), key=lambda e: list(e.absolute_path))
    messages = [f"/{'/'.join(map(str, e.absolute_path))}: {e.message[:120]}" for e in errors]
    assert not messages, "schema violations: " + "; ".join(messages[:20])


def test_real_products_carry_dsld_provenance_fields() -> None:
    bad = []
    for p in real_products():
        entry_date = p.get("source_entry_date")
        valid_date = False
        if isinstance(entry_date, str):
            try:
                valid_date = date.fromisoformat(entry_date) <= datasets.REFERENCE_NOW.date()
            except ValueError:
                valid_date = False
        ok = (
            p.get("source") == "DSLD"
            and isinstance(p.get("source_ref"), str)
            and p["source_ref"].strip() != ""
            and valid_date
            and p.get("synthetic") is False
        )
        if not ok:
            bad.append(p["code"])
    assert not bad, (
        f"products without DSLD source, source_ref, source_entry_date or synthetic=false: {bad}"
    )


def test_real_product_codes_are_unique_and_distinct_from_fixture_codes() -> None:
    codes = [p["code"] for p in real_products()]
    assert codes, "no real products"
    assert [c for c, n in Counter(codes).items() if n > 1] == []
    assert sorted(set(codes) & fixture_codes()) == []
    refs = [p["source_ref"] for p in real_products()]
    assert [r for r, n in Counter(refs).items() if n > 1] == [], "duplicate source_ref"


def test_real_products_are_available_in_us_only() -> None:
    expected = [{"country_code": "US", "status": "AVAILABLE"}]
    bad = [
        p["code"]
        for p in real_products()
        if [{k: v for k, v in a.items() if k != "note"} for a in p["availability"]] != expected
    ]
    assert not bad, f"real products not AVAILABLE in US only: {bad}"


def test_real_nutrient_and_form_codes_are_known() -> None:
    forms = {r["code"]: r["nutrient_code"] for r in reference_rows("forms.csv")}
    unknown_nutrient = []
    unknown_form = []
    duplicates = []
    for p in real_products():
        codes = [n["nutrient_code"] for n in p["nutrients"]]
        duplicates += [(p["code"], c) for c, k in Counter(codes).items() if k > 1]
        for n in p["nutrients"]:
            if n["nutrient_code"] not in datasets.NUTRIENT_REGISTRY:
                unknown_nutrient.append((p["code"], n["nutrient_code"]))
            form = n.get("form_code")
            if form is not None and form != "UNKNOWN" and forms.get(form) != n["nutrient_code"]:
                unknown_form.append((p["code"], n["nutrient_code"], form))
    assert not unknown_nutrient, f"unknown nutrient codes: {unknown_nutrient}"
    assert not unknown_form, f"form codes not registered for the nutrient: {unknown_form}"
    assert not duplicates, f"nutrient listed twice: {duplicates}"


def test_real_units_are_valid_and_convertible() -> None:
    table = ConversionTable(reference_rows("unit_conversions.csv"))
    bad: list[tuple[str, ...]] = []
    for p in real_products():
        for n in p["nutrients"]:
            code = n["nutrient_code"]
            unit = datasets.normalise_unit(n["unit"])
            canonical = datasets.NUTRIENT_REGISTRY.get(code, ("", ""))[1]
            form = n.get("form_code") or "UNKNOWN"
            if (
                unit not in datasets.VALID_UNITS
                or table.factor(code, form, unit, canonical) is None
            ):
                bad.append((p["code"], code, n["unit"], form))
            elif unit == "IU" and code not in {"VIT_A", "VIT_D", "VIT_E"}:
                bad.append((p["code"], code, "IU"))
    assert not bad, f"invalid or unconvertible units (product, nutrient, unit, form): {bad}"


def test_real_ul_amount_does_not_exceed_the_amount() -> None:
    bad = [
        (p["code"], n["nutrient_code"])
        for p in real_products()
        for n in p["nutrients"]
        if n.get("ul_amount_per_serving") is not None
        and datasets.dec(n["ul_amount_per_serving"]) > datasets.dec(n["amount_per_serving"])
    ]
    assert not bad, f"ul_amount_per_serving above amount_per_serving: {bad}"


# --- provenance.json -----------------------------------------------------------------------


def test_provenance_has_an_entry_for_every_product_and_nothing_extra() -> None:
    entries = datasets.load_json(real_dir() / "provenance.json")["products"]
    entry_codes = [e["code"] for e in entries]
    assert [c for c, n in Counter(entry_codes).items() if n > 1] == [], "duplicate entries"
    products = {p["code"]: p for p in real_products()}
    assert set(entry_codes) == set(products), (
        f"missing {sorted(set(products) - set(entry_codes))}, "
        f"extra {sorted(set(entry_codes) - set(products))}"
    )
    mismatched = [
        (e["code"], e["label_id"], products[e["code"]]["source_ref"])
        for e in entries
        if e["code"] in products and str(e["label_id"]) != products[e["code"]]["source_ref"]
    ]
    assert not mismatched, (
        f"label id differs from source_ref (code, label_id, source_ref): {mismatched}"
    )


# --- Demo offers ---------------------------------------------------------------------------


def test_demo_offers_file_is_named_synthetic() -> None:
    assert (real_dir() / "offers.us-demo.synthetic.csv").is_file()


def test_demo_offers_follow_the_column_spec_with_example_domain_urls() -> None:
    def host_allowed(host: str) -> bool:
        return any(host == d or host.endswith("." + d) for d in EXAMPLE_DOMAINS)

    problems = datasets.offer_file_problems(
        real_dir() / "offers.us-demo.synthetic.csv",
        {p["code"] for p in real_products()},
        {r["code"]: r["currency"] for r in reference_rows("countries.csv")},
        host_allowed,
    )
    assert not problems, "; ".join(problems[:20])


def test_demo_offers_cover_only_real_products() -> None:
    real = {p["code"] for p in real_products()}
    outside = sorted({r["product_code"] for r in demo_offers()} - real)
    assert not outside, f"demo offers for products outside the real snapshot: {outside}"


def test_demo_offers_are_usd_us_with_demo_retailers_only() -> None:
    bad = [
        (line, r["country_code"], r["currency"], r["retailer"])
        for line, r in enumerate(demo_offers(), start=2)
        if r["country_code"] != "US"
        or r["currency"] != "USD"
        or r["retailer"] not in DEMO_RETAILERS
    ]
    assert not bad, f"(line, country, currency, retailer) outside US/USD/demo retailers: {bad}"


def test_demo_offers_natural_keys_are_unique() -> None:
    counts = Counter((r["product_code"], r["country_code"], r["retailer"]) for r in demo_offers())
    assert [k for k, n in counts.items() if n > 1] == []


def test_real_products_embed_no_offers() -> None:
    assert [p["code"] for p in real_products() if p.get("offers")] == []


def test_synthetic_fixture_offers_never_mention_real_products() -> None:
    real = {p["code"] for p in real_products()}
    stray = [
        (name, r["product_code"], r["country_code"])
        for name in ("offers.synthetic.csv", "offers.price-drop.synthetic.csv")
        for r in datasets.read_rows(datasets.fixtures_dir() / name)
        if r["product_code"] in real
    ]
    assert not stray, f"real products with synthetic fixture offers: {stray}"
