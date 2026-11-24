package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.entitlement.EntitlementService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Section 15.7 case 16 through {@link EntitlementService}, the single decision point the Phase 5
 * endpoints will call: parallel writers that each check the limit and then insert never exceed the
 * plan limit. The saved-product and alert endpoints do not exist in Phase 3; the transactions below
 * do exactly what section 8.4 prescribes for them (check, then insert or activate, in one
 * transaction).
 */
class LimitRaceAdversarialTest extends AdversarialIntegrationTest {

  @Autowired EntitlementService entitlements;
  @Autowired PlatformTransactionManager transactionManager;

  private record Outcome(boolean success, String code) {}

  private List<Outcome> race(List<Callable<Outcome>> calls) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Outcome> outcomes = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
      List<Future<Outcome>> futures = new ArrayList<>();
      for (Callable<Outcome> call : calls) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return call.call();
            }));
      }
      start.countDown();
      for (Future<Outcome> future : futures) {
        outcomes.add(future.get());
      }
    }
    return outcomes;
  }

  private Outcome guarded(Runnable write) {
    try {
      new TransactionTemplate(transactionManager).executeWithoutResult(status -> write.run());
      return new Outcome(true, null);
    } catch (ProblemException e) {
      return new Outcome(false, e.code().code());
    }
  }

  @Test
  void tenParallelSavesForAFreeUserWithFourSavedProductsYieldExactlyOne() throws Exception {
    UUID user = insertUser(true);
    for (int i = 0; i < 4; i++) {
      saveProduct(user, product());
    }
    List<UUID> candidates = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      candidates.add(product());
    }
    List<Callable<Outcome>> calls = new ArrayList<>();
    for (UUID product : candidates) {
      calls.add(() ->
          guarded(() -> {
            entitlements.requireSavedProductSlot(user);
            saveProduct(user, product);
          }));
    }
    List<Outcome> outcomes = race(calls);
    assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
    assertThat(outcomes.stream().filter(o -> !o.success()).map(Outcome::code))
        .containsOnly("entitlement_exceeded");
    assertThat(count("SELECT count(*) FROM saved_product WHERE user_id = ?", user)).isEqualTo(5);
  }

  @Test
  void parallelAlertActivationsNeverExceedTheAlertLimit() throws Exception {
    UUID user = insertUser(true);
    UUID product = product();
    alert(user, product, "PRICE_DROP", true);
    alert(user, product, "BACK_IN_STOCK", true);
    List<UUID> inactive = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      inactive.add(alert(user, product(), "PRICE_CHANGE", false));
    }
    List<Callable<Outcome>> calls = new ArrayList<>();
    for (UUID alert : inactive) {
      calls.add(() ->
          guarded(() -> {
            entitlements.requireAlertSlot(user);
            jdbc.sql("UPDATE alert_subscription SET active = true WHERE id = ?")
                .param(alert)
                .update();
          }));
    }
    List<Outcome> outcomes = race(calls);
    assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM alert_subscription WHERE user_id = ? AND active", user))
        .isEqualTo(3);
  }

  @Test
  void unverifiedUsersGetNoAlertSlotEvenInParallel() throws Exception {
    UUID user = insertUser(false);
    List<Callable<Outcome>> calls = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      calls.add(() -> guarded(() -> entitlements.requireAlertSlot(user)));
    }
    assertThat(race(calls)).allSatisfy(o -> assertThat(o.code()).isEqualTo("email_not_verified"));
  }
}
