package com.vitadelta.api.common.watch;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One alert subscription of the caller as shown on a product (contract schema {@code
 * AlertSubscription}). {@code baselinePrice} is in the country's native currency ({@code
 * baselineCurrency}) and is never converted to the display currency.
 */
public record WatchedAlert(
    UUID id,
    UUID productId,
    String countryCode,
    @Schema(allowableValues = {"PRICE_DROP", "BACK_IN_STOCK", "PRICE_CHANGE"}) String trigger,
    BigDecimal thresholdPercent,
    BigDecimal baselinePrice,
    String baselineCurrency,
    boolean active,
    Instant createdAt,
    Instant lastTriggeredAt) {}
