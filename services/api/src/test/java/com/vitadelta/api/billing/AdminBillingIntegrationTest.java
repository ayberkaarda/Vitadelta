package com.vitadelta.api.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.entitlement.Contract;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.entitlement.SqlTime;
import com.vitadelta.api.entitlement.SubscriptionActivatedEvent;
import com.vitadelta.api.entitlement.SubscriptionRepository;
import com.vitadelta.api.entitlement.SubscriptionSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** Billing administration: manual grant and promo-code endpoints (sections 8.8, 8.9). */
class AdminBillingIntegrationTest extends BillingIntegrationTest {

  static final String PROMO_CODES = "/api/v1/admin/promo-codes";

  @Autowired SubscriptionRepository repository;
  @Autowired DataSource dataSource;

  private static String grantPath(UUID userId) {
    return "/api/v1/admin/users/" + userId + "/subscription";
  }

  private static String grantBody(int days) {
    return body(Map.of("planCode", "PRO", "durationDays", days));
  }

  private static String createBody(String prefix, Instant expiresAt) {
    Map<String, Object> fields = new HashMap<>();
    if (prefix != null) {
      fields.put("prefix", prefix);
    }
    fields.put("planCode", "PRO");
    fields.put("durationDays", 30);
    fields.put("maxRedemptions", 100);
    fields.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
    return body(fields);
  }

  @Test
  void adminRoutesRequireTheAdminRole() {
    TestUser user = user();
    TestUser target = user();
    String admin = admin().token();
    for (String token : new String[] {null, user.token()}) {
      int status = token == null ? 401 : 403;
      String code = token == null ? "unauthorized" : "forbidden";
      assertProblem(post(grantPath(target.id()), grantBody(30), token), status, code);
      assertProblem(post(PROMO_CODES, createBody(PROMO_PREFIX, null), token), status, code);
      assertProblem(get(PROMO_CODES, token), status, code);
      assertProblem(
          delete(PROMO_CODES + "/" + PROMO_PREFIX + "-0000000000000000", token), status, code);
    }
    assertStatus(post(grantPath(target.id()), grantBody(30), admin), 200);
    EntityExchangeResult<String> created = post(PROMO_CODES, createBody(PROMO_PREFIX, null), admin);
    assertStatus(created, 201);
    assertStatus(get(PROMO_CODES, admin), 200);
    assertThat(
            delete(PROMO_CODES + "/" + json(created).get("code").asString(), admin)
                .getStatus()
                .value())
        .isEqualTo(204);
  }

  @Test
  void manualGrantActivatesAndExtendsPro() {
    TestUser admin = admin();
    TestUser target = user();
    Instant now = clock.instant();
    EntityExchangeResult<String> granted =
        post(grantPath(target.id()), grantBody(30), admin.token());
    assertStatus(granted, 200);
    Contract.assertConforms("Subscription", granted.getResponseBody());
    JsonNode subscription = json(granted);
    assertThat(subscription.get("planCode").asString()).isEqualTo("PRO");
    assertThat(subscription.get("source").asString()).isEqualTo("MANUAL");
    assertThat(subscription.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(Instant.parse(subscription.get("currentPeriodEnd").asString()))
        .isEqualTo(now.plus(Duration.ofDays(30)));

    EntityExchangeResult<String> extended =
        post(grantPath(target.id()), grantBody(5), admin.token());
    assertStatus(extended, 200);
    assertThat(json(extended).get("id").asString()).isEqualTo(subscription.get("id").asString());
    assertThat(Instant.parse(json(extended).get("currentPeriodEnd").asString()))
        .isEqualTo(now.plus(Duration.ofDays(35)));
    assertThat(activeCount(target.id())).isEqualTo(1);
    assertThat(mail().await(target.email(), 2))
        .allSatisfy(m -> assertThat(m.subject()).isEqualTo("Your VitaDelta Pro plan is active"));
    assertThat(events.activated)
        .contains(
            new SubscriptionActivatedEvent(
                target.id(),
                UUID.fromString(subscription.get("id").asString()),
                PlanCode.PRO,
                SubscriptionSource.MANUAL,
                now.plus(Duration.ofDays(35)),
                true));
  }

  @Test
  void manualGrantValidatesItsInput() {
    String admin = admin().token();
    TestUser target = user();
    assertProblem(post(grantPath(UUID.randomUUID()), grantBody(30), admin), 404, "about:blank");
    assertProblem(
        post(grantPath(target.id()), body(Map.of("planCode", "FREE", "durationDays", 30)), admin),
        422,
        "validation_failed");
    assertProblem(post(grantPath(target.id()), grantBody(0), admin), 422, "validation_failed");
    assertProblem(
        post(grantPath(target.id()), grantBody(BillingApi.MAX_DURATION_DAYS + 1), admin),
        422,
        "validation_failed");
    assertProblem(
        post(grantPath(target.id()), body(Map.of("planCode", "PRO")), admin),
        422,
        "validation_failed");
    assertThat(repository.findActive(target.id()).orElseThrow().planCode())
        .isEqualTo(PlanCode.FREE);
  }

  @Test
  void aUniqueIndexConflictIsConflictRetry() throws Exception {
    String admin = admin().token();
    TestUser target = user();
    jdbc.sql(
            "UPDATE subscription SET status = 'CANCELED', canceled_at = ?"
                + " WHERE user_id = ? AND status = 'ACTIVE'")
        .params(SqlTime.ts(clock.instant()), target.id())
        .update();
    try (Connection writer = dataSource.getConnection()) {
      writer.setAutoCommit(false);
      try (PreparedStatement replica =
          writer.prepareStatement("SET LOCAL session_replication_role = replica")) {
        replica.execute();
      }
      // An ACTIVE row written without the user-row lock (foreign-key checks are off in this
      // session, so it does not wait for the lock either) and not yet committed.
      try (PreparedStatement insert =
          writer.prepareStatement(
              "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at)"
                  + " VALUES (?, ?, 'FREE', 'ACTIVE', 'MANUAL', now())")) {
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, target.id());
        insert.executeUpdate();
      }
      String clientIp = ip;
      CompletableFuture<EntityExchangeResult<String>> grant =
          CompletableFuture.supplyAsync(
              () -> post(grantPath(target.id()), grantBody(30), admin, clientIp, null));
      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .until(() ->
              jdbc.sql(
                          "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                              + " AND query LIKE 'INSERT INTO subscription%'")
                      .query(Long.class)
                      .single()
                  > 0);
      writer.commit();
      EntityExchangeResult<String> result = grant.get(20, TimeUnit.SECONDS);
      assertProblem(result, 409, "conflict_retry");
    }
    assertThat(activeCount(target.id())).isEqualTo(1);
    assertThat(repository.findActive(target.id()).orElseThrow().planCode())
        .isEqualTo(PlanCode.FREE);
  }

  @Test
  void promoCodesAreGeneratedListedAndDeleted() {
    TestUser admin = admin();
    Instant expiresAt = clock.instant().plus(Duration.ofDays(60));
    EntityExchangeResult<String> created =
        post(
            PROMO_CODES,
            createBody(PROMO_PREFIX.toLowerCase(Locale.ROOT), expiresAt),
            admin.token());
    assertStatus(created, 201);
    Contract.assertConforms("PromoCode", created.getResponseBody());
    JsonNode code = json(created);
    String value = code.get("code").asString();
    assertThat(value).matches("^" + PROMO_PREFIX + "-[0-9A-HJKMNP-TV-Z]{16}$");
    assertThat(code.get("planCode").asString()).isEqualTo("PRO");
    assertThat(code.get("durationDays").asInt()).isEqualTo(30);
    assertThat(code.get("maxRedemptions").asInt()).isEqualTo(100);
    assertThat(code.get("redeemedCount").asInt()).isZero();
    assertThat(Instant.parse(code.get("expiresAt").asString())).isEqualTo(expiresAt);
    assertThat(code.get("createdBy").asString()).isEqualTo(admin.id().toString());

    EntityExchangeResult<String> bare = post(PROMO_CODES, createBody(null, null), admin.token());
    assertStatus(bare, 201);
    String bareCode = json(bare).get("code").asString();
    assertThat(bareCode).matches("^[0-9A-HJKMNP-TV-Z]{16}$");
    assertThat(json(bare).get("expiresAt").isNull()).isTrue();

    EntityExchangeResult<String> list = get(PROMO_CODES, admin.token());
    assertStatus(list, 200);
    Contract.assertConforms("PromoCodeList", list.getResponseBody());
    List<String> codes = new ArrayList<>();
    for (JsonNode item : json(list).get("items")) {
      codes.add(item.get("code").asString());
    }
    assertThat(codes).contains(value, bareCode);

    assertStatus(redeem(value, user().token()), 200);
    assertThat(redeemedCount(value)).isEqualTo(1);

    assertStatus(delete(PROMO_CODES + "/" + value.toLowerCase(Locale.ROOT), admin.token()), 204);
    assertProblem(delete(PROMO_CODES + "/" + value, admin.token()), 404, "about:blank");
    assertStatus(delete(PROMO_CODES + "/" + bareCode, admin.token()), 204);
    assertThat(
            jdbc.sql("SELECT count(*) FROM promo_redemption WHERE code = CAST(? AS citext)")
                .param(value)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void promoCodeCreationValidatesItsInput() {
    String admin = admin().token();
    assertProblem(
        post(PROMO_CODES, createBody(PROMO_PREFIX, clock.instant().minusSeconds(1)), admin),
        422,
        "validation_failed");
    assertProblem(
        post(PROMO_CODES, createBody("bad-prefix", null), admin), 422, "validation_failed");
    Map<String, Object> freePlan = new HashMap<>();
    freePlan.put("planCode", "FREE");
    freePlan.put("durationDays", 30);
    freePlan.put("maxRedemptions", 1);
    freePlan.put("expiresAt", null);
    assertProblem(post(PROMO_CODES, body(freePlan), admin), 422, "validation_failed");
    freePlan.put("planCode", "PRO");
    freePlan.put("maxRedemptions", 0);
    assertProblem(post(PROMO_CODES, body(freePlan), admin), 422, "validation_failed");
    EntityExchangeResult<String> past =
        post(PROMO_CODES, createBody(PROMO_PREFIX, clock.instant()), admin);
    assertThat(json(past).get("errors").get(0).get("field").asString()).isEqualTo("expiresAt");
  }
}
