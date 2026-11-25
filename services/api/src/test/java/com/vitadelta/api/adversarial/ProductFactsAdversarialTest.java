package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The regulatory facts of {@code GET /products/{id}} ({@code reference} and {@code upperLimit})
 * attacked from outside: the values are recomputed here from the catalog tables with the contract's
 * rule (the requested country's reference set and UL set; UL row of the form, else {@code ANY},
 * else none; exempt rows give none) and must match exactly; no other country's set ever appears; a
 * product that is not in market never exposes them; hostile country codes never select another
 * country's data; extreme amounts are written as plain decimals with four places.
 */
class ProductFactsAdversarialTest extends CatalogAdversarialTest {

  private static final List<String> COUNTRIES = List.of("TR", "DE", "GB", "US");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private static final JsonMapper EXACT =
      JsonMapper.builder()
          .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  /** Every numeric member of a fact object as written in the raw body. */
  private static final Pattern FACT_NUMBER =
      Pattern.compile("\"(amount|percentOfReference|ulAmountPerDay|amountPerDay)\":([^,}\\]]+)");

  private String userToken;

  @BeforeEach
  void signIn() {
    userToken = token(Role.USER);
  }

  record Sets(String reference, String upperLimit) {}

  record UlRow(BigDecimal amount, String unit, String basis, String note) {}

  private Sets sets(String country) {
    return jdbc.sql("SELECT reference_set_code, ul_set_code FROM country WHERE code = ?")
        .param(country)
        .query((rs, i) -> new Sets(rs.getString(1), rs.getString(2)))
        .single();
  }

  private Optional<BigDecimal> reference(String set, String nutrient) {
    return jdbc.sql(
            "SELECT amount FROM reference_value WHERE reference_set_code = ? AND nutrient_code = ?")
        .params(set, nutrient)
        .query(BigDecimal.class)
        .optional();
  }

  private Optional<UlRow> ulRow(String set, String nutrient, String form) {
    return jdbc.sql(
            "SELECT amount, unit, basis, note FROM upper_limit WHERE ul_set_code = ?"
                + " AND nutrient_code = ? AND form_code = ?")
        .params(set, nutrient, form)
        .query((rs, i) ->
            new UlRow(rs.getBigDecimal(1), rs.getString(2), rs.getString(3), rs.getString(4)))
        .optional();
  }

  /** The contract's matching rule, recomputed from the table: form row, else ANY, else none. */
  private Optional<UlRow> expectedUl(String set, String nutrient, String form) {
    Optional<UlRow> row = Optional.empty();
    if (!form.equals("UNKNOWN")) {
      row = ulRow(set, nutrient, form);
    }
    if (row.isEmpty()) {
      row = ulRow(set, nutrient, "ANY");
    }
    return row.filter(r -> r.amount() != null);
  }

  private Resp detail(UUID id, String country) {
    return req("GET", API + "/products/" + id + "?country=" + country).bearer(userToken).send();
  }

  private record StoredRow(
      String nutrient,
      BigDecimal amountPerServing,
      BigDecimal ulAmountPerServing,
      String unit,
      String form,
      String canonicalUnit) {}

  private List<StoredRow> storedRows(UUID productId) {
    return jdbc.sql(
            "SELECT pn.nutrient_code, pn.amount_per_serving, pn.ul_amount_per_serving, pn.unit,"
                + " pn.form_code, n.canonical_unit FROM product_nutrient pn JOIN nutrient n ON"
                + " n.code = pn.nutrient_code WHERE pn.product_id = ?")
        .param(productId)
        .query((rs, i) ->
            new StoredRow(
                rs.getString(1),
                rs.getBigDecimal(2),
                rs.getBigDecimal(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6)))
        .list();
  }

  private BigDecimal servingsPerDay(UUID productId) {
    return jdbc.sql("SELECT servings_per_day FROM product WHERE id = ?")
        .param(productId)
        .query(BigDecimal.class)
        .single();
  }

  private static BigDecimal four(BigDecimal value) {
    return value.setScale(4, RoundingMode.HALF_EVEN);
  }

  /**
   * Checks every nutrient row of a detail response against the tables; returns the problems found
   * (empty when the response is exactly what the contract rule gives).
   */
  private List<String> verify(UUID productId, String country, Resp response) {
    List<String> problems = new ArrayList<>();
    Sets own = sets(country);
    BigDecimal spd = servingsPerDay(productId);
    Map<String, StoredRow> stored = new HashMap<>();
    storedRows(productId).forEach(r -> stored.put(r.nutrient(), r));
    JsonNode product = EXACT.readTree(response.body()).get("product");
    String where = country + "/" + product.get("code").asString();
    for (JsonNode n : product.get("nutrients")) {
      String code = n.get("code").asString();
      String at = where + "/" + code;
      StoredRow row = stored.get(code);
      if (row == null) {
        problems.add(at + ": not a stored nutrient row");
        continue;
      }
      if (!n.has("reference") || !n.has("upperLimit")) {
        problems.add(at + ": a fact key is missing");
        continue;
      }
      boolean canonical = row.unit().equals(row.canonicalUnit());
      BigDecimal amountPerDay =
          canonical ? row.amountPerServing().multiply(spd) : n.get("amountPerDay").decimalValue();

      Optional<BigDecimal> ref = reference(own.reference(), code);
      JsonNode reference = n.get("reference");
      if (ref.isEmpty() != reference.isNull()) {
        problems.add(at + ": reference " + reference + " but table has " + ref.isPresent());
      } else if (ref.isPresent()) {
        BigDecimal refAmount = ref.get();
        if (!reference.get("setCode").asString().equals(own.reference())
            || !reference.get("source").asString().equals("REGULATORY")
            || !reference.get("unit").asString().equals(row.canonicalUnit())
            || reference.get("amount").decimalValue().compareTo(four(refAmount)) != 0) {
          problems.add(at + ": reference " + reference + " vs " + own.reference() + refAmount);
        }
        BigDecimal percent = reference.get("percentOfReference").decimalValue();
        BigDecimal expected =
            amountPerDay.multiply(HUNDRED).divide(refAmount, 4, RoundingMode.HALF_EVEN);
        BigDecimal tolerance =
            canonical
                ? BigDecimal.ZERO
                : expected.abs().movePointLeft(3).add(new BigDecimal("0.0002"));
        if (percent.subtract(expected).abs().compareTo(tolerance) > 0) {
          problems.add(at + ": percentOfReference " + percent + " expected " + expected);
        }
      }

      Optional<UlRow> ul = expectedUl(own.upperLimit(), code, row.form());
      JsonNode limit = n.get("upperLimit");
      if (ul.isEmpty() != limit.isNull()) {
        problems.add(at + ": upperLimit " + limit + " but rule gives " + ul);
      } else if (ul.isPresent()) {
        UlRow u = ul.get();
        String note = u.note() == null || u.note().isEmpty() ? null : u.note();
        String writtenNote = limit.get("note").isNull() ? null : limit.get("note").asString();
        if (!limit.get("setCode").asString().equals(own.upperLimit())
            || !limit.get("unit").asString().equals(row.canonicalUnit())
            || !limit.get("basis").asString().equals(u.basis())
            || limit.get("amount").decimalValue().compareTo(four(u.amount())) != 0
            || !java.util.Objects.equals(writtenNote, note)) {
          problems.add(at + ": upperLimit " + limit + " vs " + own.upperLimit() + " " + u);
        }
        if (canonical) {
          BigDecimal ulPerDay =
              (row.ulAmountPerServing() == null ? row.amountPerServing() : row.ulAmountPerServing())
                  .multiply(spd);
          if (limit.get("ulAmountPerDay").decimalValue().compareTo(four(ulPerDay)) != 0
              || limit.get("exceeded").asBoolean() != (ulPerDay.compareTo(u.amount()) > 0)) {
            problems.add(at + ": ulAmountPerDay/exceeded " + limit + " expected " + ulPerDay);
          }
        }
      }
    }
    Matcher numbers = FACT_NUMBER.matcher(response.body());
    while (numbers.find()) {
      String value = numbers.group(2);
      if (!value.equals("null") && !value.matches("\\d+\\.\\d{4}")) {
        problems.add(where + ": " + numbers.group(1) + " written as " + value);
      }
    }
    return problems;
  }

  private List<UUID> inMarket(String country, boolean synthetic, int limit) {
    return jdbc.sql(
            "SELECT p.id FROM product p JOIN product_availability a ON a.product_id = p.id"
                + " WHERE a.country_code = ? AND a.status = 'AVAILABLE' AND p.synthetic = ?"
                + " ORDER BY p.code LIMIT ?")
        .params(country, synthetic, limit)
        .query(UUID.class)
        .list();
  }

  @Test
  void everyFactIsTheRequestedCountrysOwnValueRecomputedFromTheCatalog() {
    List<String> problems = new ArrayList<>();
    int checked = 0;
    for (String country : COUNTRIES) {
      List<UUID> ids = new ArrayList<>(inMarket(country, true, 200));
      ids.addAll(inMarket(country, false, 15));
      Set<String> foreign = new java.util.HashSet<>();
      for (String other : COUNTRIES) {
        Sets s = sets(other);
        foreign.add(s.reference());
        foreign.add(s.upperLimit());
      }
      foreign.remove(sets(country).reference());
      foreign.remove(sets(country).upperLimit());
      for (UUID id : ids) {
        Resp response = detail(id, country);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        ContractSchemas.assertConforms("ProductDetailResponse", response.body());
        problems.addAll(verify(id, country, response));
        for (String set : foreign) {
          if (response.body().contains("\"setCode\":\"" + set + "\"")) {
            problems.add(country + "/" + id + ": names the foreign set " + set);
          }
        }
        checked++;
      }
    }
    assertThat(checked).as("products checked").isGreaterThan(20);
    assertThat(problems).as("facts that differ from the contract rule").isEmpty();
  }

  @Test
  void oneProductInSeveralCountriesShowsEachCountrysOwnSets() {
    List<UUID> shared =
        jdbc.sql(
                "SELECT product_id FROM product_availability WHERE status = 'AVAILABLE'"
                    + " AND country_code IN ('TR', 'US') GROUP BY product_id"
                    + " HAVING count(*) = 2 ORDER BY product_id LIMIT 5")
            .query(UUID.class)
            .list();
    assertThat(shared).as("the seed sells products in TR and US").isNotEmpty();
    List<String> problems = new ArrayList<>();
    for (UUID id : shared) {
      for (String country : List.of("TR", "US")) {
        Resp response = detail(id, country);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        problems.addAll(verify(id, country, response));
      }
    }
    assertThat(problems).isEmpty();
  }

  private UUID insertProduct(String servingsPerDay, Map<String, String[]> nutrients) {
    UUID id = UUID.randomUUID();
    String code = PREFIX + "FACTS-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    jdbc.sql(
            "INSERT INTO product (id, code, brand, name, dosage_form, units_per_container,"
                + " units_per_serving, servings_per_day, synthetic, created_at, updated_at)"
                + " VALUES (?, ?, 'Synthetic Adversary Labs', 'Synthetic facts probe', 'TABLET',"
                + " 60, 1, CAST(? AS numeric), true, now(), now())")
        .params(id, code, servingsPerDay)
        .update();
    for (Map.Entry<String, String[]> n : nutrients.entrySet()) {
      String[] v = n.getValue();
      jdbc.sql(
              "INSERT INTO product_nutrient (product_id, nutrient_code, amount_per_serving,"
                  + " ul_amount_per_serving, unit, form_code) VALUES (?, ?, CAST(? AS numeric),"
                  + " CAST(? AS numeric), ?, ?)")
          .params(id, n.getKey(), v[0], v[1], v[2], v[3])
          .update();
    }
    jdbc.sql(
            "INSERT INTO product_availability (product_id, country_code, status)"
                + " VALUES (?, 'TR', 'AVAILABLE'), (?, 'US', 'NOT_SOLD')")
        .params(id, id)
        .update();
    jdbc.sql("UPDATE catalog_version SET version = version + 1, bumped_at = now() WHERE id = 1")
        .update();
    return id;
  }

  private static JsonNode nutrient(Resp response, String code) {
    for (JsonNode n : EXACT.readTree(response.body()).get("product").get("nutrients")) {
      if (n.get("code").asString().equals(code)) {
        return n;
      }
    }
    throw new AssertionError("no " + code + " in " + response.body());
  }

  private String betaCaroteneForm() {
    return jdbc.sql(
            "SELECT form_code FROM upper_limit WHERE nutrient_code = 'VIT_A' AND amount IS NULL"
                + " AND form_code <> 'ANY' ORDER BY form_code LIMIT 1")
        .query(String.class)
        .single();
  }

  @Test
  void extremeAmountsAreWrittenAsPlainDecimalsAndFollowTheRule() {
    Map<String, String[]> extreme = new LinkedHashMap<>();
    extreme.put("ZINC", new String[] {"9999999999.9999", null, "mg", "UNKNOWN"});
    extreme.put("VIT_D", new String[] {"0.0001", null, "mcg", "UNKNOWN"});
    extreme.put("VIT_B3", new String[] {"5000", null, "mg", "UNKNOWN"});
    extreme.put("VIT_A", new String[] {"8000", null, "mcg", betaCaroteneForm()});
    extreme.put("COQ10", new String[] {"0.0001", null, "mg", "UNKNOWN"});
    UUID huge = insertProduct("1", extreme);
    Resp response = detail(huge, "TR");
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    ContractSchemas.assertConforms("ProductDetailResponse", response.body());
    assertThat(verify(huge, "TR", response)).isEmpty();
    JsonNode zinc = nutrient(response, "ZINC");
    assertThat(zinc.get("upperLimit").get("exceeded").asBoolean()).isTrue();
    assertThat(response.body()).contains("\"amountPerDay\":9999999999.9999");
    JsonNode vitaminD = nutrient(response, "VIT_D");
    assertThat(vitaminD.get("upperLimit").get("exceeded").asBoolean()).isFalse();
    assertThat(nutrient(response, "VIT_A").get("upperLimit").isNull())
        .as("an exempt form has no upper limit")
        .isTrue();
    assertThat(nutrient(response, "COQ10").get("reference").isNull()).isTrue();
    assertThat(nutrient(response, "COQ10").get("upperLimit").isNull()).isTrue();
    assertThat(response.body()).doesNotContain("EXCEEDS_UL");

    // The strict comparison on exact values: 3 x 33.3334 = 100.0002 > 100, 1 x 100 = 100.
    Map<String, String[]> above = new LinkedHashMap<>();
    above.put("VIT_D", new String[] {"33.3334", null, "mcg", "UNKNOWN"});
    UUID aboveId = insertProduct("3", above);
    Resp aboveResponse = detail(aboveId, "TR");
    assertThat(verify(aboveId, "TR", aboveResponse)).isEmpty();
    Map<String, String[]> equal = new LinkedHashMap<>();
    equal.put("VIT_D", new String[] {"100", null, "mcg", "UNKNOWN"});
    UUID equalId = insertProduct("1", equal);
    Resp equalResponse = detail(equalId, "TR");
    assertThat(verify(equalId, "TR", equalResponse)).isEmpty();
    // The UL-relevant part below the scored amount.
    Map<String, String[]> part = new LinkedHashMap<>();
    part.put("ZINC", new String[] {"40", "10", "mg", "UNKNOWN"});
    UUID partId = insertProduct("2", part);
    Resp partResponse = detail(partId, "TR");
    assertThat(verify(partId, "TR", partResponse)).isEmpty();
    assertThat(nutrient(partResponse, "ZINC").get("upperLimit").get("exceeded").asBoolean())
        .isFalse();
  }

  private static final List<String> FACT_MARKERS =
      List.of(
          "\"reference\"",
          "\"upperLimit\"",
          "setCode",
          "percentOfReference",
          "ulAmountPerDay",
          "\"nutrients\"");

  @Test
  void aProductThatIsNotInMarketNeverExposesItsFacts() {
    List<Object[]> hidden =
        jdbc.sql(
                "SELECT product_id, country_code FROM product_availability WHERE status <> 'AVAILABLE'"
                    + " ORDER BY product_id, country_code LIMIT 40")
            .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)})
            .list();
    assertThat(hidden).isNotEmpty();
    Map<String, String[]> probe = new LinkedHashMap<>();
    probe.put("ZINC", new String[] {"40", null, "mg", "UNKNOWN"});
    UUID notSoldInUs = insertProduct("1", probe);
    hidden.add(new Object[] {notSoldInUs, "US"});
    for (Object[] h : hidden) {
      UUID id = (UUID) h[0];
      String country = (String) h[1];
      List<String> paths =
          List.of(
              "/products/" + id + "?country=" + country,
              "/products/" + id.toString().toUpperCase(Locale.ROOT) + "?country=" + country,
              "/products/" + id + "?country=" + country + "&displayCurrency=USD",
              "/products/" + id + "?displayCurrency=EUR&country=" + country,
              "/products/" + id + "/?country=" + country);
      for (String path : paths) {
        for (String token : java.util.Arrays.asList(null, userToken)) {
          ip = randomIp();
          Resp response = req("GET", API + path).bearer(token).send();
          assertThat(response.status()).as("%s -> %s", path, response).isIn(404, 422);
          for (String marker : FACT_MARKERS) {
            assertThat(response.body()).as(path).doesNotContain(marker);
          }
          AttackClient.assertNoLeak(response);
        }
      }
    }
  }

  @Test
  void hostileCountryCodesNeverSelectAnotherCountrysData() {
    Map<String, String[]> probe = new LinkedHashMap<>();
    probe.put("ZINC", new String[] {"40", null, "mg", "UNKNOWN"});
    UUID id = insertProduct("1", probe);
    List<String> hostile =
        List.of(
            "tr",
            "Tr",
            "TRY",
            "T",
            "",
            "XX",
            "ZZ",
            "US%00",
            "TR%00",
            "TR%20",
            "%20TR",
            "TR%0d%0a",
            "T%C5%98",
            "%EF%BC%B4%EF%BC%B2",
            "TR,US",
            "US;TR",
            "%27%20OR%20%271%27%3D%271",
            "TR%27--",
            "..%2FUS",
            "x".repeat(5000),
            "TR&country=US",
            "US&country=TR");
    for (String country : hostile) {
      ip = randomIp();
      Resp response =
          req("GET", API + "/products/" + id + "?country=" + country).bearer(userToken).send();
      AttackClient.assertNoLeak(response);
      if (response.status() == 200) {
        // A lenient reading may answer, but only with TR data: the product is not sold in US.
        assertThat(verify(id, "TR", response)).isEmpty();
        Sets us = sets("US");
        assertThat(response.body())
            .doesNotContain("\"setCode\":\"" + us.reference() + "\"")
            .doesNotContain("\"setCode\":\"" + us.upperLimit() + "\"");
        continue;
      }
      assertThat(response.status()).as("%s -> %s", country, response).isIn(404, 422);
      for (String marker : FACT_MARKERS) {
        assertThat(response.body()).as(country).doesNotContain(marker);
      }
    }
    ip = randomIp();
    Resp missing = req("GET", API + "/products/" + id).bearer(userToken).send();
    AttackClient.assertProblem(missing, 422, "validation_failed");
  }
}
