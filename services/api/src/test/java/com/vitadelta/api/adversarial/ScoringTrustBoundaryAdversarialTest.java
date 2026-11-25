package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The scoring service as an untrusted dependency (sections 7.6, 9.3, 15.4): malformed, oversized,
 * hostile or hanging answers must map to the contract outcomes ({@code 503 scoring_unavailable},
 * {@code 500 internal_error}, or {@code 422 validation_failed} for client-caused rejections)
 * without forwarding the scoring body, stack traces or internal host names; the quota of an
 * anonymous caller is refunded; only connection errors are retried; the request id is propagated.
 */
class ScoringTrustBoundaryAdversarialTest extends CatalogAdversarialTest {

  private static final String INTERNAL_HOST = "scoring-internal.svc.cluster.local";
  private static final String STACK =
      "stack trace: Traceback (most recent call last):\\n  File \\\"/srv/scoring/app.py\\\","
          + " line 42, in score\\n    raise ValueError  at com.vitadelta.scoring.Engine"
          + " ("
          + INTERNAL_HOST
          + ":8000) postgres://scoring:secret@db/scoring";

  private void stub(ResponseDefinitionBuilder response) {
    scoring().resetAll();
    caches.clear();
    scoring().stubFor(post(urlEqualTo("/internal/v1/score")).willReturn(response));
  }

  private static void assertNothingForwarded(Resp response) {
    AttackClient.assertNoLeak(response);
    assertThat(response.body())
        .doesNotContain(INTERNAL_HOST)
        .doesNotContain("/srv/scoring")
        .doesNotContain("secret@")
        .doesNotContain("Traceback")
        .doesNotContain("ADV-MARKER");
    AttackClient.assertApiSecurityHeaders(response);
  }

  /** Sends one anonymous query and checks the outcome, the refund and the single scoring call. */
  private Resp attack(int status, String code) {
    String cookie = anonymousCookie();
    Resp response = rawQuery(TR_QUERY, null, cookie);
    AttackClient.assertProblem(response, status, code);
    assertNothingForwarded(response);
    assertThat(storedQueriesUsed(cookie)).as("refunded after %s", response).isZero();
    assertThat(scoringRequests("score")).as("not retried").hasSize(1);
    return response;
  }

  @Test
  void malformedAnswersAreInternalErrorsWithoutTheScoringBody() {
    String valid =
        scoreAnswer(inStock("TR"), com.vitadelta.api.catalog.price.StockFilter.IN_STOCK_ONLY)
            .toString();
    String firstScore =
        valid.replaceFirst("\"content_score\":[0-9.eE+-]+", "\"content_score\":NaN");
    String stranger =
        valid.replaceFirst(
            "\"product_id\":\"[0-9a-f-]{36}\"", "\"product_id\":\"" + UUID.randomUUID() + "\"");
    String notUuid =
        valid.replaceFirst("\"product_id\":\"[0-9a-f-]{36}\"", "\"product_id\":\"ADV-MARKER\"");
    String foreign =
        valid.replaceFirst(
            "\"product_id\":\"[0-9a-f-]{36}\"",
            "\"product_id\":\""
                + inStock("DE").stream()
                    .map(p -> p.id())
                    .filter(id -> inStock("TR").stream().noneMatch(t -> t.id().equals(id)))
                    .findFirst()
                    .orElseThrow()
                + "\"");
    for (String body :
        List.of(
            "<html>ADV-MARKER " + INTERNAL_HOST + "</html>",
            "{\"items\":[",
            firstScore,
            stranger,
            notUuid,
            foreign,
            "{}",
            "[]",
            "null")) {
      stub(
          aResponse()
              .withStatus(200)
              .withHeader("Content-Type", "application/json")
              .withBody(body));
      attack(500, "internal_error");
    }
    stub(aResponse().withStatus(200).withHeader("Content-Type", "application/json"));
    attack(500, "internal_error");
  }

  @Test
  void extraFieldsInAnAnswerAreNeverForwarded() {
    ObjectNode answer =
        scoreAnswer(inStock("TR"), com.vitadelta.api.catalog.price.StockFilter.IN_STOCK_ONLY);
    answer.put("debug", "ADV-MARKER " + INTERNAL_HOST);
    ((ObjectNode) answer.get("items").get(0)).put("internal_note", "ADV-MARKER /srv/scoring");
    ((ObjectNode) answer.get("meta")).put("host", INTERNAL_HOST);
    stub(
        aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withHeader("X-Internal-Host", INTERNAL_HOST)
            .withBody(answer.toString()));
    String cookie = anonymousCookie();
    Resp response = rawQuery(TR_QUERY, null, cookie);
    assertThat(response.status()).as(response.toString()).isIn(200, 500);
    assertNothingForwarded(response);
    assertThat(response.headers().map().toString()).doesNotContain(INTERNAL_HOST);
    assertThat(storedQueriesUsed(cookie)).isEqualTo(response.status() == 200 ? 1 : 0);
  }

  @Test
  void serverErrorsAndBrokenConnectionsAreUnavailableAndNotRetried() {
    for (int status : List.of(500, 502, 503, 504)) {
      stub(
          aResponse()
              .withStatus(status)
              .withHeader("Content-Type", "text/plain")
              .withHeader("Server", INTERNAL_HOST)
              .withBody(STACK.replace("\\n", "\n") + " ADV-MARKER"));
      attack(503, "scoring_unavailable");
    }
    for (Fault fault : List.of(Fault.CONNECTION_RESET_BY_PEER, Fault.EMPTY_RESPONSE)) {
      stub(aResponse().withFault(fault));
      attack(503, "scoring_unavailable");
    }
    for (Fault fault : List.of(Fault.MALFORMED_RESPONSE_CHUNK, Fault.RANDOM_DATA_THEN_CLOSE)) {
      stub(aResponse().withFault(fault));
      String cookie = anonymousCookie();
      Resp response = rawQuery(TR_QUERY, null, cookie);
      assertThat(response.status()).as(fault + " -> " + response).isIn(500, 503);
      assertNothingForwarded(response);
      assertThat(storedQueriesUsed(cookie)).isZero();
      assertThat(scoringRequests("score")).as("%s not retried", fault).hasSize(1);
    }
  }

  @Test
  void rejectionTextFromTheScoringServiceIsNeverForwarded() {
    String clientField =
        "{\"type\":\"https://vitadelta.dev/problems/validation_failed\",\"title\":\"Validation"
            + " failed\",\"status\":422,\"detail\":\""
            + STACK
            + "\",\"requestId\":\"ADV-MARKER\",\"errors\":[{\"field\":\"weights\",\"code\":"
            + "\"out_of_range\",\"message\":\""
            + STACK
            + "\"}]}";
    stub(
        aResponse()
            .withStatus(422)
            .withHeader("Content-Type", "application/problem+json")
            .withBody(clientField));
    String cookie = anonymousCookie();
    Resp response = rawQuery(TR_QUERY, null, cookie);
    assertThat(response.status()).as(response.toString()).isIn(422, 500);
    assertNothingForwarded(response);
    assertThat(storedQueriesUsed(cookie)).isZero();

    String hostileCode =
        "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":422,\"errors\":[{\"field\":"
            + "\"weights\",\"code\":\"<script>ADV-MARKER\",\"message\":\"x\"}]}";
    stub(
        aResponse()
            .withStatus(422)
            .withHeader("Content-Type", "application/problem+json")
            .withBody(hostileCode));
    attack(500, "internal_error");

    String huge =
        "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":422,\"detail\":\"ADV-MARKER"
            + "x".repeat(2 * 1024 * 1024)
            + "\",\"errors\":[]}";
    stub(
        aResponse()
            .withStatus(422)
            .withHeader("Content-Type", "application/problem+json")
            .withBody(huge));
    Resp big = attack(500, "internal_error");
    assertThat(big.body().length()).isLessThan(4096);
  }

  @Test
  void aSlowOrHangingAnswerIsCutOffByTheReadTimeout() {
    String valid =
        scoreAnswer(inStock("TR"), com.vitadelta.api.catalog.price.StockFilter.IN_STOCK_ONLY)
            .toString();
    stub(
        aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(valid)
            .withFixedDelay(10_000));
    long started = System.nanoTime();
    attack(503, "scoring_unavailable");
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(4_000L);

    stub(
        aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(valid)
            .withChunkedDribbleDelay(20, 10_000));
    started = System.nanoTime();
    String cookie = anonymousCookie();
    Resp dribbled = rawQuery(TR_QUERY, null, cookie);
    long elapsed = (System.nanoTime() - started) / 1_000_000;
    assertThat(elapsed)
        .as("a response trickling in for 10 s holds the request for %d ms: %s", elapsed, dribbled)
        .isLessThan(4_000L);
    AttackClient.assertProblem(dribbled, 503, "scoring_unavailable");
    assertThat(storedQueriesUsed(cookie)).isZero();
  }

  @Test
  void theClientRequestIdIsPropagatedAndAnInvalidOneIsReplacedEverywhere() {
    stubTr();
    String token = user(Role.USER).token();
    Resp accepted =
        req("POST", API + "/recommendations/query")
            .bearer(token)
            .requestId("adv-req-0001")
            .json(TR_QUERY)
            .send();
    assertThat(accepted.status()).isEqualTo(200);
    assertThat(accepted.header("x-request-id")).isEqualTo("adv-req-0001");
    assertThat(scoringRequestIds("score")).containsExactly("adv-req-0001");

    scoring().resetAll();
    caches.clear();
    stubTr();
    Resp replaced =
        req("POST", API + "/recommendations/query")
            .bearer(token)
            .requestId("bad id;X-Injected: 1 " + "z".repeat(100))
            .json(TR_QUERY)
            .send();
    assertThat(replaced.status()).as(replaced.toString()).isIn(200, 400);
    if (replaced.status() == 200) {
      String id = replaced.header("x-request-id");
      assertThat(id).matches("^[A-Za-z0-9-]{1,64}$");
      assertThat(scoringRequestIds("score")).containsExactly(id);
    }
  }

  @Test
  void aComparisonAnswerAboutOtherProductsIsAnInternalError() {
    UUID a = productId("GOLD-A");
    UUID b = productId("GOLD-B");
    UUID stranger = UUID.randomUUID();
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/compare"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            "{\"swapped\":false,\"verdict\":\"WORTH_IT\",\"a\":{\"product_id\":\""
                                + stranger
                                + "\"},\"b\":{\"product_id\":\""
                                + b
                                + "\"},\"debug\":\"ADV-MARKER "
                                + INTERNAL_HOST
                                + "\"}")));
    Resp response =
        rawCompare(
            "{\"country\":\"TR\",\"baselineProductId\":\""
                + a
                + "\",\"candidateProductId\":\""
                + b
                + "\"}",
            null);
    AttackClient.assertProblem(response, 500, "internal_error");
    assertNothingForwarded(response);
    assertThat(scoringRequests("compare")).hasSize(1);
  }
}
