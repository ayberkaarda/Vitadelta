package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Section 15.7 case 15: every failing promo redemption looks the same, unverified users cannot
 * redeem, a code can never be redeemed more often than its limit even under 50 parallel attempts,
 * the per-user limit applies, and concurrent extensions of one user are never lost (section 8.3).
 */
class PromoAbuseAdversarialTest extends AdversarialIntegrationTest {

  private Resp redeem(String token, String code, String requestId) {
    var request =
        http.req("POST", API + "/me/subscription/promo")
            .ip(randomIp())
            .bearer(token)
            .json(Map.of("code", code));
    if (requestId != null) {
      request.requestId(requestId);
    }
    return request.send();
  }

  private long proSubscriptions(UUID userId) {
    return count(
        "SELECT count(*) FROM subscription WHERE user_id = ? AND plan_code = 'PRO' AND status ="
            + " 'ACTIVE'",
        userId);
  }

  @Test
  void everyFailureIsTheSameProblemByteForByte() {
    UUID victim = insertUser(true);
    String token = accessToken(victim, "USER", true);

    String expired = insertPromo(10, 0, clock.instant().plus(Duration.ofDays(1)));
    String exhausted = insertPromo(1, 0, null);
    String reusable = insertPromo(5, 0, null);
    assertThat(redeem(accessToken(insertUser(true), "USER", true), exhausted, null).status())
        .isEqualTo(200);
    assertThat(redeem(token, reusable, null).status()).isEqualTo(200);
    clock.advance(Duration.ofDays(2));
    token = accessToken(victim, "USER", true);

    String requestId = "promo-" + UUID.randomUUID();
    Resp unknown = redeem(token, randomCode(), requestId);
    Resp expiredResponse = redeem(token, expired, requestId);
    Resp exhaustedResponse = redeem(token, exhausted, requestId);
    Resp alreadyRedeemed = redeem(token, reusable.toLowerCase(Locale.ROOT), requestId);
    assertProblem(unknown, 400, "promo_invalid");
    for (Resp response : List.of(expiredResponse, exhaustedResponse, alreadyRedeemed)) {
      assertProblem(response, 400, "promo_invalid");
      assertThat(response.body()).isEqualTo(unknown.body());
      assertThat(response.comparableHeaders()).isEqualTo(unknown.comparableHeaders());
    }
    assertThat(unknown.body()).doesNotContain("exhausted").doesNotContain("already");
    assertThat(count("SELECT count(*) FROM promo_redemption WHERE user_id = ?", victim))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT redeemed_count FROM promo_code WHERE code = CAST(? AS citext)", exhausted))
        .isEqualTo(1);
    assertThat(
            count("SELECT redeemed_count FROM promo_code WHERE code = CAST(? AS citext)", expired))
        .isZero();
  }

  @Test
  void unverifiedUsersCannotRedeem() {
    String code = insertPromo(10, 0, null);
    UUID unverified = insertUser(false);
    Resp response = redeem(accessToken(unverified, "USER", true), code, null);
    assertProblem(response, 403, "email_not_verified");
    assertThat(proSubscriptions(unverified)).isZero();
    assertThat(count("SELECT redeemed_count FROM promo_code WHERE code = CAST(? AS citext)", code))
        .isZero();

    String email = uniqueEmail();
    register(email, PASSWORD);
    Session session = login(email, PASSWORD);
    assertProblem(redeem(session.accessToken(), code, null), 403, "email_not_verified");
  }

  @Test
  void fiftyParallelRedemptionsOfATenUseCodeActivateExactlyTen() throws Exception {
    String code = insertPromo(10, 0, null);
    List<UUID> users = new ArrayList<>();
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      UUID id = insertUser(true);
      users.add(id);
      tokens.add(accessToken(id, "USER", true));
    }
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Resp>> calls = new ArrayList<>();
    for (String token : tokens) {
      calls.add(() -> {
        start.await();
        return redeem(token, code, null);
      });
    }
    List<Integer> statuses = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(50)) {
      List<Future<Resp>> futures = new ArrayList<>();
      for (Callable<Resp> call : calls) {
        futures.add(pool.submit(call));
      }
      start.countDown();
      for (Future<Resp> future : futures) {
        Resp response = future.get();
        statuses.add(response.status());
        if (response.status() != 200) {
          assertProblem(response, 400, "promo_invalid");
        }
      }
    }
    assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(10);
    long activated = users.stream().mapToLong(this::proSubscriptions).sum();
    assertThat(activated).isEqualTo(10);
    assertThat(count("SELECT redeemed_count FROM promo_code WHERE code = CAST(? AS citext)", code))
        .isEqualTo(10);
    assertThat(count("SELECT count(*) FROM promo_redemption WHERE code = CAST(? AS citext)", code))
        .isEqualTo(10);
  }

  @Test
  void perUserLimitAppliesAcrossIpsAndCodes() {
    String token = accessToken(insertUser(true), "USER", true);
    for (int i = 0; i < 5; i++) {
      assertProblem(redeem(token, randomCode(), null), 400, "promo_invalid");
    }
    Resp limited = redeem(token, insertPromo(10, 0, null), null);
    assertProblem(limited, 429, "rate_limited");
    assertThat(Long.parseLong(limited.header("retry-after"))).isPositive();
  }

  @Test
  void parallelRedemptionsByOneUserExtendWithoutLosingTime() throws Exception {
    UUID id = insertUser(true);
    String token = accessToken(id, "USER", true);
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      codes.add(insertPromo(10, 0, null));
    }
    Instant now = clock.instant();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
      List<Future<Resp>> futures = new ArrayList<>();
      for (String code : codes) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return redeem(token, code, null);
            }));
      }
      start.countDown();
      for (Future<Resp> future : futures) {
        Resp response = future.get();
        assertThat(response.status()).as(response.toString()).isIn(200, 409);
      }
    }
    long successes = count("SELECT count(*) FROM promo_redemption WHERE user_id = ?", id);
    assertThat(proSubscriptions(id)).isEqualTo(1);
    Timestamp end =
        jdbc.sql(
                "SELECT current_period_end FROM subscription WHERE user_id = ? AND status ="
                    + " 'ACTIVE'")
            .param(id)
            .query(Timestamp.class)
            .single();
    // Timestamps are stored with microsecond precision; one second of slack absorbs the rounding.
    assertThat(Duration.between(now, end.toInstant()).toSeconds())
        .as("%d redemptions of 30 days each", successes)
        .isBetween(
            Duration.ofDays(30 * successes).toSeconds() - 1,
            Duration.ofDays(30 * successes).toSeconds() + 1);
    assertThat(successes).as("the per-user limit allows all five").isEqualTo(5);
    assertThat(
            count("SELECT count(*) FROM subscription WHERE user_id = ? AND status = 'ACTIVE'", id))
        .isEqualTo(1);
  }
}
