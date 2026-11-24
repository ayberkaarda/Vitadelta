package com.vitadelta.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.catalog.ReferenceValues.CountryRow;
import com.vitadelta.api.catalog.ReferenceValues.NutrientRow;
import com.vitadelta.api.catalog.ReferenceValues.ReferenceValueRow;
import com.vitadelta.api.catalog.ReferenceValues.SetRow;
import com.vitadelta.api.catalog.ReferenceValues.UpperLimitRow;
import com.vitadelta.api.catalog.unit.ConversionRule;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class CatalogWriteTransactionTest extends PostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

  @Autowired private CatalogWriteTransaction writeTransaction;
  @Autowired private CatalogWriter writer;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ProductRepository products;
  @Autowired private OfferRepository offers;
  @Autowired private CatalogVersionRepository versions;

  private long version() {
    return jdbc.queryForObject("SELECT version FROM catalog_version WHERE id = 1", Long.class);
  }

  private static NutrientRow nutrient(String code, String name) {
    return new NutrientRow(code, name, NutrientCategory.OTHER, Unit.MG, null, 900);
  }

  @Test
  void emptyWriteBumpsTheVersionOnce() {
    long before = version();
    Instant bumpedBefore = versions.findById(CatalogVersion.ROW_ID).orElseThrow().getBumpedAt();
    String result = writeTransaction.execute(() -> "done");
    assertThat(result).isEqualTo("done");
    assertThat(version()).isEqualTo(before + 1);
    CatalogVersion row = versions.findById(CatalogVersion.ROW_ID).orElseThrow();
    assertThat(row.getId()).isEqualTo(CatalogVersion.ROW_ID);
    assertThat(row.getBumpedAt()).isAfterOrEqualTo(bumpedBefore);
  }

  @Test
  void largeWriteBumpsTheVersionOnce() {
    long before = version();
    List<NutrientRow> rows = new ArrayList<>();
    for (int i = 0; i < 150; i++) {
      rows.add(nutrient("TXN_BULK_" + i, "Bulk " + i));
    }
    WriteReport report = new WriteReport();
    writeTransaction.execute(() -> {
      writer.upsertNutrients(rows, report);
      return null;
    });
    assertThat(version()).isEqualTo(before + 1);
    assertThat(report.table("nutrient").inserted() + report.table("nutrient").unchanged())
        .isEqualTo(150);
  }

  @Test
  void failingWriteRollsBackAndDoesNotBump() {
    long before = version();
    assertThatThrownBy(() ->
            writeTransaction.execute(() -> {
              writer.upsertNutrients(
                  List.of(nutrient("TXN_ROLLBACK", "Rolled back")), new WriteReport());
              throw new IllegalStateException("boom");
            }))
        .hasMessage("boom");
    assertThat(version()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM nutrient WHERE code = 'TXN_ROLLBACK'", Integer.class))
        .isZero();
  }

  @Test
  void constraintViolationAtFlushDoesNotBump() {
    long before = version();
    assertThatThrownBy(() ->
            writeTransaction.execute(() -> {
              writer.upsertNutrients(
                  List.of(
                      new NutrientRow("TXN_BAD", " ", NutrientCategory.OTHER, Unit.MG, null, 1)),
                  new WriteReport());
              return null;
            }))
        .hasStackTraceContaining("nutrient_name_not_blank");
    assertThat(version()).isEqualTo(before);
  }

  @Test
  void writesOutsideTheHelperAreRejected() {
    assertThatThrownBy(() -> writer.upsertNutrients(List.of(), new WriteReport()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CatalogWriteTransaction");
    assertThat(CatalogWriteTransaction.isActive()).isFalse();
  }

  @Test
  void helperRefusesToJoinASurroundingTransaction() {
    long before = version();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status ->
            assertThatThrownBy(() -> writeTransaction.execute(() -> null))
                .isInstanceOf(IllegalStateException.class));
    assertThat(version()).isEqualTo(before);
  }

  @Test
  void bumpIsTheLastStatementAndTheVersionIsVisibleAfterCommit() {
    long before = version();
    long seenInside =
        writeTransaction.execute(
            () -> jdbc.queryForObject("SELECT version FROM catalog_version", Long.class));
    assertThat(seenInside).isEqualTo(before);
    assertThat(version()).isEqualTo(before + 1);
  }

  @Test
  void writerUpsertsByBusinessKeysAndReplacesSets() {
    String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String code = "TXN-" + suffix;
    SetRow refSet = new SetRow("TXN_REF", "Txn ref", "citation");
    SetRow ulSet = new SetRow("TXN_UL", "Txn UL", "citation");
    CountryRow country = new CountryRow("QX", "Txnland", "QXD", "TXN_REF", "TXN_UL", "en");
    ProductDetails details =
        new ProductDetails(
            code,
            "Fixture Labs",
            "Txn product",
            DosageForm.CAPSULE,
            30,
            1,
            BigDecimal.ONE,
            null,
            "test",
            null,
            null,
            true);
    ProductNutrientValues zinc =
        new ProductNutrientValues("TXN_N1", new BigDecimal("10"), null, Unit.MG, "UNKNOWN", null);
    ProductNutrientValues other =
        new ProductNutrientValues("TXN_N2", new BigDecimal("5"), null, Unit.MG, "UNKNOWN", "x");
    OfferValues shopA =
        new OfferValues(
            code, "QX", "Shop A", new BigDecimal("10.00"), "QXD", true, "https://a.example/a", NOW);
    OfferValues shopB =
        new OfferValues(
            code,
            "QX",
            "Shop B",
            new BigDecimal("12.00"),
            "QXD",
            false,
            "https://a.example/b",
            NOW);
    ProductImport first =
        new ProductImport(
            details,
            List.of(zinc, other),
            List.of(new AvailabilityValues("QX", AvailabilityStatus.AVAILABLE, null)),
            List.of(shopA, shopB));

    WriteReport firstReport = new WriteReport();
    writeTransaction.execute(() -> {
      writer.upsertNutrients(
          List.of(nutrient("TXN_N1", "N1"), nutrient("TXN_N2", "N2")), firstReport);
      writer.upsertReferenceSets(List.of(refSet), firstReport);
      writer.upsertUlSets(List.of(ulSet), firstReport);
      writer.upsertCountries(List.of(country), firstReport);
      writer.applyProducts(List.of(first), NOW, firstReport);
      return null;
    });
    Product stored = products.findByCode(code).orElseThrow();
    UUID productId = stored.getId();
    UUID offerA =
        offers
            .findByProductIdAndCountryCodeAndRetailer(productId, "QX", "Shop A")
            .orElseThrow()
            .getId();
    assertThat(firstReport.table("offer").inserted()).isEqualTo(2);
    assertThat(stored.getCreatedAt()).isEqualTo(NOW);
    assertThat(stored.toDetails())
        .usingRecursiveComparison()
        .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .isEqualTo(details);

    // Second write: same product with one nutrient, changed availability, Shop A repriced and
    // Shop B dropped from the pair.
    ProductImport second =
        new ProductImport(
            details,
            List.of(zinc),
            List.of(new AvailabilityValues("QX", AvailabilityStatus.DISCONTINUED, "gone")),
            List.of());
    OfferValues repriced =
        new OfferValues(
            code, "QX", "Shop A", new BigDecimal("9.50"), "QXD", true, "https://a.example/a", NOW);
    WriteReport secondReport = new WriteReport();
    Instant later = NOW.plus(1, ChronoUnit.DAYS);
    writeTransaction.execute(() -> {
      writer.upsertCountries(List.of(country), secondReport);
      writer.applyProducts(List.of(second), later, secondReport);
      writer.applyOffers(List.of(repriced), secondReport);
      return null;
    });
    assertThat(products.findByCode(code).orElseThrow().getId()).isEqualTo(productId);
    assertThat(products.findByCode(code).orElseThrow().getUpdatedAt()).isEqualTo(NOW);
    assertThat(secondReport.table("product").unchanged()).isEqualTo(1);
    assertThat(secondReport.table("country").unchanged()).isEqualTo(1);
    assertThat(secondReport.table("product_nutrient").deleted()).isEqualTo(1);
    assertThat(secondReport.table("product_nutrient").unchanged()).isEqualTo(1);
    assertThat(secondReport.table("product_availability").updated()).isEqualTo(1);
    assertThat(secondReport.table("offer").updated()).isEqualTo(1);
    assertThat(secondReport.table("offer").deleted()).isEqualTo(1);
    List<Offer> remaining = offers.findByProductIdAndCountryCode(productId, "QX");
    assertThat(remaining)
        .singleElement()
        .satisfies(o -> {
          assertThat(o.getId()).isEqualTo(offerA);
          assertThat(o.getPrice()).isEqualByComparingTo("9.50");
          assertThat(o.getCurrency()).isEqualTo("QXD");
          assertThat(o.isInStock()).isTrue();
          assertThat(o.getUrl()).isEqualTo("https://a.example/a");
          assertThat(o.getCheckedAt()).isEqualTo(NOW);
          assertThat(o.getProductId()).isEqualTo(productId);
          assertThat(o.getCountryCode()).isEqualTo("QX");
          assertThat(o.getRetailer()).isEqualTo("Shop A");
        });

    // Third write: changed product attributes move updated_at.
    ProductDetails renamed =
        new ProductDetails(
            code,
            "Fixture Labs",
            "Txn product renamed",
            DosageForm.CAPSULE,
            30,
            1,
            new BigDecimal("1.00"),
            null,
            "test",
            null,
            null,
            true);
    WriteReport thirdReport = new WriteReport();
    writeTransaction.execute(() -> {
      writer.applyProducts(
          List.of(new ProductImport(renamed, List.of(zinc), List.of(), List.of())),
          later,
          thirdReport);
      return null;
    });
    Product renamedProduct = products.findByCode(code).orElseThrow();
    assertThat(renamedProduct.getName()).isEqualTo("Txn product renamed");
    assertThat(renamedProduct.getUpdatedAt()).isEqualTo(later);
    assertThat(thirdReport.table("product").updated()).isEqualTo(1);
    assertThat(thirdReport.changedRows()).isEqualTo(1);
    assertThat(thirdReport.table("product").toString())
        .isEqualTo("inserted=0 updated=1 unchanged=0 deleted=0");
  }

  @Test
  void referenceReplaceDeletesRowsAbsentFromTheInput() {
    SetRow refSet = new SetRow("TXR_REF", "Ref", "citation");
    SetRow ulSet = new SetRow("TXR_UL", "UL", "citation");
    NutrientRow n = nutrient("TXR_N", "N");
    WriteReport report = new WriteReport();
    List<ReferenceValueRow> storedValues =
        jdbc.query(
            "SELECT reference_set_code, nutrient_code, amount, unit FROM reference_value",
            (rs, i) ->
                new ReferenceValueRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getBigDecimal(3),
                    Unit.fromSymbol(rs.getString(4)).orElseThrow()));
    List<UpperLimitRow> storedLimits =
        jdbc.query(
            "SELECT ul_set_code, nutrient_code, form_code, amount, unit, basis FROM upper_limit",
            (rs, i) ->
                new UpperLimitRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBigDecimal(4),
                    Unit.fromSymbol(rs.getString(5)).orElseThrow(),
                    rs.getString(6) == null ? null : UlBasis.valueOf(rs.getString(6))));
    List<ConversionRule> storedRules =
        jdbc.query(
            "SELECT nutrient_code, from_unit, to_unit, factor, form_code, note FROM unit_conversion",
            (rs, i) ->
                new ConversionRule(
                    rs.getString(1),
                    Unit.fromSymbol(rs.getString(2)).orElseThrow(),
                    Unit.fromSymbol(rs.getString(3)).orElseThrow(),
                    rs.getBigDecimal(4),
                    rs.getString(5),
                    rs.getString(6)));
    List<ReferenceValueRow> values = new ArrayList<>(storedValues);
    values.add(new ReferenceValueRow("TXR_REF", "TXR_N", BigDecimal.TEN, Unit.MG));
    List<UpperLimitRow> limits = new ArrayList<>(storedLimits);
    limits.add(new UpperLimitRow("TXR_UL", "TXR_N", "ANY", null, Unit.MG, null));
    List<ConversionRule> rules = new ArrayList<>(storedRules);
    rules.add(new ConversionRule("TXR_N", Unit.G, Unit.MG, new BigDecimal("1000"), "ANY", "t"));
    writeTransaction.execute(() -> {
      writer.upsertNutrients(List.of(n), report);
      writer.upsertReferenceSets(List.of(refSet), report);
      writer.upsertUlSets(List.of(ulSet), report);
      writer.replaceReferenceValues(values, report);
      writer.replaceUpperLimits(limits, report);
      writer.replaceUnitConversions(rules, report);
      return null;
    });
    assertThat(count("reference_value", "TXR_N")).isEqualTo(1);
    assertThat(count("upper_limit", "TXR_N")).isEqualTo(1);
    assertThat(count("unit_conversion", "TXR_N")).isEqualTo(1);

    WriteReport update = new WriteReport();
    List<ReferenceValueRow> changedValues = new ArrayList<>(storedValues);
    changedValues.add(new ReferenceValueRow("TXR_REF", "TXR_N", BigDecimal.ONE, Unit.MG));
    List<UpperLimitRow> changedLimits = new ArrayList<>(storedLimits);
    changedLimits.add(
        new UpperLimitRow("TXR_UL", "TXR_N", "ANY", BigDecimal.TEN, Unit.MG, UlBasis.TOTAL));
    List<ConversionRule> changedRules = new ArrayList<>(storedRules);
    changedRules.add(
        new ConversionRule("TXR_N", Unit.G, Unit.MG, new BigDecimal("999"), "ANY", "t"));
    writeTransaction.execute(() -> {
      writer.upsertNutrients(
          List.of(
              new NutrientRow("TXR_N", "N2", NutrientCategory.OTHER, Unit.MG, BigDecimal.ONE, 1)),
          update);
      writer.upsertReferenceSets(List.of(new SetRow("TXR_REF", "Ref 2", "citation")), update);
      writer.upsertUlSets(List.of(new SetRow("TXR_UL", "UL 2", "citation")), update);
      writer.replaceReferenceValues(changedValues, update);
      writer.replaceUpperLimits(changedLimits, update);
      writer.replaceUnitConversions(changedRules, update);
      return null;
    });
    assertThat(update.table("nutrient").updated()).isEqualTo(1);
    assertThat(update.table("reference_set").updated()).isEqualTo(1);
    assertThat(update.table("ul_set").updated()).isEqualTo(1);
    assertThat(update.table("reference_value").updated()).isEqualTo(1);
    assertThat(update.table("upper_limit").updated()).isEqualTo(1);
    assertThat(update.table("unit_conversion").updated()).isEqualTo(1);

    WriteReport removal = new WriteReport();
    writeTransaction.execute(() -> {
      writer.replaceReferenceValues(storedValues, removal);
      writer.replaceUpperLimits(storedLimits, removal);
      writer.replaceUnitConversions(storedRules, removal);
      return null;
    });
    assertThat(removal.table("reference_value").deleted()).isEqualTo(1);
    assertThat(removal.table("upper_limit").deleted()).isEqualTo(1);
    assertThat(removal.table("unit_conversion").deleted()).isEqualTo(1);
    assertThat(count("reference_value", "TXR_N")).isZero();
    assertThat(count("upper_limit", "TXR_N")).isZero();
    assertThat(count("unit_conversion", "TXR_N")).isZero();
  }

  private int count(String table, String nutrient) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE nutrient_code = ?", Integer.class, nutrient);
  }

  @Test
  void offersForUnknownProductsAreRejected() {
    long before = version();
    OfferValues offer =
        new OfferValues(
            "NO-SUCH-PRODUCT", "QX", "Shop", BigDecimal.ONE, "QXD", true, "https://a.example", NOW);
    assertThatThrownBy(() ->
            writeTransaction.execute(() -> {
              writer.applyOffers(List.of(offer), new WriteReport());
              return null;
            }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(version()).isEqualTo(before);
  }
}
