package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Section 15.7 case 9 on the catalog and recommendation endpoints: SQL and LIKE metacharacters in
 * {@code q}, country codes, nutrient codes, override keys and the display currency are either
 * validation errors or matched literally; no wildcard matches everything and the catalog row counts
 * never change.
 */
class CatalogInjectionAdversarialTest extends CatalogAdversarialTest {

  private static final List<String> HOSTILE =
      List.of(
          "' OR '1'='1",
          "'); DROP TABLE product;--",
          "\" OR \"\"=\"",
          "1; SELECT pg_sleep(5)--",
          "%",
          "%%",
          "_",
          "__%",
          "\\",
          "\\%",
          "*",
          ".*",
          "$where",
          "${jndi:ldap://127.0.0.1/a}",
          "#{7*7}",
          "{{7*7}}");

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private long[] counts() {
    return new long[] {
      productRows(),
      offerRows(),
      jdbc.sql("SELECT count(*) FROM product_availability").query(Long.class).single(),
      jdbc.sql("SELECT count(*) FROM nutrient").query(Long.class).single(),
      jdbc.sql("SELECT count(*) FROM country").query(Long.class).single()
    };
  }

  /** Products of TR whose brand, name or code contains {@code q} (case-insensitive, literal). */
  private long literalMatches(String q) {
    String needle = q.toLowerCase(Locale.ROOT);
    return catalog
        .searchProducts("TR", null, com.vitadelta.api.common.paging.PageQuery.of(1, 100))
        .items()
        .stream()
        .filter(p ->
            p.brand().toLowerCase(Locale.ROOT).contains(needle)
                || p.name().toLowerCase(Locale.ROOT).contains(needle)
                || p.code().toLowerCase(Locale.ROOT).contains(needle))
        .count();
  }

  @Test
  void searchTermsAreMatchedLiterallyAndWildcardsMatchNothingSpecial() {
    long[] before = counts();
    long all = rawGet(API + "/products?country=TR", null).json().get("meta").get("total").asLong();
    assertThat(all).isGreaterThan(3);
    for (String q : HOSTILE) {
      Resp response = rawGet(API + "/products?country=TR&q=" + encode(q), null);
      assertThat(response.status()).as(q + " -> " + response).isEqualTo(200);
      JsonNode meta = response.json().get("meta");
      assertThat(meta.get("total").asLong()).as("matches of %s", q).isEqualTo(literalMatches(q));
      assertThat(meta.get("total").asLong()).as("%s must not match everything", q).isLessThan(all);
      AttackClient.assertApiSecurityHeaders(response);
    }
    Resp gold = rawGet(API + "/products?country=TR&q=gOlD-a", null);
    assertThat(gold.json().get("meta").get("total").asLong()).isGreaterThanOrEqualTo(1);
    for (String control : List.of("%00", "a%00b", "%0A", "%1F", "%7F", "")) {
      Resp response = rawGet(API + "/products?country=TR&q=" + control, null);
      AttackClient.assertProblem(response, 422, "validation_failed");
      assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("q");
    }
    AttackClient.assertProblem(
        rawGet(API + "/products?country=TR&q=" + "a".repeat(201), null), 422, "validation_failed");
    assertThat(counts()).isEqualTo(before);
  }

  @Test
  void hostileCountryCurrencyAndPagingParametersAreValidationErrors() {
    long[] before = counts();
    for (String value : HOSTILE) {
      ip = randomIp();
      String encoded = encode(value);
      for (String path :
          List.of(
              API + "/products?country=" + encoded,
              API + "/products?country=TR&displayCurrency=" + encoded,
              API + "/products?country=TR&page=" + encoded,
              API + "/products?country=TR&pageSize=" + encoded,
              API + "/products/" + productId("GOLD-A") + "?country=" + encoded,
              API
                  + "/products/"
                  + productId("GOLD-A")
                  + "?country=TR&displayCurrency="
                  + encoded)) {
        Resp response = rawGet(path, null);
        assertThat(response.status()).as(path + " -> " + response).isIn(400, 422);
        AttackClient.assertNoLeak(response);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
      }
    }
    for (String path :
        List.of(
            API + "/products?country=tr",
            API + "/products?country=TR&country=DE",
            API + "/products?country=TR%00",
            API + "/products?country=T%C3%9C")) {
      Resp response = rawGet(path, null);
      assertThat(response.status()).as(path + " -> " + response).isIn(200, 400, 422);
      AttackClient.assertNoLeak(response);
    }
    assertThat(counts()).isEqualTo(before);
  }

  @Test
  void hostileCodesInRecommendationBodiesAreValidationErrors() {
    stubTr();
    long[] before = counts();
    String cookie = anonymousCookie();
    for (String value : HOSTILE) {
      ip = randomIp();
      String text = AttackClient.JSON.writeValueAsString(value);
      for (String body :
          List.of(
              "{\"country\":" + text + "}",
              "{\"country\":\"TR\",\"displayCurrency\":" + text + "}",
              "{\"country\":\"TR\",\"stockFilter\":" + text + "}",
              "{\"country\":\"TR\",\"ranking\":{\"mode\":" + text + "}}",
              "{\"country\":\"TR\",\"weights\":{\"overrides\":{" + text + ":1}}}",
              "{\"country\":\"TR\",\"weights\":{\"categories\":{" + text + ":1}}}",
              "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":" + text + "}]}",
              "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"ZINC\","
                  + "\"minAmountPerDay\":1,\"unit\":"
                  + text
                  + "}]}")) {
        Resp response = rawQuery(body, null, cookie);
        AttackClient.assertProblem(response, 422, "validation_failed");
      }
      Resp compare =
          rawCompare(
              "{\"country\":\"TR\",\"baselineProductId\":"
                  + text
                  + ",\"candidateProductId\":\""
                  + productId("GOLD-B")
                  + "\"}",
              null);
      AttackClient.assertProblem(compare, 422, "validation_failed");
    }
    assertThat(storedQueriesUsed(cookie)).isZero();
    assertThat(scoringRequests("score")).isEmpty();
    assertThat(counts()).isEqualTo(before);
  }
}
