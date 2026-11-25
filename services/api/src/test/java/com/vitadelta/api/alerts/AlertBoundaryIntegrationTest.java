package com.vitadelta.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The catalog version boundary of a subscription (section 8.6): an {@code OFFERS_CHANGED} event
 * applies only when its {@code catalogVersion} is greater than the version the subscription's state
 * was taken at (creation, re-activation, threshold change). Events of the test carry the version
 * their transaction commits with, as an import's do.
 */
class AlertBoundaryIntegrationTest extends AlertsIntegrationTest {

  @Autowired private WatchlistService watchlist;

  private UUID gold() {
    return productId("GOLD-A");
  }

  private BigDecimal currentPrice() {
    return watchlist.currentPrice(gold(), "TR");
  }

  private static String price(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
  }

  private UUID create(TestUser user, AlertTrigger trigger) {
    return watchlist.create(user.id(), gold(), "TR", trigger, null).alert().id();
  }

  private long boundary(UUID subscription) {
    return jdbc.sql("SELECT catalog_version_boundary FROM alert_subscription WHERE id = ?")
        .param(subscription)
        .query(Long.class)
        .single();
  }

  @Test
  void aSubscriptionCreatedAfterAnImportButBeforeTheOutboxRunsIgnoresThatChange() {
    BigDecimal now = currentPrice();
    offersChanged(
        gold(),
        "TR",
        state(price(now), false),
        state(price(now.multiply(new BigDecimal("0.5"))), true));
    TestUser user = user();
    UUID change = create(user, AlertTrigger.PRICE_CHANGE);
    UUID restock = create(user, AlertTrigger.BACK_IN_STOCK);
    UUID drop = create(user, AlertTrigger.PRICE_DROP);
    assertThat(boundary(change)).isEqualTo(committedCatalogVersion());

    drainOutbox();

    assertThat(notifications(user)).as("changes older than the subscriptions").isEmpty();
    assertThat(subscriptionRow(drop).get("baseline_price")).isEqualTo(new BigDecimal(price(now)));
    assertThat(subscriptionRow(restock).get("last_triggered_at")).isNull();

    offersChanged(
        gold(), "TR", state(price(now), true), state(price(now.add(BigDecimal.ONE)), true));
    drainOutbox();
    assertThat(notifications(user)).as("a later change fires").hasSize(1);
    assertThat(notifications(user).getFirst().get("type")).isEqualTo("ALERT_PRICE_CHANGE");
  }

  @Test
  void aQueuedDropAndRecoveryBeforeTheSubscriptionNeverFire() {
    BigDecimal now = currentPrice();
    String low = price(now.multiply(new BigDecimal("0.8")));
    offersChanged(gold(), "TR", state(price(now), true), state(low, true));
    offersChanged(gold(), "TR", state(low, true), state(price(now), true));
    TestUser user = user();
    UUID drop = create(user, AlertTrigger.PRICE_DROP);
    create(user, AlertTrigger.PRICE_CHANGE);
    assertThat(subscriptionRow(drop).get("baseline_price")).isEqualTo(new BigDecimal(price(now)));

    drainOutbox();

    assertThat(notifications(user)).isEmpty();
    assertThat(subscriptionRow(drop).get("baseline_price"))
        .as("the historical 80 % event does not reset the baseline")
        .isEqualTo(new BigDecimal(price(now)));
    assertThat(events().stream().filter(e -> e.get("event_type").equals("OFFERS_CHANGED")))
        .allSatisfy(e -> assertThat(e.get("outcome")).isEqualTo("DELIVERED"));
  }

  @Test
  void reActivationAndAThresholdChangeMoveTheBoundaryButDeactivationKeepsIt() {
    BigDecimal now = currentPrice();
    TestUser user = user();
    UUID change = create(user, AlertTrigger.PRICE_CHANGE);
    UUID drop = create(user, AlertTrigger.PRICE_DROP);
    long created = boundary(change);
    watchlist.update(user.id(), change, false, null);
    assertThat(boundary(change)).as("deactivation keeps the boundary").isEqualTo(created);

    offersChanged(
        gold(),
        "TR",
        state(price(now), true),
        state(price(now.multiply(new BigDecimal("0.5"))), true));
    watchlist.update(user.id(), change, true, null);
    watchlist.update(user.id(), drop, null, new BigDecimal("10.00"));
    assertThat(boundary(change)).isEqualTo(committedCatalogVersion()).isGreaterThan(created);
    assertThat(boundary(drop)).isEqualTo(committedCatalogVersion());

    drainOutbox();

    assertThat(notifications(user))
        .as("the pending change predates the re-activation and the new threshold")
        .isEmpty();
  }

  @Test
  void anEventOfTheBoundaryVersionIsIgnoredAndTheNextVersionApplies() {
    WatchlistRepository.AlertRow row =
        new WatchlistRepository.AlertRow(
            UUID.randomUUID(),
            UUID.randomUUID(),
            gold(),
            "TR",
            AlertTrigger.PRICE_CHANGE,
            null,
            null,
            true,
            Instant.EPOCH,
            null,
            41,
            "TRY");
    assertThat(AlertEvaluator.appliesTo(40, row)).isFalse();
    assertThat(AlertEvaluator.appliesTo(41, row)).isFalse();
    assertThat(AlertEvaluator.appliesTo(42, row)).isTrue();
  }

  @Test
  void aFiringMovesTheBoundaryToTheEventsVersion() {
    BigDecimal now = currentPrice();
    TestUser user = user();
    UUID change = create(user, AlertTrigger.PRICE_CHANGE);
    offersChanged(
        gold(), "TR", state(price(now), true), state(price(now.add(BigDecimal.ONE)), true));
    long eventVersion = committedCatalogVersion();
    drainOutbox();
    assertThat(notifications(user)).hasSize(1);
    assertThat(boundary(change)).isEqualTo(eventVersion);
  }

  /**
   * A catalog write that has bumped the version but not committed yet: the creation waits for it
   * and then takes both the new version and the new price, so the write's own event (same version)
   * does not fire, and the baseline already is the new price.
   */
  @Test
  void aCreationDuringACatalogWriteTakesTheWritesVersionAndPriceTogether() throws Exception {
    BigDecimal before = currentPrice();
    TestUser user = user();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Connection writer = dataSource.getConnection();
    boolean committed = false;
    try {
      writer.setAutoCommit(false);
      long version;
      try (PreparedStatement offers =
              writer.prepareStatement(
                  "UPDATE offer SET price = price - 10 WHERE product_id = ? AND country_code = 'TR'");
          PreparedStatement bump =
              writer.prepareStatement(
                  "UPDATE catalog_version SET version = version + 1 WHERE id = 1"
                      + " RETURNING version")) {
        offers.setObject(1, gold());
        offers.executeUpdate();
        try (ResultSet bumped = bump.executeQuery()) {
          bumped.next();
          version = bumped.getLong(1);
        }
      }
      try (PreparedStatement event =
          writer.prepareStatement(
              "INSERT INTO outbox_event (id, event_type, aggregate_type, aggregate_id, payload,"
                  + " created_at, next_attempt_at)"
                  + " VALUES (?, 'OFFERS_CHANGED', 'PRODUCT_COUNTRY', ?, CAST(? AS jsonb), ?, ?)")) {
        event.setObject(1, UUID.randomUUID());
        event.setString(2, gold() + ":TR");
        event.setString(
            3,
            OffersChangedEmitter.JSON.writeValueAsString(
                new NotificationPayloads.OffersChanged(
                    gold(),
                    "TR",
                    OfferState.of(state(price(before), true)),
                    OfferState.of(state(price(before.subtract(BigDecimal.TEN)), true)),
                    version)));
        event.setObject(4, OutboxRepository.ts(clock.instant()));
        event.setObject(5, OutboxRepository.ts(clock.instant()));
        event.executeUpdate();
      }

      Future<UUID> creation = executor.submit(() -> create(user, AlertTrigger.PRICE_DROP));
      Awaitility.await()
          .atMost(Duration.ofSeconds(20))
          .pollInterval(Duration.ofMillis(50))
          .untilAsserted(() ->
              assertThat(
                      jdbc.sql(
                              "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                  + " AND query LIKE '%catalog_version%FOR SHARE%'")
                          .query(Long.class)
                          .single())
                  .as("the creation waits for the uncommitted write")
                  .isEqualTo(1));
      writer.commit();
      committed = true;
      UUID drop = creation.get(30, TimeUnit.SECONDS);

      BigDecimal after = currentPrice();
      assertThat(after).isEqualByComparingTo(before.subtract(BigDecimal.TEN));
      assertThat(subscriptionRow(drop).get("baseline_price")).isEqualTo(after);
      assertThat(boundary(drop)).isEqualTo(version);
      drainOutbox();
      assertThat(notifications(user)).isEmpty();
    } finally {
      if (!committed) {
        writer.rollback();
      }
      writer.close();
      executor.shutdownNow();
      executor.awaitTermination(30, TimeUnit.SECONDS);
      if (committed) {
        jdbc.sql("UPDATE offer SET price = price + 10 WHERE product_id = ? AND country_code = 'TR'")
            .param(gold())
            .update();
        jdbc.sql("UPDATE catalog_version SET version = version + 1 WHERE id = 1").update();
      }
    }
    assertThat(currentPrice()).isEqualByComparingTo(before);
  }
}
