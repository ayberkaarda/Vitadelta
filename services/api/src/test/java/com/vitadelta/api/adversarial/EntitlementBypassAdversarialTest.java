package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Section 15.7 case 3 (entitlement bypass) against {@code POST /recommendations/query}, attacked
 * from the outside: paging and size tricks, ranking modes and filters used as an enumeration
 * oracle, quota double-spend and refund inflation, forged and replayed {@code vd_anon} cookies,
 * mixed and invalid credentials, and the shared ranking cache across principals. Locked items are
 * searched for in every byte of the raw response body.
 *
 * <p>Accepted exposure (section 8.4): an anonymous caller may narrow the ranking with filters so
 * that other products reach the top 3, and {@code /products} and {@code /recommendations/compare}
 * reveal catalog data without quota. The tests therefore check that each such probe consumes one
 * trial query and that the per-IP daily cap bounds probing with fresh identities.
 */
class EntitlementBypassAdversarialTest extends CatalogAdversarialTest {

  /** Raw-body fingerprints of products, by id. */
  private Map<String, List<String>> fingerprints() {
    Map<String, List<String>> result = new java.util.HashMap<>();
    for (CatalogProduct p : catalog.loadCountry("TR").orElseThrow().products()) {
      List<String> marks = new ArrayList<>();
      marks.add(p.id().toString());
      marks.add("\"code\":" + AttackClient.JSON.writeValueAsString(p.code()));
      marks.add("\"name\":" + AttackClient.JSON.writeValueAsString(p.name()));
      for (OfferSnapshot offer : p.offers()) {
        marks.add("\"url\":" + AttackClient.JSON.writeValueAsString(offer.url()));
        marks.add("\"retailer\":" + AttackClient.JSON.writeValueAsString(offer.retailer()));
      }
      result.put(p.id().toString(), marks);
    }
    return result;
  }

  /**
   * Asserts that the raw body contains no fingerprint of a locked product, except fingerprints it
   * shares with a product that is legitimately visible.
   */
  private void assertNothingLocked(String rawBody, List<String> ranking, int visible) {
    Map<String, List<String>> marks = fingerprints();
    Set<String> visibleMarks = new HashSet<>();
    for (String id : ranking.subList(0, Math.min(visible, ranking.size()))) {
      visibleMarks.addAll(marks.getOrDefault(id, List.of()));
    }
    for (String id : ranking.subList(Math.min(visible, ranking.size()), ranking.size())) {
      for (String mark : marks.getOrDefault(id, List.of())) {
        if (!visibleMarks.contains(mark)) {
          assertThat(rawBody).as("locked product %s leaks %s", id, mark).doesNotContain(mark);
        }
      }
    }
  }

  private static List<String> responseIds(Resp response) {
    List<String> ids = new ArrayList<>();
    response.json().get("items").forEach(i -> ids.add(i.get("productId").asString()));
    return ids;
  }

  private static void assertAnonymousVisibility(Resp response, int total) {
    assertThat(response.json().get("meta").get("visibility").toString())
        .isEqualTo(
            "{\"shown\":3,\"total\":"
                + total
                + ",\"locked\":"
                + (total - 3)
                + ",\"reason\":\"SIGN_UP_REQUIRED\"}");
    assertThat(response.json().get("meta").get("principal").get("type").asString())
        .isEqualTo("ANONYMOUS");
  }

  @Test
  void pagingAndSizeTricksNeverRevealMoreThanTheTopThree() {
    ObjectNode answer = stubTr();
    List<String> ranking = rankedIds(answer);
    int total = ranking.size();
    assertThat(total).isGreaterThan(3);
    List<String> top = ranking.subList(0, 3);

    for (String paging :
        List.of(
            "",
            ",\"page\":1,\"pageSize\":100",
            ",\"pageSize\":100",
            ",\"page\":2",
            ",\"page\":2,\"pageSize\":1",
            ",\"page\":2147483647,\"pageSize\":100",
            ",\"pageSize\":1")) {
      String cookie = anonymousCookie();
      Resp response = rawQuery("{\"country\":\"TR\"" + paging + "}", null, cookie);
      assertThat(response.status()).as(paging + " -> " + response).isEqualTo(200);
      ContractSchemas.assertConforms("RecommendationQueryResponse", response.body());
      List<String> shown = responseIds(response);
      boolean firstPage = !paging.contains("\"page\":2");
      if (firstPage) {
        assertThat(shown).as(paging).containsExactlyElementsOf(top);
      } else {
        assertThat(shown).as(paging).isEmpty();
      }
      assertAnonymousVisibility(response, total);
      assertNothingLocked(response.body(), ranking, 3);
      assertThat(storedQueriesUsed(cookie)).isEqualTo(1);
    }

    String cookie = anonymousCookie();
    for (String invalid :
        List.of(
            ",\"pageSize\":0",
            ",\"pageSize\":-5",
            ",\"page\":0",
            ",\"page\":-1",
            ",\"pageSize\":101",
            ",\"pageSize\":2147483647",
            ",\"pageSize\":99999999999",
            ",\"page\":1e10")) {
      Resp response = rawQuery("{\"country\":\"TR\"" + invalid + "}", null, cookie);
      AttackClient.assertProblem(response, 422, "validation_failed");
      assertNothingLocked(response.body(), ranking, 0);
    }
    assertThat(storedQueriesUsed(cookie)).as("invalid requests consume nothing").isZero();
  }

  @Test
  void everyOrderingAndFilterProbeIsTruncatedAndConsumesOneTrialQuery() {
    ObjectNode answer = stubTr();
    List<String> ranking = rankedIds(answer);
    String cookie = anonymousCookie();
    List<String> probes =
        List.of(
            "{\"country\":\"TR\",\"ranking\":{\"mode\":\"PRICE\"}}",
            "{\"country\":\"TR\",\"ranking\":{\"mode\":\"CUSTOM\",\"lambda\":0.95},"
                + "\"weights\":{\"overrides\":{\"ZINC\":3}}}",
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"ZINC\"}],"
                + "\"excludeExceedingUl\":true,\"stockFilter\":\"INCLUDE_OUT_OF_STOCK\"}");
    for (int i = 0; i < probes.size(); i++) {
      Resp response = rawQuery(probes.get(i), null, cookie);
      assertThat(response.status()).as(response.toString()).isEqualTo(200);
      assertThat(response.json().get("items").size()).isLessThanOrEqualTo(3);
      assertNothingLocked(response.body(), ranking, 3);
      assertThat(storedQueriesUsed(cookie)).isEqualTo(i + 1);
    }
    for (String fourth :
        List.of(
            "{\"country\":\"TR\",\"ranking\":{\"mode\":\"RICHNESS\"}}",
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"VIT_C\"}]}",
            TR_QUERY)) {
      Resp rejected = rawQuery(fourth, null, cookie);
      AttackClient.assertProblem(rejected, 403, "quota_exceeded");
      ContractSchemas.assertConforms("QuotaExceededProblem", rejected.body());
      assertNothingLocked(rejected.body(), ranking, 0);
    }
    assertThat(storedQueriesUsed(cookie)).isEqualTo(3);
  }

  @Test
  void twentyParallelQueriesWithOneCookieYieldExactlyThreeSuccesses() throws Exception {
    stubTr();
    String cookie = anonymousCookie();
    List<Integer> statuses = parallel(20, i -> rawQuery(TR_QUERY, null, cookie).status());
    assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(3);
    assertThat(statuses.stream().filter(s -> s == 403).count()).isEqualTo(17);
    assertThat(storedQueriesUsed(cookie)).isEqualTo(3);
  }

  @Test
  void scoringFailuresRefundTheQuotaButCanNeitherInflateItNorDriveItNegative() {
    scoring()
        .stubFor(post(urlEqualTo("/internal/v1/score")).willReturn(aResponse().withStatus(500)));
    String cookie = anonymousCookie();
    for (int i = 0; i < 5; i++) {
      AttackClient.assertProblem(rawQuery(TR_QUERY, null, cookie), 503, "scoring_unavailable");
    }
    assertThat(storedQueriesUsed(cookie)).isZero();
    assertThat(minimumStoredQueriesUsed()).isGreaterThanOrEqualTo(0);

    scoring().resetAll();
    stubTr();
    for (int i = 0; i < 3; i++) {
      assertThat(rawQuery(TR_QUERY, null, cookie).status()).isEqualTo(200);
    }
    AttackClient.assertProblem(rawQuery(TR_QUERY, null, cookie), 403, "quota_exceeded");

    // With the quota used up, failures are rejected before scoring and refund nothing.
    caches.clear();
    scoring().resetAll();
    scoring()
        .stubFor(post(urlEqualTo("/internal/v1/score")).willReturn(aResponse().withStatus(500)));
    for (int i = 0; i < 5; i++) {
      AttackClient.assertProblem(
          rawQuery("{\"country\":\"TR\",\"excludeExceedingUl\":true}", null, cookie),
          403,
          "quota_exceeded");
    }
    assertThat(storedQueriesUsed(cookie)).isEqualTo(3);
    assertThat(scoringRequests("score")).isEmpty();
    assertThat(minimumStoredQueriesUsed()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void parallelFailingAndSucceedingQueriesNeverGrantMoreThanTheQuota() throws Exception {
    stubTr();
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/score"))
                .withRequestBody(matchingJsonPath("$.exclude_exceeding_ul", equalTo("true")))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500).withFixedDelay(150)));
    String cookie = anonymousCookie();
    List<Integer> statuses =
        parallel(
            20,
            i ->
                rawQuery(
                        i % 2 == 0 ? "{\"country\":\"TR\",\"excludeExceedingUl\":true}" : TR_QUERY,
                        null,
                        cookie)
                    .status());
    long successes = statuses.stream().filter(s -> s == 200).count();
    assertThat(successes).isBetween(0L, 3L);
    assertThat(statuses).allMatch(s -> s == 200 || s == 403 || s == 503);
    assertThat(storedQueriesUsed(cookie)).isEqualTo((int) successes);
    for (long i = successes; i < 3; i++) {
      assertThat(rawQuery(TR_QUERY, null, cookie).status()).isEqualTo(200);
    }
    AttackClient.assertProblem(rawQuery(TR_QUERY, null, cookie), 403, "quota_exceeded");
    assertThat(storedQueriesUsed(cookie)).isEqualTo(3);
  }

  @Test
  void forgedTamperedAndReplayedCookiesNeverYieldExtraQueries() {
    stubTr();
    String cookie = anonymousCookie();
    for (int i = 0; i < 3; i++) {
      assertThat(rawQuery(TR_QUERY, null, cookie).status()).isEqualTo(200);
    }
    String value = cookie.substring("vd_anon=".length());
    String id = value.substring(0, value.indexOf('.'));
    String signature = value.substring(value.indexOf('.') + 1);

    // Replays of the exhausted cookie stay exhausted, whatever else is added to the header.
    for (String header : List.of(cookie, cookie + "; other=1", "other=1; " + cookie)) {
      AttackClient.assertProblem(rawQuery(TR_QUERY, null, header), 403, "quota_exceeded");
    }

    // Forged or tampered values are new identities: never the exhausted one, never an error.
    String flipped = (signature.charAt(0) == 'A' ? "B" : "A") + signature.substring(1);
    for (String forged :
        List.of(
            "vd_anon=" + id + "." + flipped,
            "vd_anon=" + id,
            "vd_anon=" + id + "..",
            "vd_anon=" + id + "." + signature + "." + signature,
            "vd_anon=AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "vd_anon=' OR '1'='1",
            "vd_anon=" + "x".repeat(4000))) {
      Resp response = rawQuery(TR_QUERY, null, forged);
      assertThat(response.status()).as(forged + " -> " + response).isEqualTo(200);
      assertThat(response.json().get("meta").get("principal").get("queriesUsed").asInt())
          .isEqualTo(1);
      assertThat(response.setCookie("vd_anon")).as("a new identity is issued").isPresent();
      AttackClient.assertNoLeak(response);
    }
    assertThat(storedQueriesUsed(cookie)).isEqualTo(3);
  }

  @Test
  void aValidBearerTokenWinsOverTheCookieAndLeavesTheAnonymousQuotaAlone() {
    ObjectNode answer = stubTr();
    String cookie = anonymousCookie();
    for (int i = 0; i < 3; i++) {
      assertThat(rawQuery(TR_QUERY, null, cookie).status()).isEqualTo(200);
    }
    TestUser user = user(Role.USER);
    Resp response = rawQuery("{\"country\":\"TR\",\"pageSize\":100}", user.token(), cookie);
    assertThat(response.status()).as(response.toString()).isEqualTo(200);
    assertThat(response.json().get("meta").get("principal").toString())
        .isEqualTo("{\"type\":\"USER\",\"plan\":\"FREE\"}");
    assertThat(responseIds(response)).containsExactlyElementsOf(rankedIds(answer));
    assertThat(response.setCookie("vd_anon")).isEmpty();
    assertThat(storedQueriesUsed(cookie)).isEqualTo(3);
  }

  @Test
  void anInvalidBearerTokenIsRejectedAndNeverDowngradedToAnonymous() {
    stubTr();
    TestUser user = user(Role.USER);
    UUID subject = user.id();
    String expired =
        AdversarialIntegrationTest.sign(
            AdversarialIntegrationTest.currentSecret(),
            AdversarialIntegrationTest.currentKeyId(),
            JWSAlgorithm.HS256,
            AdversarialIntegrationTest.claims(
                subject.toString(),
                "USER",
                true,
                Instant.now().minus(Duration.ofHours(2)),
                Duration.ofMinutes(15)));
    String valid = user.token();
    String tampered = valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA");
    String foreignKey =
        AdversarialIntegrationTest.sign(
            AdversarialIntegrationTest.randomSecret(32)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            AdversarialIntegrationTest.currentKeyId(),
            JWSAlgorithm.HS256,
            AdversarialIntegrationTest.claims(
                subject.toString(), "ADMIN", true, Instant.now(), Duration.ofMinutes(15)));
    String cookie = anonymousCookie();
    for (String token : List.of(expired, tampered, foreignKey, "not-a-jwt", "a.b.c")) {
      Resp query = rawQuery(TR_QUERY, token, cookie);
      AttackClient.assertProblem(query, 401, "unauthorized");
      assertThat(query.setCookie("vd_anon")).isEmpty();
      Resp products = rawGet(API + "/products?country=TR", token);
      AttackClient.assertProblem(products, 401, "unauthorized");
      Resp compare =
          rawCompare(
              "{\"country\":\"TR\",\"baselineProductId\":\""
                  + productId("GOLD-A")
                  + "\",\"candidateProductId\":\""
                  + productId("GOLD-B")
                  + "\"}",
              token);
      AttackClient.assertProblem(compare, 401, "unauthorized");
    }
    assertThat(storedQueriesUsed(cookie)).as("rejected requests consume nothing").isZero();
    assertThat(scoringRequests("score")).isEmpty();
  }

  @Test
  void theSharedRankingCacheNeverLeaksAcrossPrincipalsOrCurrencies() {
    ObjectNode answer = stubTr();
    List<String> ranking = rankedIds(answer);
    TestUser first = user(Role.USER);
    Resp full =
        rawQuery(
            "{\"country\":\"TR\",\"pageSize\":100,\"displayCurrency\":\"EUR\"}",
            first.token(),
            null);
    assertThat(full.status()).isEqualTo(200);
    assertThat(responseIds(full)).containsExactlyElementsOf(ranking);
    assertThat(full.json().get("meta").get("currency").asString()).isEqualTo("EUR");

    for (String body :
        List.of(
            "{\"country\":\"TR\",\"pageSize\":100,\"displayCurrency\":\"EUR\"}",
            "{\"country\":\"TR\",\"pageSize\":100}",
            "{\"country\":\"TR\",\"page\":2,\"pageSize\":3}")) {
      String cookie = anonymousCookie();
      Resp anonymous = rawQuery(body, null, cookie);
      assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(200);
      assertAnonymousVisibility(anonymous, ranking.size());
      assertThat(anonymous.json().get("items").size()).isLessThanOrEqualTo(3);
      assertNothingLocked(anonymous.body(), ranking, 3);
      assertThat(storedQueriesUsed(cookie)).isEqualTo(1);
    }

    TestUser second = user(Role.USER);
    Resp native_ = rawQuery("{\"country\":\"TR\",\"pageSize\":100}", second.token(), null);
    assertThat(native_.json().get("meta").get("currency").asString()).isEqualTo("TRY");
    assertThat(native_.json().get("meta").get("principal").toString())
        .isEqualTo("{\"type\":\"USER\",\"plan\":\"FREE\"}");
    assertThat(native_.json().get("items").get(0).get("cost").get("currency").asString())
        .isEqualTo("TRY");
    assertThat(responseIds(native_)).containsExactlyElementsOf(ranking);
    assertThat(scoringRequests("score")).as("one scoring call served everyone").hasSize(1);
  }

  @Test
  void unknownFieldsAndDuplicateKeysCannotWidenAnonymousVisibility() {
    ObjectNode answer = stubTr();
    List<String> ranking = rankedIds(answer);
    String cookie = anonymousCookie();
    String body =
        "{\"country\":\"TR\",\"visibleResults\":100,\"queriesLimit\":1000,"
            + "\"principal\":{\"type\":\"USER\",\"plan\":\"PRO\"},\"plan\":\"PRO\",\"role\":\"ADMIN\","
            + "\"meta\":{\"visibility\":{\"locked\":0}},\"pageSize\":3,\"pageSize\":100}";
    Resp response = rawQuery(body, null, cookie);
    assertThat(response.status()).as(response.toString()).isIn(200, 422);
    if (response.status() == 200) {
      assertThat(responseIds(response)).containsExactlyElementsOf(ranking.subList(0, 3));
      assertAnonymousVisibility(response, ranking.size());
      assertThat(response.json().get("meta").get("principal").toString())
          .isEqualTo("{\"type\":\"ANONYMOUS\",\"queriesUsed\":1,\"queriesLimit\":3}");
    }
    assertNothingLocked(response.body(), ranking, 3);
  }

  @Test
  void freshIdentitiesFromOneAddressAreCappedAtThirtyQueriesPerDay() {
    stubTr();
    for (int i = 0; i < 30; i++) {
      Resp response = rawQuery(TR_QUERY, null, null);
      assertThat(response.status()).as("query %d: %s", i + 1, response).isEqualTo(200);
      assertThat(response.json().get("meta").get("principal").get("queriesUsed").asInt())
          .isEqualTo(1);
    }
    Resp capped = rawQuery(TR_QUERY, null, null);
    AttackClient.assertProblem(capped, 429, "rate_limited");
    assertThat(capped.header("retry-after")).matches("[1-9][0-9]*");
    // Spoofed entries to the left of the trusted proxy's entry are ignored.
    Resp spoofed =
        raw.req("POST", API + "/recommendations/query")
            .header("X-Forwarded-For", randomIp() + ", " + ip)
            .header("X-Real-IP", randomIp())
            .header("Forwarded", "for=" + randomIp())
            .json(TR_QUERY)
            .send();
    AttackClient.assertProblem(spoofed, 429, "rate_limited");
  }

  private long minimumStoredQueriesUsed() {
    return jdbc.sql("SELECT coalesce(min(queries_used), 0) FROM anonymous_identity")
        .query(Long.class)
        .single();
  }

  private static <T> List<T> parallel(int n, Function<Integer, T> call) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int index = i;
        Callable<T> task =
            () -> {
              start.await();
              return call.apply(index);
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(90, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
