package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * The anonymous principal: signed {@code vd_anon} cookie, concurrent creation, the atomic trial
 * quota and conversion on registration (sections 7.6, 8.1 and 8.4).
 */
class AnonymousIdentityIntegrationTest extends IdentityIntegrationTest {

  @Autowired AnonymousIdentityService anonymous;
  @Autowired AnonymousQuotaService quota;

  private EntityExchangeResult<String> quota(String cookie) {
    var spec = client.get().uri("/api/v1/session/quota").header("X-Forwarded-For", ip);
    if (cookie != null) {
      spec = spec.cookie(AuthCookies.ANONYMOUS, cookie);
    }
    return spec.exchange().returnResult(String.class);
  }

  private long rows(String idHash) {
    return jdbc.sql("SELECT count(*) FROM anonymous_identity WHERE id_hash = ?")
        .param(idHash)
        .query(Long.class)
        .single();
  }

  @Test
  void quotaCreatesASignedCookieOnceAndReusesIt() {
    EntityExchangeResult<String> first = quota(null);
    assertThat(first.getStatus().value()).isEqualTo(200);
    ContractSchemas.assertConforms("SessionQuota", first.getResponseBody());
    assertThat(json(first).get("principal").asString()).isEqualTo("ANONYMOUS");
    assertThat(json(first).get("queriesUsed").asInt()).isZero();
    assertThat(json(first).get("queriesLimit").asInt()).isEqualTo(3);
    assertThat(json(first).get("visibleResults").asInt()).isEqualTo(3);
    String setCookie = setCookie(first, AuthCookies.ANONYMOUS).orElseThrow();
    assertThat(setCookie)
        .contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=15552000", "Secure");
    String cookie = cookieValue(setCookie);
    String idHash = anonymous.verify(cookie).orElseThrow();
    assertThat(rows(idHash)).isEqualTo(1);

    EntityExchangeResult<String> second = quota(cookie);
    assertThat(second.getResponseHeaders().containsHeader(HttpHeaders.SET_COOKIE)).isFalse();
    assertThat(quota.reserve(idHash)).hasValue(1);
    assertThat(json(quota(cookie)).get("queriesUsed").asInt()).isEqualTo(1);
  }

  @Test
  void badlySignedCookiesAreReplaced() {
    String cookie = cookieValue(setCookie(quota(null), AuthCookies.ANONYMOUS).orElseThrow());
    String id = cookie.substring(0, cookie.indexOf('.'));
    String signature = cookie.substring(cookie.indexOf('.') + 1);
    String tampered = id + "." + (signature.charAt(0) == 'A' ? "B" : "A") + signature.substring(1);
    for (String bad :
        List.of(
            tampered, id, id + ".", "." + signature, "x.y", cookie + ".z", "AAAA." + signature)) {
      assertThat(anonymous.verify(bad)).as(bad).isEmpty();
      EntityExchangeResult<String> replaced = quota(bad);
      String fresh = cookieValue(setCookie(replaced, AuthCookies.ANONYMOUS).orElseThrow());
      assertThat(fresh).isNotEqualTo(cookie);
      assertThat(anonymous.verify(fresh)).isPresent();
    }
  }

  @Test
  void concurrentFirstRequestsCreateExactlyOneIdentity() throws Exception {
    byte[] id = SecureTokens.randomBytes(16);
    String cookie = anonymous.sign(id);
    String idHash = SecureTokens.sha256Hex(id);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      calls.add(() -> {
        start.await();
        return quota(cookie).getStatus().value();
      });
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> call : calls) {
        futures.add(pool.submit(call));
      }
      start.countDown();
      for (Future<Integer> future : futures) {
        assertThat(future.get()).isEqualTo(200);
      }
    }
    assertThat(rows(idHash)).isEqualTo(1);
  }

  @Test
  void tenParallelReservationsYieldExactlyTheLimit() throws Exception {
    String cookie = cookieValue(setCookie(quota(null), AuthCookies.ANONYMOUS).orElseThrow());
    String idHash = anonymous.verify(cookie).orElseThrow();
    CountDownLatch start = new CountDownLatch(1);
    List<Future<OptionalInt>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
      for (int i = 0; i < 10; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return quota.reserve(idHash);
            }));
      }
      start.countDown();
      List<Integer> granted = new ArrayList<>();
      for (Future<OptionalInt> future : futures) {
        future.get().ifPresent(granted::add);
      }
      assertThat(granted).containsExactlyInAnyOrder(1, 2, 3);
    }
    assertThat(quota.queriesUsed(idHash)).isEqualTo(3);
    assertThat(quota.reserve(idHash)).isEmpty();

    assertThat(quota.refund(idHash)).isTrue();
    assertThat(quota.queriesUsed(idHash)).isEqualTo(2);
    assertThat(quota.reserve(idHash)).hasValue(3);
    assertThat(json(quota(cookie)).get("queriesUsed").asInt()).isEqualTo(3);

    String empty = cookieValue(setCookie(quota(null), AuthCookies.ANONYMOUS).orElseThrow());
    String emptyHash = anonymous.verify(empty).orElseThrow();
    assertThat(quota.refund(emptyHash)).isFalse();
    assertThat(quota.queriesUsed(emptyHash)).isZero();
    assertThat(quota.reserve(SecureTokens.sha256Hex("missing"))).isEmpty();
    assertThat(quota.queriesUsed(SecureTokens.sha256Hex("missing"))).isZero();
  }

  @Test
  void registrationConvertsTheIdentityAndClearsTheCookie() {
    String cookie = cookieValue(setCookie(quota(null), AuthCookies.ANONYMOUS).orElseThrow());
    String idHash = anonymous.verify(cookie).orElseThrow();
    String email = uniqueEmail();
    EntityExchangeResult<String> registered =
        post(
            "/api/v1/auth/register",
            body(Map.of("email", email, "password", PASSWORD)),
            HttpHeaders.COOKIE,
            AuthCookies.ANONYMOUS + "=" + cookie);
    assertThat(registered.getStatus().value()).isEqualTo(202);
    assertThat(setCookie(registered, AuthCookies.ANONYMOUS).orElseThrow())
        .startsWith(AuthCookies.ANONYMOUS + "=;")
        .contains("Max-Age=0", "Path=/");
    assertThat(
            jdbc.sql("SELECT converted_user_id FROM anonymous_identity WHERE id_hash = ?")
                .param(idHash)
                .query(java.util.UUID.class)
                .single())
        .isEqualTo(userId(email));
  }

  @Test
  void signedInUsersGetTheirUsageSummary() {
    String email = uniqueEmail();
    registerVerified(email);
    Login session = login(email, PASSWORD);
    EntityExchangeResult<String> result = get("/api/v1/session/quota", session.accessToken());
    assertThat(result.getStatus().value()).isEqualTo(200);
    ContractSchemas.assertConforms("SessionQuota", result.getResponseBody());
    assertThat(json(result).get("principal").asString()).isEqualTo("USER");
    assertThat(json(result).get("plan").asString()).isEqualTo("FREE");
    assertThat(json(result).get("usage").get("savedProducts").get("limit").asInt()).isEqualTo(5);
    assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.SET_COOKIE)).isFalse();

    String admin = adminToken();
    assertThat(json(get("/api/v1/session/quota", admin)).get("principal").asString())
        .isEqualTo("ADMIN");
    assertProblem(get("/api/v1/session/quota", "invalid.token.value"), 401, "unauthorized");
  }
}
