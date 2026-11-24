package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Req;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 7 and malformed input on {@code POST /recommendations/query} and {@code
 * /compare}: bodies over 64 kB (declared and chunked), oversized maps and lists, deep nesting,
 * non-finite and out-of-range number literals, extreme exponents inside the allowed ranges, type
 * confusion and duplicate keys. Every rejection is a problem+json without internals, happens before
 * the quota is reserved and before the scoring service is called.
 */
class RecommendationInputAdversarialTest extends CatalogAdversarialTest {

  private static final int LIMIT = 64 * 1024;

  private String compareBody() {
    return "{\"country\":\"TR\",\"baselineProductId\":\""
        + productId("GOLD-A")
        + "\",\"candidateProductId\":\""
        + productId("GOLD-B")
        + "\"";
  }

  @Test
  void bodiesOverSixtyFourKilobytesAreRejectedWhetherDeclaredOrChunked() {
    stubTr();
    String cookie = anonymousCookie();
    String padding = ",\"pad\":\"" + "p".repeat(LIMIT) + "\"}";
    for (boolean chunked : List.of(false, true)) {
      for (String path : List.of("/recommendations/query", "/recommendations/compare")) {
        String body = (path.endsWith("query") ? "{\"country\":\"TR\"" : compareBody()) + padding;
        Req request = req("POST", API + path).header("Cookie", cookie).json(body);
        Resp response = (chunked ? request.chunked() : request).send();
        AttackClient.assertProblem(response, 413, "validation_failed");
        AttackClient.assertApiSecurityHeaders(response);
      }
    }
    assertThat(storedQueriesUsed(cookie)).isZero();
    assertThat(scoringRequests("score")).isEmpty();
    assertThat(scoringRequests("compare")).isEmpty();
  }

  @Test
  void tenThousandWeightKeysAreRejectedBySizeAndThousandsOfBogusKeysDoNotStall() {
    StringBuilder huge = new StringBuilder("{\"country\":\"TR\",\"weights\":{\"overrides\":{");
    for (int i = 0; i < 10_000; i++) {
      huge.append(i == 0 ? "" : ",").append("\"N").append(i).append("\":1");
    }
    huge.append("}}}");
    assertThat(huge.length()).isGreaterThan(LIMIT);
    AttackClient.assertProblem(rawQuery(huge.toString(), null, null), 413, "validation_failed");

    StringBuilder many = new StringBuilder("{\"country\":\"TR\",\"weights\":{\"overrides\":{");
    int keys = 0;
    for (char a = 'A'; a <= 'Z'; a++) {
      for (char b = 'A'; b <= 'Z'; b++) {
        for (char c = 'A'; c <= 'F'; c++) {
          many.append(keys++ == 0 ? "" : ",").append("\"Q").append(a).append(b).append(c);
          many.append("\":1");
        }
      }
    }
    many.append("}}}");
    assertThat(many.length()).isLessThan(LIMIT);
    String cookie = anonymousCookie();
    Resp response = rawQuery(many.toString(), null, cookie);
    AttackClient.assertProblem(response, 422, "validation_failed");
    assertThat(response.json().get("errors").size()).isEqualTo(keys);
    assertThat(response.nanos()).isLessThan(5_000_000_000L);
    assertThat(storedQueriesUsed(cookie)).isZero();

    StringBuilder required = new StringBuilder("{\"country\":\"TR\",\"requiredNutrients\":[");
    for (int i = 0; i < 3_000; i++) {
      required.append(i == 0 ? "" : ",").append("{\"code\":\"ZINC\"}");
    }
    required.append("]}");
    Resp duplicates = rawQuery(required.toString(), null, cookie);
    AttackClient.assertProblem(duplicates, 422, "validation_failed");
    assertThat(duplicates.nanos()).isLessThan(5_000_000_000L);
    assertThat(storedQueriesUsed(cookie)).isZero();
    assertThat(scoringRequests("score")).isEmpty();
  }

  @Test
  void deeplyNestedJsonIsAValidationErrorNotAServerError() {
    String cookie = anonymousCookie();
    for (String body :
        List.of(
            "{\"country\":\"TR\",\"weights\":" + "[".repeat(10_000) + "]".repeat(10_000) + "}",
            "{\"country\":\"TR\",\"ranking\":"
                + "{\"a\":".repeat(5_000)
                + "1"
                + "}".repeat(5_000)
                + "}",
            "[".repeat(30_000))) {
      Resp response = rawQuery(body, null, cookie);
      AttackClient.assertProblem(response, 422, "validation_failed");
    }
    Resp compare = rawCompare(compareBody() + ",\"weights\":" + "[".repeat(10_000) + "}", null);
    AttackClient.assertProblem(compare, 422, "validation_failed");
    assertThat(storedQueriesUsed(cookie)).isZero();
  }

  @Test
  void nonFiniteAndOutOfRangeNumberLiteralsAreValidationErrors() {
    String cookie = anonymousCookie();
    for (String body :
        List.of(
            "{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":NaN}}}",
            "{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":Infinity}}}",
            "{\"country\":\"TR\",\"weights\":{\"overrides\":{\"ZINC\":-Infinity}}}",
            "{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":1e400}}}",
            "{\"country\":\"TR\",\"weights\":{\"overrides\":{\"ZINC\":-1e-400}}}",
            "{\"country\":\"TR\",\"ranking\":{\"mode\":\"CUSTOM\",\"lambda\":1e400}}",
            "{\"country\":\"TR\",\"ranking\":{\"mode\":\"CUSTOM\",\"lambda\":-0.0000001}}",
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"ZINC\","
                + "\"minAmountPerDay\":NaN,\"unit\":\"mg\"}]}",
            "{\"country\":\"TR\",\"requiredNutrients\":[{\"code\":\"ZINC\","
                + "\"minAmountPerDay\":-1e400,\"unit\":\"mg\"}]}",
            "{\"country\":\"TR\",\"page\":" + "9".repeat(2000) + "}",
            "{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":0."
                + "1".repeat(5000)
                + "}}}")) {
      Resp response = rawQuery(body, null, cookie);
      AttackClient.assertProblem(response, 422, "validation_failed");
      assertThat(response.nanos()).isLessThan(5_000_000_000L);
    }
    assertThat(storedQueriesUsed(cookie)).isZero();
    assertThat(scoringRequests("score")).isEmpty();
  }

  /**
   * A weight or lambda of {@code 1e-N} lies inside its allowed range for every N, so it passes
   * validation. It must still be answered promptly ({@code 200} or {@code 422}); the text is 15
   * bytes, so any work proportional to N is an amplification. The exponent is chosen so that a
   * plain-text rendering of the number could never fit the heap of this JVM.
   */
  @Test
  void inRangeNumbersWithExtremeExponentsAreAnsweredPromptlyAndOnlyA200ConsumesQuota() {
    stubTr();
    long exponent = Math.min(2_000_000_000L, 2 * Runtime.getRuntime().maxMemory());
    for (String body :
        List.of(
            "{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":1e-" + exponent + "}}}",
            "{\"country\":\"TR\",\"ranking\":{\"mode\":\"CUSTOM\",\"lambda\":1e-"
                + exponent
                + "}}")) {
      String cookie = anonymousCookie();
      Resp response = rawQuery(body, null, cookie);
      int used = storedQueriesUsed(cookie);
      AttackClient.assertNoLeak(response);
      org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
      soft.assertThat(response.status())
          .as("a valid tiny weight is ranked or rejected, never a server error: %s", response)
          .isIn(200, 422);
      soft.assertThat(response.nanos()).isLessThan(5_000_000_000L);
      soft.assertThat(used)
          .as("only a 200 consumes the trial query (status %d)", response.status())
          .isEqualTo(response.status() == 200 ? 1 : 0);
      soft.assertAll();
    }
  }

  @Test
  void typeConfusionIsRejectedOrInterpretedExactlyLikeTheTypedValue() {
    stubTr();
    String token = user(Role.USER).token();
    for (String body :
        List.of(
            "{\"country\":123}",
            "{\"country\":[\"TR\"]}",
            "{\"country\":{\"code\":\"TR\"}}",
            "{\"country\":\"TR\",\"requiredNutrients\":\"ZINC\"}",
            "{\"country\":\"TR\",\"requiredNutrients\":{\"code\":\"ZINC\"}}",
            "{\"country\":\"TR\",\"weights\":[1,2]}",
            "{\"country\":\"TR\",\"ranking\":\"VALUE\"}",
            "{\"country\":\"TR\",\"ranking\":{\"mode\":[\"VALUE\"]}}",
            "{\"country\":\"TR\",\"stockFilter\":true}",
            "{\"country\":\"TR\",\"weights\":{\"categories\":{\"VITAMIN\":{\"$gt\":0}}}}",
            "\"TR\"",
            "null",
            "",
            "[]")) {
      Resp response = rawQuery(body, token, null);
      assertThat(response.status()).as(body + " -> " + response).isIn(415, 422);
      AttackClient.assertNoLeak(response);
    }
    Resp numeric = rawQuery("{\"country\":\"TR\",\"page\":2,\"pageSize\":5}", token, null);
    for (String body :
        List.of(
            "{\"country\":\"TR\",\"page\":\"2\",\"pageSize\":\"5\"}",
            "{\"country\":\"TR\",\"page\":2.0,\"pageSize\":5.0}")) {
      Resp response = rawQuery(body, token, null);
      assertThat(response.status()).as(body + " -> " + response).isIn(200, 422);
      if (response.status() == 200) {
        assertThat(response.json().get("items")).isEqualTo(numeric.json().get("items"));
      }
    }
  }

  @Test
  void duplicateKeysCannotSmuggleASecondCountryPastValidation() {
    stubTr();
    String cookie = anonymousCookie();
    Resp response = rawQuery("{\"country\":\"TR\",\"country\":\"ZZ\"}", null, cookie);
    assertThat(response.status()).as(response.toString()).isIn(422);
    AttackClient.assertNoLeak(response);
    Resp reversed = rawQuery("{\"country\":\"ZZ\",\"country\":\"TR\"}", null, cookie);
    assertThat(reversed.status()).as(reversed.toString()).isIn(200, 422);
    if (reversed.status() == 200) {
      assertThat(reversed.json().get("meta").get("country").asString()).isEqualTo("TR");
    }
  }
}
