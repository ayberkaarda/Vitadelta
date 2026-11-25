package com.vitadelta.api.alerts;

import com.vitadelta.api.mail.MailLinks;
import com.vitadelta.api.mail.MailLocale;
import com.vitadelta.api.mail.MailModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Template models of the alert and cap e-mails (section 8.7). Every model carries {@code
 * preferencesUrl}, the notification preferences in {@code /account} (also the {@code
 * List-Unsubscribe} target); the renderer adds {@code homeUrl} and {@code privacyUrl}. Product
 * texts come from the catalog and are rendered escaped by the HTML templates. Money is the native
 * snapshot of the notification, formatted with its currency in the recipient's language.
 */
public final class AlertMails {

  /** Template of each alert notification type. */
  public static final String PRICE_DROP_TEMPLATE = "alert-price-drop";

  public static final String BACK_IN_STOCK_TEMPLATE = "alert-back-in-stock";
  public static final String PRICE_CHANGE_TEMPLATE = "alert-price-change";
  public static final String CAP_REACHED_TEMPLATE = "notification-cap-reached";

  /** Variable holding the notification preference link. */
  public static final String PREFERENCES_URL = "preferencesUrl";

  private AlertMails() {}

  /** The {@code alert-*} template of an alert notification. */
  public record AlertMail(NotificationPayloads.AlertNotificationPayload payload)
      implements MailModel {

    public AlertMail {
      Objects.requireNonNull(payload, "payload");
      Objects.requireNonNull(payload.after(), "after");
    }

    @Override
    public String templateName() {
      return switch (AlertTrigger.valueOf(payload.trigger())) {
        case PRICE_DROP -> PRICE_DROP_TEMPLATE;
        case BACK_IN_STOCK -> BACK_IN_STOCK_TEMPLATE;
        case PRICE_CHANGE -> PRICE_CHANGE_TEMPLATE;
      };
    }

    @Override
    public Map<String, Object> variables(MailLinks links, MailLocale locale) {
      Map<String, Object> variables = new HashMap<>();
      variables.put(PREFERENCES_URL, links.account());
      variables.put("productName", payload.productName());
      variables.put("brand", payload.brand());
      variables.put("productCode", payload.productCode());
      variables.put("countryCode", payload.countryCode());
      variables.put("retailer", text(payload.after().retailer()));
      variables.put(
          "newPrice", money(payload.after().effectivePrice(), payload.currency(), locale));
      OfferState before = payload.before() == null ? OfferState.NONE : payload.before();
      variables.put("oldPrice", money(before.effectivePrice(), payload.currency(), locale));
      variables.put("baselinePrice", money(payload.baselinePrice(), payload.currency(), locale));
      variables.put("thresholdPercent", percent(payload.thresholdPercent(), locale));
      variables.put("dropPercent", dropPercent(payload, locale));
      return variables;
    }

    private static String dropPercent(
        NotificationPayloads.AlertNotificationPayload payload, MailLocale locale) {
      BigDecimal baseline = payload.baselinePrice();
      BigDecimal after = payload.after().effectivePrice();
      if (baseline == null || after == null || baseline.signum() <= 0) {
        return "";
      }
      BigDecimal drop =
          baseline
              .subtract(after)
              .multiply(BigDecimal.valueOf(100))
              .divide(baseline, 1, RoundingMode.HALF_EVEN);
      return percent(drop, locale);
    }
  }

  /** The {@code notification-cap-reached} template. */
  public record CapReachedMail(NotificationPayloads.CapReachedPayload payload)
      implements MailModel {

    public CapReachedMail {
      Objects.requireNonNull(payload, "payload");
      Objects.requireNonNull(payload.periodMonth(), "periodMonth");
    }

    @Override
    public String templateName() {
      return CAP_REACHED_TEMPLATE;
    }

    @Override
    public Map<String, Object> variables(MailLinks links, MailLocale locale) {
      String month =
          DateTimeFormatter.ofPattern("LLLL yyyy", locale.locale()).format(payload.periodMonth());
      return Map.of(
          PREFERENCES_URL,
          links.account(),
          "periodMonth",
          month,
          "limit",
          NumberFormat.getIntegerInstance(locale.locale()).format(payload.limit()));
    }
  }

  /** {@code amount} with its currency in the language of {@code locale}; empty without amount. */
  static String money(BigDecimal amount, String currency, MailLocale locale) {
    if (amount == null) {
      return "";
    }
    NumberFormat format = NumberFormat.getCurrencyInstance(locale.locale());
    Currency unit = Currency.getInstance(currency);
    format.setCurrency(unit);
    format.setMinimumFractionDigits(unit.getDefaultFractionDigits());
    format.setMaximumFractionDigits(unit.getDefaultFractionDigits());
    format.setRoundingMode(RoundingMode.HALF_EVEN);
    return format.format(amount);
  }

  static String percent(BigDecimal value, MailLocale locale) {
    if (value == null) {
      return "";
    }
    NumberFormat format = NumberFormat.getNumberInstance(locale.locale());
    format.setMinimumFractionDigits(0);
    format.setMaximumFractionDigits(2);
    return format.format(value.stripTrailingZeros());
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
