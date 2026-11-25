package com.vitadelta.api.alerts;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.DosageForm;
import com.vitadelta.api.catalog.web.ProductPresenter;
import com.vitadelta.api.common.paging.PageMeta;
import com.vitadelta.api.common.watch.WatchedAlert;
import com.vitadelta.api.entitlement.PriceConversion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of the saved-products, alerts and notifications operations; each
 * record is the contract schema of the same name.
 */
public final class AlertViews {

  private AlertViews() {}

  // ---------- Requests (documentation of the parsed JSON bodies) ----------

  /** Contract schema {@code SaveProductRequest}. */
  public record SaveProductRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") String productId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String countryCode) {}

  /** Contract schema {@code CreateAlertRequest}. */
  public record CreateAlertRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") String productId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String countryCode,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {"PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE"})
          String trigger,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal thresholdPercent) {}

  /** Contract schema {@code UpdateAlertRequest}: at least one member. */
  public record UpdateAlertRequest(
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Boolean active,
      @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) BigDecimal thresholdPercent) {}

  // ---------- Saved products ----------

  /** Contract schema {@code SavedProductRef}. */
  public record SavedProductRef(UUID productId, String countryCode, Instant savedAt) {}

  /** Contract schema {@code SavedProduct}. */
  public record SavedProduct(
      UUID productId,
      String code,
      String brand,
      String name,
      DosageForm dosageForm,
      ProductPresenter.Provenance provenance,
      String countryCode,
      Instant savedAt,
      AvailabilityStatus availabilityStatus,
      ProductPresenter.Cost cost,
      ProductPresenter.SelectedOffer selectedOffer,
      List<ProductPresenter.Flag> flags,
      List<WatchedAlert> alerts) {}

  /** Contract schema {@code SavedProductListMeta}. */
  public record SavedProductListMeta(
      String country, String nativeCurrency, String currency, PriceConversion.Fx fx) {}

  /** Contract schema {@code SavedProductList}. */
  public record SavedProductList(SavedProductListMeta meta, List<SavedProduct> items) {}

  // ---------- Alerts ----------

  /** Contract schema {@code ProductRef}. */
  public record ProductRef(
      UUID id,
      String code,
      String brand,
      String name,
      AvailabilityStatus availabilityStatus,
      ProductPresenter.Provenance provenance) {}

  /** Contract schema {@code AlertListItem}: an {@code AlertSubscription} with its product. */
  public record AlertListItem(
      UUID id,
      UUID productId,
      String countryCode,
      @Schema(allowableValues = {"PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE"}) String trigger,
      BigDecimal thresholdPercent,
      BigDecimal baselinePrice,
      String baselineCurrency,
      boolean active,
      Instant createdAt,
      Instant lastTriggeredAt,
      ProductRef product) {

    static AlertListItem of(WatchedAlert alert, ProductRef product) {
      return new AlertListItem(
          alert.id(),
          alert.productId(),
          alert.countryCode(),
          alert.trigger(),
          alert.thresholdPercent(),
          alert.baselinePrice(),
          alert.baselineCurrency(),
          alert.active(),
          alert.createdAt(),
          alert.lastTriggeredAt(),
          product);
    }
  }

  /** Contract schema {@code AlertList}. */
  public record AlertList(List<AlertListItem> items) {}

  // ---------- Notifications ----------

  /** Contract schema {@code Notification}: one inbox entry, {@code type} selects the payload. */
  @Schema(
      oneOf = {
        AlertNotification.class,
        CapReachedNotification.class,
        PlanActivatedNotification.class,
        PlanExpiredNotification.class
      })
  public sealed interface Notification
      permits AlertNotification,
          CapReachedNotification,
          PlanActivatedNotification,
          PlanExpiredNotification {

    UUID id();

    String type();

    Instant createdAt();

    Instant readAt();
  }

  /** Contract schema {@code AlertNotification}. */
  public record AlertNotification(
      UUID id,
      @Schema(
              allowableValues = {
                "ALERT_PRICE_DROP",
                "ALERT_BACK_IN_STOCK",
                "ALERT_PRICE_CHANGE",
                "NOTIFICATION_CAP_REACHED",
                "PLAN_ACTIVATED",
                "PLAN_EXPIRED"
              })
          String type,
      NotificationPayloads.AlertNotificationPayload payload,
      Instant createdAt,
      Instant readAt)
      implements Notification {}

  /** Contract schema {@code CapReachedNotification}. */
  public record CapReachedNotification(
      UUID id,
      @Schema(
              allowableValues = {
                "ALERT_PRICE_DROP",
                "ALERT_BACK_IN_STOCK",
                "ALERT_PRICE_CHANGE",
                "NOTIFICATION_CAP_REACHED",
                "PLAN_ACTIVATED",
                "PLAN_EXPIRED"
              })
          String type,
      NotificationPayloads.CapReachedPayload payload,
      Instant createdAt,
      Instant readAt)
      implements Notification {}

  /** Contract schema {@code PlanActivatedNotification}. */
  public record PlanActivatedNotification(
      UUID id,
      @Schema(
              allowableValues = {
                "ALERT_PRICE_DROP",
                "ALERT_BACK_IN_STOCK",
                "ALERT_PRICE_CHANGE",
                "NOTIFICATION_CAP_REACHED",
                "PLAN_ACTIVATED",
                "PLAN_EXPIRED"
              })
          String type,
      NotificationPayloads.PlanActivatedPayload payload,
      Instant createdAt,
      Instant readAt)
      implements Notification {}

  /** Contract schema {@code PlanExpiredNotification}. */
  public record PlanExpiredNotification(
      UUID id,
      @Schema(
              allowableValues = {
                "ALERT_PRICE_DROP",
                "ALERT_BACK_IN_STOCK",
                "ALERT_PRICE_CHANGE",
                "NOTIFICATION_CAP_REACHED",
                "PLAN_ACTIVATED",
                "PLAN_EXPIRED"
              })
          String type,
      NotificationPayloads.PlanExpiredPayload payload,
      Instant createdAt,
      Instant readAt)
      implements Notification {}

  /** Contract schema {@code NotificationPage}. */
  public record NotificationPage(PageMeta meta, List<Notification> items) {}

  /** Contract schema {@code UnreadCount}. */
  public record UnreadCount(long unreadCount) {}
}
