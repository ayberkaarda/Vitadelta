package com.vitadelta.api.catalog.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SeedTest extends PostgresIntegrationTest {

  private static final List<String> TABLES =
      List.of(
          "nutrient",
          "nutrient_form",
          "reference_set",
          "ul_set",
          "unit_conversion",
          "reference_value",
          "upper_limit",
          "country",
          "product",
          "product_nutrient",
          "product_availability",
          "offer");

  @Autowired private SeedService seedService;
  @Autowired private JdbcTemplate jdbc;

  private long version() {
    return jdbc.queryForObject("SELECT version FROM catalog_version WHERE id = 1", Long.class);
  }

  private Map<String, Integer> counts() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String table : TABLES) {
      counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
    }
    return counts;
  }

  /** Product ids by code and offer ids by natural key, as one ordered text. */
  private String ids() {
    String products =
        jdbc.queryForObject(
            "SELECT string_agg(code || '=' || id, ',' ORDER BY code) FROM product", String.class);
    String offers =
        jdbc.queryForObject(
            "SELECT string_agg(p.code || '/' || o.country_code || '/' || o.retailer || '=' || o.id,"
                + " ',' ORDER BY p.code, o.country_code, o.retailer)"
                + " FROM offer o JOIN product p ON p.id = o.product_id",
            String.class);
    return products + "\n" + offers;
  }

  @Test
  void seedingTwiceKeepsCountsAndIdsAndBumpsOncePerRun() throws IOException {
    Path dataDir = TestData.dataDir();
    long start = version();
    SeedService.Result first = seedService.seed(dataDir);
    assertThat(version()).isEqualTo(start + 1);
    assertThat(first.catalogVersion()).isEqualTo(start + 1);
    Map<String, Integer> countsAfterFirst = counts();
    String idsAfterFirst = ids();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM product WHERE synthetic", Integer.class))
        .isGreaterThanOrEqualTo(14);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM product WHERE NOT synthetic AND source = 'DSLD'"
                    + " AND source_ref IS NOT NULL AND source_entry_date IS NOT NULL",
                Integer.class))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM product_nutrient WHERE unit = 'IU'", Integer.class))
        .isPositive();

    int formRows = Files.readAllLines(dataDir.resolve("reference/forms.csv")).size() - 1;
    assertThat(countsAfterFirst.get("nutrient_form")).isEqualTo(formRows);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM upper_limit WHERE btrim(note) = ''", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT note FROM upper_limit WHERE ul_set_code = 'EFSA'"
                    + " AND nutrient_code = 'VIT_A' AND form_code = 'BETA_CAROTENE'",
                String.class))
        .startsWith("exempt: EFSA UL Overview");
    assertThat(
            jdbc.queryForObject(
                "SELECT name_en FROM nutrient_form WHERE code = 'D3_CHOLECALCIFEROL'",
                String.class))
        .isEqualTo("Vitamin D3 (cholecalciferol)");

    SeedService.Result second = seedService.seed(dataDir);
    assertThat(version()).isEqualTo(start + 2);
    assertThat(second.catalogVersion()).isEqualTo(start + 2);
    assertThat(counts()).isEqualTo(countsAfterFirst);
    assertThat(ids()).isEqualTo(idsAfterFirst);
    assertThat(second.report().changedRows()).isZero();
    assertThat(second.report().tables().keySet()).containsAll(TABLES);
  }

  @Test
  void seedRestoresChangedNotesAndFormNamesFromTheFiles() {
    Path dataDir = TestData.dataDir();
    seedService.seed(dataDir);
    String expected =
        jdbc.queryForObject(
            "SELECT note FROM upper_limit WHERE ul_set_code = 'IOM' AND nutrient_code = 'ZINC'",
            String.class);
    jdbc.update(
        "UPDATE upper_limit SET note = 'changed' WHERE ul_set_code = 'IOM'"
            + " AND nutrient_code = 'ZINC'");
    jdbc.update("UPDATE nutrient_form SET name_en = 'changed' WHERE code = 'UBIQUINOL'");
    SeedService.Result result = seedService.seed(dataDir);
    assertThat(result.report().table("upper_limit").updated()).isEqualTo(1);
    assertThat(result.report().table("nutrient_form").updated()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT note FROM upper_limit WHERE ul_set_code = 'IOM'"
                    + " AND nutrient_code = 'ZINC'",
                String.class))
        .isEqualTo(expected);
    assertThat(
            jdbc.queryForObject(
                "SELECT name_en FROM nutrient_form WHERE code = 'UBIQUINOL'", String.class))
        .isEqualTo("Ubiquinol (reduced coenzyme Q10)");
  }

  @Test
  void invalidDataIsRejectedWithRowMessagesAndWritesNothing(@TempDir Path copy) throws IOException {
    copyData(TestData.dataDir(), copy);
    append(copy.resolve("reference/countries.csv"), "QZ,Nowhere,QZD,NO_SUCH_SET,EFSA,en\n");
    append(
        copy.resolve("fixtures/offers.synthetic.csv"),
        "GOLD-A,TR,Wrong Currency Shop,10.00,EUR,true,https://a.example/x,2026-11-20T09:00:00Z\n");
    long before = version();
    Map<String, Integer> countsBefore = counts();

    assertThatThrownBy(() -> seedService.seed(copy))
        .isInstanceOfSatisfying(
            SeedValidationException.class,
            e ->
                assertThat(e.problems())
                    .extracting(SeedProblem::describe)
                    .anySatisfy(m ->
                        assertThat(m)
                            .startsWith("reference/countries.csv row ")
                            .contains("unknown_set")));
    assertThat(version()).isEqualTo(before);
    assertThat(counts()).isEqualTo(countsBefore);

    // With valid reference data the offer row error is reported with its row number.
    Files.copy(
        TestData.dataDir().resolve("reference/countries.csv"),
        copy.resolve("reference/countries.csv"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    int offerRows = Files.readAllLines(copy.resolve("fixtures/offers.synthetic.csv")).size();
    assertThatThrownBy(() -> seedService.seed(copy))
        .isInstanceOfSatisfying(
            SeedValidationException.class,
            e -> {
              assertThat(e.getExitCode()).isEqualTo(SeedValidationException.EXIT_CODE);
              assertThat(e.problems())
                  .extracting(SeedProblem::describe)
                  .containsExactly(
                      "fixtures/offers.synthetic.csv row "
                          + offerRows
                          + ": currency_mismatch: currency EUR differs from the currency of TR");
              assertThat(e.getMessage()).contains("1 problem(s)");
            });
    assertThat(version()).isEqualTo(before);
  }

  @Test
  void missingFilesAndBrokenReferenceRowsAreReported(@TempDir Path copy) throws IOException {
    copyData(TestData.dataDir(), copy);
    Files.delete(copy.resolve("real/products.dsld.json"));
    append(copy.resolve("reference/nutrients.csv"), "bad code,,HERB,kg,-1,x\n");
    append(copy.resolve("reference/forms.csv"), "ANY,NOPE,\n");
    append(copy.resolve("reference/unit_conversions.csv"), "NOPE,kg,mg,0,UNKNOWN,\n");
    append(copy.resolve("reference/unit_conversions.csv"), "ZINC,IU,mg,1,ANY,widened\n");
    append(
        copy.resolve("reference/unit_conversions.csv"),
        "VIT_A,IU,mcg,0.05,BETA_CAROTENE,widened\n");
    append(copy.resolve("reference/reference_values.csv"), "NOPE,VIT_C,80,mcg\n");
    append(copy.resolve("reference/upper_limits.csv"), "EFSA,VIT_C,ANY,100,mg,,\n");
    append(copy.resolve("reference/ul_sets.csv"), "efsa,,\n");
    append(copy.resolve("reference/countries.csv"), "TR,Dup,try,EU_NRV,EFSA,TR\n");
    long before = version();
    assertThatThrownBy(() -> seedService.seed(copy))
        .isInstanceOfSatisfying(
            SeedValidationException.class,
            e -> {
              List<String> codes =
                  e.problems().stream().map(p -> p.file() + ":" + p.problem().code()).toList();
              assertThat(codes)
                  .contains(
                      "reference/nutrients.csv:invalid_code",
                      "reference/nutrients.csv:invalid_text",
                      "reference/nutrients.csv:invalid_value",
                      "reference/nutrients.csv:invalid_unit",
                      "reference/nutrients.csv:invalid_amount",
                      "reference/nutrients.csv:invalid_sort_order",
                      "reference/forms.csv:invalid_form_code",
                      "reference/forms.csv:unknown_nutrient",
                      "reference/unit_conversions.csv:unknown_nutrient",
                      "reference/unit_conversions.csv:invalid_unit",
                      "reference/unit_conversions.csv:invalid_amount",
                      "reference/unit_conversions.csv:invalid_form_code",
                      "reference/unit_conversions.csv:iu_not_allowed",
                      "reference/unit_conversions.csv:unsupported_iu_form",
                      "reference/reference_values.csv:unknown_set",
                      "reference/reference_values.csv:unit_not_canonical",
                      "reference/upper_limits.csv:invalid_exempt_row",
                      "reference/upper_limits.csv:missing_note",
                      "reference/ul_sets.csv:invalid_code",
                      "reference/countries.csv:duplicate_code",
                      "reference/countries.csv:invalid_currency",
                      "reference/countries.csv:invalid_locale");
            });
    assertThat(version()).isEqualTo(before);

    // Reference data restored: the missing products file is reported.
    for (String file : SeedDataReader.REFERENCE_FILES) {
      Files.copy(
          TestData.dataDir().resolve(file),
          copy.resolve(file),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    Files.writeString(copy.resolve("reference/ul_sets.csv"), "code,name\nEFSA,x\n");
    assertThatThrownBy(() -> seedService.seed(copy))
        .isInstanceOfSatisfying(
            SeedValidationException.class,
            e ->
                assertThat(e.problems())
                    .extracting(p -> p.problem().code())
                    .contains("invalid_header"));
    Files.copy(
        TestData.dataDir().resolve("reference/ul_sets.csv"),
        copy.resolve("reference/ul_sets.csv"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertThatThrownBy(() -> seedService.seed(copy))
        .isInstanceOfSatisfying(
            SeedValidationException.class,
            e ->
                assertThat(e.problems())
                    .singleElement()
                    .satisfies(p -> {
                      assertThat(p.file()).isEqualTo("real/products.dsld.json");
                      assertThat(p.problem().code()).isEqualTo("missing_file");
                    }));
    assertThat(version()).isEqualTo(before);
  }

  @Test
  void productCodesMustBeUniqueAcrossFilesAndOfferPairsBelongToOneFile(@TempDir Path copy)
      throws IOException {
    copyData(TestData.dataDir(), copy);
    Files.copy(
        copy.resolve("fixtures/products.synthetic.json"),
        copy.resolve("real/products.dsld.json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    List<String> lines = Files.readAllLines(copy.resolve("fixtures/offers.synthetic.csv"));
    Files.writeString(
        copy.resolve("real/offers.us-demo.synthetic.csv"),
        lines.get(0) + "\n" + lines.get(1).replace(",true,", ",false,") + "\n");
    assertThatThrownBy(() -> seedService.seed(copy))
        .isInstanceOfSatisfying(
            SeedValidationException.class,
            e ->
                assertThat(e.problems())
                    .extracting(p -> p.problem().code())
                    .contains("duplicate_product_code", "duplicate_offer_key"));
  }

  @Test
  void runnerExitsWithCodeTwoOnValidationErrors(@TempDir Path copy) throws IOException {
    copyData(TestData.dataDir(), copy);
    Files.writeString(copy.resolve("reference/nutrients.csv"), "wrong,header\n");
    SeedRunner runner = new SeedRunner(seedService, new SeedProperties(copy));
    assertThatThrownBy(() -> runner.run(null))
        .isInstanceOfSatisfying(
            ExitCodeGenerator.class, e -> assertThat(e.getExitCode()).isEqualTo(2));
    assertThatThrownBy(() -> new SeedRunner(seedService, new SeedProperties(null)).run(null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void runnerSeedsTheConfiguredDirectory() {
    long before = version();
    new SeedRunner(seedService, new SeedProperties(TestData.dataDir())).run(null);
    assertThat(version()).isEqualTo(before + 1);
  }

  private static void append(Path file, String text) throws IOException {
    Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
  }

  private static void copyData(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path source : paths.toList()) {
        Path target = to.resolve(from.relativize(source).toString());
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.copy(source, target);
        }
      }
    }
  }

  @Test
  void pairsDefinedByBothAProductsDocumentAndAnOffersFileAreRejected(@TempDir Path copy)
      throws IOException {
    copyData(TestData.dataDir(), copy);
    Path products = copy.resolve("fixtures/products.synthetic.json");
    tools.jackson.databind.json.JsonMapper mapper =
        tools.jackson.databind.json.JsonMapper.builder().build();
    tools.jackson.databind.node.ObjectNode root =
        (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(products));
    for (tools.jackson.databind.JsonNode product : root.get("products")) {
      if (product.get("code").stringValue().equals("GOLD-A")) {
        tools.jackson.databind.node.ObjectNode offer = mapper.createObjectNode();
        offer.put("country_code", "TR");
        offer.put("retailer", "Json Shop");
        offer.put("price", new java.math.BigDecimal("799.00"));
        offer.put("currency", "TRY");
        offer.put("in_stock", true);
        offer.put("url", "https://json-shop.example/gold-a");
        offer.put("checked_at", "2026-11-20T09:00:00Z");
        ((tools.jackson.databind.node.ObjectNode) product).putArray("offers").add(offer);
      }
    }
    Files.writeString(products, mapper.writeValueAsString(root));
    long before = version();
    assertThatThrownBy(() -> seedService.seed(copy))
        .isInstanceOfSatisfying(
            SeedValidationException.class,
            e ->
                assertThat(e.problems())
                    .singleElement()
                    .satisfies(p -> {
                      assertThat(p.file()).isEqualTo("fixtures/offers.synthetic.csv");
                      assertThat(p.problem().code()).isEqualTo("duplicate_offer_key");
                      assertThat(p.problem().message())
                          .contains("GOLD-A/TR")
                          .contains("fixtures/products.synthetic.json");
                    }));
    assertThat(version()).isEqualTo(before);
  }
}
