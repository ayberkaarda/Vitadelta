package com.vitadelta.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** {@code V2__identity_and_plans.sql}: clean migration, seeds and constraints (section 5.9). */
class IdentitySchemaIntegrationTest extends IdentityIntegrationTest {

  private UUID user(String email) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO app_user (id, email, password_hash, created_at, updated_at)"
                + " VALUES (?, ?, 'x', now(), now())")
        .params(id, email)
        .update();
    return id;
  }

  private void subscription(UUID user, String status) {
    jdbc.sql(
            "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at)"
                + " VALUES (?, ?, 'FREE', ?, 'MANUAL', now())")
        .params(UUID.randomUUID(), user, status)
        .update();
  }

  private String promo(UUID createdBy) {
    String code =
        "SCHEMA-" + SecureTokens.newToken().substring(0, 16).toUpperCase(java.util.Locale.ROOT);
    jdbc.sql(
            "INSERT INTO promo_code (code, plan_code, duration_days, max_redemptions, created_by,"
                + " created_at) VALUES (?, 'PRO', 30, 10, ?, now())")
        .params(code, createdBy)
        .update();
    return code;
  }

  @Test
  void migrationsApplyToACleanDatabase() throws Exception {
    String database = "identity_clean_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
      try {
        String url = POSTGRES.getJdbcUrl().replace("/vitadelta", "/" + database);
        Flyway flyway =
            Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        List<String> versions =
            java.util.Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .map(Object::toString)
                .toList();
        assertThat(versions).startsWith("1", "2");
        try (Connection clean =
                DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
            Statement query = clean.createStatement();
            var rows = query.executeQuery("SELECT count(*) FROM plan")) {
          rows.next();
          assertThat(rows.getInt(1)).isEqualTo(2);
        }
      } finally {
        statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
      }
    }
  }

  @Test
  void planSeedsMatchTheSpecification() {
    List<Map<String, Object>> plans =
        jdbc.sql(
                "SELECT code, name, saved_products_limit, alert_subscriptions_limit,"
                    + " notifications_per_month_limit, price_amount::text AS price, price_currency,"
                    + " billing_period FROM plan ORDER BY code")
            .query()
            .listOfRows();
    assertThat(plans).hasSize(2);
    assertThat(plans.get(0))
        .containsEntry("code", "FREE")
        .containsEntry("saved_products_limit", 5)
        .containsEntry("alert_subscriptions_limit", 3)
        .containsEntry("notifications_per_month_limit", 30)
        .containsEntry("price", null)
        .containsEntry("price_currency", null);
    assertThat(plans.get(1))
        .containsEntry("code", "PRO")
        .containsEntry("saved_products_limit", null)
        .containsEntry("alert_subscriptions_limit", null)
        .containsEntry("notifications_per_month_limit", null)
        .containsEntry("price", "199.00")
        .containsEntry("price_currency", "TRY")
        .containsEntry("billing_period", "MONTHLY");
  }

  @Test
  void oneActiveSubscriptionPerUser() {
    UUID id = user(uniqueEmail());
    subscription(id, "ACTIVE");
    subscription(id, "CANCELED");
    subscription(id, "EXPIRED");
    assertThatThrownBy(() -> subscription(id, "ACTIVE"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("subscription_one_active_per_user");
    assertThatThrownBy(() -> subscription(id, "PAUSED"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void emailIsCaseInsensitivelyUnique() {
    String email = uniqueEmail();
    user(email);
    assertThatThrownBy(() -> user(email.toUpperCase(java.util.Locale.ROOT)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("app_user_email_unique");
    assertThat(
            jdbc.sql("SELECT count(*) FROM app_user WHERE email = CAST(? AS citext)")
                .param(email.toUpperCase(java.util.Locale.ROOT))
                .query(Long.class)
                .single())
        .isOne();
  }

  @Test
  void promoRedemptionsAreUniquePerCodeAndUserCaseInsensitively() {
    UUID id = user(uniqueEmail());
    String code = promo(null);
    String redeem =
        "INSERT INTO promo_redemption (id, code, user_id, redeemed_at) VALUES (?, ?, ?, now())";
    jdbc.sql(redeem).params(UUID.randomUUID(), code, id).update();
    assertThatThrownBy(() ->
            jdbc.sql(redeem)
                .params(UUID.randomUUID(), code.toLowerCase(java.util.Locale.ROOT), id)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("promo_redemption_code_user_unique");
    assertThatThrownBy(() ->
            jdbc.sql("UPDATE promo_code SET redeemed_count = 11 WHERE code = ?")
                .param(code)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void emailTokenHashesAreUnique() {
    UUID id = user(uniqueEmail());
    String hash = SecureTokens.sha256Hex(UUID.randomUUID().toString());
    String insert =
        "INSERT INTO email_token (id, user_id, kind, token_hash, expires_at, created_at)"
            + " VALUES (?, ?, ?, ?, now(), now())";
    jdbc.sql(insert).params(UUID.randomUUID(), id, "VERIFY_EMAIL", hash).update();
    assertThatThrownBy(
            () -> jdbc.sql(insert).params(UUID.randomUUID(), id, "RESET_PASSWORD", hash).update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("email_token_hash_unique");
    assertThatThrownBy(() ->
            jdbc.sql(insert)
                .params(UUID.randomUUID(), id, "VERIFY_EMAIL", "plaintext-token")
                .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void limitedResourceTablesEnforceTheirRules() {
    UUID id = user(uniqueEmail());
    UUID product = testProduct();
    String country = testCountry();
    String alert =
        "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
            + " threshold_percent, created_at) VALUES (?, ?, ?, ?, ?, ?, now())";
    assertThatThrownBy(() ->
            jdbc.sql(alert)
                .params(UUID.randomUUID(), id, product, country, "PRICE_DROP", null)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            jdbc.sql(alert)
                .params(UUID.randomUUID(), id, product, country, "BACK_IN_STOCK", 5)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() ->
            jdbc.sql(alert)
                .params(UUID.randomUUID(), id, product, country, "PRICE_DROP", 100)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.sql(
            "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
                + " created_at) VALUES (?, ?, ?, ?, 'PRICE_DROP', now())")
        .params(UUID.randomUUID(), id, product, country)
        .update();
    assertThat(
            jdbc.sql("SELECT threshold_percent::text FROM alert_subscription WHERE user_id = ?")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo("5.00");
    assertThatThrownBy(() ->
            jdbc.sql(alert)
                .params(UUID.randomUUID(), id, product, country, "PRICE_DROP", 7)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("alert_subscription_unique");
    assertThatThrownBy(() ->
            jdbc.sql(
                    "INSERT INTO usage_counter (user_id, period_month) VALUES (?, DATE"
                        + " '2026-11-02')")
                .param(id)
                .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    String saved =
        "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
            + " VALUES (?, ?, ?, now())";
    jdbc.sql(saved).params(id, product, country).update();
    assertThatThrownBy(() -> jdbc.sql(saved).params(id, product, country).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletingAUserCascadesAndDetachesReferences() {
    UUID id = user(uniqueEmail());
    UUID admin = user(uniqueEmail());
    UUID product = testProduct();
    String country = testCountry();
    subscription(id, "ACTIVE");
    String code = promo(id);
    String otherCode = promo(admin);
    jdbc.sql(
            "INSERT INTO promo_redemption (id, code, user_id, redeemed_at) VALUES (?, ?, ?, now())")
        .params(UUID.randomUUID(), otherCode, id)
        .update();
    jdbc.sql(
            "INSERT INTO email_token (id, user_id, kind, token_hash, expires_at, created_at)"
                + " VALUES (?, ?, 'VERIFY_EMAIL', ?, now(), now())")
        .params(UUID.randomUUID(), id, SecureTokens.sha256Hex(UUID.randomUUID().toString()))
        .update();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID family = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO refresh_token (id, user_id, token_hash, family_id, expires_at,"
                + " created_at) VALUES (?, ?, ?, ?, now(), now()), (?, ?, ?, ?, now(), now())")
        .params(
            second,
            id,
            SecureTokens.sha256Hex("a" + second),
            family,
            first,
            id,
            SecureTokens.sha256Hex("b" + first),
            family)
        .update();
    jdbc.sql("UPDATE refresh_token SET revoked_at = now(), replaced_by = ? WHERE id = ?")
        .params(second, first)
        .update();
    jdbc.sql(
            "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                + " VALUES (?, ?, ?, now())")
        .params(id, product, country)
        .update();
    jdbc.sql(
            "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
                + " threshold_percent, created_at) VALUES (?, ?, ?, ?, 'BACK_IN_STOCK', NULL,"
                + " now())")
        .params(UUID.randomUUID(), id, product, country)
        .update();
    jdbc.sql("INSERT INTO usage_counter (user_id, period_month) VALUES (?, DATE '2026-11-01')")
        .param(id)
        .update();
    String anon = SecureTokens.sha256Hex(UUID.randomUUID().toString());
    jdbc.sql(
            "INSERT INTO anonymous_identity (id_hash, first_seen_at, last_seen_at,"
                + " converted_user_id) VALUES (?, now(), now(), ?)")
        .params(anon, id)
        .update();

    assertThat(jdbc.sql("DELETE FROM app_user WHERE id = ?").param(id).update()).isOne();

    for (String table :
        List.of(
            "refresh_token",
            "email_token",
            "subscription",
            "promo_redemption",
            "saved_product",
            "alert_subscription",
            "usage_counter")) {
      assertThat(
              jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = ?")
                  .param(id)
                  .query(Long.class)
                  .single())
          .as(table)
          .isZero();
    }
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM anonymous_identity WHERE id_hash = ?"
                        + " AND converted_user_id IS NULL")
                .param(anon)
                .query(Long.class)
                .single())
        .isOne();
    assertThat(
            jdbc.sql("SELECT count(*) FROM promo_code WHERE code = ? AND created_by IS NULL")
                .param(code)
                .query(Long.class)
                .single())
        .isOne();
    jdbc.sql("DELETE FROM promo_code WHERE code IN (?, ?)").params(code, otherCode).update();
    jdbc.sql("DELETE FROM app_user WHERE id = ?").param(admin).update();
  }
}
