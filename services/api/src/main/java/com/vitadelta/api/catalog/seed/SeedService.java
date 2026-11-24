package com.vitadelta.api.catalog.seed;

import com.vitadelta.api.catalog.CatalogVersion;
import com.vitadelta.api.catalog.CatalogVersionRepository;
import com.vitadelta.api.catalog.CatalogWriteTransaction;
import com.vitadelta.api.catalog.CatalogWriter;
import com.vitadelta.api.catalog.WriteReport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Loads the reference data, the synthetic fixtures and the real label snapshot into the database.
 *
 * <p>Everything is validated first ({@link SeedDataReader}); on any problem nothing is written. The
 * writes then run in <b>one</b> {@link CatalogWriteTransaction}: reference data, products and
 * offers commit together, and each run bumps {@code catalog_version} exactly once, also when no row
 * changed. All upserts use business keys (codes and offer natural keys), so a second run with the
 * same files keeps every id and changes no row.
 */
@Service
public class SeedService {

  /** Outcome of one seed run. */
  public record Result(WriteReport report, long catalogVersion) {}

  private final CatalogWriteTransaction writeTransaction;
  private final CatalogWriter writer;
  private final CatalogVersionRepository versions;
  private final Clock clock;

  public SeedService(
      CatalogWriteTransaction writeTransaction,
      CatalogWriter writer,
      CatalogVersionRepository versions,
      Clock clock) {
    this.writeTransaction = writeTransaction;
    this.writer = writer;
    this.versions = versions;
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
    WriteReport report =
        writeTransaction.execute(() -> {
          WriteReport r = new WriteReport();
          ReferenceData ref = data.reference();
          writer.upsertNutrients(ref.nutrients(), r);
          writer.upsertReferenceSets(ref.referenceSets(), r);
          writer.upsertUlSets(ref.ulSets(), r);
          writer.replaceUnitConversions(ref.conversions(), r);
          writer.replaceReferenceValues(ref.referenceValues(), r);
          writer.replaceUpperLimits(ref.upperLimits(), r);
          writer.upsertCountries(ref.countries(), r);
          writer.applyProducts(data.products(), now, r);
          writer.applyOffers(data.offers(), r);
          return r;
        });
    long version = versions.findById(CatalogVersion.ROW_ID).orElseThrow().getVersion();
    return new Result(report, version);
  }
}
