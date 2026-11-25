package com.vitadelta.api.adversarial;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.Role;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Attacks on the admin import endpoints: RBAC, stored XSS and hostile offer URLs (section 15.7 case
 * 8, API part), all-or-nothing integrity, oversized and malformed uploads (case 7), and job id
 * probing. Content errors must become job errors with nothing written and no catalog version bump;
 * stored text is returned verbatim as JSON data, never fetched or interpreted.
 */
class ImportAbuseAdversarialTest extends CatalogAdversarialTest {

  private static final String XSS = "<script>alert(1)</script><img src=x onerror=alert(2)>";
  private static final int IMPORT_LIMIT = 20 * 1024 * 1024;

  private long importJobs() {
    return jdbc.sql("SELECT count(*) FROM import_job").query(Long.class).single();
  }

  private String csvRow(String code, String retailer, String price, String url) {
    return code + ",TR," + retailer + "," + price + ",TRY,true," + url + ",2026-11-20T09:00:00Z\n";
  }

  /** Imports one valid product and returns its code. */
  private String importedProduct(String token) {
    String code = code("BASE");
    JsonNode job =
        awaitJob(
            importProducts(
                productDocument(code, "Adv Labs", "Adv base", validOffer("Adv Shop")), token),
            token);
    assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("SUCCEEDED");
    return code;
  }

  @Test
  void everyImportRouteRejectsAnonymousUsersAndForgedAdminHeaders() {
    String user = user(Role.USER).token();
    String pro = proUser().token();
    String doc = productDocument(code("RBAC"), "Adv Labs", "Rbac", "");
    byte[] csv = (CSV_HEADER).getBytes(StandardCharsets.UTF_8);
    long jobs = importJobs();
    String job = API + "/admin/imports/" + UUID.randomUUID();
    for (String token : Arrays.asList(null, user, pro)) {
      int expected = token == null ? 401 : 403;
      String code = token == null ? "unauthorized" : "forbidden";
      Resp products =
          req("POST", API + "/admin/imports/products")
              .bearer(token)
              .header("X-Admin-Key", "admin")
              .header("X-Role", "ADMIN")
              .json(doc)
              .send();
      AttackClient.assertProblem(products, expected, code);
      AttackClient.assertApiSecurityHeaders(products);
      AttackClient.assertProblem(
          importOffersMultipart(
              multipart(List.of(new Part("file", "offers.csv", "text/csv", csv))), token, false),
          expected,
          code);
      AttackClient.assertProblem(rawGet(job, token), expected, code);
      AttackClient.assertProblem(rawGet(API + "/admin/imports/not-a-uuid", token), expected, code);
      for (String method : List.of("PUT", "DELETE", "PATCH")) {
        Resp other = req(method, API + "/admin/imports/products").bearer(token).json(doc).send();
        assertThat(other.status()).as(method + " " + other).isIn(401, 403, 405);
        AttackClient.assertNoLeak(other);
      }
    }
    assertThat(importJobs()).isEqualTo(jobs);
  }

  @Test
  void aHostileProductNameIsStoredAndReturnedVerbatimAsInertJson() {
    String token = admin();
    String code = code("XSS");
    String document = productDocument(code, XSS, XSS, validOffer(XSS));
    JsonNode job = awaitJob(importProducts(document, token), token);
    assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("SUCCEEDED");
    UUID id = productId(code);
    assertThat(
            jdbc.sql("SELECT name FROM product WHERE id = ?")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo(XSS);

    Resp detail = rawGet(API + "/products/" + id + "?country=TR", null);
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.header("content-type")).startsWith("application/json");
    AttackClient.assertApiSecurityHeaders(detail);
    assertThat(detail.json().get("product").get("name").asString()).isEqualTo(XSS);
    assertThat(detail.json().get("product").get("brand").asString()).isEqualTo(XSS);

    Resp search = rawGet(API + "/products?country=TR&q=%3Cscript%3Ealert(1)%3C%2Fscript%3E", null);
    assertThat(search.status()).isEqualTo(200);
    assertThat(search.header("content-type")).startsWith("application/json");
    assertThat(search.json().get("items").get(0).get("name").asString()).isEqualTo(XSS);

    // Reflected values in errors stay inside problem+json under the API's CSP.
    for (Resp error :
        List.of(
            rawQuery(
                "{\"country\":\"TR\",\"weights\":{\"categories\":{\"" + XSS + "\":1}}}",
                null,
                null),
            rawGet(API + "/products?country=%3Cscript%3E", null),
            rawGet(API + "/products/%3Cimg%20src%3Dx%20onerror%3Dalert(2)%3E?country=TR", null))) {
      assertThat(error.status()).as(error.toString()).isIn(400, 404, 422);
      assertThat(error.header("content-type")).startsWith("application/problem+json");
      AttackClient.assertApiSecurityHeaders(error);
      AttackClient.assertNoLeak(error);
    }
  }

  @Test
  void hostileOfferUrlsFailTheWholeImportWithRowErrorsAndWriteNothing() {
    String token = admin();
    String code = importedProduct(token);
    long version = catalogVersion();
    long offers = offerRows();
    List<String> hostile =
        List.of(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(document.domain)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "/relative/path",
            "//evil.example/path",
            "http://",
            "https://",
            "http:///path",
            "https://user@:443/",
            "http://evil.example\\@good.example/",
            "https://shop.example.com/" + "a".repeat(3000),
            "https://shop.example.com/a b",
            "file:///etc/passwd",
            "ftp://shop.example.com/x");
    StringBuilder csv = new StringBuilder(CSV_HEADER);
    for (int i = 0; i < hostile.size(); i++) {
      String url = hostile.get(i);
      String cell = url.contains(",") || url.contains("\"") ? "\"" + url + "\"" : url;
      csv.append(csvRow(code, "Shop " + i, "10.00", cell));
    }
    JsonNode job = awaitJob(importOffers(csv.toString(), token), token);
    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < hostile.size(); i++) {
      expected.add((i + 2) + ":invalid_offer_url");
    }
    assertThat(jobErrors(job)).containsExactlyElementsOf(expected);
    assertThat(job.get("summary").get("inserted").asLong()).isZero();

    for (String url : hostile) {
      String doc =
          productDocument(code("URL"), "Adv Labs", "Url", offerJson("Shop", "1.00", "TRY", url));
      JsonNode products = awaitJob(importProducts(doc, token), token);
      assertThat(products.get("status").asString()).as(url).isEqualTo("FAILED");
      assertThat(jobErrors(products))
          .as(url)
          .containsAnyOf("1:invalid_offer_url", "1:schema_violation");
    }
    assertThat(catalogVersion()).isEqualTo(version);
    assertThat(offerRows()).isEqualTo(offers);
  }

  @Test
  void internalAddressesAreStoredAsDataAndNeverFetched() {
    String token = admin();
    String code = code("SSRF");
    String probe = "http://localhost:" + scoring().port() + "/ssrf-probe/" + code;
    String doc =
        productDocument(
            code,
            "Adv Labs",
            "Ssrf",
            offerJson("Probe Shop", "5.00", "TRY", probe)
                + ","
                + offerJson(
                    "Meta Shop", "6.00", "TRY", "http://169.254.169.254/latest/meta-data/"));
    JsonNode job = awaitJob(importProducts(doc, token), token);
    assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("SUCCEEDED");
    UUID id = productId(code);
    Resp detail = rawGet(API + "/products/" + id + "?country=TR", null);
    assertThat(detail.body()).contains(probe);
    awaitJob(importOffers(CSV_HEADER + csvRow(code, "Probe Shop", "4.00", probe), token), token);
    assertThat(scoring().findAll(anyRequestedFor(urlMatching("/ssrf-probe/.*"))))
        .as("the API never fetches offer URLs")
        .isEmpty();
  }

  @Test
  void aFailingImportWritesNothingAndKeepsTheCatalogVersion() {
    String token = admin();
    String code = importedProduct(token);
    long version = catalogVersion();
    long products = productRows();
    long offers = offerRows();
    String fresh = code("NEW");
    String goodThenBad =
        "{\"schema_version\":1,\"products\":["
            + inner(productDocument(fresh, "Adv Labs", "Fresh", validOffer("Shop A")))
            + ","
            + inner(
                productDocument(
                    code("BAD"),
                    "Adv Labs",
                    "Bad",
                    offerJson("Shop", "-1", "TRY", "https://shop.example.com/x")))
            + "]}";
    JsonNode job = awaitJob(importProducts(goodThenBad, token), token);
    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(productRows()).isEqualTo(products);
    assertThat(count("SELECT count(*) FROM product WHERE code = ?", fresh)).isZero();

    String csv =
        CSV_HEADER
            + csvRow(code, "Valid Shop", "10.00", "https://shop.example.com/ok")
            + csvRow("NOPE-UNKNOWN", "Shop", "10.00", "https://shop.example.com/x")
            + code
            + ",ZZ,Shop,10.00,TRY,true,https://shop.example.com/x,2026-11-20T09:00:00Z\n"
            + code
            + ",TR,Euro,10.00,EUR,true,https://shop.example.com/x,2026-11-20T09:00:00Z\n"
            + code
            + ",TR,Future,10.00,TRY,true,https://shop.example.com/x,2099-01-01T00:00:00Z\n"
            + csvRow(code, "Zero", "0", "https://shop.example.com/x")
            + csvRow(code, "Zero2", "0.00", "https://shop.example.com/x")
            + csvRow(code, "Negative", "-5.00", "https://shop.example.com/x")
            + csvRow(code, "NotANumber", "NaN", "https://shop.example.com/x")
            + csvRow(code, "Inf", "Infinity", "https://shop.example.com/x")
            + csvRow(code, "Exp", "1e3", "https://shop.example.com/x")
            + csvRow(code, "Thousands", "\"1,000.00\"", "https://shop.example.com/x")
            + csvRow(code, "ThreeDecimals", "1.001", "https://shop.example.com/x")
            + csvRow(code, "Huge", "99999999999.00", "https://shop.example.com/x")
            + csvRow(code, "Hex", "0x10", "https://shop.example.com/x")
            + csvRow(code, "Valid Shop", "11.00", "https://shop.example.com/dup")
            + csvRow(
                code.toLowerCase(java.util.Locale.ROOT),
                "Lower",
                "1.00",
                "https://shop.example.com/x");
    JsonNode offersJob = awaitJob(importOffers(csv, token), token);
    assertThat(offersJob.get("status").asString()).isEqualTo("FAILED");
    assertThat(jobErrors(offersJob))
        .containsExactly(
            "3:unknown_product_code",
            "4:unknown_country",
            "5:currency_mismatch",
            "6:checked_at_in_future",
            "7:invalid_price",
            "8:invalid_price",
            "9:invalid_price",
            "10:invalid_price",
            "11:invalid_price",
            "12:invalid_price",
            "13:invalid_price",
            "14:invalid_price",
            "15:invalid_price",
            "16:invalid_price",
            "17:duplicate_offer_key",
            "18:unknown_product_code");
    for (String price : List.of("0", "-1", "\"NaN\"", "1e400", "1.001", "\"10\"")) {
      JsonNode priced =
          awaitJob(
              importProducts(
                  productDocument(
                      code("PRC"),
                      "Adv Labs",
                      "Price",
                      offerJson("Shop", price, "TRY", "https://shop.example.com/p")),
                  token),
              token);
      assertThat(priced.get("status").asString()).as(price).isEqualTo("FAILED");
    }
    JsonNode nan =
        awaitJob(
            importProducts(
                productDocument(
                    code("NAN"),
                    "Adv Labs",
                    "Nan",
                    offerJson("Shop", "NaN", "TRY", "https://shop.example.com/p")),
                token),
            token);
    assertThat(jobErrors(nan)).containsExactly("1:invalid_json");
    assertThat(catalogVersion()).isEqualTo(version);
    assertThat(offerRows()).isEqualTo(offers);
    assertThat(productRows()).isEqualTo(products);
  }

  @Test
  void aCodeThatDiffersOnlyInCaseCannotOverwriteAnExistingProduct() {
    String token = admin();
    UUID gold = productId("GOLD-A");
    String name =
        jdbc.sql("SELECT name FROM product WHERE id = ?").param(gold).query(String.class).single();
    long version = catalogVersion();
    for (String variant :
        List.of("gold-a", "Gold-A", "GOLD-a", "GOLD-A ", " GOLD-A", "GOLD\u2010A")) {
      JsonNode job =
          awaitJob(
              importProducts(productDocument(variant, "Hijack", "Hijacked", ""), token), token);
      assertThat(job.get("status").asString()).as(variant).isEqualTo("FAILED");
    }
    assertThat(
            jdbc.sql("SELECT name FROM product WHERE id = ?")
                .param(gold)
                .query(String.class)
                .single())
        .isEqualTo(name);
    assertThat(catalogVersion()).isEqualTo(version);
  }

  @Test
  void twoImportsSubmittedAtOnceAreSerializedAndBothOutcomesAreConsistent() throws Exception {
    String token = admin();
    String code = code("RACE");
    long version = catalogVersion();
    CompletableFuture<Resp> first =
        CompletableFuture.supplyAsync(() ->
            importProducts(
                productDocument(code, "Adv Labs", "First", validOffer("Shop A")), token));
    CompletableFuture<Resp> second =
        CompletableFuture.supplyAsync(() ->
            importProducts(
                productDocument(code, "Adv Labs", "Second", validOffer("Shop B")), token));
    JsonNode a = awaitJob(first.get(), token);
    JsonNode b = awaitJob(second.get(), token);
    assertThat(a.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(b.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(count("SELECT count(*) FROM product WHERE code = ?", code)).isEqualTo(1);
    String stored =
        jdbc.sql("SELECT name FROM product WHERE code = ?")
            .param(code)
            .query(String.class)
            .single();
    assertThat(stored).isIn("First", "Second");
    List<String> retailers =
        jdbc.sql(
                "SELECT o.retailer FROM offer o JOIN product p ON p.id = o.product_id"
                    + " WHERE p.code = ? ORDER BY o.retailer")
            .param(code)
            .query(String.class)
            .list();
    assertThat(retailers).containsExactly(stored.equals("First") ? "Shop A" : "Shop B");
    assertThat(catalogVersion()).isEqualTo(version + 2);
  }

  @Test
  void jobIdsThatAreNotStoredUuidsAre404() {
    String token = admin();
    String code = importedProduct(token);
    String realJob =
        jdbc.sql(
                "SELECT id::text FROM import_job WHERE status = 'SUCCEEDED'"
                    + " ORDER BY created_at DESC LIMIT 1")
            .query(String.class)
            .single();
    assertThat(code).isNotBlank();
    for (String id :
        List.of(
            UUID.randomUUID().toString(),
            "00000000-0000-0000-0000-000000000000",
            "not-a-uuid",
            "1-1-1-1-1",
            realJob + "0",
            realJob.replace("-", ""),
            "%27%20OR%20%271%27%3D%271",
            "..%2Fproducts",
            "%00",
            "x".repeat(3000))) {
      Resp response = rawGet(API + "/admin/imports/" + id, token);
      assertThat(response.status()).as(id + " -> " + response).isIn(400, 404);
      AttackClient.assertNoLeak(response);
    }
    Resp upper =
        rawGet(API + "/admin/imports/" + realJob.toUpperCase(java.util.Locale.ROOT), token);
    assertThat(upper.status()).isEqualTo(200);
  }

  @Test
  void oversizedImportsAreRejectedWhetherDeclaredOrChunked() {
    String token = admin();
    long jobs = importJobs();
    byte[] large = new byte[IMPORT_LIMIT + 1];
    Arrays.fill(large, (byte) ' ');
    byte[] csv = new byte[IMPORT_LIMIT + 1];
    Arrays.fill(csv, (byte) 'a');
    byte[] body = multipart(List.of(new Part("file", "offers.csv", "text/csv", csv)));
    List<Supplier<Resp>> attempts =
        List.of(
            () ->
                req("POST", API + "/admin/imports/products")
                    .bearer(token)
                    .body(large, "application/json")
                    .send(),
            () ->
                req("POST", API + "/admin/imports/products")
                    .bearer(token)
                    .body(large, "application/json")
                    .chunked()
                    .send(),
            () -> importOffersMultipart(body, token, false),
            () -> importOffersMultipart(body, token, true));
    for (int i = 0; i < attempts.size(); i++) {
      Resp response = sendTolerant(attempts.get(i));
      if (response != null) {
        assertThat(response.status()).as("attempt %d: %s", i, response).isEqualTo(413);
        AttackClient.assertProblem(response, 413, "validation_failed");
      }
    }
    assertThat(importJobs()).isEqualTo(jobs);
  }

  /**
   * The response, or {@code null} when the server closed the connection while the oversized body
   * was still being sent (the upload was cut off before processing).
   */
  private static Resp sendTolerant(Supplier<Resp> attempt) {
    try {
      return attempt.get();
    } catch (java.io.UncheckedIOException e) {
      return null;
    }
  }

  @Test
  void wrongContentTypesAreRejectedWithoutCreatingAJob() {
    String token = admin();
    long jobs = importJobs();
    String doc = productDocument(code("CT"), "Adv Labs", "Ct", "");
    for (String type :
        Arrays.asList(
            "text/plain",
            "text/csv",
            "application/x-www-form-urlencoded",
            "application/xml",
            "multipart/form-data; boundary=" + BOUNDARY,
            null)) {
      Resp response =
          req("POST", API + "/admin/imports/products").bearer(token).body(doc, type).send();
      assertThat(response.status()).as(type + " -> " + response).isIn(415);
      AttackClient.assertNoLeak(response);
    }
    for (String type :
        Arrays.asList("application/json", "text/csv", "application/octet-stream", null)) {
      Resp response =
          req("POST", API + "/admin/imports/offers").bearer(token).body(CSV_HEADER, type).send();
      assertThat(response.status()).as(type + " -> " + response).isIn(415);
      AttackClient.assertNoLeak(response);
    }
    Resp brokenMultipart =
        req("POST", API + "/admin/imports/offers")
            .bearer(token)
            .body("--nope\r\ngarbage", "multipart/form-data; boundary=" + BOUNDARY)
            .send();
    assertThat(brokenMultipart.status()).as(brokenMultipart.toString()).isIn(400, 422);
    AttackClient.assertNoLeak(brokenMultipart);
    assertThat(importJobs()).isEqualTo(jobs);
  }

  @Test
  void severalFilesAndHostileFilenamesNeitherBreakNorMixImports() {
    String token = admin();
    String code = importedProduct(token);
    long jobs = importJobs();
    byte[] first =
        (CSV_HEADER + csvRow(code, "First File", "10.00", "https://shop.example.com/1"))
            .getBytes(StandardCharsets.UTF_8);
    byte[] second =
        (CSV_HEADER + csvRow(code, "Second File", "11.00", "https://shop.example.com/2"))
            .getBytes(StandardCharsets.UTF_8);
    Resp both =
        importOffersMultipart(
            multipart(
                List.of(
                    new Part("file", "a.csv", "text/csv", first),
                    new Part("file", "b.csv", "text/csv", second))),
            token,
            false);
    assertThat(both.status()).as(both.toString()).isIn(202, 422);
    AttackClient.assertNoLeak(both);
    if (both.status() == 202) {
      assertThat(awaitJob(both, token).get("status").asString()).isIn("SUCCEEDED", "FAILED");
      List<String> retailers =
          jdbc.sql(
                  "SELECT o.retailer FROM offer o JOIN product p ON p.id = o.product_id"
                      + " WHERE p.code = ? ORDER BY o.retailer")
              .param(code)
              .query(String.class)
              .list();
      assertThat(retailers)
          .as("never a mix of both files")
          .doesNotContainSequence("First File", "Second File");
    }
    assertThat(importJobs()).isLessThanOrEqualTo(jobs + 1);

    for (String filename :
        List.of(
            "../../../../etc/passwd",
            "..\\..\\..\\Windows\\win.ini",
            "C:\\Windows\\System32\\drivers\\etc\\hosts",
            "/etc/shadow",
            "offers.csv\u0000.exe",
            "<script>alert(1)</script>.csv")) {
      Resp response =
          importOffersMultipart(
              multipart(
                  List.of(
                      new Part(
                          "file", filename, "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)))),
              token,
              false);
      assertThat(response.status()).as(filename + " -> " + response).isIn(202, 400, 422);
      assertThat(response.body()).doesNotContain("passwd", "win.ini", "shadow", "<script>");
      if (response.status() == 202) {
        JsonNode job = awaitJob(response, token);
        assertThat(jobErrors(job)).containsExactly("1:invalid_header");
        assertThat(job.toString()).doesNotContain("passwd", "win.ini", "shadow", "<script>");
      }
    }
  }

  @Test
  void bomNulBytesHugeFieldsAndFormulaCellsAreHandledAsPlainData() {
    String token = admin();
    String code = importedProduct(token);
    long version = catalogVersion();
    long offers = offerRows();

    byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] withBom =
        concat(
            bom,
            (CSV_HEADER + csvRow(code, "Bom Shop", "12.00", "https://shop.example.com/b"))
                .getBytes(StandardCharsets.UTF_8));
    JsonNode bomJob = awaitJob(importOffers(withBom, token), token);
    assertThat(bomJob.get("status").asString()).as(bomJob.toString()).isEqualTo("SUCCEEDED");

    String nul = CSV_HEADER + csvRow(code, "Nul\u0000Shop", "12.00", "https://shop.example.com/n");
    JsonNode nulJob = awaitJob(importOffers(nul, token), token);
    assertThat(nulJob.get("status").asString()).isEqualTo("FAILED");
    assertThat(count("SELECT count(*) FROM offer WHERE retailer LIKE 'Nul%'")).isZero();
    JsonNode nulProduct =
        awaitJob(
            importProducts(productDocument(code("NUL"), "Adv" + '\0' + "Labs", "Nul", ""), token),
            token);
    assertThat(nulProduct.get("status").asString()).isEqualTo("FAILED");

    String megabyte = "M".repeat(1024 * 1024);
    for (String row :
        List.of(
            csvRow(code, megabyte, "12.00", "https://shop.example.com/m"),
            csvRow(megabyte, "Shop", "12.00", "https://shop.example.com/m"),
            csvRow(code, "Shop", megabyte, "https://shop.example.com/m"),
            csvRow(code, "Shop", "12.00", "https://shop.example.com/" + megabyte))) {
      JsonNode job = awaitJob(importOffers(CSV_HEADER + row, token), token);
      assertThat(job.get("status").asString()).isEqualTo("FAILED");
      assertThat(job.get("errors").size()).isGreaterThanOrEqualTo(1);
      job.get("errors")
          .forEach(e -> assertThat(e.get("message").asString().length()).isLessThanOrEqualTo(2000));
    }

    List<String> formulas =
        List.of(
            "=cmd|' /C calc'!A0",
            "+SUM(1+1)*cmd|' /C calc'!A0",
            "-2+3+cmd|' /C calc'!A0",
            "@SUM(1+1)*cmd|' /C calc'!A0");
    StringBuilder csv = new StringBuilder(CSV_HEADER);
    for (int i = 0; i < formulas.size(); i++) {
      csv.append(csvRow(code, formulas.get(i), "1" + i + ".00", "https://shop.example.com/f" + i));
    }
    JsonNode formulaJob = awaitJob(importOffers(csv.toString(), token), token);
    assertThat(formulaJob.get("status").asString())
        .as(formulaJob.toString())
        .isEqualTo("SUCCEEDED");
    List<String> stored =
        jdbc.sql(
                "SELECT o.retailer FROM offer o JOIN product p ON p.id = o.product_id"
                    + " WHERE p.code = ? ORDER BY o.price")
            .param(code)
            .query(String.class)
            .list();
    assertThat(stored).containsExactlyElementsOf(formulas);
    Resp detail = rawGet(API + "/products/" + productId(code) + "?country=TR", null);
    List<String> served = new ArrayList<>();
    detail
        .json()
        .get("product")
        .get("offers")
        .forEach(o -> served.add(o.get("retailer").asString()));
    assertThat(served).containsExactlyElementsOf(formulas);
    assertThat(catalogVersion()).isEqualTo(version + 2);
    assertThat(offerRows()).isEqualTo(offers + 3);
  }

  @Test
  void deeplyNestedProductsJsonFailsTheJobAndTheWorkerKeepsRunning() {
    String token = admin();
    String nested =
        "{\"schema_version\":1,\"products\":" + "[".repeat(10_000) + "]".repeat(10_000) + "}";
    JsonNode job = awaitJob(importProducts(nested, token), token);
    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(jobErrors(job)).hasSize(1);
    assertThat(jobErrors(job).getFirst()).isIn("1:invalid_json", "1:schema_violation");
    String objects =
        "{\"schema_version\":1,\"products\":[{\"code\":"
            + "{\"a\":".repeat(10_000)
            + "1"
            + "}".repeat(10_000)
            + "}]}";
    JsonNode objectJob = awaitJob(importProducts(objects, token), token);
    assertThat(objectJob.get("status").asString()).isEqualTo("FAILED");
    job.get("errors")
        .forEach(
            e -> assertThat(e.get("message").asString()).doesNotContain("Exception", "at com."));
    assertThat(importedProduct(token)).startsWith(PREFIX);
  }

  private long count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Long.class).single();
  }

  private static String inner(String document) {
    return document.substring(document.indexOf("[{") + 1, document.length() - 2);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
