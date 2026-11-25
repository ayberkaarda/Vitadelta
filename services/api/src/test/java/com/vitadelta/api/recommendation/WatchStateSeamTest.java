package com.vitadelta.api.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.common.watch.WatchState;
import com.vitadelta.api.common.watch.WatchStateReader;
import com.vitadelta.api.common.watch.WatchedAlert;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * The {@code saved} and {@code alerts} fields of ranked items and product details come from the
 * {@link WatchStateReader} seam: asked once per response for a signed-in caller with the page's
 * product ids and the response country, never for anonymous callers.
 */
@Import(WatchStateSeamTest.RecordingWatchConfig.class)
class WatchStateSeamTest extends RecommendationApiTestBase {

  /** One call of the seam. */
  record Call(UUID userId, String country, List<UUID> productIds) {}

  static final List<Call> CALLS = new CopyOnWriteArrayList<>();

  /** Products the seam reports as saved with one PRICE_DROP alert. */
  static final List<UUID> WATCHED = new CopyOnWriteArrayList<>();

  @TestConfiguration(proxyBeanMethods = false)
  static class RecordingWatchConfig {

    @Bean
    @Primary
    WatchStateReader recordingWatchStateReader() {
      return (UUID userId, String country, Collection<UUID> ids) -> {
        CALLS.add(new Call(userId, country, List.copyOf(ids)));
        Map<UUID, WatchState> states = new java.util.HashMap<>();
        for (UUID id : ids) {
          if (WATCHED.contains(id)) {
            states.put(id, new WatchState(true, List.of(alert(id, country))));
          }
        }
        return states;
      };
    }
  }

  static WatchedAlert alert(UUID productId, String country) {
    return new WatchedAlert(
        UUID.nameUUIDFromBytes(
            productId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        productId,
        country,
        "PRICE_DROP",
        new BigDecimal("5.00"),
        new BigDecimal("800.00"),
        "TRY",
        true,
        Instant.parse("2026-11-20T10:00:00Z"),
        null);
  }

  @BeforeEach
  void resetSeam() {
    CALLS.clear();
    WATCHED.clear();
  }

  @Test
  void signedInQueriesShowTheCallersWatchStatePerPageItem() {
    List<CatalogProduct> candidates = expectedCandidates("TR", StockFilter.IN_STOCK_ONLY);
    stubScore(candidates, StockFilter.IN_STOCK_ONLY);
    TestUser user = user(Role.USER);
    UUID watched = candidates.getFirst().id();
    WATCHED.add(watched);

    EntityExchangeResult<String> result =
        query(
            "{\"country\":\"TR\",\"displayCurrency\":\"EUR\",\"pageSize\":2}", user.token(), null);

    assertStatus(result, 200);
    ContractSchemas.assertConforms("RecommendationQueryResponse", result.getResponseBody());
    JsonNode body = json(result);
    List<UUID> page = itemIds(body);
    assertThat(CALLS).containsExactly(new Call(user.id(), "TR", page));
    for (JsonNode item : body.get("items")) {
      UUID id = UUID.fromString(item.get("productId").asString());
      if (id.equals(watched)) {
        assertThat(item.get("saved").asBoolean()).isTrue();
        JsonNode alert = item.get("alerts").get(0);
        assertThat(alert.get("trigger").asString()).isEqualTo("PRICE_DROP");
        // Baseline in the native currency, never converted to the display currency.
        assertThat(alert.get("baselinePrice").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(alert.get("baselineCurrency").asString()).isEqualTo("TRY");
      } else {
        assertThat(item.get("saved").asBoolean()).isFalse();
        assertThat(item.get("alerts").size()).isZero();
      }
    }
  }

  @Test
  void anonymousQueriesNeverAskTheSeam() {
    List<CatalogProduct> candidates = expectedCandidates("TR", StockFilter.IN_STOCK_ONLY);
    stubScore(candidates, StockFilter.IN_STOCK_ONLY);
    WATCHED.add(candidates.getFirst().id());
    JsonNode body = json(query("{\"country\":\"TR\"}", null, anonymousCookie()));
    assertThat(CALLS).isEmpty();
    List<Boolean> saved = new ArrayList<>();
    body.get("items").forEach(item -> saved.add(item.get("saved").asBoolean()));
    assertThat(saved).isNotEmpty().containsOnly(false);
  }

  @Test
  void productDetailsShowTheCallersWatchState() {
    UUID id = productId("GOLD-A");
    WATCHED.add(id);
    TestUser user = user(Role.USER);
    EntityExchangeResult<String> signedIn =
        get("/api/v1/products/" + id + "?country=TR", user.token());
    assertStatus(signedIn, 200);
    ContractSchemas.assertConforms("ProductDetailResponse", signedIn.getResponseBody());
    JsonNode product = json(signedIn).get("product");
    assertThat(product.get("saved").asBoolean()).isTrue();
    assertThat(product.get("alerts").get(0).get("productId").asString()).isEqualTo(id.toString());
    assertThat(CALLS).containsExactly(new Call(user.id(), "TR", List.of(id)));

    CALLS.clear();
    JsonNode anonymous = json(get("/api/v1/products/" + id + "?country=TR", null)).get("product");
    assertThat(anonymous.get("saved").asBoolean()).isFalse();
    assertThat(anonymous.get("alerts").size()).isZero();
    assertThat(CALLS).isEmpty();
  }
}
