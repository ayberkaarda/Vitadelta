"""Loaders and helpers shared by the data-layer tests.

The tests are read-only over ``data/`` and ``contracts/``. The data root and the contracts
root are resolved from the location of this file; the environment variables
``VITADELTA_DATA_ROOT`` and ``VITADELTA_CONTRACTS_ROOT`` redirect them to a copy of the
directory (used to prove that a test fails when its data is broken).
"""

import csv
import json
import os
import re
from collections.abc import Callable
from datetime import UTC, datetime
from decimal import Decimal
from functools import cache
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

Row = dict[str, str]
Product = dict[str, Any]

# Fixed "today" of the data layer (spec 5.8): offers and entry dates are checked against it.
REFERENCE_NOW = datetime(2026, 11, 24, tzinfo=UTC)

VALID_UNITS = frozenset({"mg", "mcg", "g", "IU"})
UNIT_ALIASES = {"µg": "mcg", "μg": "mcg", "ug": "mcg", "iu": "IU"}

_VITAMIN_UNITS = {
    "VIT_A": "mcg",
    "VIT_C": "mg",
    "VIT_D": "mcg",
    "VIT_E": "mg",
    "VIT_K": "mcg",
    "VIT_B1": "mg",
    "VIT_B2": "mg",
    "VIT_B3": "mg",
    "VIT_B5": "mg",
    "VIT_B6": "mg",
    "VIT_B7": "mcg",
    "VIT_B9": "mcg",
    "VIT_B12": "mcg",
    "CHOLINE": "mg",
}
_MINERAL_UNITS = {
    "CALCIUM": "mg",
    "MAGNESIUM": "mg",
    "ZINC": "mg",
    "IRON": "mg",
    "SELENIUM": "mcg",
    "COPPER": "mg",
    "MANGANESE": "mg",
    "CHROMIUM": "mcg",
    "MOLYBDENUM": "mcg",
    "IODINE": "mcg",
    "POTASSIUM": "mg",
    "PHOSPHORUS": "mg",
    "CHLORIDE": "mg",
    "FLUORIDE": "mg",
}
_COENZYMES = ("COQ10", "ALPHA_LIPOIC_ACID", "PQQ", "NADH")
_OTHERS = ("LUTEIN", "ZEAXANTHIN", "LYCOPENE", "OMEGA3_EPA", "OMEGA3_DHA", "INOSITOL")

# Spec 5.4: code -> (category, canonical unit).
NUTRIENT_REGISTRY: dict[str, tuple[str, str]] = {
    **{code: ("VITAMIN", unit) for code, unit in _VITAMIN_UNITS.items()},
    **{code: ("MINERAL", unit) for code, unit in _MINERAL_UNITS.items()},
    **{code: ("COENZYME", "mg") for code in _COENZYMES},
    **{code: ("OTHER", "mg") for code in _OTHERS},
}

# Spec 5.4 form codes.
SPEC_FORM_CODES = frozenset(
    {
        "RETINOL",
        "RETINYL_ACETATE",
        "RETINYL_PALMITATE",
        "BETA_CAROTENE",
        "D2_ERGOCALCIFEROL",
        "D3_CHOLECALCIFEROL",
        "D_ALPHA_TOCOPHEROL",
        "DL_ALPHA_TOCOPHEROL",
        "NICOTINIC_ACID",
        "NICOTINAMIDE",
        "FOLIC_ACID",
        "METHYLFOLATE",
        "CYANOCOBALAMIN",
        "METHYLCOBALAMIN",
        "MAGNESIUM_CITRATE",
        "MAGNESIUM_OXIDE",
        "MAGNESIUM_BISGLYCINATE",
        "ZINC_PICOLINATE",
        "ZINC_GLUCONATE",
        "UBIQUINONE",
        "UBIQUINOL",
    }
)

CSV_HEADERS: dict[str, list[str]] = {
    "nutrients.csv": [
        "code",
        "name_en",
        "category",
        "canonical_unit",
        "presence_floor_per_day",
        "sort_order",
    ],
    "forms.csv": ["code", "nutrient_code", "name_en"],
    "unit_conversions.csv": [
        "nutrient_code",
        "from_unit",
        "to_unit",
        "factor",
        "form_code",
        "note",
    ],
    "reference_sets.csv": ["code", "name", "source_citation"],
    "reference_values.csv": ["reference_set_code", "nutrient_code", "amount", "unit"],
    "ul_sets.csv": ["code", "name", "source_citation"],
    "upper_limits.csv": [
        "ul_set_code",
        "nutrient_code",
        "form_code",
        "amount",
        "unit",
        "basis",
        "note",
    ],
    "countries.csv": [
        "code",
        "name_en",
        "currency",
        "reference_set_code",
        "ul_set_code",
        "default_locale",
    ],
}

# Columns that may be empty (everything else must be filled in every row).
OPTIONAL_COLUMNS: dict[str, frozenset[str]] = {
    "nutrients.csv": frozenset({"presence_floor_per_day"}),
    "upper_limits.csv": frozenset({"amount", "basis"}),
}

OFFER_COLUMNS = [
    "product_code",
    "country_code",
    "retailer",
    "price",
    "currency",
    "in_stock",
    "url",
    "checked_at",
]


def repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _root_from_env(variable: str, default: Path) -> Path:
    override = os.environ.get(variable)
    if override is None:
        return default
    path = Path(override)
    if not path.is_dir():
        raise RuntimeError(f"{variable} does not point to an existing directory: {override}")
    return path


def data_root() -> Path:
    return _root_from_env("VITADELTA_DATA_ROOT", repo_root() / "data")


def contracts_root() -> Path:
    return _root_from_env("VITADELTA_CONTRACTS_ROOT", repo_root() / "contracts")


def reference_dir() -> Path:
    return data_root() / "reference"


def fixtures_dir() -> Path:
    return data_root() / "fixtures"


def real_dir() -> Path:
    return data_root() / "real"


def product_schema_path() -> Path:
    return contracts_root() / "schemas" / "import-products.schema.json"


@cache
def read_records(path: Path) -> list[list[str]]:
    """All CSV records of a file, header included, parsed as RFC 4180."""
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.reader(handle))


@cache
def read_rows(path: Path) -> tuple[Row, ...]:
    """Data rows keyed by header. Files with ragged rows fail in the shape tests first."""
    records = read_records(path)
    header = records[0]
    return tuple(dict(zip(header, record, strict=False)) for record in records[1:])


def reference_rows(name: str) -> tuple[Row, ...]:
    return read_rows(reference_dir() / name)


@cache
def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def load_products(path: Path) -> list[Product]:
    products: list[Product] = load_json(path)["products"]
    return products


def dec(value: Any) -> Decimal:
    return Decimal(str(value))


def normalise_unit(unit: str) -> str:
    return UNIT_ALIASES.get(unit, unit)


def parse_timestamp(text: str) -> datetime:
    return datetime.fromisoformat(text)


def in_stock(row: Row) -> bool:
    return row["in_stock"] == "true"


def availability(product: Product) -> dict[str, str]:
    return {entry["country_code"]: entry["status"] for entry in product["availability"]}


def available_countries(product: Product) -> frozenset[str]:
    return frozenset(c for c, status in availability(product).items() if status == "AVAILABLE")


def nutrient_map(product: Product) -> dict[str, dict[str, Any]]:
    return {entry["nutrient_code"]: entry for entry in product["nutrients"]}


def daily_amount(product: Product, nutrient_code: str, conversions: ConversionTable) -> Decimal:
    """Labelled amount per day in the nutrient's canonical unit."""
    entry = nutrient_map(product)[nutrient_code]
    canonical = NUTRIENT_REGISTRY[nutrient_code][1]
    return per_day_amount(entry, product["servings_per_day"], canonical, conversions, ul=False)


class ConversionTable:
    """Unit conversion lookup following spec 5.5 (specific form, then ANY, then generic)."""

    def __init__(self, rows: tuple[Row, ...]) -> None:
        self._factors: dict[tuple[str, str, str, str], Decimal] = {
            (r["nutrient_code"], r["from_unit"], r["to_unit"], r["form_code"]): dec(r["factor"])
            for r in rows
        }

    def factor(self, nutrient: str, form: str, from_unit: str, to_unit: str) -> Decimal | None:
        if from_unit == to_unit:
            return Decimal(1)
        forms = [form, "ANY"] if form not in ("ANY", "UNKNOWN", "") else ["ANY"]
        for nutrient_key in (nutrient, "*"):
            for form_key in forms:
                found = self._factors.get((nutrient_key, from_unit, to_unit, form_key))
                if found is not None:
                    return found
        return None


class UlTable:
    """Upper limit lookup following the UL matching rule of spec 5.7."""

    def __init__(self, rows: tuple[Row, ...]) -> None:
        self._rows: dict[tuple[str, str, str], Row] = {
            (r["ul_set_code"], r["nutrient_code"], r["form_code"]): r for r in rows
        }

    def match(self, ul_set: str, nutrient: str, form: str) -> tuple[str, Row | None]:
        """Return "limit" or "exempt" with the row, else "form_dependent" or "none"."""
        row = None
        if form not in ("UNKNOWN", "", "ANY"):
            row = self._rows.get((ul_set, nutrient, form))
        if row is None:
            row = self._rows.get((ul_set, nutrient, "ANY"))
        if row is not None:
            return ("exempt" if row["amount"] == "" else "limit", row)
        specific = any(
            key[0] == ul_set and key[1] == nutrient and key[2] != "ANY" for key in self._rows
        )
        return ("form_dependent", None) if specific else ("none", None)


def per_day_amount(
    nutrient_entry: dict[str, Any],
    servings_per_day: Any,
    target_unit: str,
    conversions: ConversionTable,
    ul: bool,
) -> Decimal:
    """Daily amount in ``target_unit``; with ``ul`` the UL-relevant amount of spec 5.7."""
    ul_amount = nutrient_entry.get("ul_amount_per_serving") if ul else None
    amount = dec(ul_amount if ul_amount is not None else nutrient_entry["amount_per_serving"])
    form = nutrient_entry.get("form_code") or "UNKNOWN"
    from_unit = normalise_unit(nutrient_entry["unit"])
    factor = conversions.factor(nutrient_entry["nutrient_code"], form, from_unit, target_unit)
    if factor is None:
        raise AssertionError(
            f"no unit conversion {from_unit} -> {target_unit} for "
            f"{nutrient_entry['nutrient_code']} ({form})"
        )
    return amount * factor * dec(servings_per_day)


def ul_exceeders(
    products: list[Product],
    country: str,
    ul_set: str,
    uls: UlTable,
    conversions: ConversionTable,
    only_synthetic: bool = True,
) -> dict[str, list[str]]:
    """Products AVAILABLE in ``country`` whose daily UL-relevant amount exceeds the set's UL.

    Returns product code -> nutrient codes.
    """
    result: dict[str, list[str]] = {}
    for product in products:
        if only_synthetic and not product.get("synthetic", False):
            continue
        if country not in available_countries(product):
            continue
        for entry in product["nutrients"]:
            form = entry.get("form_code") or "UNKNOWN"
            kind, row = uls.match(ul_set, entry["nutrient_code"], form)
            if kind != "limit" or row is None:
                continue
            daily = per_day_amount(
                entry, product["servings_per_day"], row["unit"], conversions, ul=True
            )
            if daily > dec(row["amount"]):
                result.setdefault(product["code"], []).append(entry["nutrient_code"])
    return result


_PRICE_PATTERN = re.compile(r"^(?:0|[1-9][0-9]{0,9})(?:\.[0-9]{1,2})?$")
_TIMESTAMP_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$"
)


def offer_file_problems(
    path: Path,
    known_products: set[str],
    country_currency: dict[str, str],
    host_allowed: Callable[[str], bool],
) -> list[str]:
    """Violations of contracts/schemas/offers-csv.columns.md (plus the host policy)."""
    raw = path.read_bytes()
    records = read_records(path)
    problems: list[str] = []
    if raw.startswith(b"\xef\xbb\xbf"):
        problems.append("byte order mark")
    if records[0] != OFFER_COLUMNS:
        problems.append(f"header {records[0]}")
    if len(records) < 2:
        problems.append("no data rows")
    for line, rec in enumerate(records[1:], start=2):
        if len(rec) != len(OFFER_COLUMNS):
            problems.append(f"line {line}: {len(rec)} fields")
            continue
        row = dict(zip(OFFER_COLUMNS, rec, strict=True))
        if any(v.strip() == "" for v in rec):
            problems.append(f"line {line}: empty value")
        if row["product_code"] not in known_products:
            problems.append(f"line {line}: unknown product {row['product_code']}")
        if row["country_code"] not in country_currency:
            problems.append(f"line {line}: unknown country {row['country_code']}")
        elif row["currency"] != country_currency[row["country_code"]]:
            problems.append(f"line {line}: currency {row['currency']} != {row['country_code']}")
        if not 1 <= len(row["retailer"]) <= 100 or row["retailer"] != row["retailer"].strip():
            problems.append(f"line {line}: retailer {row['retailer']!r}")
        if not _PRICE_PATTERN.match(row["price"]) or not 0 < dec(row["price"]) <= dec(
            "9999999999.99"
        ):
            problems.append(f"line {line}: price {row['price']!r}")
        if row["in_stock"] not in {"true", "false"}:
            problems.append(f"line {line}: in_stock {row['in_stock']!r}")
        url = urlsplit(row["url"])
        host = url.hostname or ""
        if url.scheme not in {"http", "https"} or not host or len(row["url"]) > 2048:
            problems.append(f"line {line}: url {row['url']!r}")
        elif not host_allowed(host):
            problems.append(f"line {line}: url host {host!r} not allowed")
        if not _TIMESTAMP_PATTERN.match(row["checked_at"]):
            problems.append(f"line {line}: checked_at {row['checked_at']!r}")
        elif parse_timestamp(row["checked_at"]) > REFERENCE_NOW:
            problems.append(f"line {line}: checked_at {row['checked_at']} is in the future")
    return problems
