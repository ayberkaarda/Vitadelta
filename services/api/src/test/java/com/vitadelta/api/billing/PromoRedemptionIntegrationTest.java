package com.vitadelta.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.entitlement.Contract;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SubscriptionActivatedEvent;
import com.vitadelta.api.entitlement.SubscriptionExpiryJob;
import com.vitadelta.api.entitlement.SubscriptionRepository;
import com.vitadelta.api.entitlement.SubscriptionSource;
import com.vitadelta.api.identity.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** {@code POST /me/subscription/promo} (section 8.8). */
class PromoRedemptionIntegrationTest extends BillingIntegrationTest {

  @Autowired SubscriptionRepository repository;
  @Autowired SubscriptionExpiryJob expiryJob;
  @Autowired MeterRegistry meters;

  @Test
  void aValidCodeActivatesProCaseInsensitively() {
    TestUser user = user();
    String code = promo(30, 10, clock.instant().plus(Duration.ofDays(5)));
    EntityExchangeResult<String> result = redeem(code.toLowerCase(Locale.ROOT), user.token());
    assertStatus(result, 200);
    Contract.assertConforms("SubscriptionActivation", result.getResponseBody());
    JsonNode body = json(result);
    assertThat(body.get("plan").asString()).isEqualTo("PRO");
    JsonNode subscription = body.get("subscription");
    assertThat(subscription.get("planCode").asString()).isEqualTo("PRO");
    assertThat(subscription.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(subscription.get("source").asString()).isEqualTo("PROMO");
    assertThat(Instant.parse(subscription.get("currentPeriodEnd").asString()))
        .isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    assertThat(redeemedCount(code)).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT code FROM promo_redemption WHERE user_id = ?")
                .param(user.id())
                .query(String.class)
                .single())
        .isEqualTo(code);
    assertThat(mail().await(user.email(), 1).get(0).subject())
        .isEqualTo("Your VitaDelta Pro plan is active");
    assertThat(events.activated)
        .anySatisfy(event -> {
          assertThat(event.userId()).isEqualTo(user.id());
          assertThat(event.source()).isEqualTo(SubscriptionSource.PROMO);
          assertThat(event.extended()).isFalse();
        });
  }

  @Test
  void everyFailureReturnsTheSameBody() {
    TestUser user = user();
    String used = promo(30, 10, null);
    assertStatus(redeem(used, user.token()), 200);
    String exhausted = promo(30, 1, null);
    assertStatus(redeem(exhausted, user().token()), 200);
    String expired = promo(30, 10, clock.instant().minusSeconds(1));
    String unknown = generator.generate(PROMO_PREFIX);

    double rejectedBefore = rejected();
    String requestId = "promo-" + UUID.randomUUID().toString().substring(0, 8);
    List<EntityExchangeResult<String>> failures =
        List.of(
            redeem(unknown, user.token(), ip, requestId),
            redeem(expired, user.token(), ip, requestId),
            redeem(exhausted, user.token(), ip, requestId),
            redeem(used, user.token(), ip, requestId));
    for (EntityExchangeResult<String> failure : failures) {
      assertProblem(failure, 400, "promo_invalid");
      assertThat(json(failure).get("detail").asString())
          .isEqualTo("The promo code cannot be redeemed.");
    }
    byte[] reference = failures.get(0).getResponseBodyContent();
    assertThat(failures)
        .allSatisfy(failure -> assertThat(failure.getResponseBodyContent()).isEqualTo(reference));
    assertThat(failures)
        .extracting(failure -> failure.getResponseHeaders().getContentType())
        .containsOnly(failures.get(0).getResponseHeaders().getContentType());
    assertThat(rejected() - rejectedBefore).isEqualTo(4);
    for (String reason : List.of("unknown", "expired", "exhausted", "already_redeemed")) {
      assertThat(meters.counter("vitadelta.promo.rejected", "reason", reason).count())
          .isGreaterThanOrEqualTo(1);
    }

    assertThat(redeemedCount(expired)).isZero();
    assertThat(redeemedCount(exhausted)).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM promo_redemption WHERE code IN (?, ?)")
                .params(expired, exhausted)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(activeCount(user.id())).isEqualTo(1);
  }

  private double rejected() {
    return meters.find("vitadelta.promo.rejected").counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  /** Code values that can never be a promo code: wrong format, empty, oversized, control chars. */
  private static final List<String> MALFORMED =
      List.of(
          "!",
          "",
          "x".repeat(500),
          PROMO_PREFIX + "-\u0001" + "0123456789ABCDE",
          "not a code",
          PROMO_PREFIX + "-0123456789ABCDEFG",
          PROMO_PREFIX + "-0123456789ABCDEI",
          "\u0000' OR 1=1--",
          " " + PROMO_PREFIX + "-0123456789ABCDEF",
          "ÄÖ-0123456789ABCDEF");

  @Test
  void malformedCodeValuesGetTheUniformInvalidBody() {
    TestUser user = user();
    String requestId = "promo-" + UUID.randomUUID().toString().substring(0, 8);
    EntityExchangeResult<String> unknown =
        redeem(generator.generate(PROMO_PREFIX), user.token(), ip, requestId);
    assertProblem(unknown, 400, "promo_invalid");
    double malformedBefore =
        meters.counter("vitadelta.promo.rejected", "reason", "malformed").count();
    for (String code : MALFORMED.subList(0, 4)) {
      EntityExchangeResult<String> result = redeem(code, user.token(), ip, requestId);
      assertProblem(result, 400, "promo_invalid");
      assertThat(result.getResponseBodyContent())
          .as("code %s", code.length() > 20 ? code.substring(0, 20) + "..." : code)
          .isEqualTo(unknown.getResponseBodyContent());
      assertThat(result.getResponseHeaders().getContentType())
          .isEqualTo(unknown.getResponseHeaders().getContentType());
    }
    assertThat(meters.counter("vitadelta.promo.rejected", "reason", "malformed").count())
        .isEqualTo(malformedBefore + 4);
    assertThat(
            jdbc.sql("SELECT count(*) FROM promo_redemption WHERE user_id = ?")
                .param(user.id())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void malformedCodeValuesCountAgainstThePerUserLimit() {
    TestUser user = user();
    for (String code : MALFORMED.subList(0, 5)) {
      assertProblem(redeem(code, user.token(), randomIp(), null), 400, "promo_invalid");
    }
    EntityExchangeResult<String> limited = redeem(promo(30, 10, null), user.token(), ip, null);
    assertProblem(limited, 429, "rate_limited");
    assertThat(limited.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
  }

  @Test
  void malformedCodeValuesCountAgainstThePerIpLimit() {
    for (String code : MALFORMED) {
      assertProblem(redeem(code, user().token()), 400, "promo_invalid");
    }
    EntityExchangeResult<String> limited = redeem(promo(30, 10, null), user().token());
    assertProblem(limited, 429, "rate_limited");
    assertThat(limited.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
  }

  @Test
  void anUnverifiedEmailWinsOverAMalformedCode() {
    TestUser unverified = user(false, Role.USER);
    assertProblem(redeem("!", unverified.token()), 403, "email_not_verified");
  }

  @Test
  void aMissingOrNonTextCodeIsAMalformedRequest() {
    TestUser user = user();
    assertProblem(post(REDEEM, "{}", user.token()), 422, "validation_failed");
    assertProblem(post(REDEEM, "{\"code\":null}", user.token()), 422, "validation_failed");
    assertProblem(post(REDEEM, "{\"code\":[\"!\"]}", user.token()), 422, "validation_failed");
    assertProblem(post(REDEEM, "{\"code\":{\"a\":1}}", user.token()), 422, "validation_failed");
    // Malformed requests are not code guesses: the per-user budget of five is still intact.
    for (int i = 0; i < 5; i++) {
      assertProblem(redeem(generator.generate(PROMO_PREFIX), user.token()), 400, "promo_invalid");
    }
  }

  @Test
  void anonymousCallersAreRejected() {
    assertProblem(redeem(promo(30, 10, null), null), 401, "unauthorized");
  }

  @Test
  void anUnverifiedEmailCannotRedeem() {
    TestUser unverified = user(false, Role.USER);
    String code = promo(30, 10, null);
    assertProblem(redeem(code, unverified.token()), 403, "email_not_verified");
    assertThat(redeemedCount(code)).isZero();
    assertThat(repository.findActive(unverified.id()).orElseThrow().planCode())
        .isEqualTo(PlanCode.FREE);
  }

  @Test
  void redemptionsAreLimitedPerUser() {
    TestUser user = user();
    for (int i = 0; i < 5; i++) {
      assertProblem(redeem(generator.generate(PROMO_PREFIX), user.token()), 400, "promo_invalid");
    }
    EntityExchangeResult<String> limited = redeem(generator.generate(PROMO_PREFIX), user.token());
    assertProblem(limited, 429, "rate_limited");
    assertThat(limited.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
  }

  @Test
  void redemptionsAreLimitedPerIp() {
    for (int i = 0; i < 10; i++) {
      assertProblem(redeem(generator.generate(PROMO_PREFIX), user().token()), 400, "promo_invalid");
    }
    EntityExchangeResult<String> limited = redeem(generator.generate(PROMO_PREFIX), user().token());
    assertProblem(limited, 429, "rate_limited");
    assertThat(limited.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    assertStatus(redeem(promo(30, 10, null), user().token(), randomIp(), null), 200);
  }

  @Test
  void fiftyParallelRedemptionsOfATenUseCodeYieldExactlyTen() throws Exception {
    String code = promo(30, 10, null);
    List<TestUser> redeemers = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      redeemers.add(user());
    }
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (TestUser redeemer : redeemers) {
      String clientIp = randomIp();
      tasks.add(() -> redeem(code, redeemer.token(), clientIp, null).getStatus().value());
    }
    List<Integer> statuses = parallel(tasks);
    assertThat(statuses).filteredOn(status -> status == 200).hasSize(10);
    assertThat(statuses).filteredOn(status -> status == 400).hasSize(40);
    assertThat(redeemedCount(code)).isEqualTo(10);
    assertThat(
            jdbc.sql("SELECT count(*) FROM promo_redemption WHERE code = CAST(? AS citext)")
                .param(code)
                .query(Long.class)
                .single())
        .isEqualTo(10);
    long pro =
        redeemers.stream()
            .filter(r -> repository.findActive(r.id()).orElseThrow().planCode() == PlanCode.PRO)
            .count();
    assertThat(pro).isEqualTo(10);
  }

  @Test
  void concurrentActivationsOfOneUserApplyBothExtensions() throws Exception {
    for (int round = 0; round < 3; round++) {
      TestUser user = user();
      String month = promo(30, 10, null);
      String tenDays = promo(10, 10, null);
      List<Integer> statuses =
          parallel(
              List.of(
                  () -> redeem(month, user.token(), randomIp(), null).getStatus().value(),
                  () -> redeem(tenDays, user.token(), randomIp(), null).getStatus().value()));
      assertThat(statuses).containsOnly(200);
      assertThat(activeCount(user.id())).isEqualTo(1);
      assertThat(repository.findActive(user.id()).orElseThrow().currentPeriodEnd())
          .isEqualTo(clock.instant().plus(Duration.ofDays(40)));
      assertThat(subscriptions(user.id()))
          .extracting(row -> row.get("plan_code") + "/" + row.get("status"))
          .containsExactlyInAnyOrder("FREE/CANCELED", "PRO/ACTIVE");
      List<SubscriptionActivatedEvent> mine =
          events.activated.stream().filter(e -> e.userId().equals(user.id())).toList();
      assertThat(mine)
          .extracting(SubscriptionActivatedEvent::extended)
          .containsExactlyInAnyOrder(false, true);
    }
  }

  @Test
  void aRedemptionRacingTheExpiryJobLeavesOneActiveSubscription() throws Exception {
    List<TestUser> racers = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      TestUser user = user();
      transactions.executeWithoutResult(status ->
          subscriptionService.activate(user.id(), PlanCode.PRO, 30, SubscriptionSource.PROMO));
      racers.add(user);
    }
    clock.advance(Duration.ofDays(31));
    List<Callable<Integer>> tasks = new ArrayList<>();
    tasks.add(() -> {
      expiryJob.run();
      return 0;
    });
    for (TestUser racer : racers) {
      String code = promo(30, 10, null);
      String token = token(racer);
      tasks.add(() -> redeem(code, token, randomIp(), null).getStatus().value());
    }
    List<Integer> statuses = parallel(tasks);
    assertThat(statuses.subList(1, statuses.size())).containsOnly(200);
    for (TestUser racer : racers) {
      assertThat(activeCount(racer.id())).isEqualTo(1);
      assertThat(repository.findActive(racer.id()).orElseThrow())
          .satisfies(active -> {
            assertThat(active.planCode()).isEqualTo(PlanCode.PRO);
            assertThat(active.currentPeriodEnd())
                .isEqualTo(clock.instant().plus(Duration.ofDays(30)));
          });
    }
    expiryJob.run();
    for (TestUser racer : racers) {
      assertThat(repository.findActive(racer.id()).orElseThrow().planCode())
          .isEqualTo(PlanCode.PRO);
    }
  }
}
