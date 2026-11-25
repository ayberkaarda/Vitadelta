package com.vitadelta.api.alerts;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.common.watch.WatchedAlert;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Statements on {@code saved_product} and {@code alert_subscription} (sections 8.5 and 8.6) and the
 * few catalog facts they need (availability, native currency, offers). Writes join the caller's
 * transaction.
 */
@Repository
public class WatchlistRepository {

  /** Columns of an {@link AlertRow}, with the country's native currency. */
  private static final String ALERT_COLUMNS =
      "s.id, s.user_id, s.product_id, s.country_code, s.trigger, s.threshold_percent,"
          + " s.baseline_price, s.active, s.created_at, s.last_triggered_at,"
          + " c.currency AS baseline_currency";

  /** Ordering of a product's subscriptions: PRICE_DROP, BACK_IN_STOCK, PRICE_CHANGE. */
  static final String TRIGGER_ORDER =
      "CASE s.trigger WHEN 'PRICE_DROP' THEN 1 WHEN 'BACK_IN_STOCK' THEN 2 ELSE 3 END";

  private final JdbcClient jdbc;

  public WatchlistRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** One {@code alert_subscription} row with the native currency of its country. */
  public record AlertRow(
      UUID id,
      UUID userId,
      UUID productId,
      String countryCode,
      AlertTrigger trigger,
      BigDecimal thresholdPercent,
      BigDecimal baselinePrice,
      boolean active,
      Instant createdAt,
      Instant lastTriggeredAt,
      String baselineCurrency) {

    /** Contract schema {@code AlertSubscription}. */
    public WatchedAlert view() {
      return new WatchedAlert(
          id,
          productId,
          countryCode,
          trigger.name(),
          thresholdPercent,
          baselinePrice,
          baselineCurrency,
          active,
          createdAt,
          lastTriggeredAt);
    }
  }

  /** One {@code saved_product} row. */
  public record SavedRow(UUID productId, String countryCode, Instant savedAt) {}

  // ---------- Catalog facts ----------

  public boolean productExists(UUID productId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM product WHERE id = ?)")
        .param(productId)
        .query(Boolean.class)
        .single();
  }

  /** Availability of the product in the country; {@code NOT_SOLD} when no row exists. */
  public AvailabilityStatus availability(UUID productId, String countryCode) {
    return jdbc.sql(
            "SELECT status FROM product_availability WHERE product_id = ? AND country_code = ?")
        .params(productId, countryCode)
        .query(String.class)
        .optional()
        .map(AvailabilityStatus::valueOf)
        .orElse(AvailabilityStatus.NOT_SOLD);
  }

  /** Native currency of the country, empty for an unknown country. */
  public Optional<String> countryCurrency(String countryCode) {
    return jdbc.sql("SELECT currency FROM country WHERE code = ?")
        .param(countryCode)
        .query(String.class)
        .optional();
  }

  /** The offers of a product in a country. */
  public List<OfferSnapshot> offers(UUID productId, String countryCode) {
    return jdbc.sql(
            "SELECT retailer, price, currency, in_stock, url, checked_at FROM offer"
                + " WHERE product_id = ? AND country_code = ?")
        .params(productId, countryCode)
        .query((rs, i) ->
            new OfferSnapshot(
                rs.getString("retailer"),
                rs.getBigDecimal("price"),
                rs.getString("currency"),
                rs.getBoolean("in_stock"),
                rs.getString("url"),
                OutboxRepository.instant(rs, "checked_at")))
        .list();
  }

  /** Product code, brand and name (the snapshot of an alert notification). */
  public record ProductLabel(UUID id, String code, String brand, String name) {}

  public Optional<ProductLabel> productLabel(UUID productId) {
    return jdbc.sql("SELECT id, code, brand, name FROM product WHERE id = ?")
        .param(productId)
        .query((rs, i) ->
            new ProductLabel(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("brand"),
                rs.getString("name")))
        .optional();
  }

  // ---------- Saved products ----------

  public Optional<SavedRow> savedProduct(UUID userId, UUID productId, String countryCode) {
    return jdbc.sql(
            "SELECT product_id, country_code, created_at FROM saved_product"
                + " WHERE user_id = ? AND product_id = ? AND country_code = ?")
        .params(userId, productId, countryCode)
        .query(WatchlistRepository::savedRow)
        .optional();
  }

  /** The user's saved rows of a country: {@code created_at} desc, then product id asc. */
  public List<SavedRow> savedProducts(UUID userId, String countryCode) {
    return jdbc.sql(
            "SELECT product_id, country_code, created_at FROM saved_product"
                + " WHERE user_id = ? AND country_code = ?"
                + " ORDER BY created_at DESC, CAST(product_id AS text) ASC")
        .params(userId, countryCode)
        .query(WatchlistRepository::savedRow)
        .list();
  }

  public void insertSavedProduct(UUID userId, UUID productId, String countryCode, Instant now) {
    jdbc.sql(
            "INSERT INTO saved_product (user_id, product_id, country_code, created_at)"
                + " VALUES (?, ?, ?, ?)")
        .params(userId, productId, countryCode, OutboxRepository.ts(now))
        .update();
  }

  public int deleteSavedProduct(UUID userId, UUID productId, String countryCode) {
    return jdbc.sql(
            "DELETE FROM saved_product WHERE user_id = ? AND product_id = ? AND country_code = ?")
        .params(userId, productId, countryCode)
        .update();
  }

  /** Saved state of several products of one country (one statement). */
  public List<UUID> savedAmong(UUID userId, String countryCode, Collection<UUID> productIds) {
    return jdbc.sql(
            "SELECT product_id FROM saved_product WHERE user_id = :user"
                + " AND country_code = :country AND product_id IN (:ids)")
        .param("user", userId)
        .param("country", countryCode)
        .param("ids", productIds)
        .query(UUID.class)
        .list();
  }

  // ---------- Alert subscriptions ----------

  /** The caller's subscription {@code id}; empty when unknown or foreign. */
  public Optional<AlertRow> alert(UUID userId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + ALERT_COLUMNS
                + " FROM alert_subscription s JOIN country c ON c.code = s.country_code"
                + " WHERE s.id = ? AND s.user_id = ?")
        .params(id, userId)
        .query(WatchlistRepository::alertRow)
        .optional();
  }

  /** The active subscription {@code id} locked for update; empty when gone or inactive. */
  public Optional<AlertRow> lockActiveAlert(UUID id) {
    return jdbc.sql(
            "SELECT "
                + ALERT_COLUMNS
                + " FROM alert_subscription s JOIN country c ON c.code = s.country_code"
                + " WHERE s.id = ? AND s.active FOR UPDATE OF s")
        .param(id)
        .query(WatchlistRepository::alertRow)
        .optional();
  }

  public Optional<AlertRow> alert(
      UUID userId, UUID productId, String countryCode, AlertTrigger trigger) {
    return jdbc.sql(
            "SELECT "
                + ALERT_COLUMNS
                + " FROM alert_subscription s JOIN country c ON c.code = s.country_code"
                + " WHERE s.user_id = ? AND s.product_id = ? AND s.country_code = ?"
                + " AND s.trigger = ?")
        .params(userId, productId, countryCode, trigger.name())
        .query(WatchlistRepository::alertRow)
        .optional();
  }

  /** Every subscription of the user: {@code created_at} desc, then id asc. */
  public List<AlertRow> alerts(UUID userId) {
    return jdbc.sql(
            "SELECT "
                + ALERT_COLUMNS
                + " FROM alert_subscription s JOIN country c ON c.code = s.country_code"
                + " WHERE s.user_id = ? ORDER BY s.created_at DESC, CAST(s.id AS text) ASC")
        .param(userId)
        .query(WatchlistRepository::alertRow)
        .list();
  }

  /** The user's subscriptions on products of one country, by product then trigger order. */
  public List<AlertRow> alertsAmong(
      UUID userId, String countryCode, Collection<UUID> productIds, boolean activeOnly) {
    return jdbc.sql(
            "SELECT "
                + ALERT_COLUMNS
                + " FROM alert_subscription s JOIN country c ON c.code = s.country_code"
                + " WHERE s.user_id = :user AND s.country_code = :country"
                + " AND s.product_id IN (:ids) AND (:activeOnly = false OR s.active)"
                + " ORDER BY s.product_id, "
                + TRIGGER_ORDER)
        .param("user", userId)
        .param("country", countryCode)
        .param("ids", productIds)
        .param("activeOnly", activeOnly)
        .query(WatchlistRepository::alertRow)
        .list();
  }

  /** Active subscriptions of a product and country, ordered by user then id (lock order). */
  public List<AlertRow> activeAlertsFor(UUID productId, String countryCode) {
    return jdbc.sql(
            "SELECT "
                + ALERT_COLUMNS
                + " FROM alert_subscription s JOIN country c ON c.code = s.country_code"
                + " WHERE s.product_id = ? AND s.country_code = ? AND s.active"
                + " ORDER BY CAST(s.user_id AS text), CAST(s.id AS text)")
        .params(productId, countryCode)
        .query(WatchlistRepository::alertRow)
        .list();
  }

  public void insertAlert(
      UUID id,
      UUID userId,
      UUID productId,
      String countryCode,
      AlertTrigger trigger,
      BigDecimal thresholdPercent,
      BigDecimal baselinePrice,
      Instant now) {
    jdbc.sql(
            "INSERT INTO alert_subscription (id, user_id, product_id, country_code, trigger,"
                + " threshold_percent, baseline_price, active, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, true, ?)")
        .params(
            id,
            userId,
            productId,
            countryCode,
            trigger.name(),
            thresholdPercent,
            baselinePrice,
            OutboxRepository.ts(now))
        .update();
  }

  public void updateAlert(
      UUID id, boolean active, BigDecimal thresholdPercent, BigDecimal baselinePrice) {
    jdbc.sql(
            "UPDATE alert_subscription SET active = ?, threshold_percent = ?, baseline_price = ?"
                + " WHERE id = ?")
        .params(active, thresholdPercent, baselinePrice, id)
        .update();
  }

  /** Sets the baseline of a {@code PRICE_DROP} subscription (first price seen). */
  public void updateBaseline(UUID id, BigDecimal baselinePrice) {
    jdbc.sql("UPDATE alert_subscription SET baseline_price = ? WHERE id = ?")
        .params(baselinePrice, id)
        .update();
  }

  /** Records a firing: {@code last_triggered_at} and, for {@code PRICE_DROP}, the new baseline. */
  public void markTriggered(UUID id, Instant now, BigDecimal newBaseline) {
    jdbc.sql(
            "UPDATE alert_subscription SET last_triggered_at = ?,"
                + " baseline_price = coalesce(?, baseline_price) WHERE id = ?")
        .params(OutboxRepository.ts(now), newBaseline, id)
        .update();
  }

  public int deleteAlert(UUID userId, UUID id) {
    return jdbc.sql("DELETE FROM alert_subscription WHERE id = ? AND user_id = ?")
        .params(id, userId)
        .update();
  }

  /** Deactivates the user's active subscriptions of a product and country (baseline kept). */
  public int deactivateAlerts(UUID userId, UUID productId, String countryCode) {
    return jdbc.sql(
            "UPDATE alert_subscription SET active = false WHERE user_id = ? AND product_id = ?"
                + " AND country_code = ? AND active")
        .params(userId, productId, countryCode)
        .update();
  }

  /**
   * Inserts the dedupe row of a firing.
   *
   * @return false when the subscription already has a delivery for the event
   */
  public boolean insertDelivery(UUID subscriptionId, UUID eventId, Instant now) {
    return jdbc.sql(
                "INSERT INTO alert_delivery (subscription_id, outbox_event_id, created_at)"
                    + " VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
            .params(subscriptionId, eventId, OutboxRepository.ts(now))
            .update()
        == 1;
  }

  public void linkDelivery(UUID subscriptionId, UUID eventId, UUID notificationId) {
    jdbc.sql(
            "UPDATE alert_delivery SET notification_id = ? WHERE subscription_id = ?"
                + " AND outbox_event_id = ?")
        .params(notificationId, subscriptionId, eventId)
        .update();
  }

  private static SavedRow savedRow(ResultSet rs, int row) throws SQLException {
    return new SavedRow(
        rs.getObject("product_id", UUID.class),
        rs.getString("country_code"),
        OutboxRepository.instant(rs, "created_at"));
  }

  private static AlertRow alertRow(ResultSet rs, int row) throws SQLException {
    return new AlertRow(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("country_code"),
        AlertTrigger.valueOf(rs.getString("trigger")),
        rs.getBigDecimal("threshold_percent"),
        rs.getBigDecimal("baseline_price"),
        rs.getBoolean("active"),
        OutboxRepository.instant(rs, "created_at"),
        OutboxRepository.instant(rs, "last_triggered_at"),
        rs.getString("baseline_currency"));
  }
}
