package com.vitadelta.api.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.support.TestEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * The anonymous trial quota through {@link EntitlementService} (section 8.4): 3 queries, the 4th is
 * {@code 403 quota_exceeded}, and parallel reservations never exceed the limit.
 */
class AnonymousQuotaEntitlementTest extends EntitlementIntegrationTest {

  @Autowired EntitlementService entitlements;

  private final List<String> identities = new ArrayList<>();

  private String identity() {
    String idHash = TestEnvironment.randomHex(32);
    jdbc.sql(
            "INSERT INTO anonymous_identity (id_hash, queries_used, first_seen_at, last_seen_at)"
                + " VALUES (?, 0, now(), now())")
        .param(idHash)
        .update();
    identities.add(idHash);
    return idHash;
  }

  @AfterEach
  void removeIdentities() {
    for (String idHash : identities) {
      jdbc.sql("DELETE FROM anonymous_identity WHERE id_hash = ?").param(idHash).update();
    }
  }

  private EntityExchangeResult<String> query(String idHash) {
    return post("/api/v1/recommendations/entitlement-probe/" + idHash, "{}", null);
  }

  @Test
  void threeQueriesAreAllowedAndTheFourthIsDenied() {
    String idHash = identity();
    assertThat(entitlements.anonymousQueryLimit()).isEqualTo(3);
    assertThat(entitlements.anonymousVisibleResults()).isEqualTo(3);
    for (int i = 1; i <= 3; i++) {
      EntityExchangeResult<String> result = query(idHash);
      assertStatus(result, 200);
      assertThat(json(result).get("used").asInt()).isEqualTo(i);
    }
    EntityExchangeResult<String> fourth = query(idHash);
    assertProblem(fourth, 403, "quota_exceeded", "QuotaExceededProblem");
    JsonNode problem = json(fourth);
    assertThat(problem.get("limit").asInt()).isEqualTo(3);
    assertThat(problem.get("used").asInt()).isEqualTo(3);
    assertThat(problem.get("action").asString()).isEqualTo("SIGN_UP");
    assertThat(problem.get("upgradeHint").get("messageKey").asString())
        .isEqualTo("quota.exhausted");
    assertThat(problem.get("upgradeHint").get("ctaPath").asString()).isEqualTo("/auth/register");
    assertThat(entitlements.anonymousQueriesUsed(idHash)).isEqualTo(3);
  }

  @Test
  void tenParallelQueriesYieldExactlyThreeSuccesses() throws Exception {
    String idHash = identity();
    List<Callable<Integer>> queries = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      queries.add(() -> query(idHash).getStatus().value());
    }
    List<Integer> statuses = parallel(queries);
    assertThat(statuses).filteredOn(status -> status == 200).hasSize(3);
    assertThat(statuses).filteredOn(status -> status == 403).hasSize(7);
    assertThat(entitlements.anonymousQueriesUsed(idHash)).isEqualTo(3);
  }

  @Test
  void aRefundReturnsOneQuery() {
    String idHash = identity();
    for (int i = 0; i < 3; i++) {
      entitlements.reserveAnonymousQuery(idHash);
    }
    assertThat(entitlements.refundAnonymousQuery(idHash)).isTrue();
    assertThat(entitlements.reserveAnonymousQuery(idHash)).isEqualTo(3);
    String unused = identity();
    assertThat(entitlements.refundAnonymousQuery(unused)).isFalse();
  }
}
