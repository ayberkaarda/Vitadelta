package com.vitadelta.api.alerts;

import com.vitadelta.api.catalog.AvailabilityStatus;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.common.watch.WatchedAlert;
import com.vitadelta.api.entitlement.EntitlementService;
import com.vitadelta.api.identity.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Writes of the saved products and alert subscriptions (sections 8.4 to 8.6). Every write runs in
 * one transaction that first locks the caller's {@code app_user} row, so the existence checks, the
 * plan limits of {@link EntitlementService} and the insert or update of one user are serialized and
 * parallel requests can never exceed a limit. Invariant: every active subscription has a
 * saved-product row for its product and country.
 */
@Service
public class WatchlistService {

  /** Threshold of a {@code PRICE_DROP} subscription created without one. */
  static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("5.00");

  private final WatchlistRepository watchlist;
  private final EntitlementService entitlements;
  private final UserRepository users;
  private final EffectivePriceResolver resolver;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public WatchlistService(
      WatchlistRepository watchlist,
      EntitlementService entitlements,
      UserRepository users,
      EffectivePriceResolver resolver,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.watchlist = watchlist;
    this.entitlements = entitlements;
    this.users = users;
    this.resolver = resolver;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** A saved row and whether this request created it. */
  public record SaveResult(WatchlistRepository.SavedRow row, boolean created) {}

  /** A subscription and whether this request created it. */
  public record AlertResult(WatchedAlert alert, boolean created) {}

  /**
   * Checks the country, the product and its availability, in that order (contract order of the
   * saved-product and alert creation).
   */
  void requireAvailable(UUID productId, String countryCode) {
    if (watchlist.countryCurrency(countryCode).isEmpty()) {
      throw ProblemException.of(ProblemCode.COUNTRY_NOT_FOUND, "The country is not supported.");
    }
    if (!watchlist.productExists(productId)) {
      throw ProblemException.of(ProblemCode.PRODUCT_NOT_FOUND, "The product does not exist.");
    }
    requireInMarket(productId, countryCode);
  }

  private void requireInMarket(UUID productId, String countryCode) {
    if (watchlist.availability(productId, countryCode) != AvailabilityStatus.AVAILABLE) {
      throw ProblemException.of(
          ProblemCode.PRODUCT_NOT_AVAILABLE_IN_COUNTRY,
          "The product is not sold in " + countryCode + ".");
    }
  }

  /** {@code POST /me/saved-products}: idempotent save within the plan limit. */
  public SaveResult save(UUID userId, UUID productId, String countryCode) {
    requireAvailable(productId, countryCode);
    return transactions.execute(status -> {
      lockUser(userId);
      Optional<WatchlistRepository.SavedRow> existing =
          watchlist.savedProduct(userId, productId, countryCode);
      if (existing.isPresent()) {
        return new SaveResult(existing.get(), false);
      }
      entitlements.requireSavedProductSlot(userId);
      Instant now = clock.instant();
      watchlist.insertSavedProduct(userId, productId, countryCode, now);
      return new SaveResult(new WatchlistRepository.SavedRow(productId, countryCode, now), true);
    });
  }

  /**
   * {@code DELETE /me/saved-products/{productId}}: removes the row and deactivates the caller's
   * subscriptions of the product and country (baselines kept), in one transaction.
   */
  public void remove(UUID userId, UUID productId, String countryCode) {
    transactions.executeWithoutResult(status -> {
      lockUser(userId);
      watchlist.deleteSavedProduct(userId, productId, countryCode);
      watchlist.deactivateAlerts(userId, productId, countryCode);
    });
  }

  /**
   * {@code POST /me/alerts}: returns an existing subscription unchanged, else checks verification
   * and the alert limit, creates the implicit saved row within the saved limit and inserts the
   * active subscription with its baseline.
   *
   * @param thresholdPercent {@code PRICE_DROP} threshold, {@code null} for the default
   */
  public AlertResult create(
      UUID userId,
      UUID productId,
      String countryCode,
      AlertTrigger trigger,
      BigDecimal thresholdPercent) {
    requireAvailable(productId, countryCode);
    return transactions.execute(status -> {
      lockUser(userId);
      Optional<WatchlistRepository.AlertRow> existing =
          watchlist.alert(userId, productId, countryCode, trigger);
      if (existing.isPresent()) {
        return new AlertResult(existing.get().view(), false);
      }
      entitlements.requireAlertSlot(userId);
      Instant now = clock.instant();
      ensureSaved(userId, productId, countryCode, now);
      boolean priceDrop = trigger == AlertTrigger.PRICE_DROP;
      UUID id = UUID.randomUUID();
      watchlist.insertAlert(
          id,
          userId,
          productId,
          countryCode,
          trigger,
          priceDrop ? (thresholdPercent == null ? DEFAULT_THRESHOLD : thresholdPercent) : null,
          priceDrop ? currentPrice(productId, countryCode) : null,
          now);
      return new AlertResult(watchlist.alert(userId, id).orElseThrow().view(), true);
    });
  }

  /**
   * The caller's subscription {@code id}.
   *
   * @throws ResponseStatusException 404 for an unknown or foreign id
   */
  public WatchlistRepository.AlertRow requireAlert(UUID userId, UUID id) {
    return watchlist.alert(userId, id).orElseThrow(WatchlistService::notFound);
  }

  /**
   * {@code PATCH /me/alerts/{id}} after body validation, the lookup and the trigger check.
   *
   * @param active requested state, {@code null} when absent
   * @param thresholdPercent requested threshold ({@code PRICE_DROP} only), {@code null} when absent
   */
  public WatchedAlert update(UUID userId, UUID id, Boolean active, BigDecimal thresholdPercent) {
    return transactions.execute(status -> {
      lockUser(userId);
      WatchlistRepository.AlertRow stored = requireAlert(userId, id);
      boolean newActive = stored.active();
      BigDecimal newThreshold = stored.thresholdPercent();
      boolean resetBaseline = false;
      if (active != null && active != stored.active()) {
        if (active) {
          requireInMarket(stored.productId(), stored.countryCode());
          entitlements.requireAlertSlot(userId);
          ensureSaved(userId, stored.productId(), stored.countryCode(), clock.instant());
          resetBaseline = true;
        }
        newActive = active;
      }
      if (thresholdPercent != null
          && (stored.thresholdPercent() == null
              || thresholdPercent.compareTo(stored.thresholdPercent()) != 0)) {
        newThreshold = thresholdPercent;
        resetBaseline = true;
      }
      if (newActive == stored.active() && !resetBaseline) {
        return stored.view();
      }
      BigDecimal baseline = stored.baselinePrice();
      if (resetBaseline && stored.trigger() == AlertTrigger.PRICE_DROP) {
        baseline = currentPrice(stored.productId(), stored.countryCode());
      }
      watchlist.updateAlert(id, newActive, newThreshold, baseline);
      return requireAlert(userId, id).view();
    });
  }

  /** {@code DELETE /me/alerts/{id}}; 404 for an unknown or foreign id. */
  public void delete(UUID userId, UUID id) {
    Integer deleted = transactions.execute(status -> watchlist.deleteAlert(userId, id));
    if (deleted == null || deleted == 0) {
      throw notFound();
    }
  }

  /**
   * The current native effective price of the product in the country (section 7.4, {@code
   * INCLUDE_OUT_OF_STOCK}), 2 decimals; {@code null} without offers.
   */
  BigDecimal currentPrice(UUID productId, String countryCode) {
    return resolver
        .resolve(watchlist.offers(productId, countryCode), StockFilter.INCLUDE_OUT_OF_STOCK)
        .price()
        .map(p -> p.price().setScale(2, RoundingMode.HALF_EVEN))
        .orElse(null);
  }

  private void ensureSaved(UUID userId, UUID productId, String countryCode, Instant now) {
    if (watchlist.savedProduct(userId, productId, countryCode).isEmpty()) {
      entitlements.requireSavedProductSlot(userId);
      watchlist.insertSavedProduct(userId, productId, countryCode, now);
    }
  }

  private void lockUser(UUID userId) {
    if (users.findByIdForUpdate(userId).isEmpty()) {
      throw ProblemException.of(ProblemCode.UNAUTHORIZED, "Authentication is required.");
    }
  }

  static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
