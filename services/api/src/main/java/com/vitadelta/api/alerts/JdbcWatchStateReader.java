package com.vitadelta.api.alerts;

import com.vitadelta.api.common.watch.WatchState;
import com.vitadelta.api.common.watch.WatchStateReader;
import com.vitadelta.api.common.watch.WatchedAlert;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code saved} and {@code alerts} of the product-bearing responses: two batched statements per
 * response (saved rows and subscriptions of the page's products), whatever the page size.
 * Subscriptions are listed active and inactive, ordered by trigger; the baseline stays native.
 */
@Component
public class JdbcWatchStateReader implements WatchStateReader {

  private final WatchlistRepository watchlist;

  public JdbcWatchStateReader(WatchlistRepository watchlist) {
    this.watchlist = watchlist;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, WatchState> forProducts(
      UUID userId, String countryCode, Collection<UUID> productIds) {
    if (userId == null || countryCode == null || productIds == null || productIds.isEmpty()) {
      return Map.of();
    }
    Set<UUID> ids = new HashSet<>(productIds);
    Set<UUID> saved = new HashSet<>(watchlist.savedAmong(userId, countryCode, ids));
    Map<UUID, List<WatchedAlert>> alerts = new LinkedHashMap<>();
    for (WatchlistRepository.AlertRow row :
        watchlist.alertsAmong(userId, countryCode, ids, false)) {
      alerts.computeIfAbsent(row.productId(), id -> new ArrayList<>()).add(row.view());
    }
    Map<UUID, WatchState> states = new LinkedHashMap<>();
    for (UUID id : ids) {
      boolean isSaved = saved.contains(id);
      List<WatchedAlert> productAlerts = alerts.getOrDefault(id, List.of());
      if (isSaved || !productAlerts.isEmpty()) {
        states.put(id, new WatchState(isSaved, productAlerts));
      }
    }
    return states;
  }
}
