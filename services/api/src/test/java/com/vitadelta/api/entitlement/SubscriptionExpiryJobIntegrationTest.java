package com.vitadelta.api.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.common.jobs.JobLockKeys;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link SubscriptionExpiryJob}: downgrade rules of section 8.3 and the advisory lock. */
class SubscriptionExpiryJobIntegrationTest extends EntitlementIntegrationTest {

  @Autowired SubscriptionExpiryJob job;
  @Autowired SubscriptionRepository repository;
  @Autowired DataSource dataSource;

  private void pro(UUID userId, int days) {
    transactions.executeWithoutResult(status ->
        subscriptionService.activate(userId, PlanCode.PRO, days, SubscriptionSource.PROMO));
  }

  @Test
  void expiredProBecomesFreeAndExcessAlertsAreDeactivatedNewestFirst() {
    TestUser user = user();
    pro(user.id(), 30);
    savedProducts(user.id(), 8);
    Instant base = clock.instant();
    List<UUID> alerts = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      alerts.add(alert(user.id(), true, base.plusSeconds(i)));
    }
    UUID inactive = alert(user.id(), false, base.plusSeconds(10));
    Subscription pro = repository.findActive(user.id()).orElseThrow();

    clock.advance(Duration.ofDays(31));
    Optional<SubscriptionExpiryJob.Result> result = job.run();
    assertThat(result).isPresent();
    assertThat(result.get().downgraded()).isGreaterThanOrEqualTo(1);
    assertThat(result.get().failed()).isZero();

    Subscription free = repository.findActive(user.id()).orElseThrow();
    assertThat(free.planCode()).isEqualTo(PlanCode.FREE);
    assertThat(free.source()).isEqualTo(SubscriptionSource.MANUAL);
    assertThat(free.currentPeriodEnd()).isNull();
    assertThat(free.startedAt()).isEqualTo(clock.instant());
    assertThat(subscriptions(user.id()))
        .extracting(row -> row.get("plan_code") + "/" + row.get("status"))
        .containsExactly("FREE/CANCELED", "PRO/EXPIRED", "FREE/ACTIVE");

    assertThat(savedCount(user.id())).isEqualTo(8);
    Map<UUID, Boolean> active = new HashMap<>();
    jdbc.sql("SELECT id, active FROM alert_subscription WHERE user_id = ?")
        .param(user.id())
        .query((rs, row) -> active.put(rs.getObject("id", UUID.class), rs.getBoolean("active")))
        .list();
    assertThat(active).hasSize(6);
    assertThat(active.get(alerts.get(0))).isTrue();
    assertThat(active.get(alerts.get(1))).isTrue();
    assertThat(active.get(alerts.get(2))).isTrue();
    assertThat(active.get(alerts.get(3))).isFalse();
    assertThat(active.get(alerts.get(4))).isFalse();
    assertThat(active.get(inactive)).isFalse();

    assertThat(events.expired)
        .contains(new SubscriptionExpiredEvent(user.id(), pro.id(), free.id(), 2));
    assertThat(mail().await(user.email(), 2))
        .anySatisfy(m -> assertThat(m.subject()).isEqualTo("Your VitaDelta Pro plan has ended"));
  }

  @Test
  void periodsThatHaveNotEndedAreKept() {
    TestUser running = user();
    pro(running.id(), 30);
    TestUser free = user();
    clock.advance(Duration.ofDays(29));
    job.run();
    assertThat(repository.findActive(running.id()).orElseThrow().planCode())
        .isEqualTo(PlanCode.PRO);
    assertThat(repository.findActive(free.id()).orElseThrow().planCode()).isEqualTo(PlanCode.FREE);
    assertThat(subscriptionService.expireIfDue(free.id())).isEmpty();
    assertThat(subscriptionService.expireIfDue(running.id())).isEmpty();
    assertThat(subscriptionService.expireIfDue(UUID.randomUUID())).isEmpty();
  }

  @Test
  void theCheckIsRepeatedAfterLockingSoAnExtensionWins() {
    TestUser user = user();
    pro(user.id(), 30);
    clock.advance(Duration.ofDays(31));
    pro(user.id(), 30);
    assertThat(repository.expiredCandidates(clock.instant(), null, 10)).doesNotContain(user.id());
    assertThat(subscriptionService.expireIfDue(user.id())).isEmpty();
    assertThat(repository.findActive(user.id()).orElseThrow().currentPeriodEnd())
        .isEqualTo(clock.instant().plus(Duration.ofDays(30)));
  }

  @Test
  void candidatesArePagedByUserId() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      TestUser user = user();
      pro(user.id(), 1);
      ids.add(user.id());
    }
    clock.advance(Duration.ofDays(2));
    List<UUID> first = repository.expiredCandidates(clock.instant(), null, 2);
    assertThat(first).hasSize(2).doesNotHaveDuplicates();
    List<UUID> rest = repository.expiredCandidates(clock.instant(), first.get(1), 100);
    assertThat(rest).doesNotContainAnyElementsOf(first);
    List<UUID> all = new ArrayList<>(first);
    all.addAll(rest);
    assertThat(all).containsAll(ids);
  }

  @Test
  void aRunIsSkippedWhileAnotherSessionHoldsTheLock() throws Exception {
    TestUser user = user();
    pro(user.id(), 1);
    clock.advance(Duration.ofDays(2));
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(true);
      assertThat(advisory(holder, "SELECT pg_try_advisory_lock(?)")).isTrue();
      try {
        assertThat(job.run()).isEmpty();
        assertThat(repository.findActive(user.id()).orElseThrow().planCode())
            .isEqualTo(PlanCode.PRO);
      } finally {
        advisory(holder, "SELECT pg_advisory_unlock(?)");
      }
    }
    assertThat(job.run()).isPresent();
    assertThat(repository.findActive(user.id()).orElseThrow().planCode()).isEqualTo(PlanCode.FREE);
  }

  @Test
  void aFailingUserIsCountedAndTheRunContinues() {
    TestUser broken = user();
    pro(broken.id(), 1);
    TestUser healthy = user();
    pro(healthy.id(), 1);
    clock.advance(Duration.ofDays(2));
    // A trigger makes the downgrade insert of one user fail; its transaction rolls back.
    jdbc.sql(
            "CREATE FUNCTION entitlement_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION 'rejected by test'; END $$")
        .update();
    jdbc.sql(
            "CREATE TRIGGER entitlement_test_reject BEFORE INSERT ON subscription FOR EACH ROW"
                + " WHEN (NEW.user_id = CAST('"
                + broken.id()
                + "' AS uuid)) EXECUTE FUNCTION entitlement_test_reject()")
        .update();
    Optional<SubscriptionExpiryJob.Result> result;
    try {
      result = job.run();
    } finally {
      jdbc.sql("DROP TRIGGER entitlement_test_reject ON subscription").update();
      jdbc.sql("DROP FUNCTION entitlement_test_reject()").update();
    }
    assertThat(result).isPresent();
    assertThat(result.get().failed()).isEqualTo(1);
    assertThat(repository.findActive(healthy.id()).orElseThrow().planCode())
        .isEqualTo(PlanCode.FREE);
    Subscription stillPro = repository.findActive(broken.id()).orElseThrow();
    assertThat(stillPro.planCode()).isEqualTo(PlanCode.PRO);
    assertThat(subscriptionService.expireIfDue(broken.id())).isPresent();
  }

  private static boolean advisory(Connection connection, String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, JobLockKeys.SUBSCRIPTION_EXPIRY);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }
}
