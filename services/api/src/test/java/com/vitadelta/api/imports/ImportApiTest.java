package com.vitadelta.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.web.CatalogApiTest;
import com.vitadelta.api.identity.ContractSchemas;
import com.vitadelta.api.identity.Role;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

/**
 * Admin import endpoints (sections 5.3, 8.9, 9.1): RBAC, asynchronous jobs, all-or-nothing
 * validation with row errors, upserts by business key, replace-set per pair, the catalog version
 * rule (one bump per import that changes data, none otherwise), changed pairs, size caps and
 * restart recovery.
 */
@Import(ImportApiTest.ListenerConfig.class)
class ImportApiTest extends CatalogApiTest {

  private static final String PREFIX = "IMPT-";
  private static final String HEADER =
      "product_code,country_code,retailer,price,currency,in_stock,url,checked_at\n";

  /** Records the changed pairs; can block the import worker to fill the queue. */
  static class RecordingListener implements OfferChangeListener {
    final List<List<OfferPairChange>> calls = new CopyOnWriteArrayList<>();
    final AtomicReference<CountDownLatch> gate = new AtomicReference<>();
    final AtomicReference<CountDownLatch> entered = new AtomicReference<>();

    @Override
    public void offersChanged(List<OfferPairChange> changes) {
      calls.add(changes);
      CountDownLatch in = entered.get();
      if (in != null) {
        in.countDown();
      }
      CountDownLatch wait = gate.get();
      if (wait != null) {
        try {
          wait.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ListenerConfig {
    @Bean
    @org.springframework.context.annotation.Primary
    RecordingListener recordingOfferChangeListener() {
      return new RecordingListener();
    }
  }

  @Autowired private RecordingListener listener;
  @Autowired private ImportJobRecovery recovery;
  @Autowired private com.vitadelta.api.common.jobs.JobLock jobLock;

  @AfterEach
  void removeImportTestRows() {
    listener.calls.clear();
    listener.gate.set(null);
    listener.entered.set(null);
    jdbc.sql("DELETE FROM product WHERE code LIKE ?").param(PREFIX + "%").update();
  }

  private String admin() {
    return token(Role.ADMIN);
  }

  private EntityExchangeResult<String> postProducts(String body, String token) {
    return client
        .post()
        .uri("/api/v1/admin/imports/products")
        .headers(h -> {
          if (token != null) {
            h.setBearerAuth(token);
          }
        })
        .contentType(MediaType.APPLICATION_JSON)
        .body(body.getBytes(StandardCharsets.UTF_8))
        .exchange()
        .returnResult(String.class);
  }

  private EntityExchangeResult<String> postOffers(byte[] csv, String token) {
    org.springframework.util.LinkedMultiValueMap<String, Object> parts =
        new org.springframework.util.LinkedMultiValueMap<>();
    HttpHeaders partHeaders = new HttpHeaders();
    partHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    parts.add(
        "file",
        new org.springframework.http.HttpEntity<>(
            new ByteArrayResource(csv) {
              @Override
              public String getFilename() {
                return "offers.csv";
              }
            },
            partHeaders));
    return client
        .post()
        .uri("/api/v1/admin/imports/offers")
        .headers(h -> {
          if (token != null) {
            h.setBearerAuth(token);
          }
        })
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(parts)
        .exchange()
        .returnResult(String.class);
  }

  /** Waits for the job behind a 202 response to finish and returns its detail. */
  private JsonNode await(EntityExchangeResult<String> accepted, String token) {
    assertStatus(accepted, 202);
    ContractSchemas.assertConforms("ImportJob", accepted.getResponseBody());
    String location = accepted.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
    assertThat(location).isEqualTo("/api/v1/admin/imports/" + json(accepted).get("id").asString());
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (true) {
      EntityExchangeResult<String> job = get(location, token);
      assertStatus(job, 200);
      ContractSchemas.assertConforms("ImportJobDetail", job.getResponseBody());
      JsonNode body = json(job);
      if (!body.get("status").asString().equals("RUNNING")) {
        return body;
      }
      assertThat(Instant.now()).isBefore(deadline);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  private static String productJson(String code, String name, String offersJson) {
    return "{\"schema_version\":1,\"products\":[{\"code\":\""
        + code
        + "\",\"brand\":\"Fixture Labs\",\"name\":\""
        + name
        + "\",\"dosage_form\":\"TABLET\",\"units_per_container\":30,\"units_per_serving\":1,"
        + "\"servings_per_day\":1,\"source\":\"Synthetic import test product\",\"synthetic\":true,"
        + "\"nutrients\":[{\"nutrient_code\":\"VIT_C\",\"amount_per_serving\":100,\"unit\":\"mg\"},"
        + "{\"nutrient_code\":\"VIT_E\",\"amount_per_serving\":30,\"unit\":\"IU\"}],"
        + "\"availability\":[{\"country_code\":\"TR\",\"status\":\"AVAILABLE\"},"
        + "{\"country_code\":\"DE\",\"status\":\"AVAILABLE\"}],"
        + "\"offers\":["
        + offersJson
        + "]}]}";
  }

  private static String offerJson(String country, String retailer, String price, String currency) {
    return "{\"country_code\":\""
        + country
        + "\",\"retailer\":\""
        + retailer
        + "\",\"price\":"
        + price
        + ",\"currency\":\""
        + currency
        + "\",\"in_stock\":true,\"url\":\"https://shop.example.com/"
        + retailer.toLowerCase(java.util.Locale.ROOT).replace(' ', '-')
        + "\",\"checked_at\":\"2026-11-20T09:00:00Z\"}";
  }

  private Map<String, Long> summary(JsonNode job) {
    return JSON.convertValue(
        job.get("summary"), new tools.jackson.core.type.TypeReference<Map<String, Long>>() {});
  }

  private List<String> offerRows(String code) {
    return jdbc.sql(
            "SELECT o.country_code || '/' || o.retailer || '/' || o.price || '/' || o.id"
                + " FROM offer o JOIN product p ON p.id = o.product_id WHERE p.code = ?"
                + " ORDER BY o.country_code, o.retailer")
        .param(code)
        .query(String.class)
        .list();
  }

  @Test
  void importsRequireTheAdminRole() {
    String doc = productJson(PREFIX + "RBAC", "Rbac", "");
    assertProblem(postProducts(doc, null), 401, "unauthorized");
    assertProblem(postProducts(doc, token(Role.USER)), 403, "forbidden");
    byte[] csv = HEADER.getBytes(StandardCharsets.UTF_8);
    assertProblem(postOffers(csv, null), 401, "unauthorized");
    assertProblem(postOffers(csv, token(Role.USER)), 403, "forbidden");
    String job = "/api/v1/admin/imports/" + UUID.randomUUID();
    assertProblem(get(job, null), 401, "unauthorized");
    assertProblem(get(job, token(Role.USER)), 403, "forbidden");
    assertProblem(get(job, admin()), 404, "about:blank");
    assertProblem(get("/api/v1/admin/imports/not-a-uuid", admin()), 404, "about:blank");
    assertThat(
            jdbc.sql("SELECT count(*) FROM product WHERE code = ?")
                .param(PREFIX + "RBAC")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void theCommittedDataFilesImportWithoutChangesAndWithoutABump() throws IOException {
    String token = admin();
    // Other tests may have changed seeded rows; the seed restores the state of the files.
    seedService.seed(TestData.dataDir());
    long version = catalogVersion();
    for (String file : List.of("fixtures/products.synthetic.json", "real/products.dsld.json")) {
      JsonNode job =
          await(postProducts(Files.readString(TestData.dataDir().resolve(file)), token), token);
      assertThat(job.get("status").asString()).as(file).isEqualTo("SUCCEEDED");
      assertThat(job.get("errors").isEmpty()).isTrue();
      Map<String, Long> summary = summary(job);
      assertThat(summary.get("products")).isPositive();
      assertThat(summary.get("inserted") + summary.get("updated") + summary.get("deleted"))
          .isZero();
      assertThat(summary.get("changedPairs")).isZero();
    }
    for (String file :
        List.of("fixtures/offers.synthetic.csv", "real/offers.us-demo.synthetic.csv")) {
      byte[] csv = Files.readAllBytes(TestData.dataDir().resolve(file));
      JsonNode job = await(postOffers(csv, token), token);
      assertThat(job.get("status").asString()).as(file).isEqualTo("SUCCEEDED");
      Map<String, Long> summary = summary(job);
      assertThat(summary.get("rows")).isEqualTo(summary.get("unchanged")).isPositive();
      assertThat(summary.get("changedPairs")).isZero();
    }
    assertThat(catalogVersion()).isEqualTo(version);
    assertThat(listener.calls).isEmpty();
  }

  @Test
  void productsAreUpsertedByCodeWithOneBumpPerChangingImport() {
    String token = admin();
    String code = PREFIX + "UPSERT";
    String offers =
        offerJson("TR", "Import Shop", "100.00", "TRY")
            + ","
            + offerJson("DE", "Import Laden", "5.00", "EUR");
    long start = catalogVersion();
    JsonNode created = await(postProducts(productJson(code, "First name", offers), token), token);
    assertThat(created.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(catalogVersion()).isEqualTo(start + 1);
    Map<String, Long> summary = summary(created);
    assertThat(summary)
        .containsEntry("products", 1L)
        .containsEntry("updated", 0L)
        .containsEntry("deleted", 0L)
        .containsEntry("changedPairs", 2L);
    assertThat(summary.get("inserted")).isEqualTo(7L);
    UUID id = productId(code);
    assertThat(listener.calls).hasSize(1);
    assertThat(listener.calls.getFirst())
        .extracting(c ->
            c.countryCode() + ":" + (c.before() == null) + ":" + c.after().price().toPlainString())
        .containsExactly("DE:true:5", "TR:true:100");
    assertThat(listener.calls.getFirst()).allSatisfy(c -> assertThat(c.productId()).isEqualTo(id));

    JsonNode again = await(postProducts(productJson(code, "First name", offers), token), token);
    assertThat(again.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(summary(again)).containsEntry("unchanged", 7L).containsEntry("inserted", 0L);
    assertThat(catalogVersion()).isEqualTo(start + 1);
    assertThat(listener.calls).hasSize(1);

    JsonNode renamed = await(postProducts(productJson(code, "Second name", offers), token), token);
    assertThat(summary(renamed)).containsEntry("updated", 1L).containsEntry("changedPairs", 0L);
    assertThat(catalogVersion()).isEqualTo(start + 2);
    assertThat(productId(code)).isEqualTo(id);
    assertThat(
            jdbc.sql("SELECT name FROM product WHERE id = ?")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo("Second name");

    // The imported product is served by the public endpoints with its IU conversion flagged.
    JsonNode detail = json(get("/api/v1/products/" + id + "?country=TR", null)).get("product");
    assertThat(detail.get("flags").get(0).get("code").asString()).isEqualTo("CONVERSION_ASSUMED");
    assertThat(detail.get("flags").get(0).get("nutrientCodes").toString()).isEqualTo("[\"VIT_E\"]");
    JsonNode vitE = detail.get("nutrients").get(1);
    assertThat(vitE.get("amountPerServing").decimalValue()).isEqualByComparingTo("20.1");
    assertThat(vitE.get("unit").asString()).isEqualTo("mg");
    assertThat(vitE.get("conversionAssumed").asBoolean()).isTrue();
  }

  @Test
  void offersAreUpsertedByNaturalKeyWithReplaceSetPerPair() {
    String token = admin();
    String code = PREFIX + "OFFERS";
    await(
        postProducts(
            productJson(
                code,
                "Offers",
                offerJson("TR", "Shop A", "100.00", "TRY")
                    + ","
                    + offerJson("TR", "Shop B", "90.00", "TRY")
                    + ","
                    + offerJson("DE", "Laden", "5.00", "EUR")),
            token),
        token);
    List<String> before = offerRows(code);
    String idA = before.stream().filter(r -> r.startsWith("TR/Shop A/")).findFirst().orElseThrow();
    String deRow = before.stream().filter(r -> r.startsWith("DE/")).findFirst().orElseThrow();
    long start = catalogVersion();
    listener.calls.clear();

    String csv =
        HEADER
            + code
            + ",TR,Shop A,80.00,TRY,true,https://shop.example.com/a,2026-11-21T09:00:00Z\n"
            + code
            + ",TR,\"Shop C, Moda\",95.00,TRY,false,https://shop.example.com/c,2026-11-21T09:00:00Z\n";
    JsonNode job = await(postOffers(csv.getBytes(StandardCharsets.UTF_8), token), token);
    assertThat(job.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(summary(job))
        .containsEntry("rows", 2L)
        .containsEntry("inserted", 1L)
        .containsEntry("updated", 1L)
        .containsEntry("deleted", 1L)
        .containsEntry("unchanged", 0L)
        .containsEntry("changedPairs", 1L);
    assertThat(catalogVersion()).isEqualTo(start + 1);
    List<String> after = offerRows(code);
    assertThat(after).hasSize(3).contains(deRow);
    assertThat(after)
        .anySatisfy(r ->
            assertThat(r)
                .startsWith("TR/Shop A/80.00/")
                .endsWith(idA.substring(idA.lastIndexOf('/'))));
    assertThat(after).noneMatch(r -> r.startsWith("TR/Shop B/"));
    assertThat(listener.calls).hasSize(1);
    OfferChangeListener.OfferPairChange change = listener.calls.getFirst().getFirst();
    assertThat(change.countryCode()).isEqualTo("TR");
    assertThat(change.before().retailer()).isEqualTo("Shop B");
    assertThat(change.after().retailer()).isEqualTo("Shop A");
    assertThat(change.after().price()).isEqualByComparingTo("80");

    JsonNode same = await(postOffers(csv.getBytes(StandardCharsets.UTF_8), token), token);
    assertThat(summary(same)).containsEntry("unchanged", 2L).containsEntry("changedPairs", 0L);
    assertThat(offerRows(code)).isEqualTo(after);
    assertThat(catalogVersion()).isEqualTo(start + 1);
    assertThat(listener.calls).hasSize(1);

    // A new check time on the selected offer changes a row but not the effective state.
    String rechecked =
        csv.replace(
            "80.00,TRY,true,https://shop.example.com/a,2026-11-21",
            "80.00,TRY,true,https://shop.example.com/a,2026-11-22");
    JsonNode touched = await(postOffers(rechecked.getBytes(StandardCharsets.UTF_8), token), token);
    assertThat(summary(touched)).containsEntry("updated", 1L).containsEntry("changedPairs", 0L);
    assertThat(catalogVersion()).isEqualTo(start + 2);
    assertThat(listener.calls).hasSize(1);
  }

  @Test
  void anyRowErrorFailsTheWholeFileWithEveryErrorAndWritesNothing() {
    String token = admin();
    long start = catalogVersion();
    String csv =
        HEADER
            + "GOLD-A,TR,Valid Shop,700.00,TRY,true,https://shop.example.com/x,2026-11-20T09:00:00Z\n"
            + "GOLD-A,TR,Euro Shop,10.00,EUR,true,https://shop.example.com/y,2026-11-20T09:00:00Z\n"
            + "GOLD-A,TR,Ftp Shop,10.00,TRY,true,ftp://shop.example.com/z,2026-11-20T09:00:00Z\n"
            + "GOLD-A,TR,Short\n"
            + "NOPE-X,TR,Shop,10.00,TRY,true,https://shop.example.com/n,2099-01-01T00:00:00Z\n";
    JsonNode job = await(postOffers(csv.getBytes(StandardCharsets.UTF_8), token), token);
    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(summary(job))
        .containsEntry("rows", 5L)
        .containsEntry("inserted", 0L)
        .containsEntry("changedPairs", 0L);
    List<String> errors = new ArrayList<>();
    job.get("errors")
        .forEach(e -> errors.add(e.get("rowNumber").asInt() + ":" + e.get("code").asString()));
    assertThat(errors)
        .containsExactly(
            "3:currency_mismatch",
            "4:invalid_offer_url",
            "5:wrong_column_count",
            "6:unknown_product_code",
            "6:checked_at_in_future");
    assertThat(job.get("errors").get(0).get("message").asString())
        .isEqualTo("currency_mismatch: currency EUR differs from the currency of TR (row 3)");
    assertThat(catalogVersion()).isEqualTo(start);
    assertThat(offerRows("GOLD-A")).noneMatch(r -> r.contains("Valid Shop"));

    JsonNode unparseable =
        await(
            postOffers(
                (HEADER
                        + "GOLD-A,TR,\"open quote,1,TRY,true,https://a.example/x,2026-11-20T09:00:00Z\n")
                    .getBytes(StandardCharsets.UTF_8),
                token),
            token);
    assertThat(unparseable.get("status").asString()).isEqualTo("FAILED");
    assertThat(unparseable.get("errors").size()).isEqualTo(1);
    assertThat(unparseable.get("errors").get(0).get("code").asString()).isEqualTo("invalid_csv");
    assertThat(unparseable.get("errors").get(0).get("rowNumber").asInt()).isEqualTo(1);
    assertThat(summary(unparseable)).containsEntry("rows", 0L);

    JsonNode header = await(postOffers("a,b\n".getBytes(StandardCharsets.UTF_8), token), token);
    assertThat(header.get("errors").get(0).get("code").asString()).isEqualTo("invalid_header");
    JsonNode encoding = await(postOffers(new byte[] {(byte) 0xC3, (byte) 0x28}, token), token);
    assertThat(encoding.get("errors").get(0).get("code").asString()).isEqualTo("invalid_encoding");
    assertThat(catalogVersion()).isEqualTo(start);
  }

  @Test
  void productDocumentErrorsAreJobErrorsWithTheProductPosition() {
    String token = admin();
    long start = catalogVersion();
    JsonNode invalidJson = await(postProducts("{\"products\": [", token), token);
    assertThat(invalidJson.get("status").asString()).isEqualTo("FAILED");
    assertThat(invalidJson.get("errors").get(0).get("code").asString()).isEqualTo("invalid_json");
    assertThat(invalidJson.get("errors").get(0).get("rowNumber").asInt()).isEqualTo(1);
    assertThat(summary(invalidJson)).containsEntry("products", 0L);

    String good = productJson(PREFIX + "GOOD", "Good", "");
    String wrongForm =
        productJson(PREFIX + "BAD", "Bad", "")
            .replace(
                "{\"nutrient_code\":\"VIT_C\",\"amount_per_serving\":100,\"unit\":\"mg\"}",
                "{\"nutrient_code\":\"VIT_C\",\"amount_per_serving\":100,\"unit\":\"mg\","
                    + "\"form_code\":\"D3_CHOLECALCIFEROL\"}");
    String combined =
        "{\"schema_version\":1,\"products\":["
            + good.substring(good.indexOf("[{") + 1, good.length() - 2)
            + ","
            + wrongForm.substring(wrongForm.indexOf("[{") + 1, wrongForm.length() - 2)
            + "]}";
    JsonNode job = await(postProducts(combined, token), token);
    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(summary(job)).containsEntry("products", 2L).containsEntry("inserted", 0L);
    JsonNode error = job.get("errors").get(0);
    assertThat(error.get("rowNumber").asInt()).isEqualTo(2);
    assertThat(error.get("code").asString()).isEqualTo("form_nutrient_mismatch");
    assertThat(error.get("message").asString()).contains("products[1].nutrients[0]");
    assertThat(
            jdbc.sql("SELECT count(*) FROM product WHERE code LIKE ?")
                .param(PREFIX + "%")
                .query(Integer.class)
                .single())
        .isZero();

    String betaCaroteneIu =
        productJson(PREFIX + "IU", "Iu", "")
            .replace(
                "{\"nutrient_code\":\"VIT_C\",\"amount_per_serving\":100,\"unit\":\"mg\"}",
                "{\"nutrient_code\":\"VIT_A\",\"amount_per_serving\":5000,\"unit\":\"IU\","
                    + "\"form_code\":\"BETA_CAROTENE\"}");
    JsonNode iu = await(postProducts(betaCaroteneIu, token), token);
    assertThat(iu.get("errors").get(0).get("code").asString()).isEqualTo("unsupported_iu_form");
    assertThat(catalogVersion()).isEqualTo(start);
  }

  @Test
  void requestsWithoutContentAreRejectedSynchronously() {
    String token = admin();
    org.springframework.util.LinkedMultiValueMap<String, Object> otherPart =
        new org.springframework.util.LinkedMultiValueMap<>();
    otherPart.add("other", "x");
    assertProblem(postProducts("", token), 422, "validation_failed");
    EntityExchangeResult<String> noFile =
        client
            .post()
            .uri("/api/v1/admin/imports/offers")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(otherPart)
            .exchange()
            .returnResult(String.class);
    assertProblem(noFile, 422, "validation_failed");
    assertThat(json(noFile).get("errors").get(0).get("field").asString()).isEqualTo("file");
    EntityExchangeResult<String> textBody =
        client
            .post()
            .uri("/api/v1/admin/imports/products")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.TEXT_PLAIN)
            .body("x")
            .exchange()
            .returnResult(String.class);
    assertProblem(textBody, 415, "about:blank");
    EntityExchangeResult<String> jsonOffers =
        client
            .post()
            .uri("/api/v1/admin/imports/offers")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange()
            .returnResult(String.class);
    assertProblem(jsonOffers, 415, "about:blank");
  }

  @Test
  void bodiesOverTwentyMegabytesAreRejected() {
    String token = admin();
    byte[] large = new byte[20 * 1024 * 1024 + 1];
    java.util.Arrays.fill(large, (byte) ' ');
    EntityExchangeResult<String> json =
        client
            .post()
            .uri("/api/v1/admin/imports/products")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .body(large)
            .exchange()
            .returnResult(String.class);
    assertProblem(json, 413, "validation_failed");
    assertProblem(postOffers(large, token), 413, "validation_failed");
    // Just under the cap is accepted (and fails as a job: the document is empty JSON).
    byte[] fits = new byte[19 * 1024 * 1024];
    java.util.Arrays.fill(fits, (byte) ' ');
    JsonNode job = await(postProducts(new String(fits, StandardCharsets.UTF_8), token), token);
    assertThat(job.get("status").asString()).isEqualTo("FAILED");
  }

  @Test
  void aFullQueueFailsNewJobsAtOnce() throws Exception {
    String token = admin();
    CountDownLatch gate = new CountDownLatch(1);
    CountDownLatch entered = new CountDownLatch(1);
    listener.gate.set(gate);
    listener.entered.set(entered);
    List<EntityExchangeResult<String>> accepted = new ArrayList<>();
    try {
      accepted.add(
          postProducts(
              productJson(PREFIX + "BLOCK", "Block", offerJson("TR", "Shop", "1.00", "TRY")),
              token));
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < 4; i++) {
        accepted.add(postProducts(productJson(PREFIX + "Q" + i, "Queued", ""), token));
      }
      EntityExchangeResult<String> rejected =
          postProducts(productJson(PREFIX + "FULL", "Full", ""), token);
      assertStatus(rejected, 202);
      ContractSchemas.assertConforms("ImportJob", rejected.getResponseBody());
      assertThat(json(rejected).get("status").asString()).isEqualTo("FAILED");
      JsonNode detail = await(rejected, token);
      assertThat(detail.get("errors").get(0).get("message").asString())
          .isEqualTo("import_failed: the import queue is full (row 1)");
    } finally {
      gate.countDown();
    }
    for (EntityExchangeResult<String> result : accepted) {
      assertThat(await(result, token).get("status").asString()).isEqualTo("SUCCEEDED");
    }
  }

  @Test
  void jobsLeftRunningAreFailedByTheRecovery() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO import_job (id, kind, status, created_at) VALUES (?, 'OFFERS_CSV',"
                + " 'RUNNING', now())")
        .param(id)
        .update();
    // While another session holds the import lock the recovery leaves jobs alone.
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        new Thread(() ->
            jobLock.runExclusive(
                "test",
                com.vitadelta.api.common.jobs.JobLockKeys.CATALOG_IMPORT,
                () -> {
                  locked.countDown();
                  try {
                    release.await(30, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
    holder.start();
    assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(recovery.recover()).isEqualTo(-1);
    release.countDown();
    holder.join(30_000);

    assertThat(recovery.recover()).isGreaterThanOrEqualTo(1);
    String token = admin();
    JsonNode job = json(get("/api/v1/admin/imports/" + id, token));
    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(job.get("finishedAt").isNull()).isFalse();
    assertThat(job.get("errors").get(0).get("message").asString())
        .isEqualTo("import_failed: the import was interrupted by a restart (row 1)");
    assertThat(recovery.recover()).isZero();
    jdbc.sql("DELETE FROM import_job WHERE id = ?").param(id).update();
  }

  @Test
  void aFileWithMoreErrorsThanTheBoundStoresTheFirstOnesAndOneSummaryError() {
    String token = admin();
    long start = catalogVersion();
    StringBuilder csv = new StringBuilder(HEADER);
    for (int i = 0; i < 1500; i++) {
      csv.append("GOLD-A,TR,Shop ").append(i).append(",not-a-price,TRY,true,x,y\n");
    }
    JsonNode job = await(postOffers(csv.toString().getBytes(StandardCharsets.UTF_8), token), token);
    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    JsonNode errors = job.get("errors");
    assertThat(errors.size()).isEqualTo(1001);
    JsonNode last = errors.get(1000);
    assertThat(last.get("code").asString()).isEqualTo("too_many_errors");
    assertThat(last.get("rowNumber").asInt())
        .isGreaterThanOrEqualTo(errors.get(999).get("rowNumber").asInt());
    assertThat(
            jdbc.sql("SELECT count(*) FROM import_job_error WHERE job_id = ?")
                .param(UUID.fromString(job.get("id").asString()))
                .query(Long.class)
                .single())
        .isEqualTo(1001L);
    assertThat(catalogVersion()).isEqualTo(start);
  }

  @Test
  void reImportingNanosecondTimestampsChangesNothingTheSecondTime() {
    String token = admin();
    String code = PREFIX + "NANO";
    String offer =
        offerJson("TR", "Nano Shop", "10.00", "TRY")
            .replace("2026-11-20T09:00:00Z", "2026-11-20T09:00:00.123456789Z");
    String document = productJson(code, "Nano", offer);
    JsonNode first = await(postProducts(document, token), token);
    assertThat(first.get("status").asString()).as(first.toString()).isEqualTo("SUCCEEDED");
    long version = catalogVersion();
    JsonNode second = await(postProducts(document, token), token);
    assertThat(second.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(summary(second)).containsEntry("updated", 0L).containsEntry("inserted", 0L);
    assertThat(catalogVersion()).as("an unchanged re-import does not bump").isEqualTo(version);

    String csv =
        HEADER
            + code
            + ",TR,Nano Shop,10.00,TRY,true,"
            + "https://shop.example.com/nano-shop,2026-11-20T09:00:00.123456789Z\n";
    JsonNode offers = await(postOffers(csv.getBytes(StandardCharsets.UTF_8), token), token);
    assertThat(offers.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(summary(offers)).containsEntry("updated", 0L).containsEntry("unchanged", 1L);
    assertThat(catalogVersion()).isEqualTo(version);
    assertThat(
            jdbc.sql(
                    "SELECT o.checked_at FROM offer o JOIN product p ON p.id = o.product_id"
                        + " WHERE p.code = ?")
                .param(code)
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant())
        .isEqualTo(Instant.parse("2026-11-20T09:00:00.123456Z"));
  }

  @Test
  void parserFailuresAreReportedWithFixedTextAndLocationOnly() {
    String token = admin();
    JsonNode json =
        await(
            postProducts("{\"schema_version\":1,\n \"products\": [SECRET-FRAGMENT", token), token);
    assertThat(json.get("errors").size()).isEqualTo(1);
    assertThat(json.get("errors").get(0).get("message").asString())
        .startsWith("invalid_json: the document is not valid JSON (line 2, column ")
        .doesNotContain("SECRET")
        .doesNotContain("Unrecognized")
        .doesNotContain("Source");
    JsonNode csv =
        await(
            postOffers(
                (HEADER + "GOLD-A,TR,\"SECRET-FRAGMENT,1,TRY,true,u,c\n")
                    .getBytes(StandardCharsets.UTF_8),
                token),
            token);
    assertThat(csv.get("errors").get(0).get("message").asString())
        .isEqualTo("invalid_csv: the file is not valid CSV (line 2) (row 1)");
  }
}
