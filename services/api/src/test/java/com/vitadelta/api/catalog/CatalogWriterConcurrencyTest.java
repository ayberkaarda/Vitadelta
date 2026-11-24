package com.vitadelta.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.ReferenceValues.CountryRow;
import com.vitadelta.api.catalog.ReferenceValues.NutrientRow;
import com.vitadelta.api.catalog.ReferenceValues.SetRow;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two concurrent write transactions that replace the same set (offers of one pair, nutrients of one
 * product) must serialise: the second one sees the first one's result and the final set is exactly
 * the set of the transaction that committed last, never a merge of both.
 */
@SpringBootTest
class CatalogWriterConcurrencyTest extends PostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

  @Autowired private CatalogWriteTransaction writeTransaction;
  @Autowired private CatalogWriter writer;
  @Autowired private JdbcTemplate jdbc;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);
  private String code;
  private ProductDetails details;

  @BeforeEach
  void createProduct() {
    code = "CONC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    details =
        new ProductDetails(
            code,
            "Fixture Labs",
            "Concurrency",
            DosageForm.TABLET,
            30,
            1,
            BigDecimal.ONE,
            null,
            "test",
            null,
            null,
            true);
    writeTransaction.execute(() -> {
      WriteReport report = new WriteReport();
      writer.upsertNutrients(
          List.of(
              new NutrientRow("CONC_N1", "N1", NutrientCategory.OTHER, Unit.MG, null, 1),
              new NutrientRow("CONC_N2", "N2", NutrientCategory.OTHER, Unit.MG, null, 2)),
          report);
      writer.upsertReferenceSets(List.of(new SetRow("CONC_REF", "Ref", "c")), report);
      writer.upsertUlSets(List.of(new SetRow("CONC_UL", "UL", "c")), report);
      writer.upsertCountries(
          List.of(new CountryRow("QC", "Conc", "QCD", "CONC_REF", "CONC_UL", "en")), report);
      writer.applyProducts(
          List.of(new ProductImport(details, List.of(), List.of(), List.of())), NOW, report);
      return null;
    });
  }

  @AfterEach
  void shutdown() {
    pool.shutdownNow();
  }

  private OfferValues offer(String retailer) {
    return new OfferValues(
        code,
        "QC",
        retailer,
        BigDecimal.TEN,
        "QCD",
        true,
        "https://a.example/" + retailer.replace(' ', '-'),
        NOW);
  }

  private ProductImport withNutrient(String nutrient) {
    return new ProductImport(
        details,
        List.of(
            new ProductNutrientValues(nutrient, BigDecimal.ONE, null, Unit.MG, "UNKNOWN", null)),
        List.of(),
        List.of());
  }

  /**
   * Runs {@code first} and keeps its transaction open after its writes, starts {@code second},
   * gives it two seconds (it either finishes or waits for the first transaction's lock), then lets
   * the first transaction commit.
   */
  private void runOverlapping(Consumer<WriteReport> first, Consumer<WriteReport> second)
      throws Exception {
    CountDownLatch firstWrote = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    Future<?> one =
        pool.submit(() ->
            writeTransaction.execute(() -> {
              first.accept(new WriteReport());
              firstWrote.countDown();
              try {
                releaseFirst.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return null;
            }));
    assertThat(firstWrote.await(30, TimeUnit.SECONDS)).isTrue();
    Future<?> two =
        pool.submit(() ->
            writeTransaction.execute(() -> {
              second.accept(new WriteReport());
              return null;
            }));
    try {
      two.get(2, TimeUnit.SECONDS);
      secondBlocked = false;
    } catch (TimeoutException e) {
      secondBlocked = true;
    }
    releaseFirst.countDown();
    one.get(30, TimeUnit.SECONDS);
    two.get(30, TimeUnit.SECONDS);
  }

  /** Whether the second transaction of the last {@link #runOverlapping} call had to wait. */
  private boolean secondBlocked;

  private void assertSecondWaited() {
    assertThat(secondBlocked).as("second transaction waited for the first").isTrue();
  }

  @Test
  void concurrentOfferReplaceSetsDoNotMerge() throws Exception {
    runOverlapping(
        r -> writer.applyOffers(List.of(offer("Shop A")), r),
        r -> writer.applyOffers(List.of(offer("Shop B")), r));
    assertThat(
            jdbc.queryForList(
                "SELECT o.retailer FROM offer o JOIN product p ON p.id = o.product_id"
                    + " WHERE p.code = ? ORDER BY o.retailer",
                String.class,
                code))
        .containsExactly("Shop B");
    assertSecondWaited();
  }

  @Test
  void concurrentNutrientReplaceSetsDoNotMerge() throws Exception {
    runOverlapping(
        r -> writer.applyProducts(List.of(withNutrient("CONC_N1")), NOW, r),
        r -> writer.applyProducts(List.of(withNutrient("CONC_N2")), NOW, r));
    assertThat(
            jdbc.queryForList(
                "SELECT n.nutrient_code FROM product_nutrient n JOIN product p"
                    + " ON p.id = n.product_id WHERE p.code = ? ORDER BY n.nutrient_code",
                String.class,
                code))
        .containsExactly("CONC_N2");
    assertSecondWaited();
  }
}
