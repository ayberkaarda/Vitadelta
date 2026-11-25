package com.vitadelta.api.catalog.seed;

import com.vitadelta.api.catalog.FormCodes;
import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.catalog.ProductImport;
import com.vitadelta.api.catalog.ReferenceValues.CountryRow;
import com.vitadelta.api.catalog.ReferenceValues.FormRow;
import com.vitadelta.api.catalog.ReferenceValues.NutrientRow;
import com.vitadelta.api.catalog.ReferenceValues.ReferenceValueRow;
import com.vitadelta.api.catalog.ReferenceValues.SetRow;
import com.vitadelta.api.catalog.ReferenceValues.UpperLimitRow;
import com.vitadelta.api.catalog.UlBasis;
import com.vitadelta.api.catalog.unit.ConversionRule;
import com.vitadelta.api.catalog.unit.IuAllowList;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitConversionException;
import com.vitadelta.api.imports.CatalogRegistry;
import com.vitadelta.api.imports.ImportProblem;
import com.vitadelta.api.imports.OffersCsvReader;
import com.vitadelta.api.imports.ProductsDocumentReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Reads and validates the whole seed data set from the data directory. All files are validated
 * before anything is written; any problem aborts the seed with every row-level message.
 *
 * <p>Files (relative to the data directory): the reference files {@link #REFERENCE_FILES}, the
 * products documents {@link #PRODUCT_FILES} and the offers files {@link #OFFER_FILES}, applied in
 * that order.
 */
public final class SeedDataReader {

  public static final String NUTRIENTS = "reference/nutrients.csv";
  public static final String FORMS = "reference/forms.csv";
  public static final String UNIT_CONVERSIONS = "reference/unit_conversions.csv";
  public static final String REFERENCE_SETS = "reference/reference_sets.csv";
  public static final String REFERENCE_VALUES = "reference/reference_values.csv";
  public static final String UL_SETS = "reference/ul_sets.csv";
  public static final String UPPER_LIMITS = "reference/upper_limits.csv";
  public static final String COUNTRIES = "reference/countries.csv";

  public static final List<String> REFERENCE_FILES =
      List.of(
          NUTRIENTS,
          FORMS,
          UNIT_CONVERSIONS,
          REFERENCE_SETS,
          REFERENCE_VALUES,
          UL_SETS,
          UPPER_LIMITS,
          COUNTRIES);

  /** Synthetic fixtures first, then the real label snapshot. */
  public static final List<String> PRODUCT_FILES =
      List.of("fixtures/products.synthetic.json", "real/products.dsld.json");

  public static final List<String> OFFER_FILES =
      List.of("fixtures/offers.synthetic.csv", "real/offers.us-demo.synthetic.csv");

  private static final Pattern SET_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");
  private static final Pattern DECIMAL = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?$");
  private static final Pattern INTEGER = Pattern.compile("^-?[0-9]{1,9}$");

  /** Longest upper-limit citation note accepted. */
  static final int MAX_NOTE_LENGTH = 2000;

  private final Path dataDir;
  private final List<SeedProblem> problems = new ArrayList<>();

  private SeedDataReader(Path dataDir) {
    this.dataDir = dataDir;
  }

  /**
   * Reads the data set.
   *
   * @param existingProduct whether a product code already exists in the database (offers may
   *     reference stored products as well as products of the data set)
   * @param now validation time; offer {@code checked_at} must not be later
   * @throws SeedValidationException with every problem found
   */
  public static SeedData read(Path dataDir, Predicate<String> existingProduct, Instant now) {
    return new SeedDataReader(dataDir).readAll(existingProduct, now);
  }

  /** Reads and validates only the reference files. */
  public static ReferenceData readReference(Path dataDir) {
    SeedDataReader reader = new SeedDataReader(dataDir);
    ReferenceData reference = reader.reference();
    reader.failIfProblems();
    return reference;
  }

  private SeedData readAll(Predicate<String> existingProduct, Instant now) {
    ReferenceData reference = reference();
    failIfProblems();
    CatalogRegistry registry = reference.registry();
    List<ProductImport> products = new ArrayList<>();
    Set<String> codes = new HashSet<>();
    // (product, country) pair -> file that defines its offers; a pair may be defined by one file
    // only, otherwise the replace-set of the later file would recreate the other file's offers on
    // every run and their ids would not be stable.
    Map<String, String> offerKeys = new HashMap<>();
    for (String file : PRODUCT_FILES) {
      Optional<byte[]> content = bytes(file);
      if (content.isEmpty()) {
        continue;
      }
      ProductsDocumentReader.Result result =
          ProductsDocumentReader.read(content.get(), registry, now);
      result.problems().forEach(p -> problems.add(new SeedProblem(file, p)));
      for (int i = 0; i < result.products().size(); i++) {
        ProductImport product = result.products().get(i);
        if (codes.add(product.details().code())) {
          products.add(product);
          product
              .offers()
              .forEach(o -> offerKeys.put(o.productCode() + "/" + o.countryCode(), file));
        } else {
          problems.add(
              new SeedProblem(
                  file,
                  new ImportProblem(
                      "products[" + i + "].code",
                      "duplicate_product_code",
                      "product code " + product.details().code() + " is defined in another file")));
        }
      }
    }
    Predicate<String> productExists = code -> codes.contains(code) || existingProduct.test(code);
    List<OfferValues> offers = new ArrayList<>();
    Set<String> reportedPairs = new HashSet<>();
    for (String file : OFFER_FILES) {
      Optional<byte[]> content = bytes(file);
      if (content.isEmpty()) {
        continue;
      }
      OffersCsvReader.Result result =
          OffersCsvReader.read(content.get(), productExists, registry, now);
      result.problems().forEach(p -> problems.add(new SeedProblem(file, p)));
      for (OfferValues offer : result.offers()) {
        String pair = offer.productCode() + "/" + offer.countryCode();
        String owner = offerKeys.putIfAbsent(pair, file);
        if (owner != null && !owner.equals(file)) {
          if (reportedPairs.add(pair)) {
            problems.add(
                new SeedProblem(
                    file,
                    new ImportProblem(
                        "pair " + pair,
                        "duplicate_offer_key",
                        "offers of " + pair + " are already given by " + owner)));
          }
        } else {
          offers.add(offer);
        }
      }
    }
    failIfProblems();
    return new SeedData(reference, products, offers);
  }

  private void failIfProblems() {
    if (!problems.isEmpty()) {
      throw new SeedValidationException(problems);
    }
  }

  private Optional<byte[]> bytes(String file) {
    Path path = dataDir.resolve(file);
    try {
      return Optional.of(Files.readAllBytes(path));
    } catch (IOException e) {
      problems.add(
          new SeedProblem(
              file, new ImportProblem("file", "missing_file", "cannot read " + file + ": " + e)));
      return Optional.empty();
    }
  }

  private List<CsvTable.Row> table(String file, List<String> header) {
    Optional<byte[]> content = bytes(file);
    if (content.isEmpty()) {
      return List.of();
    }
    String text = new String(content.get(), StandardCharsets.UTF_8);
    if (text.contains("�")) {
      problems.add(
          new SeedProblem(
              file, ImportProblem.atRow(1, "invalid_encoding", "file is not valid UTF-8")));
      return List.of();
    }
    return CsvTable.parse(text, header, p -> problems.add(new SeedProblem(file, p)));
  }

  private Consumer<String[]> reporter(String file, int row) {
    return codeAndMessage ->
        problems.add(
            new SeedProblem(file, ImportProblem.atRow(row, codeAndMessage[0], codeAndMessage[1])));
  }

  private ReferenceData reference() {
    List<NutrientRow> nutrients = nutrients();
    Map<String, Unit> canonical = new HashMap<>();
    nutrients.forEach(n -> canonical.put(n.code(), n.canonicalUnit()));
    List<FormRow> formRows = forms(canonical.keySet());
    Map<String, String> forms = new HashMap<>();
    formRows.forEach(f -> forms.put(f.code(), f.nutrientCode()));
    List<ConversionRule> conversions = conversions(canonical.keySet(), forms);
    List<SetRow> referenceSets = sets(REFERENCE_SETS);
    List<SetRow> ulSets = sets(UL_SETS);
    List<ReferenceValueRow> referenceValues = referenceValues(codes(referenceSets), canonical);
    List<UpperLimitRow> upperLimits = upperLimits(codes(ulSets), canonical, forms);
    List<CountryRow> countries = countries(codes(referenceSets), codes(ulSets));
    return new ReferenceData(
        nutrients,
        formRows,
        forms,
        conversions,
        referenceSets,
        referenceValues,
        ulSets,
        upperLimits,
        countries);
  }

  private static Set<String> codes(List<SetRow> sets) {
    Set<String> codes = new HashSet<>();
    sets.forEach(s -> codes.add(s.code()));
    return codes;
  }

  private List<NutrientRow> nutrients() {
    List<NutrientRow> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CsvTable.Row row :
        table(
            NUTRIENTS,
            List.of(
                "code",
                "name_en",
                "category",
                "canonical_unit",
                "presence_floor_per_day",
                "sort_order"))) {
      Consumer<String[]> report = reporter(NUTRIENTS, row.number());
      int before = problems.size();
      String code = row.get("code");
      if (!FormCodes.REGISTRY_CODE.matcher(code).matches()) {
        report.accept(new String[] {"invalid_code", "invalid nutrient code " + code});
      } else if (!seen.add(code)) {
        report.accept(new String[] {"duplicate_code", "nutrient " + code + " appears twice"});
      }
      String name = text(row, "name_en", 200, report);
      NutrientCategory category = enumValue(row.get("category"), NutrientCategory.class, report);
      Unit unit = Unit.fromSymbol(row.get("canonical_unit")).filter(Unit::isMass).orElse(null);
      if (unit == null) {
        report.accept(
            new String[] {
              "invalid_unit", "canonical_unit must be mg, mcg or g: " + row.get("canonical_unit")
            });
      }
      BigDecimal floor = null;
      if (row.optional("presence_floor_per_day") != null) {
        floor = amount(row.get("presence_floor_per_day"), "presence_floor_per_day", 4, report);
      }
      Integer sortOrder = null;
      if (INTEGER.matcher(row.get("sort_order")).matches()) {
        sortOrder = Integer.valueOf(row.get("sort_order"));
      } else {
        report.accept(new String[] {"invalid_sort_order", "sort_order must be an integer"});
      }
      if (problems.size() == before) {
        result.add(new NutrientRow(code, name, category, unit, floor, sortOrder));
      }
    }
    return result;
  }

  private List<FormRow> forms(Set<String> nutrients) {
    List<FormRow> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CsvTable.Row row : table(FORMS, List.of("code", "nutrient_code", "name_en"))) {
      Consumer<String[]> report = reporter(FORMS, row.number());
      int before = problems.size();
      String code = row.get("code");
      if (!FormCodes.REGISTRY_CODE.matcher(code).matches()
          || FormCodes.ANY.equals(code)
          || FormCodes.UNKNOWN.equals(code)) {
        report.accept(new String[] {"invalid_form_code", "invalid form code " + code});
      } else if (!seen.add(code)) {
        report.accept(new String[] {"duplicate_code", "form " + code + " appears twice"});
      }
      String nutrient = row.get("nutrient_code");
      if (!nutrients.contains(nutrient)) {
        report.accept(new String[] {"unknown_nutrient", "nutrient " + nutrient + " is unknown"});
      }
      String name = text(row, "name_en", 200, report);
      if (problems.size() == before) {
        result.add(new FormRow(code, nutrient, name));
      }
    }
    return result;
  }

  private List<ConversionRule> conversions(Set<String> nutrients, Map<String, String> forms) {
    List<ConversionRule> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CsvTable.Row row :
        table(
            UNIT_CONVERSIONS,
            List.of("nutrient_code", "from_unit", "to_unit", "factor", "form_code", "note"))) {
      Consumer<String[]> report = reporter(UNIT_CONVERSIONS, row.number());
      int before = problems.size();
      String nutrient = row.get("nutrient_code");
      if (!FormCodes.GENERIC_NUTRIENT.equals(nutrient) && !nutrients.contains(nutrient)) {
        report.accept(new String[] {"unknown_nutrient", "nutrient " + nutrient + " is unknown"});
      }
      Optional<Unit> from = Unit.fromSymbol(row.get("from_unit"));
      Optional<Unit> to = Unit.fromSymbol(row.get("to_unit"));
      if (from.isEmpty() || to.isEmpty()) {
        report.accept(new String[] {"invalid_unit", "units must be mg, mcg, g or IU"});
      }
      BigDecimal factor = amount(row.get("factor"), "factor", 8, report);
      String form = row.get("form_code");
      checkReferenceForm(form, nutrient, forms, report);
      if (problems.size() > before) {
        continue;
      }
      try {
        ConversionRule rule =
            new ConversionRule(nutrient, from.get(), to.get(), factor, form, row.optional("note"));
        Optional<UnitConversionException.Reason> denied =
            rule.from() == Unit.IU ? IuAllowList.check(nutrient, form) : Optional.empty();
        if (denied.isPresent()) {
          report.accept(
              new String[] {
                denied.get().code(),
                "an IU rule for "
                    + nutrient
                    + " with form "
                    + form
                    + " is outside the IU allow-list"
              });
        } else if (seen.add(nutrient + "|" + rule.from() + "|" + rule.to() + "|" + form)) {
          result.add(rule);
        } else {
          report.accept(new String[] {"duplicate_key", "conversion appears twice"});
        }
      } catch (IllegalArgumentException e) {
        report.accept(new String[] {"invalid_conversion", e.getMessage()});
      }
    }
    return result;
  }

  private void checkReferenceForm(
      String form, String nutrient, Map<String, String> forms, Consumer<String[]> report) {
    if (FormCodes.ANY.equals(form)) {
      return;
    }
    if (FormCodes.UNKNOWN.equals(form)) {
      report.accept(new String[] {"invalid_form_code", "UNKNOWN is not a reference form"});
    } else if (!nutrient.equals(forms.get(form))) {
      report.accept(
          new String[] {
            "invalid_form_code", "form " + form + " is not a registered form of " + nutrient
          });
    }
  }

  private List<SetRow> sets(String file) {
    List<SetRow> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CsvTable.Row row : table(file, List.of("code", "name", "source_citation"))) {
      Consumer<String[]> report = reporter(file, row.number());
      int before = problems.size();
      String code = row.get("code");
      if (!SET_CODE.matcher(code).matches()) {
        report.accept(new String[] {"invalid_code", "invalid set code " + code});
      } else if (!seen.add(code)) {
        report.accept(new String[] {"duplicate_code", "set " + code + " appears twice"});
      }
      String name = text(row, "name", 200, report);
      String citation = text(row, "source_citation", 1000, report);
      if (problems.size() == before) {
        result.add(new SetRow(code, name, citation));
      }
    }
    return result;
  }

  private List<ReferenceValueRow> referenceValues(Set<String> sets, Map<String, Unit> canonical) {
    List<ReferenceValueRow> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CsvTable.Row row :
        table(REFERENCE_VALUES, List.of("reference_set_code", "nutrient_code", "amount", "unit"))) {
      Consumer<String[]> report = reporter(REFERENCE_VALUES, row.number());
      int before = problems.size();
      String set = row.get("reference_set_code");
      String nutrient = row.get("nutrient_code");
      if (!sets.contains(set)) {
        report.accept(new String[] {"unknown_set", "reference set " + set + " is unknown"});
      }
      Unit unit = canonicalUnit(row.get("unit"), nutrient, canonical, report);
      BigDecimal amount = amount(row.get("amount"), "amount", 4, report);
      if (problems.size() == before) {
        if (seen.add(set + "|" + nutrient)) {
          result.add(new ReferenceValueRow(set, nutrient, amount, unit));
        } else {
          report.accept(new String[] {"duplicate_key", "reference value appears twice"});
        }
      }
    }
    return result;
  }

  private List<UpperLimitRow> upperLimits(
      Set<String> sets, Map<String, Unit> canonical, Map<String, String> forms) {
    List<UpperLimitRow> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CsvTable.Row row :
        table(
            UPPER_LIMITS,
            List.of(
                "ul_set_code", "nutrient_code", "form_code", "amount", "unit", "basis", "note"))) {
      Consumer<String[]> report = reporter(UPPER_LIMITS, row.number());
      int before = problems.size();
      String set = row.get("ul_set_code");
      String nutrient = row.get("nutrient_code");
      String form = row.get("form_code");
      if (!sets.contains(set)) {
        report.accept(new String[] {"unknown_set", "UL set " + set + " is unknown"});
      }
      Unit unit = canonicalUnit(row.get("unit"), nutrient, canonical, report);
      checkReferenceForm(form, nutrient, forms, report);
      String amountText = row.optional("amount");
      String basisText = row.optional("basis");
      BigDecimal amount = null;
      UlBasis basis = null;
      if ((amountText == null) != (basisText == null)) {
        report.accept(
            new String[] {
              "invalid_exempt_row", "amount and basis must both be set or both be empty"
            });
      } else if (amountText != null) {
        amount = amount(amountText, "amount", 4, report);
        basis = enumValue(basisText, UlBasis.class, report);
      }
      String note = row.optional("note");
      if (note == null) {
        report.accept(new String[] {"missing_note", "every upper limit needs a citation note"});
      } else if (note.length() > MAX_NOTE_LENGTH) {
        report.accept(
            new String[] {
              "invalid_text", "note must have at most " + MAX_NOTE_LENGTH + " characters"
            });
      }
      if (problems.size() == before) {
        if (seen.add(set + "|" + nutrient + "|" + form)) {
          result.add(new UpperLimitRow(set, nutrient, form, amount, unit, basis, note));
        } else {
          report.accept(new String[] {"duplicate_key", "upper limit appears twice"});
        }
      }
    }
    return result;
  }

  private List<CountryRow> countries(Set<String> referenceSets, Set<String> ulSets) {
    List<CountryRow> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (CsvTable.Row row :
        table(
            COUNTRIES,
            List.of(
                "code",
                "name_en",
                "currency",
                "reference_set_code",
                "ul_set_code",
                "default_locale"))) {
      Consumer<String[]> report = reporter(COUNTRIES, row.number());
      int before = problems.size();
      String code = row.get("code");
      if (!COUNTRY.matcher(code).matches()) {
        report.accept(new String[] {"invalid_code", "invalid country code " + code});
      } else if (!seen.add(code)) {
        report.accept(new String[] {"duplicate_code", "country " + code + " appears twice"});
      }
      String name = text(row, "name_en", 200, report);
      String currency = row.get("currency");
      if (!CURRENCY.matcher(currency).matches()) {
        report.accept(new String[] {"invalid_currency", "invalid currency " + currency});
      }
      if (!referenceSets.contains(row.get("reference_set_code"))) {
        report.accept(
            new String[] {
              "unknown_set", "reference set " + row.get("reference_set_code") + " is unknown"
            });
      }
      if (!ulSets.contains(row.get("ul_set_code"))) {
        report.accept(
            new String[] {"unknown_set", "UL set " + row.get("ul_set_code") + " is unknown"});
      }
      if (!LOCALE.matcher(row.get("default_locale")).matches()) {
        report.accept(
            new String[] {"invalid_locale", "invalid locale " + row.get("default_locale")});
      }
      if (problems.size() == before) {
        result.add(
            new CountryRow(
                code,
                name,
                currency,
                row.get("reference_set_code"),
                row.get("ul_set_code"),
                row.get("default_locale")));
      }
    }
    return result;
  }

  private static Unit canonicalUnit(
      String symbol, String nutrient, Map<String, Unit> canonical, Consumer<String[]> report) {
    Unit expected = canonical.get(nutrient);
    if (expected == null) {
      report.accept(new String[] {"unknown_nutrient", "nutrient " + nutrient + " is unknown"});
      return null;
    }
    if (!expected.symbol().equals(symbol)) {
      report.accept(
          new String[] {
            "unit_not_canonical",
            "unit "
                + symbol
                + " differs from the canonical unit "
                + expected.symbol()
                + " of "
                + nutrient
          });
      return null;
    }
    return expected;
  }

  private static String text(CsvTable.Row row, String column, int max, Consumer<String[]> report) {
    String value = row.get(column);
    if (value.isBlank() || value.length() > max) {
      report.accept(
          new String[] {"invalid_text", column + " must have 1 to " + max + " characters"});
    }
    return value;
  }

  private static BigDecimal amount(
      String text, String column, int maxScale, Consumer<String[]> report) {
    if (!DECIMAL.matcher(text).matches()) {
      report.accept(
          new String[] {"invalid_amount", column + " must be a positive decimal: " + text});
      return null;
    }
    BigDecimal value = new BigDecimal(text);
    if (value.signum() <= 0 || value.stripTrailingZeros().scale() > maxScale) {
      report.accept(
          new String[] {
            "invalid_amount", column + " must be > 0 with at most " + maxScale + " decimals"
          });
      return null;
    }
    return value;
  }

  private static <E extends Enum<E>> E enumValue(
      String text, Class<E> type, Consumer<String[]> report) {
    for (E constant : type.getEnumConstants()) {
      if (constant.name().equals(text)) {
        return constant;
      }
    }
    report.accept(new String[] {"invalid_value", "invalid " + type.getSimpleName() + " " + text});
    return null;
  }
}
