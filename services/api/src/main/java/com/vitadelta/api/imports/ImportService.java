package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.CatalogWriteTransaction;
import com.vitadelta.api.catalog.CatalogWriter;
import com.vitadelta.api.catalog.Offer;
import com.vitadelta.api.catalog.OfferRepository;
import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.catalog.Product;
import com.vitadelta.api.catalog.ProductImport;
import com.vitadelta.api.catalog.ProductRepository;
import com.vitadelta.api.catalog.WriteReport;
import com.vitadelta.api.catalog.price.EffectivePrice;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.OfferSnapshot;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.common.jobs.JobLock;
import com.vitadelta.api.common.jobs.JobLockKeys;
import com.vitadelta.api.config.properties.ImportLimitsProperties;
import com.vitadelta.api.imports.OfferChangeListener.OfferPairChange;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The admin import pipeline (sections 5.3, 8.9 and 9.1).
 *
 * <p>{@link #submit} creates a {@code RUNNING} job and hands the uploaded bytes to the bounded
 * import executor; the request returns at once (202). A worker then, under the advisory lock {@link
 * JobLockKeys#CATALOG_IMPORT}:
 *
 * <ol>
 *   <li>reads the registry (nutrients, form registry, countries, unit conversions, product codes)
 *       in a short read-only transaction;
 *   <li>validates the whole document without any database connection (the existing readers);
 *   <li>on any error fails the job with every error: nothing is written and the catalog version is
 *       not bumped;
 *   <li>otherwise applies the document in one {@link CatalogWriteTransaction}: products upserted by
 *       code, offers upserted by natural key with replace-set per (product, country) pair; the
 *       effective state of every pair is compared before and after and the changed pairs are passed
 *       to the {@link OfferChangeListener}; the job is marked {@code SUCCEEDED} in the same
 *       transaction; the version is bumped exactly once, and only when a catalog row changed.
 * </ol>
 *
 * An unexpected failure rolls the write back and fails the job with {@code import_failed}.
 */
@Service
public class ImportService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);

  /** How long a worker waits for the import lock held by another session. */
  static final Duration LOCK_WAIT = Duration.ofSeconds(60);

  private static final Duration LOCK_RETRY = Duration.ofMillis(250);

  private final ImportJobStore store;
  private final StoredRegistry registry;
  private final CatalogWriteTransaction writeTransaction;
  private final CatalogWriter writer;
  private final ProductRepository products;
  private final OfferRepository offers;
  private final EffectivePriceResolver resolver;
  private final OfferChangeListener listener;
  private final JobLock jobLock;
  private final ThreadPoolExecutor executor;
  private final Clock clock;
  private final ImportLimitsProperties limits;

  public ImportService(
      ImportJobStore store,
      StoredRegistry registry,
      CatalogWriteTransaction writeTransaction,
      CatalogWriter writer,
      ProductRepository products,
      OfferRepository offers,
      EffectivePriceResolver resolver,
      OfferChangeListener listener,
      JobLock jobLock,
      @Qualifier(ImportSeamConfig.IMPORT_EXECUTOR) ThreadPoolExecutor executor,
      Clock clock,
      ImportLimitsProperties limits) {
    this.limits = limits;
    this.store = store;
    this.registry = registry;
    this.writeTransaction = writeTransaction;
    this.writer = writer;
    this.products = products;
    this.offers = offers;
    this.resolver = resolver;
    this.listener = listener;
    this.jobLock = jobLock;
    this.executor = executor;
    this.clock = clock;
  }

  /**
   * Creates a job and queues the import. When the queue is full, the job fails at once with {@code
   * import_failed}.
   *
   * @return the job as created (or as failed when it could not be queued)
   */
  public ImportJob submit(ImportJob.Kind kind, byte[] content) {
    Objects.requireNonNull(content, "content");
    ImportJob job = store.create(kind);
    try {
      executor.execute(() -> run(job.getId(), kind, content));
    } catch (RejectedExecutionException e) {
      LOG.warn("Import job {} rejected: the import queue is full", job.getId());
      store.fail(
          job.getId(),
          List.of(new ImportProblem("row 1", "import_failed", "the import queue is full")),
          new LinkedHashMap<>());
      return store.detail(job.getId()).orElseThrow().job();
    }
    return job;
  }

  /** Runs one queued import (worker thread). */
  void run(UUID jobId, ImportJob.Kind kind, byte[] content) {
    Instant deadline = clock.instant().plus(LOCK_WAIT);
    try {
      while (!jobLock.runExclusive(
          "CatalogImport", JobLockKeys.CATALOG_IMPORT, () -> runLocked(jobId, kind, content))) {
        if (!clock.instant().isBefore(deadline)) {
          store.fail(
              jobId,
              List.of(new ImportProblem("row 1", "import_failed", "another import is running")),
              new LinkedHashMap<>());
          return;
        }
        TimeUnit.MILLISECONDS.sleep(LOCK_RETRY.toMillis());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Import job {} interrupted before it started", jobId);
    } catch (RuntimeException e) {
      LOG.error("Import job {} failed", jobId, e);
      store.fail(
          jobId,
          List.of(new ImportProblem("row 1", "import_failed", "the import could not be written")),
          new LinkedHashMap<>());
    }
  }

  private void runLocked(UUID jobId, ImportJob.Kind kind, byte[] content) {
    if (!store.isRunning(jobId)) {
      LOG.info("Import job {} is no longer running; skipped", jobId);
      return;
    }
    try {
      Outcome outcome =
          kind == ImportJob.Kind.PRODUCTS_JSON
              ? importProducts(jobId, content)
              : importOffers(jobId, content);
      LOG.info(
          "Import job {} {}: {} changed row(s), {} changed pair(s)",
          jobId,
          outcome.succeeded() ? "succeeded" : "failed",
          outcome.changedRows(),
          outcome.changedPairs());
    } catch (RuntimeException e) {
      LOG.error("Import job {} failed while writing", jobId, e);
      store.fail(
          jobId,
          List.of(new ImportProblem("row 1", "import_failed", "the import could not be written")),
          new LinkedHashMap<>());
    }
  }

  private Outcome importOffers(UUID jobId, byte[] content) {
    Instant now = clock.instant();
    StoredRegistry.Snapshot snapshot = registry.load();
    OffersCsvReader.Result result =
        OffersCsvReader.read(
            content, snapshot.productCodes()::contains, snapshot.registry(), now, limits);
    if (!result.valid()) {
      store.fail(jobId, result.problems(), failedSummary("rows", result.rows()));
      return new Outcome(false, 0, 0);
    }
    return apply(jobId, List.of(), result.offers(), "rows", result.offers().size(), now);
  }

  private Outcome importProducts(UUID jobId, byte[] content) {
    Instant now = clock.instant();
    StoredRegistry.Snapshot snapshot = registry.load();
    ProductsDocumentReader.Result result =
        ProductsDocumentReader.read(content, snapshot.registry(), now, limits);
    if (!result.valid()) {
      store.fail(jobId, result.problems(), failedSummary("products", result.entries()));
      return new Outcome(false, 0, 0);
    }
    List<OfferValues> productOffers =
        result.products().stream().flatMap(p -> p.offers().stream()).toList();
    return apply(
        jobId, result.products(), productOffers, "products", result.products().size(), now);
  }

  /** Applies validated products and offers in one write transaction. */
  private Outcome apply(
      UUID jobId,
      List<ProductImport> productImports,
      List<OfferValues> offerRows,
      String countKey,
      int count,
      Instant now) {
    return writeTransaction.execute(
        () -> {
          Set<String> codes = new LinkedHashSet<>();
          productImports.forEach(p -> codes.add(p.details().code()));
          offerRows.forEach(o -> codes.add(o.productCode()));
          writer.lockProductsByCode(codes);
          Set<PairKey> pairs = new LinkedHashSet<>();
          offerRows.forEach(o -> pairs.add(new PairKey(o.productCode(), o.countryCode())));
          Map<PairKey, EffectivePrice.State> before = states(pairs, now);
          WriteReport report = new WriteReport();
          if (!productImports.isEmpty()) {
            writer.applyProducts(productImports, now, report);
          } else {
            writer.applyOffers(offerRows, report);
          }
          Map<PairKey, EffectivePrice.State> after = states(pairs, now);
          List<OfferPairChange> changes = changes(pairs, before, after);
          if (!changes.isEmpty()) {
            listener.offersChanged(changes);
          }
          store.succeed(jobId, summary(countKey, count, report, changes.size()));
          return new Outcome(true, report.changedRows(), changes.size());
        },
        outcome -> outcome.changedRows() > 0);
  }

  /** Effective state of each pair (INCLUDE_OUT_OF_STOCK basis); pairs without offers are absent. */
  private Map<PairKey, EffectivePrice.State> states(Set<PairKey> pairs, Instant now) {
    Map<PairKey, EffectivePrice.State> states = new LinkedHashMap<>();
    Map<String, Optional<Product>> byCode = new LinkedHashMap<>();
    for (PairKey pair : pairs) {
      Optional<Product> product = byCode.computeIfAbsent(pair.productCode(), products::findByCode);
      if (product.isEmpty()) {
        continue;
      }
      List<OfferSnapshot> snapshots =
          offers.findByProductIdAndCountryCode(product.get().getId(), pair.countryCode()).stream()
              .map(ImportService::snapshot)
              .toList();
      resolver
          .resolve(snapshots, StockFilter.INCLUDE_OUT_OF_STOCK, now)
          .price()
          .ifPresent(p -> states.put(pair, p.state()));
    }
    return states;
  }

  private List<OfferPairChange> changes(
      Set<PairKey> pairs,
      Map<PairKey, EffectivePrice.State> before,
      Map<PairKey, EffectivePrice.State> after) {
    Map<PairKey, OfferPairChange> changes =
        new TreeMap<>(
            Comparator.comparing(PairKey::productCode).thenComparing(PairKey::countryCode));
    for (PairKey pair : pairs) {
      EffectivePrice.State old = before.get(pair);
      EffectivePrice.State now = after.get(pair);
      if (!Objects.equals(old, now)) {
        UUID productId = products.findByCode(pair.productCode()).map(Product::getId).orElse(null);
        changes.put(
            pair, new OfferPairChange(productId, pair.productCode(), pair.countryCode(), old, now));
      }
    }
    return List.copyOf(changes.values());
  }

  private static OfferSnapshot snapshot(Offer offer) {
    return new OfferSnapshot(
        offer.getRetailer(),
        offer.getPrice(),
        offer.getCurrency(),
        offer.isInStock(),
        offer.getUrl(),
        offer.getCheckedAt());
  }

  /** Summary of a successful import; counts are summed over every written table. */
  static Map<String, Integer> summary(
      String countKey, int count, WriteReport report, int changedPairs) {
    Map<String, Integer> summary = new LinkedHashMap<>();
    summary.put(countKey, count);
    int inserted = 0;
    int updated = 0;
    int unchanged = 0;
    int deleted = 0;
    for (WriteReport.TableCounts table : report.tables().values()) {
      inserted += table.inserted();
      updated += table.updated();
      unchanged += table.unchanged();
      deleted += table.deleted();
    }
    summary.put("inserted", inserted);
    summary.put("updated", updated);
    summary.put("unchanged", unchanged);
    summary.put("deleted", deleted);
    summary.put("changedPairs", changedPairs);
    return summary;
  }

  /** Summary of a failed import: the rows or products read and 0 for every write count. */
  static Map<String, Integer> failedSummary(String countKey, int count) {
    Map<String, Integer> summary = new LinkedHashMap<>();
    summary.put(countKey, count);
    summary.put("inserted", 0);
    summary.put("updated", 0);
    summary.put("unchanged", 0);
    summary.put("deleted", 0);
    summary.put("changedPairs", 0);
    return summary;
  }

  /** Stops accepting imports; running imports get a grace period, then are interrupted. */
  @PreDestroy
  void shutdown() throws InterruptedException {
    executor.shutdown();
    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }
  }

  /** Result of one import run. */
  record Outcome(boolean succeeded, int changedRows, int changedPairs) {}

  private record PairKey(String productCode, String countryCode) {}
}
