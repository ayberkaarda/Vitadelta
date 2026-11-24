package com.vitadelta.api.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.entitlement.NotificationAllowance.Outcome;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Monthly notification cap through {@code usage_counter} (sections 8.4 and 8.6). */
class NotificationCapIntegrationTest extends EntitlementIntegrationTest {

  @Autowired EntitlementService entitlements;
  @Autowired UsageRepository usage;

  private NotificationAllowance consume(UUID userId) {
    return entitlements.consumeNotification(userId);
  }

  @Test
  void freeUsersGetThirtyNotificationsAMonthThenTheCapSignal() {
    TestUser user = user();
    for (int i = 1; i <= 30; i++) {
      NotificationAllowance allowance = consume(user.id());
      assertThat(allowance.allowed()).isTrue();
      assertThat(allowance.used()).isEqualTo(i);
      assertThat(allowance.limit()).isEqualTo(30);
    }
    NotificationAllowance capped = consume(user.id());
    assertThat(capped.outcome()).isEqualTo(Outcome.CAP_REACHED);
    assertThat(capped.allowed()).isFalse();
    assertThat(capped.used()).isEqualTo(30);
    assertThat(consume(user.id()).outcome()).isEqualTo(Outcome.CAP_REACHED);
    assertThat(usage.notifications(user.id(), capped.periodMonth())).isEqualTo(30);

    LocalDate nextMonth = capped.periodMonth().plusMonths(1);
    clock.set(nextMonth.atStartOfDay().toInstant(ZoneOffset.UTC));
    NotificationAllowance fresh = consume(user.id());
    assertThat(fresh.outcome()).isEqualTo(Outcome.COUNTED);
    assertThat(fresh.used()).isEqualTo(1);
    assertThat(fresh.periodMonth()).isEqualTo(nextMonth);
  }

  @Test
  void parallelNotificationsNeverExceedTheCap() throws Exception {
    TestUser user = user();
    List<Callable<NotificationAllowance>> tasks = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      tasks.add(() -> consume(user.id()));
    }
    List<NotificationAllowance> results = parallel(tasks);
    assertThat(results).filteredOn(NotificationAllowance::allowed).hasSize(30);
    assertThat(results).filteredOn(r -> r.outcome() == Outcome.CAP_REACHED).hasSize(10);
    assertThat(usage.notifications(user.id(), entitlements.currentPeriodMonth())).isEqualTo(30);
  }

  @Test
  void proUsersAreCountedWithoutACap() {
    TestUser user = user();
    transactions.executeWithoutResult(status ->
        subscriptionService.activate(user.id(), PlanCode.PRO, 30, SubscriptionSource.MANUAL));
    for (int i = 0; i < 35; i++) {
      assertThat(consume(user.id()).allowed()).isTrue();
    }
    NotificationAllowance last = consume(user.id());
    assertThat(last.used()).isEqualTo(36);
    assertThat(last.limit()).isNull();
  }

  /**
   * A PRO user whose period ended sits at 30 notifications; the expiry downgrade holds the user's
   * row lock with its change uncommitted while a notification is consumed. The consumption must see
   * the plan the downgrade leaves behind (FREE, cap 30), never the stale PRO.
   */
  @Test
  void aDowngradeRacingAConsumptionAtTheCapNeverEndsAboveTheCap() throws Exception {
    TestUser user = user();
    transactions.executeWithoutResult(status ->
        subscriptionService.activate(user.id(), PlanCode.PRO, 30, SubscriptionSource.MANUAL));
    clock.advance(Duration.ofDays(31));
    for (int i = 0; i < 30; i++) {
      assertThat(consume(user.id()).allowed()).isTrue();
    }
    LocalDate month = entitlements.currentPeriodMonth();
    assertThat(usage.notifications(user.id(), month)).isEqualTo(30);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch downgradedUncommitted = new CountDownLatch(1);
      CountDownLatch commit = new CountDownLatch(1);
      Future<Boolean> downgrade =
          executor.submit(() ->
              transactions.execute(status -> {
                boolean due = subscriptionService.expireIfDue(user.id()).isPresent();
                downgradedUncommitted.countDown();
                try {
                  commit.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
                return due;
              }));
      assertThat(downgradedUncommitted.await(30, TimeUnit.SECONDS)).isTrue();
      Future<NotificationAllowance> consumption = executor.submit(() -> consume(user.id()));
      // The consumption either finishes on its own (it must not have seen PRO) or waits for the
      // row lock; give it time to do either before the downgrade commits.
      try {
        consumption.get(2, TimeUnit.SECONDS);
      } catch (TimeoutException waitingForTheLock) {
        assertThat(consumption.isDone()).isFalse();
      }
      commit.countDown();
      assertThat(downgrade.get(30, TimeUnit.SECONDS)).isTrue();
      NotificationAllowance allowance = consumption.get(30, TimeUnit.SECONDS);
      assertThat(allowance.outcome()).isEqualTo(Outcome.CAP_REACHED);
      assertThat(allowance.limit()).isEqualTo(30);
    } finally {
      executor.shutdownNow();
    }
    assertThat(usage.notifications(user.id(), month)).isEqualTo(30);
    assertThat(entitlements.currentPlan(user.id()).code()).isEqualTo(PlanCode.FREE);
  }

  @Test
  void anUnknownUserHasNoAllowance() {
    UUID unknown = UUID.randomUUID();
    assertThatThrownBy(() -> consume(unknown)).isInstanceOf(IllegalStateException.class);
    assertThat(usage.notifications(unknown, entitlements.currentPeriodMonth())).isZero();
  }

  @Test
  void aZeroLimitAllowsNothing() {
    TestUser user = user();
    assertThat(usage.incrementNotifications(user.id(), entitlements.currentPeriodMonth(), 0))
        .isEmpty();
    assertThat(usage.notifications(user.id(), entitlements.currentPeriodMonth())).isZero();
  }
}
