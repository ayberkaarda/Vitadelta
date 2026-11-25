package com.vitadelta.api.alerts;

import com.vitadelta.api.common.problem.FieldViolation;
import com.vitadelta.api.common.problem.ProblemException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Field-level validation of the JSON request bodies of this module, so that every malformed value
 * is a {@code 422 validation_failed} with one {@code errors[]} entry per field (section 9.3) and
 * the checks run in the order the contract defines.
 */
final class JsonFields {

  static final String REQUIRED = "required";
  static final String INVALID_FORMAT = "invalid_format";
  static final String OUT_OF_RANGE = "out_of_range";
  static final String THRESHOLD_NOT_ALLOWED = "threshold_not_allowed";

  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final BigDecimal MIN_THRESHOLD = new BigDecimal("0.01");
  private static final BigDecimal MAX_THRESHOLD = new BigDecimal("99.99");

  private JsonFields() {}

  /** Fails with {@code validation_failed} unless {@code body} is a JSON object. */
  static void requireObject(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw ProblemException.validation(
          "The request body must be a JSON object.",
          List.of(new FieldViolation("body", INVALID_FORMAT, "The body must be a JSON object.")));
    }
  }

  /** A required UUID member. */
  static UUID uuid(JsonNode body, String field, List<FieldViolation> errors) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) {
      errors.add(new FieldViolation(field, REQUIRED, "Value is required."));
      return null;
    }
    Optional<UUID> parsed = value.isString() ? parseUuid(value.asString()) : Optional.empty();
    if (parsed.isEmpty()) {
      errors.add(new FieldViolation(field, INVALID_FORMAT, field + " must be a UUID."));
      return null;
    }
    return parsed.get();
  }

  /** A required country code member. */
  static String country(JsonNode body, String field, List<FieldViolation> errors) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull()) {
      errors.add(new FieldViolation(field, REQUIRED, "Value is required."));
      return null;
    }
    if (!value.isString() || !COUNTRY.matcher(value.asString()).matches()) {
      errors.add(
          new FieldViolation(field, INVALID_FORMAT, field + " must be two capital letters."));
      return null;
    }
    return value.asString();
  }

  /** A required trigger member. */
  static AlertTrigger trigger(JsonNode body, List<FieldViolation> errors) {
    JsonNode value = body.get("trigger");
    if (value == null || value.isNull()) {
      errors.add(new FieldViolation("trigger", REQUIRED, "Value is required."));
      return null;
    }
    Optional<AlertTrigger> trigger =
        value.isString() ? AlertTrigger.parse(value.asString()) : Optional.empty();
    if (trigger.isEmpty()) {
      errors.add(
          new FieldViolation(
              "trigger",
              INVALID_FORMAT,
              "trigger must be one of PRICE_DROP, BACK_IN_STOCK, PRICE_CHANGE."));
      return null;
    }
    return trigger.get();
  }

  /**
   * A threshold value: a JSON number from 0.01 to 99.99 with at most 2 decimals once trailing zeros
   * are removed; never rounded.
   *
   * @return the value with scale 2, or {@code null} when invalid (an error is added)
   */
  static BigDecimal threshold(JsonNode value, List<FieldViolation> errors) {
    if (value == null || !value.isNumber()) {
      errors.add(
          new FieldViolation(
              "thresholdPercent", INVALID_FORMAT, "thresholdPercent must be a number."));
      return null;
    }
    BigDecimal number;
    try {
      number = value.decimalValue();
    } catch (RuntimeException e) {
      number = null;
    }
    BigDecimal stripped = number == null ? null : number.stripTrailingZeros();
    if (number == null
        || number.compareTo(MIN_THRESHOLD) < 0
        || number.compareTo(MAX_THRESHOLD) > 0
        || stripped.scale() > 2) {
      errors.add(
          new FieldViolation(
              "thresholdPercent",
              OUT_OF_RANGE,
              "thresholdPercent must be between 0.01 and 99.99 with at most 2 decimals."));
      return null;
    }
    return number.setScale(2);
  }

  /** A query parameter that must be a country code. */
  static void checkCountryParameter(String country, List<FieldViolation> errors) {
    if (country == null) {
      errors.add(new FieldViolation("country", REQUIRED, "Value is required."));
    } else if (!COUNTRY.matcher(country).matches()) {
      errors.add(
          new FieldViolation("country", INVALID_FORMAT, "country must be two capital letters."));
    }
  }

  /** An optional query parameter that must be a currency code. */
  static void checkCurrencyParameter(String currency, List<FieldViolation> errors) {
    if (currency != null && !CURRENCY.matcher(currency).matches()) {
      errors.add(
          new FieldViolation(
              "displayCurrency", INVALID_FORMAT, "displayCurrency must be three capital letters."));
    }
  }

  static void failIfInvalid(List<FieldViolation> errors) {
    if (!errors.isEmpty()) {
      throw ProblemException.validation("The request contains invalid fields.", errors);
    }
  }

  /** A canonical UUID (any case); empty for anything else. */
  static Optional<UUID> parseUuid(String value) {
    if (value == null || value.length() != 36) {
      return Optional.empty();
    }
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equalsIgnoreCase(value) ? Optional.of(parsed) : Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
