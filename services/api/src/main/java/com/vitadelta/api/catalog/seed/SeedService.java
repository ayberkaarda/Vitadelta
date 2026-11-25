package com.vitadelta.api.catalog.seed;

import com.vitadelta.api.catalog.CatalogVersion;
import com.vitadelta.api.catalog.CatalogVersionRepository;
import com.vitadelta.api.catalog.CatalogWriteTransaction;
import com.vitadelta.api.catalog.CatalogWriter;
import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.catalog.WriteReport;
import com.vitadelta.api.imports.OfferChangeListener;
import com.vitadelta.api.imports.OfferChangeListener.OfferPairChange;
import com.vitadelta.api.imports.OfferChangeTracker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Loads the reference data, the synthetic fixtures and the real label snapshot into the database.
 *
 * <p>Everything is validated first ({@link SeedDataReader}); on any problem nothing is written. The
 * writes then run in <b>one</b> {@link CatalogWriteTransaction}: reference data, products and
 * offers commit together. All upserts use business keys (codes and offer natural keys), so a second
 * run with the same files keeps every id and changes no row.
 *
 * <p>The same rule as for admin imports applies (section 5.3): {@code catalog_version} is bumped
 * exactly once when the run changed a catalog or reference row and not at all otherwise. The
 * (product, country) pairs whose effective offer state changed are passed to the {@link
 * OfferChangeListener} with the new version in the same transaction, so a seed that changes offers
 * emits {@code OFFERS_CHANGED} events like an import, and an unchanged seed emits none.
 */
@Service
public class SeedService {

  /**
   * Outcome of one seed run.
   *
   * @param catalogVersion the catalog version after the run
   * @param versionBumped whether the run bumped the version (it changed at least one row)
   * @param changedPairs (product, country) pairs whose effective offer state changed
   */
  public record Result(
      WriteReport report, long catalogVersion, boolean versionBumped, int changedPairs) {}

  private record Applied(WriteReport report, List<OfferPairChange> changes) {

    boolean changed() {
      return report.changedRows() > 0 || !changes.isEmpty();
    }
  }

  private final CatalogWriteTransaction writeTransaction;
  private final CatalogWriter writer;
  private final CatalogVersionRepository versions;
  private final OfferChangeTracker tracker;
  private final OfferChangeListener listener;
  private final Clock clock;

  public SeedService(
      CatalogWriteTransaction writeTransaction,
      CatalogWriter writer,
      CatalogVersionRepository versions,
      OfferChangeTracker tracker,
      OfferChangeListener listener,
      Clock clock) {
    this.writeTransaction = writeTransaction;
    this.writer = writer;
    this.versions = versions;
    this.tracker = tracker;
    this.listener = listener;
    this.clock = clock;
  }

  /**
   * Validates and loads the data directory.
   *
   * @throws SeedValidationException when any file is invalid; nothing is written then
   */
  public Result seed(Path dataDir) {
    Instant now = clock.instant();
    Set<String> stored = writer.productCodes();
    SeedData data = SeedDataReader.read(dataDir, stored::contains, now);
    List<OfferValues> offerRows = new ArrayList<>();
    data.products().forEach(p -> offerRows.addAll(p.offers()));
    offerRows.addAll(data.offers());
    Set<String> codes = new LinkedHashSet<>();
    data.products().forEach(p -> codes.add(p.details().code()));
    offerRows.forEach(o -> codes.add(o.productCode()));
    Applied applied =
        writeTransaction.execute(
            () -> {
              writer.lockProductsByCode(codes);
              OfferChangeTracker.Before before = tracker.capture(offerRows, now);
              WriteReport r = new WriteReport();
              ReferenceData ref = data.reference();
              writer.upsertNutrients(ref.nutrients(), r);
              writer.upsertForms(ref.formRows(), r);
              writer.upsertReferenceSets(ref.referenceSets(), r);
              writer.upsertUlSets(ref.ulSets(), r);
              writer.replaceUnitConversions(ref.conversions(), r);
              writer.replaceReferenceValues(ref.referenceValues(), r);
              writer.replaceUpperLimits(ref.upperLimits(), r);
              writer.upsertCountries(ref.countries(), r);
              writer.applyProducts(data.products(), now, r);
              writer.applyOffers(data.offers(), r);
              return new Applied(r, tracker.changes(before));
            },
            Applied::changed,
            (result, version) -> {
              if (!result.changes().isEmpty()) {
                listener.offersChanged(result.changes(), version);
              }
            });
    long version = versions.findById(CatalogVersion.ROW_ID).orElseThrow().getVersion();
    return new Result(applied.report(), version, applied.changed(), applied.changes().size());
  }
}
