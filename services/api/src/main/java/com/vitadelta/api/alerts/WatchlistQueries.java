package com.vitadelta.api.alerts;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.price.EffectivePrice;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.ProductInCountry;
import com.vitadelta.api.catalog.web.ProductPresenter;
import com.vitadelta.api.common.watch.WatchedAlert;
import com.vitadelta.api.entitlement.PriceConversion;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the saved products and alerts ({@code GET /me/saved-products}, {@code GET
 * /me/alerts}). Saved products reuse the product presentation of {@code GET /products} (cost with
 * out-of-stock offers included, flags, display-currency conversion); a product no longer in market
 * is listed with {@code UNAVAILABLE_IN_COUNTRY} and without price. No scoring call.
 */
@Component
public class WatchlistQueries {

  /** Flag of a saved product that is no longer {@code AVAILABLE} in the country. */
  static final ProductPresenter.Flag UNAVAILABLE =
      new ProductPresenter.Flag("UNAVAILABLE_IN_COUNTRY", List.of());

  private final WatchlistRepository watchlist;
  private final CatalogReadService catalog;
  private final ProductPresenter presenter;
  private final EffectivePriceResolver resolver;
  private final JdbcClient jdbc;
  private final Clock clock;

  public WatchlistQueries(
      WatchlistRepository watchlist,
      CatalogReadService catalog,
      ProductPresenter presenter,
      EffectivePriceResolver resolver,
      JdbcClient jdbc,
      Clock clock) {
    this.watchlist = watchlist;
    this.catalog = catalog;
    this.presenter = presenter;
    this.resolver = resolver;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** The caller's saved products of a country, {@code savedAt} desc then product id asc. */
  public List<AlertViews.SavedProduct> savedProducts(
      UUID userId, String countryCode, PriceConversion conversion) {
    List<WatchlistRepository.SavedRow> rows = watchlist.savedProducts(userId, countryCode);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<WatchedAlert>> alerts = new LinkedHashMap<>();
    for (WatchlistRepository.AlertRow row :
        watchlist.alertsAmong(
            userId,
            countryCode,
            rows.stream().map(WatchlistRepository.SavedRow::productId).toList(),
            true)) {
      alerts.computeIfAbsent(row.productId(), id -> new ArrayList<>()).add(row.view());
    }
    Instant now = clock.instant();
    List<AlertViews.SavedProduct> items = new ArrayList<>();
    for (WatchlistRepository.SavedRow row : rows) {
      Optional<ProductInCountry> found = catalog.product(row.productId(), countryCode);
      if (found.isEmpty()) {
        continue;
      }
      items.add(
          item(row, found.get(), conversion, alerts.getOrDefault(row.productId(), List.of()), now));
    }
    return List.copyOf(items);
  }

  private AlertViews.SavedProduct item(
      WatchlistRepository.SavedRow row,
      ProductInCountry found,
      PriceConversion conversion,
      List<WatchedAlert> alerts,
      Instant now) {
    CatalogProduct product = found.product();
    AvailabilityStatus status =
        found.availabilityStatus() == null
            ? AvailabilityStatus.NOT_SOLD
            : found.availabilityStatus();
    ProductPresenter.Provenance provenance =
        new ProductPresenter.Provenance(
            product.provenance().synthetic(),
            product.provenance().source(),
            product.provenance().sourceRef(),
            product.provenance().sourceEntryDate());
    ProductPresenter.Cost cost = null;
    ProductPresenter.SelectedOffer selected = null;
    List<ProductPresenter.Flag> flags;
    if (status == AvailabilityStatus.AVAILABLE) {
      ProductPresenter.ProductSummary summary = presenter.summary(product, conversion, now);
      cost = summary.cost();
      flags = summary.flags();
      EffectivePrice price =
          resolver
              .resolve(product.offers(), StockFilter.INCLUDE_OUT_OF_STOCK, now)
              .price()
              .orElse(null);
      if (price != null && cost != null) {
        selected =
            new ProductPresenter.SelectedOffer(
                price.selected().retailer(), price.selected().url(), price.selected().checkedAt());
      }
    } else {
      flags = List.of(UNAVAILABLE);
    }
    return new AlertViews.SavedProduct(
        product.id(),
        product.code(),
        product.brand(),
        product.name(),
        product.dosageForm(),
        provenance,
        row.countryCode(),
        row.savedAt(),
        status,
        cost,
        selected,
        flags,
        alerts);
  }

  /** Every subscription of the caller with its product, {@code createdAt} desc then id asc. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<AlertViews.AlertListItem> alerts(UUID userId) {
    List<WatchlistRepository.AlertRow> rows = watchlist.alerts(userId);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, AlertViews.ProductRef> products = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT DISTINCT s.product_id, s.country_code, p.code, p.brand, p.name, p.synthetic,"
                + " p.source, p.source_ref, p.source_entry_date,"
                + " coalesce(a.status, 'NOT_SOLD') AS availability"
                + " FROM alert_subscription s JOIN product p ON p.id = s.product_id"
                + " LEFT JOIN product_availability a ON a.product_id = s.product_id"
                + " AND a.country_code = s.country_code WHERE s.user_id = ?")
        .param(userId)
        .query((rs, i) -> {
          Date entry = rs.getDate("source_entry_date");
          UUID productId = rs.getObject("product_id", UUID.class);
          products.put(
              key(productId, rs.getString("country_code")),
              new AlertViews.ProductRef(
                  productId,
                  rs.getString("code"),
                  rs.getString("brand"),
                  rs.getString("name"),
                  AvailabilityStatus.valueOf(rs.getString("availability")),
                  new ProductPresenter.Provenance(
                      rs.getBoolean("synthetic"),
                      rs.getString("source"),
                      rs.getString("source_ref"),
                      entry == null ? null : entry.toLocalDate())));
          return productId;
        })
        .list();
    return rows.stream()
        .map(row ->
            AlertViews.AlertListItem.of(
                row.view(), products.get(key(row.productId(), row.countryCode()))))
        .toList();
  }

  private static String key(UUID productId, String countryCode) {
    return productId + ":" + countryCode;
  }
}
