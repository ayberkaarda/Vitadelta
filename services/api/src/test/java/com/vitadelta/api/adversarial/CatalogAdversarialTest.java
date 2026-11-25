package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Req;
import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.catalog.price.StockFilter;
import com.vitadelta.api.catalog.read.CatalogViews.CatalogProduct;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.recommendation.RecommendationApiTestBase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base of the adversarial tests of the catalog, import and recommendation endpoints (project
 * specification section 15.7, Phase 4 targets): the seeded catalog server of the recommendation
 * tests with the scoring service replaced by a WireMock stub, plus the raw {@link AttackClient} for
 * requests that a well-behaved client would never send.
 *
 * <p>Products created by these tests use the code prefix {@value #PREFIX}; they are removed after
 * every test and the catalog version is bumped so that no cached ranking keeps them.
 */
abstract class CatalogAdversarialTest extends RecommendationApiTestBase {

  static final String API = "/api/v1";
  static final String PREFIX = "ADVC-";
  static final String TR_QUERY = "{\"country\":\"TR\"}";
  static final String CSV_HEADER =
      "product_code,country_code,retailer,price,currency,in_stock,url,checked_at\n";

  AttackClient raw;

  @BeforeEach
  void setUpRawClient() {
    raw = new AttackClient(port);
  }

  @AfterEach
  void removeAdversarialProducts() {
    int removed = jdbc.sql("DELETE FROM product WHERE code LIKE ?").param(PREFIX + "%").update();
    if (removed > 0) {
      jdbc.sql("UPDATE catalog_version SET version = version + 1, bumped_at = now() WHERE id = 1")
          .update();
    }
  }

  // ---------- Raw requests ----------

  /** A raw request from this test's client IP. */
  Req req(String method, String path) {
    return raw.req(method, path).ip(ip);
  }

  /** {@code POST /recommendations/query} with an optional bearer token and cookie pair. */
  Resp rawQuery(String body, String token, String cookiePair) {
    Req request = req("POST", API + "/recommendations/query").bearer(token).json(body);
    if (cookiePair != null) {
      request.header("Cookie", cookiePair);
    }
    return request.send();
  }

  Resp rawCompare(String body, String token) {
    return req("POST", API + "/recommendations/compare").bearer(token).json(body).send();
  }

  Resp rawGet(String path, String token) {
    return req("GET", path).bearer(token).send();
  }

  /** The {@code queries_used} counter stored for a {@code vd_anon=...} cookie pair. */
  int storedQueriesUsed(String cookiePair) {
    return queriesUsed(cookiePair);
  }

  // ---------- Catalog helpers ----------

  List<CatalogProduct> inStock(String country) {
    return expectedCandidates(country, StockFilter.IN_STOCK_ONLY);
  }

  /** Stubs the scoring service with a ranking of the in-stock TR candidates. */
  ObjectNode stubTr() {
    return stubScore(inStock("TR"), StockFilter.IN_STOCK_ONLY);
  }

  /** Product ids of a scoring answer, in rank order. */
  static List<String> rankedIds(ObjectNode answer) {
    List<String> ids = new ArrayList<>();
    answer.get("items").forEach(i -> ids.add(i.get("product_id").asString()));
    return ids;
  }

  long productRows() {
    return jdbc.sql("SELECT count(*) FROM product").query(Long.class).single();
  }

  long offerRows() {
    return jdbc.sql("SELECT count(*) FROM offer").query(Long.class).single();
  }

  String admin() {
    return user(Role.ADMIN).token();
  }

  // ---------- Imports ----------

  Resp importProducts(String document, String token) {
    return req("POST", API + "/admin/imports/products").bearer(token).json(document).send();
  }

  Resp importProductsBytes(byte[] document, String token) {
    return req("POST", API + "/admin/imports/products")
        .bearer(token)
        .body(document, "application/json")
        .send();
  }

  /** One part of a multipart body. */
  record Part(String name, String filename, String contentType, byte[] content) {}

  static final String BOUNDARY = "adv-boundary-7f3c9a";

  static byte[] multipart(List<Part> parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Part part : parts) {
      StringBuilder head = new StringBuilder();
      head.append("--").append(BOUNDARY).append("\r\n");
      head.append("Content-Disposition: form-data; name=\"").append(part.name()).append('"');
      if (part.filename() != null) {
        head.append("; filename=\"").append(part.filename()).append('"');
      }
      head.append("\r\n");
      if (part.contentType() != null) {
        head.append("Content-Type: ").append(part.contentType()).append("\r\n");
      }
      head.append("\r\n");
      out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
      out.writeBytes(part.content());
      out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  Resp importOffersMultipart(byte[] multipartBody, String token, boolean chunked) {
    Req request =
        req("POST", API + "/admin/imports/offers")
            .bearer(token)
            .body(multipartBody, "multipart/form-data; boundary=" + BOUNDARY);
    return (chunked ? request.chunked() : request).send();
  }

  Resp importOffers(String csv, String token) {
    return importOffers(csv.getBytes(StandardCharsets.UTF_8), token);
  }

  Resp importOffers(byte[] csv, String token) {
    return importOffersMultipart(
        multipart(List.of(new Part("file", "offers.csv", "text/csv", csv))), token, false);
  }

  /** Waits until the job of a {@code 202} response has finished and returns its detail. */
  JsonNode awaitJob(Resp accepted, String token) {
    assertThat(accepted.status()).as(accepted.toString()).isEqualTo(202);
    String id = accepted.json().get("id").asString();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
    while (true) {
      Resp job = rawGet(API + "/admin/imports/" + id, token);
      assertThat(job.status()).as(job.toString()).isEqualTo(200);
      JsonNode body = job.json();
      if (!body.get("status").asString().equals("RUNNING")) {
        return body;
      }
      assertThat(Instant.now()).as("import job %s still running", id).isBefore(deadline);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  /** {@code rowNumber:code} of every error of a job. */
  static List<String> jobErrors(JsonNode job) {
    List<String> errors = new ArrayList<>();
    job.get("errors")
        .forEach(e -> errors.add(e.get("rowNumber").asInt() + ":" + e.get("code").asString()));
    return errors;
  }

  /** A unique product code with this suite's prefix. */
  static String code(String tag) {
    return PREFIX
        + tag
        + "-"
        + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  /** A synthetic products document with one product available in TR. */
  static String productDocument(String code, String brand, String name, String offersJson) {
    ObjectNode root = AttackClient.JSON.createObjectNode();
    root.put("schema_version", 1);
    ObjectNode product = root.putArray("products").addObject();
    product.put("code", code);
    product.put("brand", brand);
    product.put("name", name);
    product.put("dosage_form", "TABLET");
    product.put("units_per_container", 30);
    product.put("units_per_serving", 1);
    product.put("servings_per_day", 1);
    product.put("source", "Synthetic adversarial test product");
    product.put("synthetic", true);
    ObjectNode nutrient = product.putArray("nutrients").addObject();
    nutrient.put("nutrient_code", "VIT_C");
    nutrient.put("amount_per_serving", 100);
    nutrient.put("unit", "mg");
    ObjectNode availability = product.putArray("availability").addObject();
    availability.put("country_code", "TR");
    availability.put("status", "AVAILABLE");
    String json = root.toString();
    return json.substring(0, json.length() - 3) + ",\"offers\":[" + offersJson + "]}]}";
  }

  /** One offer of a products document; {@code price} is inserted as raw JSON text. */
  static String offerJson(String retailer, String price, String currency, String url) {
    ObjectNode offer = AttackClient.JSON.createObjectNode();
    offer.put("country_code", "TR");
    offer.put("retailer", retailer);
    offer.put("currency", currency);
    offer.put("in_stock", true);
    offer.put("url", url);
    offer.put("checked_at", "2026-11-20T09:00:00Z");
    return "{\"price\":" + price + "," + offer.toString().substring(1);
  }

  /** A valid TR offer of a products document. */
  static String validOffer(String retailer) {
    return offerJson(retailer, "120.00", "TRY", "https://shop.example.com/adv");
  }
}
