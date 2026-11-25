package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.identity.Role;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Section 15.7 case 2 for the Phase 4 endpoints and section 7.2: a product that is {@code
 * NOT_SOLD}, {@code DISCONTINUED} or without an availability row in a country never appears for
 * that country in {@code /products}, {@code /products/{id}}, the ranking (neither as a candidate
 * nor in the market profile sent to the scoring service) or a comparison; random and malformed ids
 * get the same answers as unknown ids; {@code saved} and {@code alerts} show the signed-in owner's
 * own state only and are {@code false} and {@code []} for everyone else.
 */
class AvailabilityAdversarialTest extends CatalogAdversarialTest {

  /** A product that is not in market in a country. */
  record Hidden(UUID productId, String country, String status) {}

  private List<Hidden> hidden() {
    List<Hidden> rows =
        new ArrayList<>(
            jdbc.sql(
                    "SELECT a.product_id, a.country_code, a.status FROM product_availability a"
                        + " WHERE a.status <> 'AVAILABLE' ORDER BY a.product_id, a.country_code")
                .query((rs, i) ->
                    new Hidden(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list());
    rows.addAll(
        jdbc.sql(
                "SELECT p.id, c.code, 'NONE' FROM product p CROSS JOIN country c WHERE NOT EXISTS"
                    + " (SELECT 1 FROM product_availability a WHERE a.product_id = p.id"
                    + " AND a.country_code = c.code) AND c.code IN ('TR', 'DE', 'GB', 'US')"
                    + " ORDER BY p.id, c.code LIMIT 6")
            .query((rs, i) ->
                new Hidden(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
            .list());
    return rows;
  }

  /** An in-market product with an in-stock offer in {@code country}. */
  private UUID pricedIn(String country) {
    return inStock(country).getFirst().id();
  }

  private Set<String> allListedIds(String country) {
    Set<String> ids = new HashSet<>();
    for (int page = 1; page <= 50; page++) {
      Resp response =
          rawGet(API + "/products?country=" + country + "&pageSize=100&page=" + page, null);
      assertThat(response.status()).as(response.toString()).isEqualTo(200);
      JsonNode items = response.json().get("items");
      if (items.isEmpty()) {
        break;
      }
      items.forEach(i -> ids.add(i.get("id").asString()));
    }
    return ids;
  }

  @Test
  void productsThatAreNotInMarketNeverAppearOnAnyPublicEndpoint() {
    List<Hidden> hidden = hidden();
    assertThat(hidden.stream().map(Hidden::status))
        .as("the seed has NOT_SOLD, DISCONTINUED and missing availability rows")
        .contains("NOT_SOLD", "DISCONTINUED", "NONE");
    String userToken = user(Role.USER).token();
    for (String country : List.of("TR", "DE", "GB", "US")) {
      ip = randomIp();
      Set<String> listed = allListedIds(country);
      for (Hidden h : hidden) {
        if (!h.country().equals(country)) {
          continue;
        }
        assertThat(listed)
            .as("%s listed in %s", h, country)
            .doesNotContain(h.productId().toString());
        for (String token : java.util.Arrays.asList(null, userToken)) {
          Resp detail = rawGet(API + "/products/" + h.productId() + "?country=" + country, token);
          AttackClient.assertProblem(detail, 404, "product_not_available_in_country");
          assertThat(detail.body()).doesNotContain("\"offers\"");
          Resp search =
              rawGet(
                  API
                      + "/products?country="
                      + country
                      + "&q="
                      + jdbc.sql("SELECT code FROM product WHERE id = ?")
                          .param(h.productId())
                          .query(String.class)
                          .single(),
                  token);
          assertThat(search.status()).isEqualTo(200);
          assertThat(search.body()).doesNotContain(h.productId().toString());
          UUID other = pricedIn(country);
          for (String body :
              List.of(
                  compareBody(country, other, h.productId()),
                  compareBody(country, h.productId(), other))) {
            Resp compare = rawCompare(body, token);
            AttackClient.assertProblem(compare, 404, "product_not_available_in_country");
          }
        }
      }
    }
    assertThat(scoringRequests("compare")).as("rejected before scoring").isEmpty();
  }

  @Test
  void theScoringRequestAndTheRankingNeverContainProductsOutOfMarket() {
    String token = user(Role.USER).token();
    for (String country : List.of("TR", "DE", "GB", "US")) {
      scoring().resetAll();
      caches.clear();
      stubScore(
          expectedCandidates(country, StockFilter.INCLUDE_OUT_OF_STOCK),
          StockFilter.INCLUDE_OUT_OF_STOCK);
      Resp response =
          rawQuery(
              "{\"country\":\""
                  + country
                  + "\",\"stockFilter\":\"INCLUDE_OUT_OF_STOCK\",\"pageSize\":100}",
              token,
              null);
      JsonNode sent = onlyScoringRequest("score");
      Set<String> candidates = new HashSet<>();
      sent.get("candidates").forEach(c -> candidates.add(c.get("product_id").asString()));
      Set<String> profile = new HashSet<>();
      sent.get("market_profile")
          .get("products")
          .forEach(p -> profile.add(p.get("product_id").asString()));
      for (Hidden h : hidden()) {
        if (h.country().equals(country)) {
          String id = h.productId().toString();
          assertThat(candidates).as("%s sent as candidate", h).doesNotContain(id);
          assertThat(profile).as("%s sent in the market profile", h).doesNotContain(id);
          assertThat(response.body()).as("%s in the ranking", h).doesNotContain(id);
        }
      }
    }
  }

  @Test
  void aProductOfAnotherCountryCannotBeComparedOrViewedThroughThisCountry() {
    UUID germanOnly =
        jdbc.sql(
                "SELECT a.product_id FROM product_availability a WHERE a.country_code = 'DE'"
                    + " AND a.status = 'AVAILABLE' AND NOT EXISTS (SELECT 1 FROM"
                    + " product_availability t WHERE t.product_id = a.product_id"
                    + " AND t.country_code = 'TR' AND t.status = 'AVAILABLE')"
                    + " ORDER BY a.product_id LIMIT 1")
            .query(UUID.class)
            .single();
    UUID turkish = pricedIn("TR");
    AttackClient.assertProblem(
        rawCompare(compareBody("TR", turkish, germanOnly), null),
        404,
        "product_not_available_in_country");
    AttackClient.assertProblem(
        rawGet(API + "/products/" + germanOnly + "?country=TR", null),
        404,
        "product_not_available_in_country");
    Resp german = rawGet(API + "/products/" + germanOnly + "?country=DE", null);
    assertThat(german.status()).isEqualTo(200);
    assertThat(german.json().get("meta").get("country").asString()).isEqualTo("DE");
  }

  @Test
  void randomAndMalformedIdsGetTheUnknownProductAnswer() {
    UUID turkish = pricedIn("TR");
    for (String id :
        List.of(
            UUID.randomUUID().toString(),
            "00000000-0000-0000-0000-000000000000",
            "not-a-uuid",
            "1-1-1-1-1",
            "' OR '1'='1",
            "%27%20OR%20%271%27%3D%271",
            turkish.toString() + "0",
            turkish.toString().replace("-", ""),
            "x".repeat(2000))) {
      Resp detail =
          rawGet(
              API + "/products/" + id.replace(" ", "%20").replace("'", "%27") + "?country=TR",
              null);
      AttackClient.assertProblem(detail, 404, "product_not_found");
    }
    Resp upper =
        rawGet(
            API
                + "/products/"
                + turkish.toString().toUpperCase(java.util.Locale.ROOT)
                + "?country=TR",
            null);
    assertThat(upper.status()).as("an upper-case UUID is the same product").isEqualTo(200);

    for (String id :
        List.of(
            "\"" + UUID.randomUUID() + "\"",
            "\"not-a-uuid\"",
            "\"" + turkish + "' OR '1'='1\"",
            "12345",
            "null",
            "{\"$ne\":null}",
            "[\"" + turkish + "\"]")) {
      Resp compare =
          rawCompare(
              "{\"country\":\"TR\",\"baselineProductId\":\""
                  + turkish
                  + "\",\"candidateProductId\":"
                  + id
                  + "}",
              null);
      assertThat(compare.status()).as(id + " -> " + compare).isIn(404, 422);
      AttackClient.assertNoLeak(compare);
      assertThat(compare.header("content-type")).startsWith("application/problem+json");
    }
    Resp unknown = rawCompare(compareBody("TR", turkish, UUID.randomUUID()), null);
    AttackClient.assertProblem(unknown, 404, "product_not_found");
    assertThat(scoringRequests("compare")).isEmpty();
  }

  private UUID insertAlert(TestUser owner, UUID product, String trigger, boolean active) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
                + " threshold_percent, baseline_price, active, created_at)"
                + " VALUES (?, ?, ?, 'TR', ?, ?, ?, ?, now())")
        .params(
            id,
            owner.id(),
            product,
            trigger,
            trigger.equals("PRICE_DROP") ? new java.math.BigDecimal("5.00") : null,
            trigger.equals("PRICE_DROP") ? new java.math.BigDecimal("100.00") : null,
            active)
        .update();
    return id;
  }

  /**
   * Phase 5 rule (contract {@code saved} / {@code alerts} of the ranking item and the product
   * detail): only the signed-in owner sees their saved state and subscriptions on a product in the
   * country; anonymous callers and every other user see {@code false} / {@code []}, also when the
   * owner's ranking was served from the cache first, and no other user's subscription data ever
   * appears in their bytes.
   */
  @Test
  void savedAndAlertsAreShownToTheOwnerOnlyAndNeverLeakToOtherPrincipals() {
    stubScore(inStock("TR"), StockFilter.IN_STOCK_ONLY);
    UUID product = pricedIn("TR");
    TestUser owner = user(Role.USER);
    TestUser other = user(Role.USER);
    jdbc.sql(
            "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                + " VALUES (?, ?, 'TR', now())")
        .params(owner.id(), product)
        .update();
    UUID priceDrop = insertAlert(owner, product, "PRICE_DROP", true);
    UUID backInStock = insertAlert(owner, product, "BACK_IN_STOCK", false);
    UUID otherProduct =
        inStock("TR").stream()
            .map(p -> p.id())
            .filter(id -> !id.equals(product))
            .findFirst()
            .orElseThrow();
    jdbc.sql(
            "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                + " VALUES (?, ?, 'TR', now())")
        .params(other.id(), otherProduct)
        .update();
    UUID otherAlert = insertAlert(other, otherProduct, "PRICE_CHANGE", true);

    // The owner first, so that every later ranking comes from the cache the owner filled.
    Resp ownerDetail = rawGet(API + "/products/" + product + "?country=TR", owner.token());
    assertThat(ownerDetail.status()).isEqualTo(200);
    assertThat(ownerDetail.json().get("product").get("saved").asBoolean()).isTrue();
    List<String> ownerAlerts = new ArrayList<>();
    ownerDetail
        .json()
        .get("product")
        .get("alerts")
        .forEach(a -> ownerAlerts.add(a.get("id").asString()));
    assertThat(ownerAlerts)
        .as("active and inactive, ordered by trigger")
        .containsExactly(priceDrop.toString(), backInStock.toString());
    Resp ownerRanking = rawQuery("{\"country\":\"TR\",\"pageSize\":100}", owner.token(), null);
    assertThat(ownerRanking.status()).isEqualTo(200);
    for (JsonNode item : ownerRanking.json().get("items")) {
      boolean mine = item.get("productId").asString().equals(product.toString());
      assertThat(item.get("saved").asBoolean())
          .as(item.get("productId").asString())
          .isEqualTo(mine);
      assertThat(item.get("alerts").size()).isEqualTo(mine ? 2 : 0);
    }
    assertThat(ownerRanking.body()).doesNotContain(otherAlert.toString());

    for (String token :
        java.util.Arrays.asList(null, other.token(), user(Role.USER).token(), proUser().token())) {
      Resp detail = rawGet(API + "/products/" + product + "?country=TR", token);
      assertThat(detail.status()).isEqualTo(200);
      assertThat(detail.json().get("product").get("saved").asBoolean()).isFalse();
      assertThat(detail.json().get("product").get("alerts").toString()).isEqualTo("[]");
      Resp ranking = rawQuery("{\"country\":\"TR\",\"pageSize\":100}", token, null);
      assertThat(ranking.status()).isEqualTo(200);
      for (JsonNode item : ranking.json().get("items")) {
        boolean theirs =
            token != null
                && token.equals(other.token())
                && item.get("productId").asString().equals(otherProduct.toString());
        assertThat(item.get("saved").asBoolean()).isEqualTo(theirs);
        assertThat(item.get("alerts").size()).isEqualTo(theirs ? 1 : 0);
      }
      for (Resp response : List.of(detail, ranking)) {
        assertThat(response.body())
            .doesNotContain(priceDrop.toString())
            .doesNotContain(backInStock.toString())
            .doesNotContain(owner.id().toString());
      }
    }
    Resp otherCountry = rawGet(API + "/products/" + product + "?country=DE", owner.token());
    if (otherCountry.status() == 200) {
      assertThat(otherCountry.json().get("product").get("saved").asBoolean())
          .as("saved in TR is not saved in DE")
          .isFalse();
      assertThat(otherCountry.json().get("product").get("alerts").toString()).isEqualTo("[]");
    }
  }

  private static String compareBody(String country, UUID baseline, UUID candidate) {
    return "{\"country\":\""
        + country
        + "\",\"baselineProductId\":\""
        + baseline
        + "\",\"candidateProductId\":\""
        + candidate
        + "\"}";
  }
}
