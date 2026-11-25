package com.vitadelta.api.catalog;

import jakarta.persistence.EntityManager;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single entry point for writes to catalog and reference data (product, product_nutrient,
 * product_availability, offer, nutrient, nutrient_form, unit_conversion, reference_set,
 * reference_value, ul_set, upper_limit, country).
 *
 * <p>{@link #execute(Supplier)} runs the work in its own transaction, flushes every pending change
 * and then executes the catalog version bump as the last statement of the transaction, so the
 * version row lock is held only for the commit. The version increases by exactly one per committed
 * transaction, whatever the number of rows written (also when nothing changed). When the work
 * throws, the transaction is rolled back and the version is not bumped.
 *
 * <p>The work must not be part of a surrounding transaction, because the bump would then not be the
 * last statement of the committing transaction; such a call is rejected.
 */
@Component
public class CatalogWriteTransaction {

  static final String BUMP_SQL =
      "UPDATE catalog_version SET version = version + 1, bumped_at = now() WHERE id = 1";

  private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private final TransactionTemplate template;
  private final EntityManager entityManager;

  public CatalogWriteTransaction(
      PlatformTransactionManager transactionManager, EntityManager entityManager) {
    this.template = new TransactionTemplate(transactionManager);
    this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.entityManager = entityManager;
  }

  /**
   * Runs {@code work} in a new write transaction that ends with the catalog version bump.
   *
   * @return the value returned by {@code work}
   * @throws IllegalStateException when called inside an active transaction
   */
  public <T> T execute(Supplier<T> work) {
    return execute(work, result -> true);
  }

  /**
   * Runs {@code work} in a new write transaction and bumps the catalog version as its last
   * statement only when {@code changed} accepts the result. Imports use it so that a write that
   * changes no catalog row commits without a bump (section 5.3: the version is bumped once per
   * committing transaction that changes catalog or reference data).
   *
   * @return the value returned by {@code work}
   * @throws IllegalStateException when called inside an active transaction
   */
  public <T> T execute(Supplier<T> work, Predicate<? super T> changed) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "CatalogWriteTransaction must start its own transaction, not join an active one");
    }
    return template.execute(status -> {
      ACTIVE.set(Boolean.TRUE);
      try {
        T result = work.get();
        entityManager.flush();
        if (changed.test(result)) {
          int bumped = entityManager.createNativeQuery(BUMP_SQL).executeUpdate();
          if (bumped != 1) {
            throw new IllegalStateException("catalog_version row is missing");
          }
        }
        return result;
      } finally {
        ACTIVE.remove();
      }
    });
  }

  /** Whether the current thread runs inside {@link #execute(Supplier)}. */
  public static boolean isActive() {
    return Boolean.TRUE.equals(ACTIVE.get());
  }

  /** Rejects a catalog write outside {@link #execute(Supplier)}. */
  static void requireActive() {
    if (!isActive()) {
      throw new IllegalStateException("catalog writes must run inside CatalogWriteTransaction");
    }
  }
}
