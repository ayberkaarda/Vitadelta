package com.vitadelta.api.catalog.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.UlBasis;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.PriceResolution;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CatalogViews.ProductNutrientView;
import com.vitadelta.api.catalog.read.CatalogViews.UpperLimitView;
import com.vitadelta.api.catalog.seed.SeedService;
import com.vitadelta.api.catalog.unit.Unit;
import com.vitadelta.api.common.paging.PageQuery;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The scoring-input read model of a country (sections 5.5, 6.1, 6.3, 7.2): one snapshot, canonical
 * units, reference values and upper limits of the country's sets, and a market profile that does
 * not depend on offers or stock.
 */
@SpringBootTest
class CatalogReadServiceTest extends PostgresIntegrationTest {

  @Autowired private CatalogReadService catalog;
  @Autowired private EffectivePriceResolver resolver;
  @Autowired private SeedService seedService;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void seed() {
    seedService.seed(TestData.dataDir());
  }

  private Set<UUID> available(String country) {
    return new HashSet<>(
        jdbc.sql(
                "SELECT product_id FROM product_availability WHERE country_code = ?"
                    + " AND status = 'AVAILABLE'")
            .param(country)
            .query(UUID.class)
            .list());
  }

  @Test
  void theCountryCatalogIsOneDetachedSnapshot() {
    CountryCatalog tr = catalog.loadCountry("TR").orElseThrow();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(tr.catalogVersion())
        .isEqualTo(jdbc.sql("SELECT version FROM catalog_version").query(Long.class).single());
    assertThat(tr.country().currency()).isEqualTo("TRY");
    assertThat(tr.nutrients())
        .hasSize(jdbc.sql("SELECT count(*) FROM nutrient").query(Integer.class).single());
    assertThat(tr.products())
        .extracting(CatalogProduct::id)
        .containsExactlyInAnyOrderElementsOf(available("TR"));
    assertThat(tr.products().stream().map(p -> p.id().toString()).toList()).isSorted();
    assertThat(tr.referenceValues())
        .allSatisfy(r -> assertThat(r.amount()).isPositive())
        .anySatisfy(r -> {
          assertThat(r.nutrientCode()).isEqualTo("VIT_D");
          assertThat(r.amount()).isEqualByComparingTo("5");
          assertThat(r.unit()).isEqualTo(Unit.MCG);
        });
    assertThat(catalog.loadCountry("US").orElseThrow().referenceValues())
        .anySatisfy(r -> {
          assertThat(r.nutrientCode()).isEqualTo("VIT_D");
          assertThat(r.amount()).isEqualByComparingTo("20");
        });
    assertThat(catalog.loadCountry("QQ")).isEmpty();
    assertThat(catalog.marketProfile("QQ")).isEmpty();
  }

  @Test
  void aCountryAboveTheProductCeilingIsNotLoaded() {
    int inMarket = available("US").size();
    assertThat(catalog.loadCountry("US", inMarket).orElseThrow().products()).hasSize(inMarket);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> catalog.loadCountry("US", inMarket - 1))
        .isInstanceOfSatisfying(
            CatalogTooLargeException.class,
            e -> {
              assertThat(e.country()).isEqualTo("US");
              assertThat(e.products()).isEqualTo(inMarket);
              assertThat(e.limit()).isEqualTo(inMarket - 1);
            });
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(catalog.loadCountry("QQ", 1)).isEmpty();
  }

  @Test
  void idListsAreBoundInBatchesBelowTheDriverParameterLimit() {
    List<UUID> ids =
        java.util.stream.IntStream.range(0, 2500).mapToObj(i -> UUID.randomUUID()).toList();
    List<List<UUID>> batches = CatalogReadService.batches(ids);
    assertThat(batches).extracting(List::size).containsExactly(1000, 1000, 500);
    assertThat(batches.stream().flatMap(List::stream).toList()).isEqualTo(ids);
    assertThat(CatalogReadService.batches(List.of())).isEmpty();
  }

  @Test
  void upperLimitsOfTheCountrySetIncludeExemptRowsWithoutAmountUnitAndBasis() {
    List<UpperLimitView> efsa = catalog.loadCountry("TR").orElseThrow().upperLimits();
    UpperLimitView betaCarotene =
        efsa.stream()
            .filter(u -> u.nutrientCode().equals("VIT_A") && u.formCode().equals("BETA_CAROTENE"))
            .findFirst()
            .orElseThrow();
    assertThat(betaCarotene.exempt()).isTrue();
    assertThat(betaCarotene.amount()).isNull();
    assertThat(betaCarotene.unit()).isNull();
    assertThat(betaCarotene.basis()).isNull();
    assertThat(betaCarotene.note()).isNotBlank();
    UpperLimitView zinc =
        efsa.stream().filter(u -> u.nutrientCode().equals("ZINC")).findFirst().orElseThrow();
    assertThat(zinc.formCode()).isEqualTo("ANY");
    assertThat(zinc.amount()).isEqualByComparingTo("25");
    assertThat(zinc.unit()).isEqualTo(Unit.MG);
    assertThat(zinc.basis()).isEqualTo(UlBasis.TOTAL);
    assertThat(efsa).noneMatch(u -> u.nutrientCode().equals("IRON"));
    assertThat(catalog.loadCountry("US").orElseThrow().upperLimits())
        .anySatisfy(u -> {
          assertThat(u.nutrientCode()).isEqualTo("IRON");
          assertThat(u.amount()).isEqualByComparingTo("45");
        });
  }

  @Test
  void nutrientsAreConvertedOnceIntoCanonicalUnits() {
    CountryCatalog tr = catalog.loadCountry("TR").orElseThrow();
    Map<String, CatalogProduct> byCode =
        tr.products().stream().collect(Collectors.toMap(CatalogProduct::code, Function.identity()));
    Map<String, Unit> canonical =
        tr.nutrients().stream()
            .collect(
                Collectors.toMap(
                    CatalogViews.NutrientView::code, CatalogViews.NutrientView::canonicalUnit));
    for (CatalogProduct product : tr.products()) {
      for (ProductNutrientView n : product.nutrients()) {
        assertThat(n.unit()).as(product.code() + " " + n.code()).isEqualTo(canonical.get(n.code()));
      }
      assertThat(product.nutrients().stream().map(ProductNutrientView::code).toList()).isSorted();
    }
    BigDecimal storedIu =
        jdbc.sql(
                "SELECT pn.amount_per_serving FROM product_nutrient pn JOIN product p"
                    + " ON p.id = pn.product_id WHERE p.code = 'MV-UBIQ-200'"
                    + " AND pn.nutrient_code = 'VIT_E' AND pn.unit = 'IU'")
            .query(BigDecimal.class)
            .single();
    ProductNutrientView vitE =
        byCode.get("MV-UBIQ-200").nutrients().stream()
            .filter(n -> n.code().equals("VIT_E"))
            .findFirst()
            .orElseThrow();
    assertThat(vitE.amountPerServing())
        .isEqualByComparingTo(storedIu.multiply(new BigDecimal("0.67")));
    assertThat(vitE.formCode()).isEqualTo("D_ALPHA_TOCOPHEROL");
    assertThat(vitE.conversionAssumed()).isFalse();
    assertThat(vitE.amountPerDay(new BigDecimal("2")))
        .isEqualByComparingTo(vitE.amountPerServing().multiply(new BigDecimal("2")));
  }

  @Test
  void theMarketProfileDoesNotDependOnOffersOrStock() {
    CountryCatalog tr = catalog.loadCountry("TR").orElseThrow();
    MarketProfile profile = tr.marketProfile();
    assertThat(profile.catalogVersion()).isEqualTo(tr.catalogVersion());
    assertThat(profile.countryCode()).isEqualTo("TR");
    assertThat(profile.products())
        .extracting(MarketProfile.Product::productId)
        .containsExactlyInAnyOrderElementsOf(available("TR"));
    long excludedInStockOnly =
        tr.products().stream()
            .filter(p -> !resolver.resolve(p.offers(), StockFilter.IN_STOCK_ONLY).isPriced())
            .count();
    assertThat(excludedInStockOnly).isPositive();
    assertThat(
            tr.products().stream()
                .map(p -> resolver.resolve(p.offers(), StockFilter.IN_STOCK_ONLY))
                .filter(r -> r.exclusion() == PriceResolution.Exclusion.NO_STOCK)
                .count())
        .isEqualTo(excludedInStockOnly);
    CatalogProduct gold =
        tr.products().stream().filter(p -> p.code().equals("GOLD-A")).findFirst().orElseThrow();
    MarketProfile.Product goldProfile =
        profile.products().stream()
            .filter(p -> p.productId().equals(gold.id()))
            .findFirst()
            .orElseThrow();
    assertThat(goldProfile.nutrients())
        .extracting(MarketProfile.Nutrient::code)
        .containsExactly("MAGNESIUM", "VIT_B12", "VIT_C", "VIT_D", "ZINC");
    assertThat(goldProfile.nutrients().get(2).amountPerDay()).isEqualByComparingTo("300");
    assertThat(catalog.marketProfile("TR").orElseThrow()).isEqualTo(profile);
    List<CatalogProduct> duplicated = List.of(gold, gold);
    assertThat(MarketProfile.of(1, "TR", duplicated).products()).hasSize(1);
  }

  @Test
  void searchAndDetailReadTheSameRows() {
    var page = catalog.searchProducts("TR", "Gold", PageQuery.of(1, 1));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).extracting(CatalogProduct::code).containsExactly("GOLD-A");
    var detail = catalog.product(page.items().getFirst().id(), "TR").orElseThrow();
    assertThat(detail.inMarket()).isTrue();
    assertThat(detail.product()).isEqualTo(page.items().getFirst());
    var elsewhere = catalog.product(page.items().getFirst().id(), "DE").orElseThrow();
    assertThat(elsewhere.inMarket()).isFalse();
    assertThat(elsewhere.availabilityStatus()).isNull();
    assertThat(elsewhere.product().offers()).isEmpty();
    assertThat(catalog.product(UUID.randomUUID(), "TR")).isEmpty();
    assertThat(catalog.catalogVersion()).isPositive();
    assertThat(catalog.countries())
        .extracting(CatalogViews.CountryView::code)
        .contains("DE", "GB", "TR", "US");
    assertThat(catalog.country("TR")).isPresent();
  }
}
