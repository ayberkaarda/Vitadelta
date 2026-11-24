package com.vitadelta.api.imports;

import com.vitadelta.api.config.properties.CatalogProperties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans of the import pipeline: the bounded import executor and the default seams. */
@Configuration(proxyBeanMethods = false)
public class ImportSeamConfig {

  /** Bean name of the import executor. */
  public static final String IMPORT_EXECUTOR = "catalogImportExecutor";

  /** Registered only when no other {@link OfferChangeListener} exists (Phase 5 replaces it). */
  @Bean
  @ConditionalOnMissingBean(OfferChangeListener.class)
  OfferChangeListener noOfferChangeListener() {
    return changes -> {};
  }

  /**
   * Bounded executor of the admin imports: {@code vitadelta.catalog.import-threads} workers and a
   * queue of {@code vitadelta.catalog.import-queue-capacity} waiting imports; a full queue rejects
   * the new import. Queued imports hold only their uploaded bytes, never a database connection.
   */
  @Bean(name = IMPORT_EXECUTOR, destroyMethod = "")
  ThreadPoolExecutor catalogImportExecutor(CatalogProperties properties) {
    AtomicInteger counter = new AtomicInteger();
    return new ThreadPoolExecutor(
        properties.importThreads(),
        properties.importThreads(),
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(properties.importQueueCapacity()),
        runnable -> {
          Thread thread = new Thread(runnable, "vitadelta-import-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }
}
