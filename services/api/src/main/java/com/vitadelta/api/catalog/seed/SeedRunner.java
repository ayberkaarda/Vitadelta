package com.vitadelta.api.catalog.seed;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the seed when the {@code seed} profile is active. A validation error ends the process with
 * exit code {@value SeedValidationException#EXIT_CODE} and every row-level message in the log; any
 * other failure ends it with a non-zero code as well.
 */
@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(SeedRunner.class);

  private final SeedService seedService;
  private final SeedProperties properties;

  public SeedRunner(SeedService seedService, SeedProperties properties) {
    this.seedService = seedService;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (properties.dataDir() == null) {
      throw new IllegalStateException("vitadelta.seed.data-dir is not set");
    }
    LOG.info("seed: loading data from {}", properties.dataDir());
    try {
      SeedService.Result result = seedService.seed(properties.dataDir());
      for (Map.Entry<String, com.vitadelta.api.catalog.WriteReport.TableCounts> table :
          result.report().tables().entrySet()) {
        LOG.info("seed: {} {}", table.getKey(), table.getValue());
      }
      LOG.info(
          "seed: done, catalog_version={} ({}), {} changed offer pair(s)",
          result.catalogVersion(),
          result.versionBumped() ? "bumped" : "unchanged, no row changed",
          result.changedPairs());
    } catch (SeedValidationException e) {
      e.problems().forEach(p -> LOG.error("seed: {}", p.describe()));
      LOG.error("seed: aborted, {} problem(s), nothing was written", e.problems().size());
      throw e;
    }
  }
}
