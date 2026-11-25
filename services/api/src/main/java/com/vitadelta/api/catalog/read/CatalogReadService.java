package com.vitadelta.api.catalog.read;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.NutrientCategory;
import com.vitadelta.api.catalog.UlBasis;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.CountryView;
import com.vitadelta.api.catalog.read.CatalogViews.FormView;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientFacts;
import com.vitadelta.api.catalog.read.CatalogViews.NutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.ProductInCountry;
import com.vitadelta.api.catalog.read.CatalogViews.ProductNutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.ProductSearchResult;
import com.vitadelta.api.catalog.read.CatalogViews.ProvenanceView;
import com.vitadelta.api.catalog.read.CatalogViews.ReferenceAmount;
import com.vitadelta.api.catalog.read.CatalogViews.UpperLimitView;
import com.vitadelta.api.catalog.unit.ConversionResult;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.catalog.unit.UnitConversionException;
import com.vitadelta.api.catalog.unit.UnitConversionService;
import com.vitadelta.api.catalog.unit.UnitConversionTable;
import com.vitadelta.api.common.paging.PageQuery;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the catalog: reference data, product search and detail, and the complete scoring
 * input of a country.
 *
 * <p>Every public method runs in its own read-only {@code REPEATABLE READ} transaction, so all rows
 * it returns (including the catalog version) come from one snapshot, and the transaction is closed
 * before the method returns. The results are detached records ({@link CatalogViews}); callers can
 * keep them across a remote call without holding a database connection (section 7.6).
 *
 * <p>Stored nutrient amounts are in their labelled unit; they are converted here, once, into the
 * canonical unit with the stored unit conversion table (section 5.5). The UL-relevant amount is
 * converted with the same rule.
 */
@Service
public class CatalogReadService {

  private static final String IN_MARKET =
      "SELECT a.product_id FROM product_availability a"
          + " WHERE a.country_code = :country AND a.status = 'AVAILABLE'";

  private static final String PRODUCT_COLUMNS =
      "p.id, p.code, p.brand, p.name, p.dosage_form, p.units_per_container, p.units_per_serving,"
          + " p.servings_per_day, p.gtin, p.source, p.source_ref, p.source_entry_date, p.synthetic";

  private static final String SEARCH_ORDER =
      " ORDER BY p.brand COLLATE \"C\", p.name COLLATE \"C\", p.code COLLATE \"C\"";

  private static final String SEARCH_MATCH =
      " AND (strpos(lower(p.brand), lower(:q)) > 0 OR strpos(lower(p.name), lower(:q)) > 0"
          + " OR strpos(lower(p.code), lower(:q)) > 0)";

  private static final String SEARCH_FROM =
      " FROM product p JOIN product_availability a ON a.product_id = p.id"
          + " WHERE a.country_code = :country AND a.status = 'AVAILABLE'";

  private static final String COUNT_ALL = "SELECT count(*)" + SEARCH_FROM;
  private static final String COUNT_MATCHING = "SELECT count(*)" + SEARCH_FROM + SEARCH_MATCH;
  private static final String PAGE_ALL =
      "SELECT " + PRODUCT_COLUMNS + SEARCH_FROM + SEARCH_ORDER + " LIMIT :limit OFFSET :offset";
  private static final String PAGE_MATCHING =
      "SELECT "
          + PRODUCT_COLUMNS
          + SEARCH_FROM
          + SEARCH_MATCH
          + SEARCH_ORDER
          + " LIMIT :limit OFFSET :offset";

  /** Largest number of ids bound into one {@code IN (:ids)} list. */
  static final int ID_BATCH = 1000;

  private final JdbcClient jdbc;
  private final UnitConversionService conversions;

  public CatalogReadService(JdbcClient jdbc, UnitConversionService conversions) {
    this.jdbc = jdbc;
    this.conversions = conversions;
  }

  /** The current catalog version. */
  @Transactional(readOnly = true)
  public long catalogVersion() {
    return version();
  }

  /** Every country of the registry, ordered by code. */
  @Transactional(readOnly = true)
  public List<CountryView> countries() {
    return jdbc.sql(
            "SELECT code, name_en, currency, reference_set_code, ul_set_code, default_locale"
                + " FROM country ORDER BY code")
        .query(CatalogReadService::country)
        .list();
  }

  /** One country of the registry. */
  @Transactional(readOnly = true)
  public Optional<CountryView> country(String code) {
    return findCountry(code);
  }

  /**
   * The nutrient registry ordered by sort order, then code, with the reference sets that define a
   * value and the registered forms of each nutrient.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<NutrientView> nutrients() {
    return registry();
  }

  /**
   * In-market products of a country matching {@code q} (case-insensitive substring of brand, name
   * or code; {@code null} matches every product), ordered by brand, name and code in binary order.
   *
   * @param country a country of the registry
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ProductSearchResult searchProducts(String country, String q, PageQuery page) {
    long total =
        (q == null ? jdbc.sql(COUNT_ALL) : jdbc.sql(COUNT_MATCHING).param("q", q))
            .param("country", country)
            .query(Long.class)
            .single();
    JdbcClient.StatementSpec statement = q == null ? jdbc.sql(PAGE_ALL) : jdbc.sql(PAGE_MATCHING);
    if (q != null) {
      statement = statement.param("q", q);
    }
    List<ProductRow> rows =
        statement
            .param("country", country)
            .param("limit", page.pageSize())
            .param("offset", page.offset())
            .query(CatalogReadService::productRow)
            .list();
    List<CatalogProduct> items = assemble(rows, country);
    return new ProductSearchResult(items, total);
  }

  /**
   * One product with its availability, nutrients and offers in {@code country}, and the country's
   * reference values and upper limits of the product's nutrients, all from one snapshot; empty when
   * the product does not exist.
   *
   * @param country a country of the registry
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<ProductInCountry> product(UUID id, String country) {
    CountryView countryView =
        findCountry(country)
            .orElseThrow(() -> new IllegalArgumentException("unknown country " + country));
    List<ProductRow> rows =
        jdbc.sql("SELECT " + PRODUCT_COLUMNS + " FROM product p WHERE p.id = :id")
            .param("id", id)
            .query(CatalogReadService::productRow)
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    record Availability(AvailabilityStatus status, String note) {}
    Optional<Availability> availability =
        jdbc.sql(
                "SELECT status, note FROM product_availability"
                    + " WHERE product_id = :id AND country_code = :country")
            .param("id", id)
            .param("country", country)
            .query((rs, i) ->
                new Availability(
                    AvailabilityStatus.valueOf(rs.getString("status")), rs.getString("note")))
            .optional();
    CatalogProduct product = assemble(rows, country).getFirst();
    List<String> codes = product.nutrients().stream().map(ProductNutrientView::code).toList();
    NutrientFacts facts =
        new NutrientFacts(
            countryView.referenceSetCode(),
            countryView.ulSetCode(),
            referenceValues(countryView.referenceSetCode()).stream()
                .filter(r -> codes.contains(r.nutrientCode()))
                .toList(),
            upperLimits(countryView.ulSetCode()).stream()
                .filter(u -> codes.contains(u.nutrientCode()))
                .toList());
    return Optional.of(
        new ProductInCountry(
            product,
            availability.map(Availability::status).orElse(null),
            availability.map(Availability::note).orElse(null),
            facts));
  }

  /**
   * Everything a scoring request needs for {@code country}, read in one snapshot (section 6.1): the
   * registry, the country's reference values and upper limits (exempt rows included), every
   * in-market product with canonical nutrients and its offers in the country, and the catalog
   * version. Empty when the country is not in the registry.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<CountryCatalog> loadCountry(String countryCode) {
    return load(countryCode, Integer.MAX_VALUE);
  }

  /**
   * {@link #loadCountry(String)} for a request that can handle at most {@code maxProducts}
   * in-market products: the products are counted first, in the same snapshot, and nothing else is
   * loaded when there are more.
   *
   * @throws CatalogTooLargeException when the country has more than {@code maxProducts} in-market
   *     products
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<CountryCatalog> loadCountry(String countryCode, int maxProducts) {
    return load(countryCode, maxProducts);
  }

  private Optional<CountryCatalog> load(String countryCode, int maxProducts) {
    Optional<CountryView> found = findCountry(countryCode);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    CountryView country = found.get();
    long inMarket =
        jdbc.sql("SELECT count(*) FROM (" + IN_MARKET + ") m")
            .param("country", countryCode)
            .query(Long.class)
            .single();
    if (inMarket > maxProducts) {
      throw new CatalogTooLargeException(countryCode, inMarket, maxProducts);
    }
    long version = version();
    List<NutrientView> nutrients = registry();
    List<ReferenceAmount> referenceValues = referenceValues(country.referenceSetCode());
    List<UpperLimitView> upperLimits = upperLimits(country.ulSetCode());
    List<ProductRow> rows =
        jdbc.sql(
                "SELECT "
                    + PRODUCT_COLUMNS
                    + " FROM product p WHERE p.id IN ("
                    + IN_MARKET
                    + ") ORDER BY p.id")
            .param("country", countryCode)
            .query(CatalogReadService::productRow)
            .list();
    return Optional.of(
        new CountryCatalog(
            version,
            country,
            nutrients,
            referenceValues,
            upperLimits,
            assemble(rows, countryCode)));
  }

  /**
   * The market profile of {@code country} at the current catalog version (section 6.3), read in one
   * snapshot. Empty when the country is not in the registry.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<MarketProfile> marketProfile(String countryCode) {
    return loadCountry(countryCode).map(CountryCatalog::marketProfile);
  }

  private List<ReferenceAmount> referenceValues(String referenceSetCode) {
    return jdbc.sql(
            "SELECT nutrient_code, amount, unit FROM reference_value"
                + " WHERE reference_set_code = :set ORDER BY nutrient_code")
        .param("set", referenceSetCode)
        .query((rs, i) ->
            new ReferenceAmount(
                rs.getString("nutrient_code"),
                rs.getBigDecimal("amount"),
                unit(rs.getString("unit"))))
        .list();
  }

  private List<UpperLimitView> upperLimits(String ulSetCode) {
    return jdbc.sql(
            "SELECT nutrient_code, form_code, amount, unit, basis, note FROM upper_limit"
                + " WHERE ul_set_code = :set ORDER BY nutrient_code, form_code")
        .param("set", ulSetCode)
        .query(CatalogReadService::upperLimit)
        .list();
  }

  private long version() {
    return jdbc.sql("SELECT version FROM catalog_version WHERE id = 1").query(Long.class).single();
  }

  private Optional<CountryView> findCountry(String code) {
    return jdbc.sql(
            "SELECT code, name_en, currency, reference_set_code, ul_set_code, default_locale"
                + " FROM country WHERE code = :code")
        .param("code", code)
        .query(CatalogReadService::country)
        .optional();
  }

  private List<NutrientView> registry() {
    Map<String, List<String>> sets = new HashMap<>();
    jdbc.sql(
            "SELECT nutrient_code, reference_set_code FROM reference_value"
                + " ORDER BY nutrient_code, reference_set_code COLLATE \"C\"")
        .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
        .list()
        .forEach(e -> sets.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
    Map<String, List<FormView>> forms = new HashMap<>();
    jdbc.sql("SELECT code, nutrient_code, name_en FROM nutrient_form ORDER BY code COLLATE \"C\"")
        .query((rs, i) ->
            Map.entry(
                rs.getString("nutrient_code"),
                new FormView(rs.getString("code"), rs.getString("name_en"))))
        .list()
        .forEach(e -> forms.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
    return jdbc.sql(
            "SELECT code, name_en, category, canonical_unit, presence_floor_per_day, sort_order"
                + " FROM nutrient ORDER BY sort_order, code COLLATE \"C\"")
        .query((rs, i) -> {
          String code = rs.getString("code");
          return new NutrientView(
              code,
              rs.getString("name_en"),
              NutrientCategory.valueOf(rs.getString("category")),
              unit(rs.getString("canonical_unit")),
              rs.getBigDecimal("presence_floor_per_day"),
              rs.getInt("sort_order"),
              sets.getOrDefault(code, List.of()),
              forms.getOrDefault(code, List.of()));
        })
        .list();
  }

  /** Loads nutrients and offers of the given products and builds the views in row order. */
  private List<CatalogProduct> assemble(List<ProductRow> rows, String country) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<UUID> allIds = rows.stream().map(ProductRow::id).toList();
    UnitConversionTable table = conversions.currentTable();
    Map<String, NutrientMeta> registry = new HashMap<>();
    jdbc.sql("SELECT code, category, canonical_unit FROM nutrient")
        .query((rs, i) ->
            new NutrientMeta(
                rs.getString("code"),
                NutrientCategory.valueOf(rs.getString("category")),
                unit(rs.getString("canonical_unit"))))
        .list()
        .forEach(n -> registry.put(n.code(), n));
    Map<UUID, List<ProductNutrientView>> nutrients = new HashMap<>();
    Map<UUID, List<OfferSnapshot>> offers = new HashMap<>();
    for (List<UUID> ids : batches(allIds)) {
      jdbc.sql(
              "SELECT product_id, nutrient_code, amount_per_serving, ul_amount_per_serving, unit,"
                  + " form_code, form_label FROM product_nutrient WHERE product_id IN (:ids)"
                  + " ORDER BY product_id, nutrient_code COLLATE \"C\"")
          .param("ids", ids)
          .query((rs, i) -> {
            UUID productId = rs.getObject("product_id", UUID.class);
            return Map.entry(productId, canonical(rs, registry, table));
          })
          .list()
          .forEach(
              e -> nutrients.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
      jdbc.sql(
              "SELECT product_id, retailer, price, currency, in_stock, url, checked_at FROM offer"
                  + " WHERE country_code = :country AND product_id IN (:ids)")
          .param("country", country)
          .param("ids", ids)
          .query((rs, i) ->
              Map.entry(
                  rs.getObject("product_id", UUID.class),
                  new OfferSnapshot(
                      rs.getString("retailer"),
                      rs.getBigDecimal("price"),
                      rs.getString("currency"),
                      rs.getBoolean("in_stock"),
                      rs.getString("url"),
                      rs.getTimestamp("checked_at").toInstant())))
          .list()
          .forEach(
              e -> offers.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
    }
    Map<UUID, CatalogProduct> result = new LinkedHashMap<>();
    for (ProductRow row : rows) {
      result.put(
          row.id(),
          new CatalogProduct(
              row.id(),
              row.code(),
              row.brand(),
              row.name(),
              row.dosageForm(),
              row.unitsPerContainer(),
              row.unitsPerServing(),
              row.servingsPerDay(),
              row.gtin(),
              row.provenance(),
              nutrients.getOrDefault(row.id(), List.of()),
              EffectivePriceResolver.displayOrder(offers.getOrDefault(row.id(), List.of()))));
    }
    return List.copyOf(result.values());
  }

  /**
   * Splits ids into lists of at most {@link #ID_BATCH} elements, so an {@code IN (:ids)} list never
   * comes near the bind-parameter limit of the database driver (32767 per statement).
   */
  static List<List<UUID>> batches(List<UUID> ids) {
    List<List<UUID>> batches = new ArrayList<>();
    for (int from = 0; from < ids.size(); from += ID_BATCH) {
      batches.add(ids.subList(from, Math.min(ids.size(), from + ID_BATCH)));
    }
    return batches;
  }

  private static ProductNutrientView canonical(
      ResultSet rs, Map<String, NutrientMeta> registry, UnitConversionTable table)
      throws SQLException {
    String code = rs.getString("nutrient_code");
    NutrientMeta meta = registry.get(code);
    Unit stored = unit(rs.getString("unit"));
    String form = rs.getString("form_code");
    BigDecimal amount = rs.getBigDecimal("amount_per_serving");
    BigDecimal ulAmount = rs.getBigDecimal("ul_amount_per_serving");
    try {
      ConversionResult converted = table.convert(code, amount, stored, form, meta.canonicalUnit());
      BigDecimal convertedUl =
          ulAmount == null
              ? null
              : table.convert(code, ulAmount, stored, form, meta.canonicalUnit()).amount();
      return new ProductNutrientView(
          code,
          meta.category(),
          converted.amount(),
          meta.canonicalUnit(),
          convertedUl,
          form,
          rs.getString("form_label"),
          converted.assumed());
    } catch (UnitConversionException e) {
      throw new IllegalStateException(
          "stored amount of " + code + " cannot be converted: " + e.detail(), e);
    }
  }

  private static CountryView country(ResultSet rs, int row) throws SQLException {
    return new CountryView(
        rs.getString("code"),
        rs.getString("name_en"),
        rs.getString("currency"),
        rs.getString("reference_set_code"),
        rs.getString("ul_set_code"),
        rs.getString("default_locale"));
  }

  private static UpperLimitView upperLimit(ResultSet rs, int row) throws SQLException {
    BigDecimal amount = rs.getBigDecimal("amount");
    String basis = rs.getString("basis");
    return new UpperLimitView(
        rs.getString("nutrient_code"),
        rs.getString("form_code"),
        amount,
        amount == null ? null : unit(rs.getString("unit")),
        basis == null ? null : UlBasis.valueOf(basis),
        rs.getString("note"));
  }

  private static ProductRow productRow(ResultSet rs, int row) throws SQLException {
    Date entryDate = rs.getDate("source_entry_date");
    return new ProductRow(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("brand"),
        rs.getString("name"),
        DosageForm.valueOf(rs.getString("dosage_form")),
        rs.getInt("units_per_container"),
        rs.getInt("units_per_serving"),
        rs.getBigDecimal("servings_per_day"),
        rs.getString("gtin"),
        new ProvenanceView(
            rs.getBoolean("synthetic"),
            rs.getString("source"),
            rs.getString("source_ref"),
            entryDate == null ? null : entryDate.toLocalDate()));
  }

  private static Unit unit(String symbol) {
    return Unit.fromSymbol(symbol)
        .orElseThrow(() -> new IllegalStateException("unknown stored unit " + symbol));
  }

  /** Columns of one product row. */
  private record ProductRow(
      UUID id,
      String code,
      String brand,
      String name,
      DosageForm dosageForm,
      int unitsPerContainer,
      int unitsPerServing,
      BigDecimal servingsPerDay,
      String gtin,
      ProvenanceView provenance) {}

  /** Registry columns needed for the conversion. */
  private record NutrientMeta(String code, NutrientCategory category, Unit canonicalUnit) {}
}
