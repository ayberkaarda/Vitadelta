package com.vitadelta.api.recommendation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.vitadelta.api.catalog.price.EffectivePriceResolver;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogReadService;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.catalog.read.CountryCatalog;
import com.vitadelta.api.catalog.web.CatalogApiTest;
import com.vitadelta.api.entitlement.DisplayPriceConverter;
import com.vitadelta.api.entitlement.NativePriceConverter;
import com.vitadelta.api.entitlement.PriceConversion;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.identity.UserAccount;
import com.vitadelta.api.identity.UserStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base of the recommendation API tests: the catalog test server (seeded PostgreSQL container) with
 * the scoring service replaced by a WireMock server, a display-currency converter with a fixed TRY
 * to EUR rate (the seam the FX module will fill; USD stays without a rate), and helpers that build
 * scoring answers for the candidates the API is expected to send.
 *
 * <p>Before each test the WireMock stubs and journal, the recommendation caches and the circuit
 * breaker are reset.
 */
@Import(RecommendationApiTestBase.FixedRateConfig.class)
public abstract class RecommendationApiTestBase extends CatalogApiTest {

  /** Stored-style rate of the test converter (8 decimals). */
  protected static final BigDecimal TRY_EUR = new BigDecimal("0.02613457");

  /** Port of the scoring stub; fixed so that a test can stop the stub and start a new one. */
  private static final int SCORING_PORT = freePort();

  private static WireMockServer server = new WireMockServer(options().port(SCORING_PORT));

  static {
    server.start();
  }

  /** The scoring stub currently running. */
  protected static WireMockServer scoring() {
    return server;
  }

  /** Stops the stub (connections are refused) and starts a new one on the same port afterwards. */
  protected static void withScoringStopped(Runnable action) {
    server.stop();
    try {
      action.run();
    } finally {
      server = new WireMockServer(options().port(SCORING_PORT));
      server.start();
    }
  }

  private static int freePort() {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void scoringProperties(DynamicPropertyRegistry registry) {
    registry.add("vitadelta.scoring.base-url", () -> "http://localhost:" + scoring().port());
  }

  /** TRY to EUR at {@link #TRY_EUR}; every other pair as the default converter answers it. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedRateConfig {

    @Bean
    @Primary
    DisplayPriceConverter fixedRateConverter() {
      NativePriceConverter fallback = new NativePriceConverter();
      return (nativeCurrency, displayCurrency) -> {
        if (nativeCurrency.equals("TRY") && displayCurrency.equals("EUR")) {
          return new PriceConversion(
              "TRY",
              "EUR",
              new PriceConversion.Fx(
                  "TRY",
                  "EUR",
                  TRY_EUR,
                  LocalDate.of(2026, 11, 23),
                  "FRANKFURTER",
                  false,
                  PriceConversion.OK));
        }
        return fallback.conversion(nativeCurrency, displayCurrency);
      };
    }
  }

  @Autowired protected RecommendationCaches caches;
  @Autowired protected CircuitBreakerRegistry circuitBreakers;
  @Autowired protected CatalogReadService catalog;
  @Autowired protected EffectivePriceResolver prices;

  @BeforeEach
  void resetScoring() {
    scoring().resetAll();
    caches.clear();
    circuitBreakers.circuitBreaker(ScoringGateway.INSTANCE).reset();
  }

  // ---------- Principals ----------

  /** A signed-in user and its bearer token. */
  protected record TestUser(UUID id, String token) {}

  /** A new verified user with an ACTIVE FREE subscription (removed after the test). */
  protected TestUser user(Role role) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          users.insertIfAbsent(
              new UserAccount(
                  id,
                  "r." + id.toString().substring(0, 13) + "@" + DOMAIN,
                  "synthetic-password-hash",
                  now,
                  role,
                  UserStatus.ACTIVE,
                  null,
                  null,
                  "en",
                  true,
                  null,
                  now,
                  now));
          provisioning.provisionFreeSubscription(id, now);
        });
    return new TestUser(id, accessTokens.issue(id, role, true).value());
  }

  /** A new verified user whose ACTIVE subscription is PRO for 30 days. */
  protected TestUser proUser() {
    TestUser user = user(Role.USER);
    jdbc.sql(
            "UPDATE subscription SET status = 'CANCELED', canceled_at = now()"
                + " WHERE user_id = ? AND status = 'ACTIVE'")
        .param(user.id())
        .update();
    jdbc.sql(
            "INSERT INTO subscription (id, user_id, plan_code, status, source, started_at,"
                + " current_period_end) VALUES (gen_random_uuid(), ?, 'PRO', 'ACTIVE', 'MANUAL',"
                + " now(), now() + interval '30 days')")
        .param(user.id())
        .update();
    return user;
  }

  // ---------- HTTP ----------

  /** POST with the test client IP, an optional bearer token and an optional cookie header. */
  protected EntityExchangeResult<String> postJson(
      String path, String body, String token, String cookie) {
    return client
        .post()
        .uri(URI.create("http://localhost:" + port + path))
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> {
          h.set("X-Forwarded-For", ip);
          if (token != null) {
            h.setBearerAuth(token);
          }
          if (cookie != null) {
            h.set("Cookie", cookie);
          }
        })
        .body(body)
        .exchange()
        .returnResult(String.class);
  }

  protected EntityExchangeResult<String> query(String body, String token, String cookie) {
    return postJson("/api/v1/recommendations/query", body, token, cookie);
  }

  protected EntityExchangeResult<String> compare(String body, String token) {
    return postJson("/api/v1/recommendations/compare", body, token, null);
  }

  /** A new anonymous identity: the {@code vd_anon} cookie pair from {@code GET /session/quota}. */
  protected String anonymousCookie() {
    EntityExchangeResult<String> result = get("/api/v1/session/quota", null);
    String header = result.getResponseHeaders().getFirst("Set-Cookie");
    if (header == null) {
      throw new IllegalStateException("no vd_anon cookie was issued");
    }
    return header.substring(0, header.indexOf(';'));
  }

  // ---------- Scoring stubs ----------

  /** Candidates the API sends for {@code country} under {@code filter}, in id order. */
  protected List<CatalogProduct> expectedCandidates(String country, StockFilter filter) {
    CountryCatalog snapshot = catalog.loadCountry(country).orElseThrow();
    return snapshot.products().stream()
        .filter(p -> prices.resolve(p.offers(), filter).isPriced())
        .toList();
  }

  /** Stubs {@code POST /internal/v1/score} with a synthetic ranking of {@code candidates}. */
  protected ObjectNode stubScore(List<CatalogProduct> candidates, StockFilter filter) {
    ObjectNode answer = scoreAnswer(candidates, filter);
    scoring()
        .stubFor(
            post(urlEqualTo("/internal/v1/score"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(answer.toString())));
    return answer;
  }

  /**
   * A score answer for {@code candidates}: content score {@code 1 + (i mod 7) / 3} (unrounded),
   * cost as section 6.2 computes it, value = content / cost, ranked by value descending (ties by
   * id), one breakdown row per nutrient and {@code m_ref = 2/3}.
   */
  protected ObjectNode scoreAnswer(List<CatalogProduct> candidates, StockFilter filter) {
    record Scored(CatalogProduct product, double price, double cost, double content) {}
    List<Scored> scored = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      CatalogProduct p = candidates.get(i);
      double price = prices.resolve(p.offers(), filter).price().orElseThrow().price().doubleValue();
      double servings = (double) p.unitsPerContainer() / (double) p.unitsPerServing();
      double days = servings / p.servingsPerDay().doubleValue();
      scored.add(new Scored(p, price, price / days, 1.0 + (i % 7) / 3.0));
    }
    scored.sort(
        Comparator.comparingDouble((Scored s) -> -s.content() / s.cost())
            .thenComparing(s -> s.product().id().toString()));
    double maxContent = scored.stream().mapToDouble(Scored::content).max().orElse(1);
    double maxValue = scored.stream().mapToDouble(s -> s.content() / s.cost()).max().orElse(1);
    ObjectNode answer = JSON.createObjectNode();
    ArrayNode items = answer.putArray("items");
    int rank = 1;
    for (Scored s : scored) {
      CatalogProduct p = s.product();
      ObjectNode item = items.addObject();
      item.put("product_id", p.id().toString());
      item.put("rank", rank++);
      ObjectNode cost = item.putObject("cost");
      cost.put("effective_price", s.price());
      cost.put("currency", "TRY");
      double servings = (double) p.unitsPerContainer() / (double) p.unitsPerServing();
      cost.put("servings_per_container", servings);
      cost.put("days_supply", servings / p.servingsPerDay().doubleValue());
      cost.put("cost_per_day", s.cost());
      cost.put("price_stale", false);
      cost.put("out_of_stock", false);
      item.put("content_score", s.content());
      item.put("content_score_display", 100.0 * s.content() / maxContent);
      item.put("value_score", s.content() / s.cost());
      item.put("value_score_display", 100.0 * (s.content() / s.cost()) / maxValue);
      item.putNull("custom_score");
      item.putArray("flags");
      ArrayNode breakdown = item.putArray("breakdown");
      p.nutrients()
          .forEach(n -> {
            ObjectNode row = breakdown.addObject();
            double perDay = n.amountPerDay(p.servingsPerDay()).doubleValue();
            row.put("code", n.code());
            row.put("category", n.category().name());
            row.put("amount_per_day", perDay);
            row.put("ul_amount_per_day", perDay);
            row.put("unit", n.unit().symbol());
            row.put("reference", 3.0);
            row.put("reference_source", "REGULATORY");
            row.put("coverage_ratio", perDay / 3.0);
            row.put("utility", 1.0 / 3.0);
            row.put("weight", 1.0);
            row.put("contribution", 1.0 / 3.0);
            row.put("capped_at_ul", false);
            row.put("capped_at_u_max", false);
            row.put("ul_below_reference", false);
            row.put("below_presence_floor", false);
          });
      ArrayNode top = item.putArray("top_contributors");
      for (int i = 0; i < Math.min(5, breakdown.size()); i++) {
        top.add(breakdown.get(i).deepCopy());
      }
    }
    ObjectNode meta = answer.putObject("meta");
    meta.put("m_ref", 2.0 / 3.0);
    meta.put("market_size", candidates.size());
    ObjectNode excluded = meta.putObject("excluded");
    excluded.put("required_nutrients", 0);
    excluded.put("exceeds_ul", 0);
    return answer;
  }

  /** The scoring requests received for {@code path} ({@code score} or {@code compare}). */
  protected List<LoggedRequest> scoringRequests(String path) {
    return scoring().findAll(postRequestedFor(urlEqualTo("/internal/v1/" + path)));
  }

  /** The body of the only scoring request received for {@code path}. */
  protected JsonNode onlyScoringRequest(String path) {
    List<LoggedRequest> requests = scoringRequests(path);
    if (requests.size() != 1) {
      throw new AssertionError("expected one " + path + " request, got " + requests.size());
    }
    return JSON.readTree(requests.getFirst().getBodyAsString());
  }

  /** Request ids received by the scoring stub for {@code path}. */
  protected List<String> scoringRequestIds(String path) {
    return scoring()
        .findAll(
            postRequestedFor(urlEqualTo("/internal/v1/" + path))
                .withHeader("X-Request-Id", matching(".+")))
        .stream()
        .map(r -> r.getHeader("X-Request-Id"))
        .toList();
  }

  protected int queriesUsed(String cookie) {
    return json(getWithCookie("/api/v1/session/quota", cookie)).get("queriesUsed").asInt();
  }

  protected EntityExchangeResult<String> getWithCookie(String path, String cookie) {
    return client
        .get()
        .uri(URI.create("http://localhost:" + port + path))
        .headers(h -> {
          h.set("X-Forwarded-For", ip);
          h.set("Cookie", cookie);
        })
        .exchange()
        .returnResult(String.class);
  }

  /** {@code ids} of the items of a query or compare body. */
  protected static List<UUID> itemIds(JsonNode body) {
    List<UUID> ids = new ArrayList<>();
    body.get("items").forEach(item -> ids.add(UUID.fromString(item.get("productId").asString())));
    return ids;
  }

  /** A JSON object literal from alternating keys and values. */
  protected static String body(Map<String, ?> fields) {
    return JSON.writeValueAsString(fields);
  }
}
