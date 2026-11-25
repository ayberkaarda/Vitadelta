package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/** {@code GET|PATCH|DELETE /me} with real usage meters and the deletion cascade. */
@ExtendWith(OutputCaptureExtension.class)
class AccountIntegrationTest extends IdentityIntegrationTest {

  private EntityExchangeResult<String> patch(String token, String json) {
    return client
        .patch()
        .uri("/api/v1/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json)
        .exchange()
        .returnResult(String.class);
  }

  private EntityExchangeResult<String> delete(String token, String json) {
    return client
        .method(org.springframework.http.HttpMethod.DELETE)
        .uri("/api/v1/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json)
        .exchange()
        .returnResult(String.class);
  }

  @Test
  void meReportsProfilePlanSubscriptionAndRealUsage() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Login session = login(email, PASSWORD);

    EntityExchangeResult<String> empty = get("/api/v1/me", session.accessToken());
    assertThat(empty.getStatus().value()).isEqualTo(200);
    ContractSchemas.assertConforms("Me", empty.getResponseBody());
    JsonNode me = json(empty);
    assertThat(me.get("id").asString()).isEqualTo(id.toString());
    assertThat(me.get("email").asString()).isEqualTo(email);
    assertThat(me.get("emailVerified").asBoolean()).isTrue();
    assertThat(me.get("plan").get("code").asString()).isEqualTo("FREE");
    assertThat(me.get("plan").get("savedProductsLimit").asInt()).isEqualTo(5);
    assertThat(me.get("subscription").get("status").asString()).isEqualTo("ACTIVE");
    assertThat(me.get("subscription").get("currentPeriodEnd").isNull()).isTrue();
    assertThat(me.get("usage").get("savedProducts").get("used").asInt()).isZero();
    assertThat(me.has("passwordHash")).isFalse();

    String country = testCountry();
    {
      UUID product = testProduct();
      jdbc.sql(
              "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                  + " VALUES (?, ?, ?, now())")
          .params(id, product, country)
          .update();
      jdbc.sql(
              "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
                  + " threshold_percent, created_at) VALUES (?, ?, ?, ?, 'PRICE_DROP', 5, now()),"
                  + " (?, ?, ?, ?, 'BACK_IN_STOCK', NULL, now())")
          .params(UUID.randomUUID(), id, product, country, UUID.randomUUID(), id, product, country)
          .update();
      jdbc.sql("UPDATE alert_subscription SET active = false WHERE user_id = ? AND trigger = ?")
          .params(id, "BACK_IN_STOCK")
          .update();
      LocalDate month = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
      jdbc.sql(
              "INSERT INTO usage_counter (user_id, period_month, notifications_created)"
                  + " VALUES (?, ?, 4), (?, ?, 9)")
          .params(id, month, id, month.minusMonths(1))
          .update();
      JsonNode usage = json(get("/api/v1/me", session.accessToken())).get("usage");
      assertThat(usage.get("savedProducts").get("used").asInt()).isEqualTo(1);
      assertThat(usage.get("alertSubscriptions").get("used").asInt()).isEqualTo(1);
      assertThat(usage.get("alertSubscriptions").get("limit").asInt()).isEqualTo(3);
      assertThat(usage.get("notificationsThisMonth").get("used").asInt()).isEqualTo(4);
      assertThat(usage.get("notificationsThisMonth").get("periodMonth").asString())
          .isEqualTo(month.toString());
    }

    assertProblem(get("/api/v1/me", null), 401, "unauthorized");
  }

  @Test
  void patchUpdatesSettingsAndValidates() {
    String email = uniqueEmail();
    registerVerified(email);
    String token = login(email, PASSWORD).accessToken();
    String country = testCountry();
    String currency = TEST_CURRENCY;

    EntityExchangeResult<String> updated =
        patch(token, "{\"locale\": \"tr\", \"emailAlertsEnabled\": false}");
    assertThat(updated.getStatus().value()).as(updated.getResponseBody()).isEqualTo(200);
    ContractSchemas.assertConforms("Me", updated.getResponseBody());
    assertThat(json(updated).get("locale").asString()).isEqualTo("tr");
    assertThat(json(updated).get("emailAlertsEnabled").asBoolean()).isFalse();

    {
      JsonNode withCountry =
          json(
              patch(
                  token,
                  "{\"defaultCountryCode\": \""
                      + country
                      + "\", \"displayCurrency\": \""
                      + currency
                      + "\"}"));
      assertThat(withCountry.get("defaultCountryCode").asString()).isEqualTo(country);
      assertThat(withCountry.get("displayCurrency").asString()).isEqualTo(currency);
      assertThat(json(patch(token, "{\"displayCurrency\": null}")).get("displayCurrency").isNull())
          .isTrue();
    }

    assertProblem(patch(token, "{}"), 422, "validation_failed");
    assertProblem(patch(token, "{\"unknown\": 1}"), 422, "validation_failed");
    EntityExchangeResult<String> invalid =
        patch(
            token,
            "{\"locale\": \"de\", \"displayCurrency\": \"eur\", \"defaultCountryCode\": 5,"
                + " \"emailAlertsEnabled\": \"yes\"}");
    assertProblem(invalid, 422, "validation_failed");
    assertThat(json(invalid).get("errors").size()).isEqualTo(4);
    assertProblem(
        patch(token, "{\"defaultCountryCode\": \"" + missingCountry() + "\"}"),
        422,
        "country_not_found");
    assertProblem(patch(token, "{\"displayCurrency\": \"XYZ\"}"), 422, "fx_unsupported_currency");
    assertProblem(patch(token, "[1]"), 422, "validation_failed");
  }

  @Test
  void deleteChecksThePasswordAndCascades(CapturedOutput output) {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Login session = login(email, PASSWORD);
    jdbc.sql("INSERT INTO usage_counter (user_id, period_month) VALUES (?, DATE '2026-11-01')")
        .param(id)
        .update();

    EntityExchangeResult<String> wrong =
        delete(session.accessToken(), body(Map.of("password", "not-the-password")));
    assertProblem(wrong, 401, "invalid_credentials");
    assertProblem(delete(session.accessToken(), "{}"), 422, "validation_failed");

    EntityExchangeResult<String> deleted =
        delete(session.accessToken(), body(Map.of("password", PASSWORD)));
    assertThat(deleted.getStatus().value()).isEqualTo(204);
    assertThat(setCookie(deleted, AuthCookies.REFRESH).orElseThrow()).contains("Max-Age=0");
    assertThat(deletionEvents.deleted).contains(id);
    for (String table :
        new String[] {
          "app_user", "refresh_token", "email_token", "subscription", "usage_counter"
        }) {
      String column = table.equals("app_user") ? "id" : "user_id";
      assertThat(
              jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")
                  .param(id)
                  .query(Long.class)
                  .single())
          .as(table)
          .isZero();
    }
    assertThat(output.getAll()).contains("Account deleted (userId=" + id + ")");
    assertThat(output.getAll()).doesNotContain(email);

    assertProblem(get("/api/v1/me", session.accessToken()), 401, "unauthorized");
    assertProblem(refresh(session.refreshToken()), 401, "unauthorized");
    assertProblem(
        delete(session.accessToken(), body(Map.of("password", PASSWORD))), 401, "unauthorized");
  }

  @Test
  void failedDeletionsAreThrottledSoTheEndpointIsNotAPasswordOracle() {
    String email = uniqueEmail();
    UUID id = registerVerified(email);
    Login session = login(email, PASSWORD);
    String wrong = body(Map.of("password", "not-the-password"));
    for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
      assertProblem(delete(session.accessToken(), wrong), 401, "invalid_credentials");
    }
    EntityExchangeResult<String> locked = delete(session.accessToken(), wrong);
    assertProblem(locked, 429, "rate_limited");
    assertThat(locked.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();

    // The correct password is not even checked while the account is locked.
    assertProblem(
        delete(session.accessToken(), body(Map.of("password", PASSWORD))), 429, "rate_limited");
    assertThat(
            jdbc.sql("SELECT count(*) FROM app_user WHERE id = ?")
                .param(id)
                .query(Long.class)
                .single())
        .isEqualTo(1L);

    // The failures count toward the shared account throttle, so login is locked as well.
    assertProblem(
        post("/api/v1/auth/login", body(Map.of("email", email, "password", PASSWORD))),
        429,
        "rate_limited");
  }
}
