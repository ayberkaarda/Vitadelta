package com.vitadelta.api.alerts;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code notification.payload} documents (stored as jsonb exactly as the API returns them) and
 * the {@code OFFERS_CHANGED} event payload.
 */
public final class NotificationPayloads {

  private NotificationPayloads() {}

  /**
   * Payload of {@code OFFERS_CHANGED} (section 8.6).
   *
   * @param catalogVersion the catalog version the import commits with
   */
  public record OffersChanged(
      UUID productId,
      String countryCode,
      OfferState before,
      OfferState after,
      long catalogVersion) {}

  /** Payload of {@code NOTIFICATION_CREATED}. */
  public record NotificationCreated(UUID notificationId) {}

  /**
   * Payload of the three alert notifications (contract schema {@code AlertNotificationPayload}): a
   * snapshot in the country's native {@code currency}, never converted.
   *
   * @param thresholdPercent threshold in effect ({@code PRICE_DROP}), {@code null} otherwise
   * @param baselinePrice baseline the drop was measured against ({@code PRICE_DROP}), {@code null}
   *     otherwise
   */
  public record AlertNotificationPayload(
      UUID subscriptionId,
      @Schema(allowableValues = {"PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE"}) String trigger,
      UUID productId,
      String productCode,
      String brand,
      String productName,
      String countryCode,
      String currency,
      BigDecimal thresholdPercent,
      BigDecimal baselinePrice,
      OfferState before,
      OfferState after) {}

  /**
   * Payload of {@code NOTIFICATION_CAP_REACHED} (contract schema {@code CapReachedPayload}).
   *
   * @param periodMonth first day of the capped month (UTC)
   * @param limit the plan's monthly limit
   */
  public record CapReachedPayload(LocalDate periodMonth, int limit) {}

  /** Payload of {@code PLAN_ACTIVATED} (contract schema {@code PlanActivatedPayload}). */
  public record PlanActivatedPayload(
      @Schema(allowableValues = {"FREE", "PRO"}) String planCode,
      @Schema(allowableValues = {"MANUAL", "PROMO", "PROVIDER"}) String source,
      Instant currentPeriodEnd,
      boolean extended) {}

  /** Payload of {@code PLAN_EXPIRED} (contract schema {@code PlanExpiredPayload}). */
  public record PlanExpiredPayload(
      @Schema(allowableValues = {"FREE", "PRO"}) String expiredPlanCode,
      @Schema(allowableValues = {"FREE", "PRO"}) String planCode,
      int deactivatedAlertSubscriptions) {}
}
