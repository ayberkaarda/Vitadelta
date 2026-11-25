package com.vitadelta.api.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.entitlement.SubscriptionService.Activation;
import com.vitadelta.api.identity.AccountUsageProvider;
import com.vitadelta.api.identity.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;

/**
 * Saved-product and alert-subscription limits through {@link EntitlementService} (section 8.4),
 * including the concurrency guarantees of the user-row lock, and the usage meters of {@code GET
 * /me} and {@code GET /session/quota}.
 */
class EntitlementLimitsIntegrationTest extends EntitlementIntegrationTest {

  @Autowired EntitlementService entitlements;
  @Autowired AccountUsageProvider usageProvider;
  @Autowired MeterRegistry meters;

  private double rejections(String action, String reason) {
    var counter =
        meters
            .find(EntitlementService.REJECTED_METRIC)
            .tag("action", action)
            .tag("reason", reason)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private EntityExchangeResult<String> save(TestUser user, UUID productId) {
    return post(
        "/api/v1/me/entitlement-probe/saved-products/" + productId + "?country=" + COUNTRY,
        "{}",
        user.token());
  }

  private EntityExchangeResult<String> activateAlert(TestUser user, UUID alertId) {
    return post("/api/v1/me/entitlement-probe/alerts/" + alertId + "/activate", "{}", user.token());
  }

  private void pro(TestUser user) {
    transactions.executeWithoutResult(status -> {
      Activation activation =
          subscriptionService.activate(user.id(), PlanCode.PRO, 30, SubscriptionSource.MANUAL);
      assertThat(activation.extended()).isFalse();
    });
  }

  @Test
  void freeUsersSaveUpToTheLimitAndDuplicatesStayIdempotent() {
    TestUser user = user();
    country();
    double rejectedBefore = rejections("SAVE_PRODUCT", "limit_reached");
    List<UUID> products = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID product = product();
      products.add(product);
      assertStatus(save(user, product), 201);
    }
    EntityExchangeResult<String> sixth = save(user, product());
    assertProblem(sixth, 403, "entitlement_exceeded", "EntitlementExceededProblem");
    assertThat(rejections("SAVE_PRODUCT", "limit_reached")).isEqualTo(rejectedBefore + 1);
    JsonNode problem = json(sixth);
    assertThat(problem.get("limit").asInt()).isEqualTo(5);
    assertThat(problem.get("used").asInt()).isEqualTo(5);
    assertThat(problem.get("plan").asString()).isEqualTo("FREE");
    assertThat(problem.get("upgradeTo").asString()).isEqualTo("PRO");
    assertThat(problem.get("upgradeHint").get("messageKey").asString())
        .isEqualTo("limits.saved_products_reached");
    assertThat(problem.get("upgradeHint").get("ctaPath").asString()).isEqualTo("/upgrade");
    assertThat(problem.get("detail").asString())
        .isEqualTo("The FREE plan allows 5 saved products.");

    assertStatus(save(user, products.get(0)), 200);
    assertThat(savedCount(user.id())).isEqualTo(5);
  }

  @Test
  void tenParallelSavesWithFourSavedYieldExactlyOneSuccess() throws Exception {
    TestUser user = user();
    savedProducts(user.id(), 4);
    List<Callable<Integer>> saves = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      UUID product = product();
      saves.add(() -> save(user, product).getStatus().value());
    }
    List<Integer> statuses = parallel(saves);
    assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
    assertThat(statuses).filteredOn(status -> status == 403).hasSize(9);
    assertThat(savedCount(user.id())).isEqualTo(5);
  }

  @Test
  void alertsRequireAVerifiedEmail() {
    TestUser unverified = user(false, Role.USER);
    UUID alert = alert(unverified.id(), false, clock.instant());
    double rejectedBefore = rejections("ALERT_SUBSCRIPTION", "email_not_verified");
    EntityExchangeResult<String> result = activateAlert(unverified, alert);
    assertProblem(result, 403, "email_not_verified");
    assertThat(rejections("ALERT_SUBSCRIPTION", "email_not_verified"))
        .isEqualTo(rejectedBefore + 1);
    assertThat(activeAlerts(unverified.id())).isZero();
  }

  @Test
  void freeUsersActivateUpToThreeAlerts() {
    TestUser user = user();
    List<UUID> alerts = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      alerts.add(alert(user.id(), false, clock.instant().plusSeconds(i)));
    }
    for (int i = 0; i < 3; i++) {
      assertStatus(activateAlert(user, alerts.get(i)), 200);
    }
    double rejectedBefore = rejections("ALERT_SUBSCRIPTION", "limit_reached");
    EntityExchangeResult<String> fourth = activateAlert(user, alerts.get(3));
    assertProblem(fourth, 403, "entitlement_exceeded", "EntitlementExceededProblem");
    assertThat(rejections("ALERT_SUBSCRIPTION", "limit_reached")).isEqualTo(rejectedBefore + 1);
    assertThat(json(fourth).get("limit").asInt()).isEqualTo(3);
    assertThat(json(fourth).get("used").asInt()).isEqualTo(3);
    assertThat(json(fourth).get("upgradeHint").get("messageKey").asString())
        .isEqualTo("limits.alert_subscriptions_reached");
    assertThat(activeAlerts(user.id())).isEqualTo(3);
  }

  @Test
  void parallelActivationsNeverExceedTheAlertLimit() throws Exception {
    TestUser user = user();
    alert(user.id(), true, clock.instant());
    List<Callable<Integer>> activations = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      UUID alert = alert(user.id(), false, clock.instant().plusSeconds(i + 1));
      activations.add(() -> activateAlert(user, alert).getStatus().value());
    }
    List<Integer> statuses = parallel(activations);
    assertThat(statuses).filteredOn(status -> status == 200).hasSize(2);
    assertThat(statuses).filteredOn(status -> status == 403).hasSize(8);
    assertThat(activeAlerts(user.id())).isEqualTo(3);
  }

  @Test
  void proUsersAreUnlimited() {
    TestUser user = user();
    pro(user);
    savedProducts(user.id(), 7);
    assertStatus(save(user, product()), 201);
    for (int i = 0; i < 6; i++) {
      assertStatus(activateAlert(user, alert(user.id(), false, clock.instant())), 200);
    }
    assertThat(savedCount(user.id())).isEqualTo(8);
    assertThat(activeAlerts(user.id())).isEqualTo(6);
    assertThat(entitlements.currentPlan(user.id()).code()).isEqualTo(PlanCode.PRO);
  }

  @Test
  void limitChecksNeedTheWritingTransactionAndAnExistingUser() {
    TestUser user = user();
    assertThatThrownBy(() -> entitlements.requireSavedProductSlot(user.id()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> entitlements.requireAlertSlot(user.id()))
        .isInstanceOf(IllegalTransactionStateException.class);
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() ->
            transactions.executeWithoutResult(
                status -> entitlements.requireSavedProductSlot(missing)))
        .isInstanceOfSatisfying(
            ProblemException.class, e -> assertThat(e.code().code()).isEqualTo("unauthorized"));
    assertThatThrownBy(() -> entitlements.currentPlan(missing))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> usageProvider.summary(missing))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anonymousCallersAreUnauthorized() {
    EntityExchangeResult<String> result =
        post(
            "/api/v1/me/entitlement-probe/saved-products/" + UUID.randomUUID() + "?country=TR",
            "{}",
            null);
    assertProblem(result, 401, "unauthorized");
  }

  @Test
  void meAndSessionQuotaCountRealRows() {
    TestUser user = user();
    assertThat(usageProvider).isInstanceOf(PlanUsageProvider.class);
    savedProducts(user.id(), 3);
    alert(user.id(), true, clock.instant());
    alert(user.id(), true, clock.instant());
    alert(user.id(), false, clock.instant());
    for (int i = 0; i < 4; i++) {
      transactions.executeWithoutResult(status -> entitlements.consumeNotification(user.id()));
    }

    EntityExchangeResult<String> me = get("/api/v1/me", user.token());
    assertStatus(me, 200);
    Contract.assertConforms("Me", me.getResponseBody());
    JsonNode usage = json(me).get("usage");
    assertThat(usage.get("savedProducts").get("used").asInt()).isEqualTo(3);
    assertThat(usage.get("savedProducts").get("limit").asInt()).isEqualTo(5);
    assertThat(usage.get("alertSubscriptions").get("used").asInt()).isEqualTo(2);
    assertThat(usage.get("alertSubscriptions").get("limit").asInt()).isEqualTo(3);
    assertThat(usage.get("notificationsThisMonth").get("used").asInt()).isEqualTo(4);
    assertThat(usage.get("notificationsThisMonth").get("limit").asInt()).isEqualTo(30);
    assertThat(usage.get("notificationsThisMonth").get("periodMonth").asString())
        .isEqualTo(entitlements.currentPeriodMonth().toString());
    assertThat(json(me).get("plan").get("code").asString()).isEqualTo("FREE");
    assertThat(json(me).get("subscription").get("source").asString()).isEqualTo("MANUAL");

    EntityExchangeResult<String> quota = get("/api/v1/session/quota", user.token());
    assertStatus(quota, 200);
    Contract.assertConforms("SessionQuota", quota.getResponseBody());
    assertThat(json(quota).get("plan").asString()).isEqualTo("FREE");
    assertThat(json(quota).get("usage").get("savedProducts").get("used").asInt()).isEqualTo(3);

    pro(user);
    JsonNode proMe = json(get("/api/v1/me", user.token()));
    assertThat(proMe.get("plan").get("code").asString()).isEqualTo("PRO");
    assertThat(proMe.get("usage").get("savedProducts").get("limit").isNull()).isTrue();
    assertThat(Instant.parse(proMe.get("subscription").get("currentPeriodEnd").asString()))
        .isEqualTo(clock.instant().plus(Duration.ofDays(30)));
  }

  @Test
  void downgradedUsersKeepTheirRowsButCannotAddMore() {
    TestUser user = user();
    pro(user);
    savedProducts(user.id(), 7);
    clock.advance(Duration.ofDays(31));
    assertThat(subscriptionService.expireIfDue(user.id())).isPresent();
    TestUser again = new TestUser(user.id(), user.email(), token(user));
    EntityExchangeResult<String> blocked = save(again, product());
    assertProblem(blocked, 403, "entitlement_exceeded", "EntitlementExceededProblem");
    assertThat(json(blocked).get("used").asInt()).isEqualTo(7);
    assertThat(savedCount(user.id())).isEqualTo(7);
    assertThat(json(get("/api/v1/me", again.token())).get("plan").get("code").asString())
        .isEqualTo("FREE");
  }
}
