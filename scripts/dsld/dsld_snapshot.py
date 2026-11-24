# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///
"""Build the real US multivitamin snapshot from the NIH Dietary Supplement Label Database.

Two steps:

  fetch <cache-dir>     Query the DSLD API, choose candidate labels and download every raw
                        response once into the cache directory (resumes from the cache).
  convert <cache-dir>   Offline: read the cache and write the snapshot files into data/real/
                        (or the directory given with --out). Re-running on the same cache gives
                        byte-identical output.

See scripts/dsld/README.md for the selection, mapping and price rules.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import io
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
DEFAULT_OUT = REPO_ROOT / "data" / "real"

API_BASES = ["https://api.ods.od.nih.gov/dsld/v9/", "https://dsldapi.od.nih.gov/v9/"]
SPEC_URL = "https://api.ods.od.nih.gov/dsld/v9/"
USER_AGENT = "Mozilla/5.0 (compatible; label-data-client/1.0)"
REQUEST_PAUSE_SECONDS = 2.0
MAX_ATTEMPTS = 3

# Search filters: on market, multi-vitamin and mineral, adults and children 4+.
SEARCH_FILTERS = {"q": "*", "status": "1", "product_type": "a1315", "target_group": "p0250"}
SEARCH_PAGE_SIZE = 200

# DSLD supplement form code -> project dosage form, with the number of candidate labels taken.
# The scarcer forms come first so that the per-brand cap does not starve them.
FORMS = [
    ("e0161", "SOFTGEL", 15),
    ("e0165", "LIQUID", 12),
    ("e0162", "POWDER", 20),
    ("e0176", "GUMMY", 25),
    ("e0159", "CAPSULE", 30),
    ("e0155", "TABLET", 40),
]
LANGUAL_TO_DOSAGE_FORM = {
    "E0155": "TABLET",
    "E0159": "CAPSULE",
    "E0161": "SOFTGEL",
    "E0176": "GUMMY",
    "E0162": "POWDER",
    "E0165": "LIQUID",
}
MAX_CANDIDATES_PER_BRAND = 2
MIN_VITAMIN_MINERAL_INGREDIENTS = 8
MAX_PRODUCTS = 100
MIN_MAPPED_NUTRIENTS = 5

# Product or brand names that point at a non-adult or special population are not selected.
EXCLUDED_NAME_PATTERN = re.compile(
    r"\b(kid|kids|child|children|children's|teen|teens|baby|babies|infant|toddler|prenatal|"
    r"postnatal|pregnan\w*|lactation|pet|pets|dog|dogs|cat|cats)\b",
    re.IGNORECASE,
)


# ---------------------------------------------------------------------------
# Cache helpers


def cache_paths(cache: Path, kind: str, name: str) -> tuple[Path, Path]:
    folder = cache / kind
    return folder / f"{name}.json", folder / f"{name}.meta.json"


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_bytes_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    tmp.write_bytes(data)
    tmp.replace(path)


def search_name(form_code: str, offset: int) -> str:
    return f"search-{form_code}-from{offset}"


def search_query(form_code: str, offset: int) -> str:
    params = dict(SEARCH_FILTERS)
    params.update(
        {
            "supplement_form": form_code,
            "sort_by": "entryDate",
            "sort_order": "desc",
            "from": str(offset),
            "size": str(SEARCH_PAGE_SIZE),
        }
    )
    return "search-filter?" + urllib.parse.urlencode(params)


# ---------------------------------------------------------------------------
# Fetch step


class Fetcher:
    def __init__(self, cache: Path) -> None:
        self.cache = cache
        self.requests_made = 0
        self.last_request = 0.0

    def _get(self, url: str) -> bytes:
        wait = REQUEST_PAUSE_SECONDS - (time.monotonic() - self.last_request)
        if wait > 0:
            time.sleep(wait)
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
        self.requests_made += 1
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        finally:
            self.last_request = time.monotonic()

    def fetch(self, kind: str, name: str, relative: str, *, expect_json: bool = True) -> Path:
        suffix = ".json" if expect_json else ".html"
        body_path = self.cache / kind / f"{name}{suffix}"
        meta_path = self.cache / kind / f"{name}.meta.json"
        if body_path.exists() and meta_path.exists():
            return body_path
        errors = []
        for attempt in range(MAX_ATTEMPTS):
            base = API_BASES[attempt % len(API_BASES)] if expect_json else SPEC_URL
            url = base + relative if expect_json else SPEC_URL
            try:
                data = self._get(url)
                if expect_json:
                    json.loads(data.decode("utf-8"))
            except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
                errors.append(f"{url}: {error}")
                time.sleep(REQUEST_PAUSE_SECONDS * (attempt + 2))
                continue
            retrieved = dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
            write_bytes_atomic(body_path, data)
            meta = {"api_base": base if expect_json else SPEC_URL, "retrieved_at": retrieved, "url": url}
            write_bytes_atomic(meta_path, (json.dumps(meta, indent=2, sort_keys=True) + "\n").encode("utf-8"))
            return body_path
        raise RuntimeError("giving up after %d attempts:\n  %s" % (MAX_ATTEMPTS, "\n  ".join(errors)))


def run_fetch(cache: Path) -> int:
    cache.mkdir(parents=True, exist_ok=True)
    fetcher = Fetcher(cache)
    try:
        fetcher.fetch("spec", "api-spec", "", expect_json=False)
        for form_code, _, _ in FORMS:
            fetcher.fetch("search", search_name(form_code, 0), search_query(form_code, 0))
        candidates = select_candidates(cache)
        print(f"candidates: {len(candidates)}")
        for index, candidate in enumerate(candidates, start=1):
            fetcher.fetch("label", candidate["id"], f"label/{candidate['id']}")
            if index % 20 == 0:
                print(f"  labels cached: {index}/{len(candidates)} (requests this run: {fetcher.requests_made})")
    except RuntimeError as error:
        print(f"fetch stopped: {error}", file=sys.stderr)
        print(f"http requests this run: {fetcher.requests_made}", file=sys.stderr)
        return 2
    print(f"http requests this run: {fetcher.requests_made}")
    return 0


# ---------------------------------------------------------------------------
# Candidate selection (shared by fetch and convert, uses only the cached search responses)


def select_candidates(cache: Path) -> list[dict]:
    chosen: list[dict] = []
    brand_count: Counter = Counter()
    seen_names: set[tuple[str, str]] = set()
    seen_ids: set[str] = set()
    for form_code, dosage_form, quota in FORMS:
        body, _ = cache_paths(cache, "search", search_name(form_code, 0))
        if not body.exists():
            continue
        hits = read_json(body).get("hits", [])
        eligible = []
        for hit in hits:
            source = hit.get("_source", {})
            label_id = str(hit.get("_id", ""))
            if not label_id.isdigit() or label_id in seen_ids:
                continue
            if source.get("offMarket") != 0:
                continue
            groups = {group.get("langualCode") for group in source.get("userGroups") or []}
            if groups != {"P0250"}:
                continue
            state = (source.get("physicalState") or {}).get("langualCode")
            if LANGUAL_TO_DOSAGE_FORM.get(state) != dosage_form:
                continue
            name = (source.get("fullName") or "").strip()
            brand = (source.get("brandName") or "").strip()
            if not name or not brand or EXCLUDED_NAME_PATTERN.search(name) or EXCLUDED_NAME_PATTERN.search(brand):
                continue
            vm = sum(
                1
                for ingredient in source.get("allIngredients") or []
                if ingredient.get("category") in ("vitamin", "mineral")
            )
            if vm < MIN_VITAMIN_MINERAL_INGREDIENTS:
                continue
            eligible.append(
                {"id": label_id, "brand": brand, "name": name, "entry_date": source.get("entryDate") or "",
                 "dosage_form": dosage_form}
            )
        eligible.sort(key=lambda item: (item["entry_date"], int(item["id"])), reverse=True)
        # Round robin over brands so that one brand cannot fill the quota of a form.
        by_brand: dict[str, list[dict]] = {}
        for item in eligible:
            by_brand.setdefault(item["brand"].casefold(), []).append(item)
        brand_order = sorted(by_brand, key=lambda key: (-len(by_brand[key]), key))
        taken = 0
        while taken < quota and any(by_brand.values()):
            progressed = False
            for key in brand_order:
                if taken >= quota:
                    break
                queue = by_brand[key]
                while queue:
                    item = queue.pop(0)
                    name_key = (key, item["name"].casefold())
                    if brand_count[key] >= MAX_CANDIDATES_PER_BRAND:
                        queue.clear()
                        break
                    if name_key in seen_names:
                        continue
                    chosen.append(item)
                    seen_names.add(name_key)
                    seen_ids.add(item["id"])
                    brand_count[key] += 1
                    taken += 1
                    progressed = True
                    break
            if not progressed:
                break
    return chosen


# ---------------------------------------------------------------------------
# Registries and mapping tables

NUTRIENT_CODES = frozenset(
    """VIT_A VIT_C VIT_D VIT_E VIT_K VIT_B1 VIT_B2 VIT_B3 VIT_B5 VIT_B6 VIT_B7 VIT_B9 VIT_B12 CHOLINE
    CALCIUM MAGNESIUM ZINC IRON SELENIUM COPPER MANGANESE CHROMIUM MOLYBDENUM IODINE POTASSIUM
    PHOSPHORUS CHLORIDE FLUORIDE COQ10 ALPHA_LIPOIC_ACID PQQ NADH LUTEIN ZEAXANTHIN LYCOPENE
    OMEGA3_EPA OMEGA3_DHA INOSITOL""".split()
)
FORM_CODES = frozenset(
    """RETINOL RETINYL_ACETATE RETINYL_PALMITATE BETA_CAROTENE D2_ERGOCALCIFEROL D3_CHOLECALCIFEROL
    D_ALPHA_TOCOPHEROL DL_ALPHA_TOCOPHEROL NICOTINIC_ACID NICOTINAMIDE FOLIC_ACID METHYLFOLATE
    CYANOCOBALAMIN METHYLCOBALAMIN MAGNESIUM_CITRATE MAGNESIUM_OXIDE MAGNESIUM_BISGLYCINATE
    ZINC_PICOLINATE ZINC_GLUCONATE UBIQUINONE UBIQUINOL""".split()
)
PREFORMED_VITAMIN_A = frozenset({"RETINOL", "RETINYL_ACETATE", "RETINYL_PALMITATE"})
FOLATE_UL_FORMS = frozenset({"FOLIC_ACID", "METHYLFOLATE"})
IU_NUTRIENTS = frozenset({"VIT_A", "VIT_D", "VIT_E"})
UNKNOWN_FORM = "UNKNOWN"
# Form texts that only qualify another form (for example "Calcium Salt" next to methylfolate).
IGNORED_FORM = "IGNORE"
AMOUNT_STEP = Decimal("0.0001")
MONEY_STEP = Decimal("0.01")


def normalise_name(text: str) -> str:
    text = (text or "").casefold().replace("µ", "u").replace("μ", "u")
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def parse_nutrient_set(spec: str) -> frozenset[str]:
    """`*` = every nutrient, `A|B` = only these, `!A|!B` = every nutrient except these."""
    if spec == "*":
        return NUTRIENT_CODES
    items = spec.split("|")
    if all(item.startswith("!") for item in items):
        excluded = frozenset(item[1:] for item in items)
        chosen = NUTRIENT_CODES - excluded
    elif not any(item.startswith("!") for item in items):
        excluded = chosen = frozenset(items)
    else:
        raise ValueError(f"unit-map.csv: cannot mix included and excluded nutrients in {spec}")
    if not excluded <= NUTRIENT_CODES:
        raise ValueError(f"unit-map.csv: unknown nutrient in {spec}")
    return chosen


class Maps:
    def __init__(self, directory: Path) -> None:
        self.by_unii: dict[str, str] = {}
        self.by_name: dict[str, str] = {}
        self.forms: dict[str, tuple[str, str]] = {}
        self.units: dict[str, tuple[str, frozenset[str], str, str]] = {}
        self.serving_units: dict[str, str] = {}
        for row in self._rows(directory / "nutrient-map.csv", ["match", "key", "nutrient_code", "note"]):
            code = row["nutrient_code"]
            if code not in NUTRIENT_CODES:
                raise ValueError(f"nutrient-map.csv: {code} is not in the nutrient registry")
            if row["match"] == "unii":
                self._put(self.by_unii, row["key"].strip().upper(), code, "nutrient-map.csv unii")
            elif row["match"] == "name":
                self._put(self.by_name, normalise_name(row["key"]), code, "nutrient-map.csv name")
            else:
                raise ValueError(f"nutrient-map.csv: unknown match type {row['match']}")
        for row in self._rows(directory / "form-map.csv", ["form_name", "nutrient_code", "form_code", "note"]):
            if row["form_code"] == IGNORED_FORM:
                if row["nutrient_code"] != "*":
                    raise ValueError(f"form-map.csv: {IGNORED_FORM} rows use nutrient_code *")
            elif row["form_code"] not in FORM_CODES:
                raise ValueError(f"form-map.csv: {row['form_code']} is not in the form registry")
            elif row["nutrient_code"] not in NUTRIENT_CODES:
                raise ValueError(f"form-map.csv: {row['nutrient_code']} is not in the nutrient registry")
            self._put(self.forms, normalise_name(row["form_name"]), (row["nutrient_code"], row["form_code"]),
                      "form-map.csv")
        for row in self._rows(directory / "unit-map.csv", ["dsld_unit", "unit", "nutrients", "note"]):
            nutrients = parse_nutrient_set(row["nutrients"])
            if row["unit"] not in ("mg", "mcg", "g", "IU"):
                raise ValueError(f"unit-map.csv: unit {row['unit']} is not an accepted unit")
            if row["dsld_unit"] in self.units:
                raise ValueError(f"unit-map.csv: duplicate unit {row['dsld_unit']}")
            self.units[row["dsld_unit"]] = (row["unit"], nutrients, row["note"], row["nutrients"])
        for row in self._rows(directory / "serving-unit-map.csv", ["dsld_unit", "counted_as", "note"]):
            self._put(self.serving_units, row["dsld_unit"], row["counted_as"], "serving-unit-map.csv")

    @staticmethod
    def _rows(path: Path, header: list[str]) -> list[dict]:
        with path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames != header:
                raise ValueError(f"{path.name}: header must be {','.join(header)}")
            return list(reader)

    @staticmethod
    def _put(target: dict, key: str, value: str, where: str) -> None:
        if key in target and target[key] != value:
            raise ValueError(f"{where}: conflicting entries for {key}")
        target[key] = value

    def nutrient_for(self, row: dict) -> str | None:
        unii = (row.get("uniiCode") or "").strip().upper()
        if len(unii) == 10 and unii in self.by_unii:
            return self.by_unii[unii]
        for text in (row.get("name"), row.get("ingredientGroup")):
            key = normalise_name(text or "")
            if key and key in self.by_name:
                return self.by_name[key]
        return None

    def form_for(self, text: str, nutrient: str) -> str | None:
        """Registry form code for a label form text, only when the form belongs to the row's nutrient."""
        entry = self.forms.get(normalise_name(text))
        if entry is None:
            return None
        form_nutrient, code = entry
        if code == IGNORED_FORM or form_nutrient == nutrient:
            return code
        return None


# ---------------------------------------------------------------------------
# Convert step: label -> product


class SkipLabel(Exception):
    def __init__(self, reason: str, detail: str = "") -> None:
        super().__init__(reason)
        self.reason = reason
        self.detail = detail


def to_decimal(value) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value))
    except ArithmeticError:
        return None


def json_number(value: Decimal):
    value = value.normalize()
    if value == value.to_integral_value():
        return int(value)
    return float(format(value, "f"))


MASS_IN_MCG = {"mcg": Decimal(1), "mg": Decimal(1000), "g": Decimal(1000000)}


def convert_mass(amount: Decimal, from_unit: str, to_unit: str) -> Decimal | None:
    """Convert a mass amount between mcg, mg and g (None for IU or other units)."""
    if from_unit not in MASS_IN_MCG or to_unit not in MASS_IN_MCG:
        return None
    return (amount * MASS_IN_MCG[from_unit] / MASS_IN_MCG[to_unit]).quantize(AMOUNT_STEP, rounding=ROUND_HALF_UP)


def quantity_of(row: dict) -> dict | None:
    rows = row.get("quantity") or []
    first = [q for q in rows if q.get("servingSizeOrder") in (1, None)]
    return first[0] if first else (rows[0] if rows else None)


def is_blend(row: dict) -> bool:
    name = normalise_name(row.get("name") or "")
    return row.get("category") == "blend" or "blend" in name.split() or "proprietary" in name.split()


def count_rows(rows: list[dict]) -> int:
    return sum(1 + count_rows(row.get("nestedRows") or []) for row in rows)


class LabelConverter:
    def __init__(self, maps: Maps, unit_seen: Counter, unmapped_names: Counter) -> None:
        self.maps = maps
        self.unit_seen = unit_seen
        self.unmapped_names = unmapped_names
        self.serving_quantity: Decimal | None = None

    # -- rows ---------------------------------------------------------------

    def _form_entries(self, row: dict, nutrient: str) -> list[dict]:
        entries = []
        for form in row.get("forms") or []:
            name = (form.get("name") or "").strip()
            if name:
                entries.append({"name": name, "code": self.maps.form_for(name, nutrient),
                                "percent": to_decimal(form.get("percent"))})
        if not entries:
            name = (row.get("name") or "").strip()
            code = self.maps.form_for(name, nutrient)
            if code and code != IGNORED_FORM:
                entries.append({"name": name, "code": code, "percent": None, "from_row_name": True})
        return entries

    def _amount(self, row: dict, nutrient: str, stats: Counter) -> tuple[Decimal, str, str] | None:
        quantity = quantity_of(row)
        raw_unit = (quantity or {}).get("unit") or ""
        amount = to_decimal((quantity or {}).get("quantity"))
        if quantity is None or amount is None or amount <= 0:
            stats["skipped:NO_AMOUNT"] += 1
            return None
        basis = to_decimal(quantity.get("servingSizeQuantity"))
        if basis is not None and self.serving_quantity is not None and basis != self.serving_quantity:
            raise SkipLabel("AMOUNT_BASIS_DIFFERS",
                            f"{row.get('name')}: amount stated per {json_number(basis)} units, serving is "
                            f"{json_number(self.serving_quantity)}")
        if quantity.get("operator") != "=":
            stats["skipped:NON_EXACT_AMOUNT"] += 1
            return None
        mapping = self.maps.units.get(raw_unit)
        if mapping is None or nutrient not in mapping[1]:
            stats["skipped:UNIT_UNSUPPORTED"] += 1
            return None
        unit = mapping[0]
        if unit == "IU" and nutrient not in IU_NUTRIENTS:
            stats["skipped:IU_NOT_ACCEPTED"] += 1
            return None
        amount = amount.quantize(AMOUNT_STEP, rounding=ROUND_HALF_UP)
        if amount <= 0:
            stats["skipped:NO_AMOUNT"] += 1
            return None
        return amount, unit, raw_unit

    def _count_units(self, row: dict) -> None:
        self.unit_seen[((quantity_of(row) or {}).get("unit") or "(none)").strip()] += 1
        for child in row.get("nestedRows") or []:
            self._count_units(child)

    def _walk(self, rows: list[dict], parts: dict[str, list[dict]], stats: Counter) -> None:
        for row in rows:
            nested = row.get("nestedRows") or []
            self.unit_seen[((quantity_of(row) or {}).get("unit") or "(none)").strip()] += 1
            if is_blend(row):
                for child in nested:
                    self._count_units(child)
                stats["skipped:PROPRIETARY_BLEND"] += 1 + count_rows(nested)
                continue
            nutrient = self.maps.nutrient_for(row)
            if nutrient is None:
                stats["unmapped"] += 1
                self.unmapped_names[(row.get("name") or "").strip() or "(no name)"] += 1
                self._walk(nested, parts, stats)
                continue
            measured = self._amount(row, nutrient, stats)
            if measured is None:
                self._walk(nested, parts, stats)
                continue
            amount, unit, raw_unit = measured
            part = {"amount": amount, "unit": unit, "raw_unit": raw_unit, "forms": self._form_entries(row, nutrient),
                    "splits": [], "name": (row.get("name") or "").strip()}
            others = []
            for child in nested:
                if not is_blend(child) and self.maps.nutrient_for(child) == nutrient:
                    child_measured = self._amount(child, nutrient, Counter())
                    if child_measured is not None:
                        part["splits"].append({"amount": child_measured[0], "unit": child_measured[1],
                                               "raw_unit": child_measured[2], "forms": self._form_entries(child, nutrient),
                                               "name": (child.get("name") or "").strip()})
                        stats["split"] += 1
                        self._count_units(child)
                        continue
                others.append(child)
            parts.setdefault(nutrient, []).append(part)
            stats["mapped"] += 1
            self._walk(others, parts, stats)

    # -- nutrients ----------------------------------------------------------

    @staticmethod
    def _codes(forms: list[dict]) -> set[str]:
        return {entry["code"] for entry in forms if entry["code"] and entry["code"] != IGNORED_FORM}

    @staticmethod
    def _label(forms: list[dict]) -> str | None:
        text = ", ".join(dict.fromkeys(entry["name"] for entry in forms))
        return text[:200] if text else None

    @staticmethod
    def _single(codes: set[str], forms: list[dict]) -> str:
        if len(codes) == 1 and all(entry["code"] for entry in forms):
            return next(iter(codes))
        return UNKNOWN_FORM

    def _vitamin_a_part(self, part: dict, label_id: str) -> tuple[Decimal | None, set[str], bool]:
        """Return (UL-relevant preformed amount or None when unknown, preformed codes, beta-carotene only)."""
        forms = part["forms"] + [entry for split in part["splits"] for entry in split["forms"]]
        codes = self._codes(forms)
        if part["unit"] == "IU":
            if not forms or not all(entry["code"] in PREFORMED_VITAMIN_A for entry in forms):
                raise SkipLabel("UNSUPPORTED_IU_FORM", f"VIT_A {json_number(part['amount'])} IU, forms: {self._label(forms) or 'none'}")
            return None, codes, False
        preformed = codes & PREFORMED_VITAMIN_A
        has_bc = "BETA_CAROTENE" in codes
        if part["splits"]:
            pre_sum = sum((s["amount"] for s in part["splits"] if self._codes(s["forms"]) & PREFORMED_VITAMIN_A
                           and s["raw_unit"] == part["raw_unit"]), Decimal(0))
            bc_sum = sum((s["amount"] for s in part["splits"] if self._codes(s["forms"]) == {"BETA_CAROTENE"}
                          and s["raw_unit"] == part["raw_unit"]), Decimal(0))
            # The UL-relevant amount is reduced only by a share explicitly stated as beta-carotene
            # (exempt); a preformed subtotal alone does not account for the rest of the total.
            if pre_sum > 0 and bc_sum > 0 and pre_sum + bc_sum == part["amount"]:
                return pre_sum, preformed, False
            if bc_sum > 0 and part["amount"] - bc_sum >= 0:
                return part["amount"] - bc_sum, preformed, part["amount"] == bc_sum
        if preformed and has_bc:
            bc_entries = [e for e in part["forms"] if e["code"] == "BETA_CAROTENE"]
            other = [e for e in part["forms"] if e["code"] not in PREFORMED_VITAMIN_A | {"BETA_CAROTENE"}]
            if not other and bc_entries and all(e["percent"] is not None for e in bc_entries):
                share = 100 - sum(e["percent"] for e in bc_entries)
                if 0 <= share <= 100:
                    return (part["amount"] * share / 100).quantize(AMOUNT_STEP, rounding=ROUND_HALF_UP), preformed, False
            return None, preformed, False
        if has_bc and codes == {"BETA_CAROTENE"} and all(entry["code"] for entry in forms):
            return Decimal(0), set(), True
        return None, preformed, False

    def _nutrient(self, code: str, parts: list[dict], label_id: str, stats: Counter, notes: list[str]) -> dict | None:
        # Identical repeated rows (same amount, unit and forms, no nested split) are one declaration.
        unique, seen = [], set()
        for part in parts:
            key = (part["amount"], part["raw_unit"], tuple(e["name"] for e in part["forms"]), bool(part["splits"]))
            if key in seen and not part["splits"]:
                stats["mapped"] -= 1
                stats["skipped:DUPLICATE_ROW"] += 1
                notes.append(f"{code}: repeated identical row ({json_number(part['amount'])} {part['raw_unit']}) used once")
                continue
            seen.add(key)
            unique.append(part)
        parts = unique
        if code == "VIT_B9":
            return self._folate(parts, stats, notes)
        raw_units = {part["raw_unit"] for part in parts}
        if len(raw_units) > 1:
            stats["skipped:CONFLICTING_UNITS"] += len(parts)
            stats["mapped"] -= len(parts)
            notes.append(f"{code} dropped: rows in different units {sorted(raw_units)}")
            return None
        total = sum((part["amount"] for part in parts), Decimal(0))
        if len(parts) > 1:
            notes.append(f"{code}: {len(parts)} rows in {parts[0]['raw_unit']} summed to {json_number(total)}")
        forms = [entry for part in parts for entry in part["forms"] + [e for s in part["splits"] for e in s["forms"]]]
        result = {"nutrient_code": code, "amount_per_serving": total, "unit": parts[0]["unit"],
                  "form_label": self._label(forms), "ul_amount_per_serving": None}
        if code == "VIT_A":
            ul_total = Decimal(0)
            preformed: set[str] = set()
            all_bc = True
            for part in parts:
                ul, pre_codes, bc_only = self._vitamin_a_part(part, label_id)
                preformed |= pre_codes
                all_bc = all_bc and bc_only
                ul_total += part["amount"] if ul is None else ul
            if all_bc:
                result["form_code"] = "BETA_CAROTENE"
            else:
                result["form_code"] = next(iter(preformed)) if len(preformed) == 1 else UNKNOWN_FORM
                if ul_total != total:
                    result["ul_amount_per_serving"] = ul_total
        else:
            result["form_code"] = self._single(self._codes(forms), forms)
        return result

    def _folate(self, parts: list[dict], stats: Counter, notes: list[str]) -> dict | None:
        dfe = [part for part in parts if part["raw_unit"] == "mcg DFE"]
        plain = [part for part in parts if part["raw_unit"] != "mcg DFE"]
        if len(dfe) > 1 or (not dfe and len({p["raw_unit"] for p in plain}) > 1):
            stats["skipped:CONFLICTING_UNITS"] += len(parts)
            stats["mapped"] -= len(parts)
            notes.append("VIT_B9 dropped: rows in conflicting units")
            return None
        total_part = None
        if dfe:
            total_part = dfe[0]
        elif len(plain) == 1 and plain[0]["splits"]:
            # A folate total in plain mcg that nests its folic acid / methylfolate amount.
            total_part, plain = plain[0], []
        if total_part is not None:
            # Stated split: nested rows of the total, or separate folic acid / methylfolate rows in mcg.
            split_parts = [s for s in total_part["splits"] if s["raw_unit"] != "mcg DFE"] + plain
            forms = total_part["forms"] + [e for s in split_parts for e in s["forms"]]
            result = {"nutrient_code": "VIT_B9", "amount_per_serving": total_part["amount"],
                      "unit": total_part["unit"], "form_label": self._label(forms), "ul_amount_per_serving": None}
            ul_parts = [s for s in split_parts if self._codes(s["forms"]) and self._codes(s["forms"]) <= FOLATE_UL_FORMS
                        and all(e["code"] for e in s["forms"])
                        and convert_mass(s["amount"], s["unit"], total_part["unit"]) is not None]
            if ul_parts:
                ul = sum((convert_mass(s["amount"], s["unit"], total_part["unit"]) for s in ul_parts), Decimal(0))
                codes = set().union(*(self._codes(s["forms"]) for s in ul_parts))
                if 0 <= ul < total_part["amount"]:
                    result["ul_amount_per_serving"] = ul
                    result["form_code"] = next(iter(codes)) if len(codes) == 1 else UNKNOWN_FORM
                    if plain:
                        stats["split"] += len(plain)
                        stats["mapped"] -= len(plain)
                    return result
                notes.append(f"VIT_B9 split {ul} not below the DFE total {total_part['amount']}; left unset")
            if plain:
                stats["skipped:CONFLICTING_UNITS"] += len(plain)
                stats["mapped"] -= len(plain)
                notes.append("VIT_B9 rows in mcg next to a DFE total without a usable split were not used")
            result["form_code"] = self._single(self._codes(forms), forms)
            return result
        total = sum((part["amount"] for part in plain), Decimal(0))
        forms = [entry for part in plain for entry in part["forms"]]
        return {"nutrient_code": "VIT_B9", "amount_per_serving": total, "unit": plain[0]["unit"],
                "form_label": self._label(forms), "ul_amount_per_serving": None,
                "form_code": self._single(self._codes(forms), forms)}

    # -- product ------------------------------------------------------------

    def serving(self, label: dict) -> tuple[int, int, Decimal]:
        sizes = label.get("servingSizes") or []
        if len(sizes) != 1:
            raise SkipLabel("SERVING_SIZE_NOT_SINGLE", f"{len(sizes)} serving sizes stated")
        size = sizes[0]
        unit = (size.get("unit") or "").strip()
        counted_as = self.maps.serving_units.get(unit)
        if counted_as is None:
            raise SkipLabel("SERVING_UNIT_UNSUPPORTED", f"serving unit {unit!r}")
        low, high = to_decimal(size.get("minQuantity")), to_decimal(size.get("maxQuantity"))
        if low is None or low != high:
            raise SkipLabel("SERVING_SIZE_RANGE", f"serving quantity {low} to {high}")
        if low != low.to_integral_value() or low < 1:
            raise SkipLabel("SERVING_SIZE_NOT_INTEGER", f"serving quantity {low} {unit}")
        per_serving = int(low)
        day_low, day_high = to_decimal(size.get("minDailyServings")), to_decimal(size.get("maxDailyServings"))
        if day_low is None or day_high is None:
            raise SkipLabel("DAILY_SERVINGS_MISSING", "daily servings not stated")
        if day_low != day_high:
            raise SkipLabel("DAILY_SERVINGS_RANGE", f"daily servings {day_low} to {day_high}")
        if day_low <= 0 or day_low > Decimal("99.99") or day_low != day_low.quantize(MONEY_STEP):
            raise SkipLabel("DAILY_SERVINGS_INVALID", f"daily servings {day_low}")
        container = None
        for content in label.get("netContents") or []:
            quantity = to_decimal(content.get("quantity"))
            if (content.get("unit") or "").strip() == unit and quantity and quantity == quantity.to_integral_value():
                container = int(quantity)
                break
        servings = to_decimal(str(label.get("servingsPerContainer") or "").strip() or None)
        if servings is not None and servings <= 0:
            servings = None
        if container is not None and servings is not None:
            # Net contents and servings per container must agree within 10 % (servings are often rounded).
            expected = servings * per_serving
            if abs(expected - container) > Decimal("0.1") * container:
                raise SkipLabel("CONTAINER_COUNT_INCONSISTENT",
                                f"net contents {container} {unit}, {servings} servings of {per_serving}")
        if container is None and servings is not None:
            units = servings * per_serving
            if units == units.to_integral_value():
                container = int(units)
        if container is None:
            raise SkipLabel("CONTAINER_COUNT_UNKNOWN", f"servingsPerContainer {label.get('servingsPerContainer')!r}")
        if container < per_serving:
            raise SkipLabel("CONTAINER_COUNT_INVALID", f"{container} units per container, {per_serving} per serving")
        return container, per_serving, day_low

    def convert(self, label: dict, dosage_form: str) -> tuple[dict, Counter, list[str]]:
        label_id = str(label.get("id"))
        if label.get("offMarket") not in (0, None):
            raise SkipLabel("OFF_MARKET", f"offMarket={label.get('offMarket')}")
        state = (label.get("physicalState") or {}).get("langualCode")
        if LANGUAL_TO_DOSAGE_FORM.get(state) != dosage_form:
            raise SkipLabel("DOSAGE_FORM_UNSUPPORTED", f"physical state {state}")
        brand = (label.get("brandName") or "").strip()
        name = (label.get("fullName") or "").strip()
        if not brand or not name:
            raise SkipLabel("NAME_MISSING")
        container, per_serving, per_day = self.serving(label)
        self.serving_quantity = Decimal(per_serving)
        stats: Counter = Counter()
        notes: list[str] = []
        parts: dict[str, list[dict]] = {}
        self._walk(label.get("ingredientRows") or [], parts, stats)
        nutrients = []
        for code in sorted(parts):
            item = self._nutrient(code, parts[code], label_id, stats, notes)
            if item is not None:
                nutrients.append(item)
        if len(nutrients) < MIN_MAPPED_NUTRIENTS:
            raise SkipLabel("TOO_FEW_MAPPED_NUTRIENTS", f"{len(nutrients)} mapped nutrients")
        product = {
            "code": f"DSLD-{label_id}",
            "brand": brand[:200],
            "name": name[:300],
            "dosage_form": dosage_form,
            "units_per_container": container,
            "units_per_serving": per_serving,
            "servings_per_day": json_number(per_day),
            "source": "DSLD",
            "source_ref": label_id,
            "source_entry_date": label.get("entryDate"),
            "synthetic": False,
            "nutrients": [self._render_nutrient(item) for item in nutrients],
            "availability": [{"country_code": "US", "status": "AVAILABLE"}],
        }
        gtin = gtin_of(label.get("upcSku"))
        if gtin:
            product["gtin"] = gtin
        return product, stats, notes

    @staticmethod
    def _render_nutrient(item: dict) -> dict:
        rendered = {
            "nutrient_code": item["nutrient_code"],
            "amount_per_serving": json_number(item["amount_per_serving"]),
            "unit": item["unit"],
            "form_code": item["form_code"],
        }
        if item.get("form_label"):
            rendered["form_label"] = item["form_label"]
        if item.get("ul_amount_per_serving") is not None:
            rendered["ul_amount_per_serving"] = json_number(item["ul_amount_per_serving"])
        return rendered


def gtin_of(upc) -> str | None:
    digits = re.sub(r"\D", "", str(upc or ""))
    if len(digits) not in (8, 12, 13, 14):
        return None
    body, check = digits[:-1], int(digits[-1])
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(body)))
    return digits if (10 - total % 10) % 10 == check else None


# ---------------------------------------------------------------------------
# Structural checks mirroring contracts/schemas/import-products.schema.json

CODE_PATTERN = re.compile(r"^[A-Z0-9][A-Z0-9-]{1,39}$")
REGISTRY_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,39}$")
DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def schema_problems(product: dict) -> list[str]:
    problems = []
    if not CODE_PATTERN.match(product["code"]):
        problems.append("code pattern")
    for key, limit in (("brand", 200), ("name", 300)):
        if not 1 <= len(product[key]) <= limit:
            problems.append(f"{key} length")
    for key in ("units_per_container", "units_per_serving"):
        if not 1 <= product[key] <= 2147483647:
            problems.append(key)
    if not 0 < product["servings_per_day"] <= 99.99:
        problems.append("servings_per_day")
    if not 1 <= len(product["source_ref"]) <= 100:
        problems.append("source_ref length")
    entry = product.get("source_entry_date") or ""
    try:
        if not DATE_PATTERN.match(entry):
            raise ValueError(entry)
        dt.date.fromisoformat(entry)
    except ValueError:
        problems.append("source_entry_date")
    seen = set()
    for item in product["nutrients"]:
        code = item["nutrient_code"]
        if code in seen or code not in NUTRIENT_CODES or not REGISTRY_PATTERN.match(code):
            problems.append(f"nutrient {code}")
        seen.add(code)
        if not 0 < item["amount_per_serving"] <= 9999999999.9999:
            problems.append(f"{code} amount")
        if item["form_code"] != UNKNOWN_FORM and item["form_code"] not in FORM_CODES:
            problems.append(f"{code} form")
        if len(item.get("form_label") or "") > 200:
            problems.append(f"{code} form_label")
        ul = item.get("ul_amount_per_serving")
        if ul is not None and not 0 <= ul <= item["amount_per_serving"]:
            problems.append(f"{code} ul amount")
        if item["unit"] == "IU" and code not in IU_NUTRIENTS:
            problems.append(f"{code} IU")
        if code == "VIT_A" and item["unit"] == "IU" and item["form_code"] not in PREFORMED_VITAMIN_A:
            problems.append("unsupported_iu_form")
    if product["units_per_container"] < product["units_per_serving"]:
        problems.append("units_per_container < units_per_serving")
    return problems


# ---------------------------------------------------------------------------
# Demo prices (synthetic, deterministic)

PRICE_RULE = (
    "Demo prices, not real prices. h = first 8 hex digits of sha256(product code) as an integer; "
    "days = units_per_container / (units_per_serving * servings_per_day); "
    "Demo Store price = 4.00 + 0.08 * days + 0.25 * mapped nutrients + (h mod 200) / 100 USD, "
    "rounded half up to cents, always in stock; "
    "Demo Market is listed when h mod 3 != 0, at the Demo Store price times (1 + ((h div 256) mod 15) / 100), "
    "rounded half up to cents, out of stock when (h div 65536) mod 7 == 0; "
    "checked_at is midnight UTC of the day the label was retrieved."
)


def demo_offers(product: dict, checked_day: str) -> list[dict]:
    code = product["code"]
    h = int(hashlib.sha256(code.encode("ascii")).hexdigest()[:8], 16)
    days = Decimal(product["units_per_container"]) / (
        Decimal(product["units_per_serving"]) * Decimal(str(product["servings_per_day"]))
    )
    store = (Decimal("4.00") + Decimal("0.08") * days + Decimal("0.25") * len(product["nutrients"])
             + Decimal(h % 200) / 100).quantize(MONEY_STEP, rounding=ROUND_HALF_UP)
    checked_at = f"{checked_day}T00:00:00Z"
    slug = code.lower()
    offers = [{"product_code": code, "country_code": "US", "retailer": "Demo Store", "price": format(store, "f"),
               "currency": "USD", "in_stock": "true", "url": f"https://demo-store.example.com/products/{slug}",
               "checked_at": checked_at}]
    if h % 3 != 0:
        market = (store * (1 + Decimal((h // 256) % 15) / 100)).quantize(MONEY_STEP, rounding=ROUND_HALF_UP)
        offers.append({"product_code": code, "country_code": "US", "retailer": "Demo Market",
                       "price": format(market, "f"), "currency": "USD",
                       "in_stock": "false" if (h // 65536) % 7 == 0 else "true",
                       "url": f"https://demo-market.example.org/item/{slug}", "checked_at": checked_at})
    return offers


OFFER_COLUMNS = ["product_code", "country_code", "retailer", "price", "currency", "in_stock", "url", "checked_at"]


# ---------------------------------------------------------------------------
# Convert step: whole snapshot


def read_licence(cache: Path) -> dict:
    body = cache / "spec" / "api-spec.html"
    meta = read_json(cache / "spec" / "api-spec.meta.json")
    text = re.sub(r"<[^>]+>", "\n", body.read_text(encoding="utf-8"))
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    statement = next((line for line in lines if "CC0" in line), None)
    licence_url = next((line for line in lines if "creativecommons.org" in line), None)
    version = next((line.split(":", 1)[1].strip() for line in lines if line.startswith("Version:")), None)
    if statement is None:
        raise SystemExit("licence statement not found in the cached API specification; refusing to convert")
    return {"statement": statement, "url": licence_url, "read_from": meta["url"],
            "read_at": meta["retrieved_at"], "api_specification_version": version}


def dump_json(data) -> str:
    return json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def check_reference_registries(maps: Maps) -> None:
    """Cross-check the built-in registries and the form map against data/reference when present."""
    reference = REPO_ROOT / "data" / "reference"
    nutrients_csv, forms_csv = reference / "nutrients.csv", reference / "forms.csv"
    if nutrients_csv.exists():
        with nutrients_csv.open("r", encoding="utf-8", newline="") as handle:
            codes = {row["code"] for row in csv.DictReader(handle)}
        if codes != NUTRIENT_CODES:
            raise SystemExit(f"nutrient registry differs from {nutrients_csv.name}: {sorted(codes ^ NUTRIENT_CODES)}")
    if forms_csv.exists():
        with forms_csv.open("r", encoding="utf-8", newline="") as handle:
            forms = {row["code"]: row["nutrient_code"] for row in csv.DictReader(handle)}
        if set(forms) != FORM_CODES:
            raise SystemExit(f"form registry differs from {forms_csv.name}: {sorted(set(forms) ^ FORM_CODES)}")
        for name, (nutrient, code) in maps.forms.items():
            if code != IGNORED_FORM and forms[code] != nutrient:
                raise SystemExit(f"form-map.csv: {name} maps {code} to {nutrient}, {forms_csv.name} says {forms[code]}")


def run_convert(cache: Path, out: Path) -> int:
    maps = Maps(SCRIPT_DIR)
    check_reference_registries(maps)
    licence = read_licence(cache)
    candidates = select_candidates(cache)
    unit_seen: Counter = Counter()
    unmapped_names: Counter = Counter()
    converter = LabelConverter(maps, unit_seen, unmapped_names)
    skipped: list[dict] = []
    converted: list[dict] = []
    labels_read = 0
    for order, candidate in enumerate(candidates):
        label_path, meta_path = cache_paths(cache, "label", candidate["id"])
        base = {"label_id": candidate["id"], "brand": candidate["brand"], "name": candidate["name"]}
        if not label_path.exists() or not meta_path.exists():
            skipped.append(base | {"reason": "NOT_IN_CACHE", "detail": "label was not fetched"})
            continue
        label = json.loads(label_path.read_text(encoding="utf-8"), parse_float=Decimal)
        meta = read_json(meta_path)
        labels_read += 1
        try:
            product, stats, notes = converter.convert(label, candidate["dosage_form"])
        except SkipLabel as skip:
            skipped.append(base | {"reason": skip.reason, "detail": skip.detail})
            continue
        problems = schema_problems(product)
        if problems:
            skipped.append(base | {"reason": "SCHEMA_INVALID", "detail": "; ".join(problems)})
            continue
        converted.append({"order": order, "product": product, "stats": stats, "notes": notes, "meta": meta,
                          "label": label})

    # Label versions: brand + name + productVersionCode, keep the latest entryDate (then the highest id).
    groups: dict[tuple, list[dict]] = {}
    for item in converted:
        label = item["label"]
        key = (item["product"]["brand"].casefold(), item["product"]["name"].casefold(),
               (label.get("productVersionCode") or "").strip().casefold())
        groups.setdefault(key, []).append(item)
    kept = []
    for key, items in groups.items():
        items.sort(key=lambda it: (it["label"].get("entryDate") or "", int(it["label"]["id"])), reverse=True)
        kept.append(items[0])
        for older in items[1:]:
            skipped.append({"label_id": str(older["label"]["id"]), "brand": older["product"]["brand"],
                            "name": older["product"]["name"], "reason": "DUPLICATE_LABEL_VERSION",
                            "detail": f"newer version {items[0]['label']['id']} kept"})
    kept.sort(key=lambda it: it["order"])
    for extra in kept[MAX_PRODUCTS:]:
        skipped.append({"label_id": str(extra["label"]["id"]), "brand": extra["product"]["brand"],
                        "name": extra["product"]["name"], "reason": "OVER_SELECTION_CAP",
                        "detail": f"only the first {MAX_PRODUCTS} products in selection order are written"})
    kept = sorted(kept[:MAX_PRODUCTS], key=lambda it: it["product"]["code"])

    products = [item["product"] for item in kept]
    document = {"schema_version": 1, "products": products}

    offers = []
    provenance_products = []
    for item in kept:
        meta, label, stats = item["meta"], item["label"], item["stats"]
        offers.extend(demo_offers(item["product"], meta["retrieved_at"][:10]))
        skipped_reasons = {k.split(":", 1)[1]: v for k, v in sorted(stats.items()) if k.startswith("skipped:") and v}
        provenance_products.append({
            "code": item["product"]["code"],
            "label_id": str(label["id"]),
            "source_url": meta["url"],
            "api_base": meta["api_base"],
            "retrieved_at": meta["retrieved_at"],
            "entry_date": label.get("entryDate"),
            "product_version_code": label.get("productVersionCode"),
            "licence": licence["statement"],
            "licence_url": licence["url"],
            "licence_read_from": licence["read_from"],
            "rows": {
                "mapped": stats["mapped"],
                "split_detail": stats["split"],
                "unmapped": stats["unmapped"],
                "skipped": sum(skipped_reasons.values()),
                "skipped_by_reason": skipped_reasons,
            },
            "notes": item["notes"],
        })
    offers.sort(key=lambda row: (row["product_code"], row["country_code"], row["retailer"]))

    searches = []
    for form_code, dosage_form, quota in FORMS:
        body, meta_path = cache_paths(cache, "search", search_name(form_code, 0))
        if body.exists():
            data = read_json(body)
            meta = read_json(meta_path)
            searches.append({"dosage_form": dosage_form, "url": meta["url"], "retrieved_at": meta["retrieved_at"],
                             "total_hits": data.get("stats", {}).get("count"), "hits_returned": len(data.get("hits", [])),
                             "candidate_quota": quota})

    skipped.sort(key=lambda s: (s["reason"], int(s["label_id"])))
    reasons = Counter(s["reason"] for s in skipped)
    brands = sorted({p["brand"] for p in products}, key=str.casefold)
    unit_rows = []
    for raw, count in sorted(unit_seen.items(), key=lambda kv: (-kv[1], kv[0])):
        mapping = maps.units.get(raw)
        unit_rows.append({"dsld_unit": raw, "rows": count,
                          "mapped_to": mapping[0] if mapping else None,
                          "nutrients": mapping[3] if mapping else None})
    unmapped_rows = [{"name": n, "rows": c} for n, c in sorted(unmapped_names.items(), key=lambda kv: (-kv[1], kv[0]))]
    retrieved = sorted(item["meta"]["retrieved_at"] for item in kept)
    provenance = {
        "source": {
            "name": "Dietary Supplement Label Database (DSLD), Office of Dietary Supplements, National Institutes of Health",
            "api_base": API_BASES[0],
            "api_mirror": API_BASES[1],
            "licence": licence,
        },
        "retrieval": {"first": retrieved[0] if retrieved else None, "last": retrieved[-1] if retrieved else None},
        "searches": searches,
        "summary": {
            "candidates": len(candidates),
            "labels_read": labels_read,
            "products_written": len(products),
            "offers_written": len(offers),
            "distinct_brands": len(brands),
            "skipped_by_reason": dict(sorted(reasons.items())),
            "dosage_forms": dict(sorted(Counter(p["dosage_form"] for p in products).items())),
        },
        "prices": {"synthetic": True, "currency": "USD", "rule": PRICE_RULE},
        "products": provenance_products,
        "skipped_labels": skipped,
        "unit_strings": unit_rows,
        "unmapped_ingredients": unmapped_rows,
    }

    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(OFFER_COLUMNS)
    for row in offers:
        writer.writerow([row[column] for column in OFFER_COLUMNS])

    write_text(out / "products.dsld.json", dump_json(document))
    write_text(out / "provenance.json", dump_json(provenance))
    write_text(out / "offers.us-demo.synthetic.csv", buffer.getvalue())

    print(f"candidates: {len(candidates)}, labels read: {labels_read}, products written: {len(products)}, "
          f"offers: {len(offers)}, distinct brands: {len(brands)}")
    print("skipped by reason: " + ", ".join(f"{k}={v}" for k, v in sorted(reasons.items())))
    print("dosage forms: " + ", ".join(f"{k}={v}" for k, v in provenance["summary"]["dosage_forms"].items()))
    print("unit strings (rows -> mapped):")
    for row in unit_rows:
        print(f"  {row['dsld_unit']!r}: {row['rows']} -> {row['mapped_to'] or 'not accepted'} ({row['nutrients'] or '-'})")
    print("unmapped ingredient names (top 15):")
    for row in unmapped_rows[:15]:
        print(f"  {row['name']}: {row['rows']}")
    return 0


# ---------------------------------------------------------------------------
# Entry point


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="step", required=True)
    fetch_parser = sub.add_parser("fetch", help="download raw DSLD responses into the cache")
    fetch_parser.add_argument("cache", type=Path)
    convert_parser = sub.add_parser("convert", help="convert the cache into the snapshot files")
    convert_parser.add_argument("cache", type=Path)
    convert_parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)
    if args.step == "fetch":
        return run_fetch(args.cache)
    return run_convert(args.cache, args.out)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
