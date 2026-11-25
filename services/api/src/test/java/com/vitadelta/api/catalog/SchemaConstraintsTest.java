package com.vitadelta.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that every rule of {@code V1__catalog.sql} rejects bad rows. Each case runs in its own
 * transaction, which is rolled back, so the shared database is left unchanged.
 */
@SpringBootTest
class SchemaConstraintsTest extends PostgresIntegrationTest {

  private static final String PRODUCT_ID = "00000000-0000-4000-8000-000000000001";
  private static final String OFFER_ID = "00000000-0000-4000-8000-000000000002";

  /** Minimal valid rows that the cases build on; codes do not collide with the seed data. */
  private static final List<String> BASE =
      List.of(
          "INSERT INTO nutrient VALUES ('TST_E', 'Test E', 'VITAMIN', 'mg', NULL, 1)",
          "INSERT INTO nutrient VALUES ('TST_A', 'Test A', 'VITAMIN', 'mcg', 5, 2)",
          "INSERT INTO reference_set VALUES ('TST_REF', 'Test reference', 'Test citation')",
          "INSERT INTO ul_set VALUES ('TST_UL', 'Test UL', 'Test citation')",
          "INSERT INTO nutrient_form VALUES ('TST_A_FORM', 'TST_A', 'Test form of A')",
          "INSERT INTO nutrient_form VALUES ('TST_E_FORM', 'TST_E', 'Test form of E')",
          "INSERT INTO country VALUES ('ZZ', 'Testland', 'ZZD', 'TST_REF', 'TST_UL', 'en')",
          "INSERT INTO country VALUES ('ZY', 'Otherland', 'ZYD', 'TST_REF', 'TST_UL', 'en-GB')",
          "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
              + " units_per_serving, servings_per_day, synthetic, created_at, updated_at) VALUES ('"
              + PRODUCT_ID
              + "', 'TEST-P1', 'Fixture Labs', 'Test', 'TABLET', 60, 1, 1.00, true, now(), now())");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private Flyway flyway;

  private static String product(String overrides) {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("id", "gen_random_uuid()");
    columns.put("code", "'TEST-P2'");
    columns.put("brand", "'Fixture Labs'");
    columns.put("name", "'Test'");
    columns.put("dosage_form", "'CAPSULE'");
    columns.put("units_per_container", "30");
    columns.put("units_per_serving", "1");
    columns.put("servings_per_day", "1");
    columns.put("gtin", "NULL");
    columns.put("source", "NULL");
    columns.put("source_ref", "NULL");
    columns.put("synthetic", "true");
    columns.put("created_at", "now()");
    columns.put("updated_at", "now()");
    for (String pair : overrides.split(";")) {
      if (!pair.isBlank()) {
        String[] kv = pair.split("=", 2);
        columns.put(kv[0].strip(), kv[1].strip());
      }
    }
    return "INSERT INTO product ("
        + String.join(", ", columns.keySet())
        + ") VALUES ("
        + String.join(", ", columns.values())
        + ")";
  }

  private static String productNutrient(String nutrient, String amount, String ul, String form) {
    return "INSERT INTO product_nutrient VALUES ('"
        + PRODUCT_ID
        + "', '"
        + nutrient
        + "', "
        + amount
        + ", "
        + ul
        + ", 'mg', "
        + form
        + ", NULL)";
  }

  private static String offer(String retailer, String price, String currency, String url) {
    return "INSERT INTO offer VALUES (gen_random_uuid(), '"
        + PRODUCT_ID
        + "', 'ZZ', "
        + retailer
        + ", "
        + price
        + ", "
        + currency
        + ", true, "
        + url
        + ", now())";
  }

  private static String validOffer() {
    return "INSERT INTO offer VALUES ('"
        + OFFER_ID
        + "', '"
        + PRODUCT_ID
        + "', 'ZZ', 'Shop', 10.00, 'ZZD', true, 'https://a.example/x', now())";
  }

  /** Runs BASE plus {@code setup}, then asserts that {@code statement} fails; rolls back. */
  private void assertRejected(String statement, String... setup) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          status.setRollbackOnly();
          BASE.forEach(jdbc::execute);
          for (String s : setup) {
            jdbc.execute(s);
          }
          assertThatThrownBy(() -> jdbc.execute(statement)).isInstanceOf(DataAccessException.class);
        });
  }

  /** Runs BASE plus {@code statements} and asserts that all succeed; rolls back. */
  private void assertAccepted(String... statements) {
    List<String> executed = new ArrayList<>();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          status.setRollbackOnly();
          BASE.forEach(jdbc::execute);
          for (String s : statements) {
            jdbc.execute(s);
            executed.add(s);
          }
        });
    assertThat(executed).hasSize(statements.length);
  }

  @Test
  void migrationAppliedOnTheCleanDatabase() {
    MigrationInfo[] applied = flyway.info().applied();
    assertThat(applied).isNotEmpty();
    assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
    assertThat(applied[0].getDescription()).isEqualTo("catalog");
    assertThat(applied[0].getState()).isEqualTo(MigrationState.SUCCESS);
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                    + " AND table_type = 'BASE TABLE' ORDER BY table_name",
                String.class))
        .contains(
            "catalog_version",
            "country",
            "import_job",
            "import_job_error",
            "nutrient",
            "nutrient_form",
            "offer",
            "product",
            "product_availability",
            "product_nutrient",
            "reference_set",
            "reference_value",
            "ul_set",
            "unit_conversion",
            "upper_limit");
  }

  @Test
  void timestampsAreTimestamptzAndNumericPrecisionsMatch() {
    List<Map<String, Object>> columns =
        jdbc.queryForList(
            "SELECT table_name, column_name, data_type, numeric_precision, numeric_scale"
                + " FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name <> 'flyway_schema_history'");
    assertThat(columns)
        .filteredOn(c -> String.valueOf(c.get("data_type")).startsWith("timestamp"))
        .allSatisfy(c -> assertThat(c.get("data_type")).isEqualTo("timestamp with time zone"))
        .hasSizeGreaterThanOrEqualTo(6);
    assertThat(precision(columns, "offer", "price")).isEqualTo("12,2");
    assertThat(precision(columns, "product", "servings_per_day")).isEqualTo("4,2");
    assertThat(precision(columns, "product_nutrient", "amount_per_serving")).isEqualTo("14,4");
    assertThat(precision(columns, "product_nutrient", "ul_amount_per_serving")).isEqualTo("14,4");
    assertThat(precision(columns, "reference_value", "amount")).isEqualTo("14,4");
    assertThat(precision(columns, "upper_limit", "amount")).isEqualTo("14,4");
    assertThat(precision(columns, "nutrient", "presence_floor_per_day")).isEqualTo("14,4");
  }

  private static String precision(List<Map<String, Object>> columns, String table, String column) {
    return columns.stream()
        .filter(c -> table.equals(c.get("table_name")) && column.equals(c.get("column_name")))
        .map(c -> c.get("numeric_precision") + "," + c.get("numeric_scale"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void requiredIndexesExist() {
    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'", String.class);
    assertThat(indexes)
        .anySatisfy(d ->
            assertThat(d)
                .contains("ON public.offer")
                .contains("(product_id, country_code, in_stock, checked_at DESC)"))
        .anySatisfy(d ->
            assertThat(d)
                .contains("ON public.product_availability")
                .contains("(country_code, status)"))
        .anySatisfy(
            d -> assertThat(d).contains("ON public.product_nutrient").contains("(nutrient_code)"))
        .anySatisfy(
            d -> assertThat(d).contains("UNIQUE").contains("ON public.product").contains("(code)"))
        .anySatisfy(d ->
            assertThat(d)
                .contains("UNIQUE")
                .contains("ON public.offer")
                .contains("(product_id, country_code, retailer)"));
  }

  @Test
  void validRowsAreAccepted() {
    assertAccepted(
        productNutrient("TST_E", "10", "NULL", "'UNKNOWN'"),
        productNutrient("TST_A", "10", "10", "'TST_A_FORM'"),
        "INSERT INTO product_availability VALUES ('" + PRODUCT_ID + "', 'ZZ', 'AVAILABLE', NULL)",
        "INSERT INTO reference_value VALUES ('TST_REF', 'TST_E', 12, 'mg')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'ANY', 300, 'mg', 'TOTAL', 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_A', 'TST_A_FORM', NULL, 'mcg', NULL, 'Test citation')",
        "INSERT INTO unit_conversion (nutrient_code, from_unit, to_unit, factor, form_code, note)"
            + " VALUES ('TST_E', 'IU', 'mg', 0.67, 'ANY', 'test')",
        "INSERT INTO unit_conversion (nutrient_code, from_unit, to_unit, factor, form_code, note)"
            + " VALUES ('*', 'g', 'mcg', 1000000, 'ANY', NULL)",
        validOffer(),
        offer("'Other Shop'", "1", "'ZZD'", "'HTTP://A.EXAMPLE'"),
        product("code='A1'; gtin='12345678'; synthetic=false; source='DSLD'; source_ref='1'"),
        "UPDATE country SET currency = 'ZXD' WHERE code = 'ZY'",
        "INSERT INTO import_job VALUES (gen_random_uuid(), 'OFFERS_CSV', 'RUNNING', now(), NULL, NULL)");
  }

  // nutrient

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO nutrient VALUES ('TST_E', 'Duplicate', 'VITAMIN', 'mg', NULL, 3)",
        "INSERT INTO nutrient VALUES ('TST_X', 'X', 'HERB', 'mg', NULL, 3)",
        "INSERT INTO nutrient VALUES ('TST_X', 'X', 'VITAMIN', 'IU', NULL, 3)",
        "INSERT INTO nutrient VALUES ('TST_X', 'X', 'VITAMIN', 'mg', 0, 3)",
        "INSERT INTO nutrient VALUES ('tst_x', 'X', 'VITAMIN', 'mg', NULL, 3)",
        "INSERT INTO nutrient VALUES ('TST_X', ' ', 'VITAMIN', 'mg', NULL, 3)",
        "INSERT INTO nutrient VALUES ('TST_X', NULL, 'VITAMIN', 'mg', NULL, 3)"
      })
  void nutrientRulesRejectBadRows(String statement) {
    assertRejected(statement);
  }

  // reference and UL sets, reference values, upper limits

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO reference_set VALUES ('TST_REF', 'Dup', 'c')",
        "INSERT INTO reference_set VALUES ('tst', 'x', 'c')",
        "INSERT INTO reference_set VALUES ('TST_R2', 'x', '  ')",
        "INSERT INTO ul_set VALUES ('TST_UL', 'Dup', 'c')",
        "INSERT INTO ul_set VALUES ('TST_U2', 'x', '')",
        "INSERT INTO reference_value VALUES ('TST_REF', 'TST_E', 0, 'mg')",
        "INSERT INTO reference_value VALUES ('TST_REF', 'TST_E', 12, 'IU')",
        "INSERT INTO reference_value VALUES ('NOPE', 'TST_E', 12, 'mg')",
        "INSERT INTO reference_value VALUES ('TST_REF', 'NOPE', 12, 'mg')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'UNKNOWN', 300, 'mg', 'TOTAL', 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', NULL, 300, 'mg', 'TOTAL', 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'ANY', 300, 'mg', NULL, 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'ANY', NULL, 'mg', 'TOTAL', 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'ANY', 300, 'mg', 'DIETARY', 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'ANY', 0, 'mg', 'TOTAL', 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'ANY', 300, 'IU', 'TOTAL', 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'any', 300, 'mg', 'TOTAL', 'Test citation')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('NOPE', 'TST_E', 'ANY', 300, 'mg', 'TOTAL', 'Test citation')"
      })
  void referenceRulesRejectBadRows(String statement) {
    assertRejected(statement);
  }

  @Test
  void upperLimitKeyIsUnique() {
    String row =
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'ANY', 300, 'mg', 'TOTAL', 'Test citation')";
    assertRejected(row, row);
  }

  // unit conversion

  @ParameterizedTest
  @ValueSource(
      strings = {
        "VALUES ('*', 'IU', 'mg', 0.67, 'D_ALPHA_TOCOPHEROL', NULL)",
        "VALUES ('TST_E', 'IU', 'mg', 0.67, 'UNKNOWN', NULL)",
        "VALUES ('TST_E', 'IU', 'mg', 0, 'ANY', NULL)",
        "VALUES ('TST_E', 'mg', 'mg', 1, 'ANY', NULL)",
        "VALUES ('TST_E', 'mg', 'IU', 1.5, 'ANY', NULL)",
        "VALUES ('TST_E', 'kg', 'mg', 1, 'ANY', NULL)",
        "VALUES ('NOPE', 'IU', 'mg', 0.67, 'ANY', NULL)",
        "VALUES ('x', 'IU', 'mg', 0.67, 'ANY', NULL)",
        "VALUES ('TST_E', 'IU', 'mg', 0.67, NULL, NULL)"
      })
  void unitConversionRulesRejectBadRows(String values) {
    assertRejected(
        "INSERT INTO unit_conversion (nutrient_code, from_unit, to_unit, factor, form_code, note) "
            + values);
  }

  @Test
  void unitConversionKeyIsUnique() {
    String row =
        "INSERT INTO unit_conversion (nutrient_code, from_unit, to_unit, factor, form_code, note)"
            + " VALUES ('TST_E', 'g', 'mg', 1000, 'ANY', NULL)";
    assertRejected(row, row);
  }

  // country

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO country VALUES ('ZZ', 'Dup', 'ZZD', 'TST_REF', 'TST_UL', 'en')",
        "INSERT INTO country VALUES ('zx', 'X', 'ZXD', 'TST_REF', 'TST_UL', 'en')",
        "INSERT INTO country VALUES ('ZX', 'X', 'zxd', 'TST_REF', 'TST_UL', 'en')",
        "INSERT INTO country VALUES ('ZX', 'X', 'ZXD', 'NOPE', 'TST_UL', 'en')",
        "INSERT INTO country VALUES ('ZX', 'X', 'ZXD', 'TST_REF', 'NOPE', 'en')",
        "INSERT INTO country VALUES ('ZX', 'X', 'ZXD', 'TST_REF', 'TST_UL', 'EN')"
      })
  void countryRulesRejectBadRows(String statement) {
    assertRejected(statement);
  }

  // product

  @ParameterizedTest
  @ValueSource(
      strings = {
        "code='TEST-P1'",
        "code='-ABC'",
        "code='abc'",
        "code='A'",
        "code='A_B'",
        "code=NULL",
        "brand=' '",
        "name=''",
        "dosage_form='PILL'",
        "units_per_serving=0",
        "units_per_container=1; units_per_serving=2",
        "servings_per_day=0",
        "servings_per_day=100",
        "gtin='1234567'",
        "gtin='123456789'",
        "synthetic=false; source='DSLD'",
        "synthetic=false; source_ref='1'",
        "source_ref=' '",
        "synthetic=NULL",
        "updated_at=now() - interval '1 day'"
      })
  void productRulesRejectBadRows(String overrides) {
    assertRejected(product(overrides));
  }

  @Test
  void productCodeAcceptsTheFullPattern() {
    assertAccepted(
        product("code='" + "A".repeat(40) + "'"), product("code='9-Z'; id='" + OFFER_ID + "'"));
    assertRejected(product("code='" + "A".repeat(41) + "'"));
  }

  // product nutrient

  @Test
  void productNutrientFormSentinelRules() {
    assertRejected(productNutrient("TST_E", "10", "NULL", "'ANY'"));
    assertRejected(productNutrient("TST_E", "10", "NULL", "NULL"));
    assertRejected(productNutrient("TST_E", "10", "NULL", "'d_alpha'"));
  }

  @Test
  void productNutrientAmountRules() {
    assertRejected(productNutrient("TST_E", "0", "NULL", "'UNKNOWN'"));
    assertRejected(productNutrient("TST_E", "-1", "NULL", "'UNKNOWN'"));
    assertRejected(productNutrient("TST_E", "10", "10.0001", "'UNKNOWN'"));
    assertRejected(productNutrient("TST_E", "10", "-0.0001", "'UNKNOWN'"));
    assertAccepted(productNutrient("TST_E", "10", "0", "'UNKNOWN'"));
  }

  @Test
  void productNutrientKeysAndReferences() {
    String row = productNutrient("TST_E", "10", "NULL", "'UNKNOWN'");
    assertRejected(row, row);
    assertRejected(productNutrient("NOPE", "10", "NULL", "'UNKNOWN'"));
    assertRejected(
        "INSERT INTO product_nutrient VALUES ('"
            + PRODUCT_ID
            + "', 'TST_E', 10, NULL, 'ug', 'UNKNOWN', NULL)");
  }

  // availability

  @Test
  void availabilityRules() {
    String row =
        "INSERT INTO product_availability VALUES ('" + PRODUCT_ID + "', 'ZZ', 'AVAILABLE', NULL)";
    assertRejected(row, row);
    assertRejected(
        "INSERT INTO product_availability VALUES ('" + PRODUCT_ID + "', 'ZZ', 'SOLD_OUT', NULL)");
    assertRejected(
        "INSERT INTO product_availability VALUES ('" + PRODUCT_ID + "', 'QQ', 'AVAILABLE', NULL)");
    assertRejected(
        "INSERT INTO product_availability VALUES (gen_random_uuid(), 'ZZ', 'AVAILABLE', NULL)");
  }

  // offer

  @ParameterizedTest
  @ValueSource(
      strings = {
        "'javascript:alert(1)'",
        "'ftp://a.example/x'",
        "'data:text/html,x'",
        "'/relative'",
        "'https://'",
        "'https:///path'",
        "'https://a.example/x y'",
        "'httpx://a.example'"
      })
  void offerUrlSchemeIsEnforced(String url) {
    assertRejected(offer("'Shop'", "10", "'ZZD'", url));
  }

  @Test
  void offerUrlLengthIsLimited() {
    assertRejected(offer("'Shop'", "10", "'ZZD'", "'https://a.example/" + "x".repeat(2040) + "'"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"'Shop', '0'", "'Shop', '-1'", "' Shop', '10'", "'Shop ', '10'", "'', '10'"})
  void offerRetailerAndPriceRules(String retailerAndPrice) {
    String[] parts = retailerAndPrice.split(", ");
    assertRejected(offer(parts[0], parts[1], "'ZZD'", "'https://a.example/x'"));
  }

  @Test
  void offerNaturalKeyIsUnique() {
    assertRejected(offer("'Shop'", "20", "'ZZD'", "'https://a.example/y'"), validOffer());
    assertAccepted(validOffer(), offer("'shop'", "20", "'ZZD'", "'https://a.example/y'"));
  }

  @Test
  void offerCurrencyMustEqualCountryCurrencyOnInsert() {
    assertRejected(offer("'Shop'", "10", "'ZYD'", "'https://a.example/x'"));
  }

  @Test
  void offerCurrencyMustEqualCountryCurrencyOnUpdate() {
    assertRejected("UPDATE offer SET currency = 'ZYD' WHERE id = '" + OFFER_ID + "'", validOffer());
    assertRejected(
        "UPDATE offer SET country_code = 'ZY' WHERE id = '" + OFFER_ID + "'", validOffer());
  }

  @Test
  void currencyMismatchMessageNamesTheRule() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          status.setRollbackOnly();
          BASE.forEach(jdbc::execute);
          assertThatThrownBy(
                  () -> jdbc.execute(offer("'Shop'", "10", "'EUR'", "'https://a.example/x'")))
              .hasMessageContaining("currency_mismatch");
        });
  }

  @Test
  void countryCurrencyCannotChangeWhileOffersExist() {
    assertRejected("UPDATE country SET currency = 'ZXD' WHERE code = 'ZZ'", validOffer());
    assertAccepted(validOffer(), "UPDATE country SET name_en = 'Renamed' WHERE code = 'ZZ'");
  }

  // catalog version

  @Test
  void catalogVersionHasExactlyOneRow() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_version", Integer.class))
        .isEqualTo(1);
    assertRejected("INSERT INTO catalog_version VALUES (2, 0, now())");
    assertRejected("INSERT INTO catalog_version VALUES (1, 0, now())");
    assertRejected("UPDATE catalog_version SET version = -1 WHERE id = 1");
    assertRejected("UPDATE catalog_version SET bumped_at = NULL WHERE id = 1");
  }

  @Test
  void noTriggerTouchesTheCatalogVersion() {
    List<String> triggers =
        jdbc.queryForList(
            "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal ORDER BY tgname", String.class);
    assertThat(triggers)
        .containsExactly("country_currency_change_guard", "offer_currency_matches_country");
  }

  // import jobs

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO import_job VALUES (gen_random_uuid(), 'XML', 'RUNNING', now(), NULL, NULL)",
        "INSERT INTO import_job VALUES (gen_random_uuid(), 'OFFERS_CSV', 'DONE', now(), NULL, NULL)",
        "INSERT INTO import_job VALUES (gen_random_uuid(), 'OFFERS_CSV', 'RUNNING', now(), now(), NULL)",
        "INSERT INTO import_job VALUES (gen_random_uuid(), 'OFFERS_CSV', 'FAILED', now(), NULL, NULL)",
        "INSERT INTO import_job VALUES (gen_random_uuid(), 'OFFERS_CSV', 'FAILED', now(),"
            + " now() - interval '1 hour', NULL)",
        "INSERT INTO import_job_error VALUES (gen_random_uuid(), gen_random_uuid(), 2, 'x')"
      })
  void importJobRulesRejectBadRows(String statement) {
    assertRejected(statement);
  }

  @Test
  void importJobErrorRules() {
    String job =
        "INSERT INTO import_job VALUES ('"
            + OFFER_ID
            + "', 'PRODUCTS_JSON', 'FAILED', now(), now(), '{\"rows\": 1}')";
    assertAccepted(
        job, "INSERT INTO import_job_error VALUES (gen_random_uuid(), '" + OFFER_ID + "', 1, 'x')");
    assertRejected(
        "INSERT INTO import_job_error VALUES (gen_random_uuid(), '" + OFFER_ID + "', 0, 'x')", job);
    assertRejected(
        "INSERT INTO import_job_error VALUES (gen_random_uuid(), '" + OFFER_ID + "', 1, ' ')", job);
  }

  // IU allow-list (independent of the conversion table)

  private static final String[] IU_NUTRIENTS = {
    "INSERT INTO nutrient VALUES ('VIT_A', 'Vitamin A', 'VITAMIN', 'mcg', NULL, 10)"
        + " ON CONFLICT DO NOTHING",
    "INSERT INTO nutrient VALUES ('VIT_D', 'Vitamin D', 'VITAMIN', 'mcg', NULL, 30)"
        + " ON CONFLICT DO NOTHING",
    "INSERT INTO nutrient VALUES ('VIT_E', 'Vitamin E', 'VITAMIN', 'mg', NULL, 40)"
        + " ON CONFLICT DO NOTHING",
    "INSERT INTO nutrient_form VALUES ('RETINOL', 'VIT_A', 'Retinol') ON CONFLICT DO NOTHING",
    "INSERT INTO nutrient_form VALUES ('RETINYL_ACETATE', 'VIT_A', 'Retinyl acetate')"
        + " ON CONFLICT DO NOTHING",
    "INSERT INTO nutrient_form VALUES ('RETINYL_PALMITATE', 'VIT_A', 'Retinyl palmitate')"
        + " ON CONFLICT DO NOTHING"
  };

  private static String iuRow(String nutrient, String form) {
    return "INSERT INTO product_nutrient VALUES ('"
        + PRODUCT_ID
        + "', '"
        + nutrient
        + "', 400, NULL, 'IU', '"
        + form
        + "', NULL)";
  }

  @Test
  void iuIsOnlyAllowedForVitaminsADAndE() {
    assertRejected(iuRow("TST_E", "UNKNOWN"));
    assertAccepted(
        IU_NUTRIENTS[0],
        IU_NUTRIENTS[1],
        IU_NUTRIENTS[2],
        IU_NUTRIENTS[5],
        iuRow("VIT_D", "UNKNOWN"),
        iuRow("VIT_E", "UNKNOWN"),
        iuRow("VIT_A", "RETINYL_PALMITATE"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"BETA_CAROTENE", "UNKNOWN", "RETINOL_X"})
  void vitaminAInIuNeedsARetinolForm(String form) {
    assertRejected(iuRow("VIT_A", form), IU_NUTRIENTS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"RETINOL", "RETINYL_ACETATE", "RETINYL_PALMITATE"})
  void vitaminAInIuWithRetinolFormIsAccepted(String form) {
    assertAccepted(
        IU_NUTRIENTS[0], IU_NUTRIENTS[3], IU_NUTRIENTS[4], IU_NUTRIENTS[5], iuRow("VIT_A", form));
  }

  // NaN is not a valid amount anywhere

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO nutrient VALUES ('TST_NAN', 'NaN', 'OTHER', 'mg', 'NaN', 1)",
        "INSERT INTO reference_value VALUES ('TST_REF', 'TST_E', 'NaN', 'mg')",
        "INSERT INTO upper_limit (ul_set_code, nutrient_code, form_code, amount, unit, basis, note)"
            + " VALUES ('TST_UL', 'TST_E', 'ANY', 'NaN', 'mg', 'TOTAL', 'Test citation')",
        "INSERT INTO unit_conversion (nutrient_code, from_unit, to_unit, factor, form_code, note)"
            + " VALUES ('TST_E', 'IU', 'mg', 'NaN', 'ANY', NULL)",
        "INSERT INTO product_nutrient VALUES ('"
            + PRODUCT_ID
            + "', 'TST_E', 'NaN', NULL, 'mg', 'UNKNOWN', NULL)",
        "INSERT INTO product_nutrient VALUES ('"
            + PRODUCT_ID
            + "', 'TST_E', 10, 'NaN', 'mg', 'UNKNOWN', NULL)",
        "INSERT INTO offer VALUES (gen_random_uuid(), '"
            + PRODUCT_ID
            + "', 'ZZ', 'Shop', 'NaN', 'ZZD', true, 'https://a.example/x', now())",
        "UPDATE product SET servings_per_day = 'NaN' WHERE code = 'TEST-P1'"
      })
  void nanIsRejected(String statement) {
    assertRejected(statement);
  }

  // offer URL host

  @ParameterizedTest
  @ValueSource(
      strings = {
        "'https://user@:443/path'",
        "'https://:443/'",
        "'https://user@/x'",
        "'https://a@b@host.example/x'"
      })
  void offerUrlNeedsAHost(String url) {
    assertRejected(offer("'Shop'", "10", "'ZZD'", url));
  }

  @Test
  void offerUrlAcceptsUserinfoPortIpv6AndIdnHosts() {
    assertAccepted(
        offer("'S1'", "10", "'ZZD'", "'https://user:pw@host.example:8443/p?q=1#f'"),
        offer("'S2'", "10", "'ZZD'", "'http://[2001:db8::1]:8080/'"),
        offer("'S3'", "10", "'ZZD'", "'https://bücher.example/p'"),
        offer("'S4'", "10", "'ZZD'", "'https://host.example'"),
        offer("'S5'", "10", "'ZZD'", "'https://host.example:443'"));
  }
}
