package com.vitadelta.api.common.watch;

import java.util.List;
import java.util.Objects;

/**
 * Whether the caller saved a product in a country, and the caller's alert subscriptions on it
 * (fields {@code saved} and {@code alerts} of {@code RankedProduct} and {@code ProductDetail}).
 *
 * @param alerts all subscriptions of the caller on the product and country, active and inactive,
 *     ordered by trigger ({@code PRICE_DROP}, {@code BACK_IN_STOCK}, {@code PRICE_CHANGE})
 */
public record WatchState(boolean saved, List<WatchedAlert> alerts) {

  /** Not saved, no alert: anonymous callers and products the caller never watched. */
  public static final WatchState NONE = new WatchState(false, List.of());

  public WatchState {
    alerts = List.copyOf(Objects.requireNonNull(alerts, "alerts"));
  }
}
