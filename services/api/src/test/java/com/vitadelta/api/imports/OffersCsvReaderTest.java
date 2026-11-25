package com.vitadelta.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.config.properties.ImportLimitsProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OffersCsvReaderTest {

  private static final String HEADER =
      "product_code,country_code,retailer,price,currency,in_stock,url,checked_at\n";
  private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");
  private static final Set<String> PRODUCTS = Set.of("GOLD-A", "GOLD-B", "ZN40");

  private final CatalogRegistry registry = TestRegistry.create();

  private OffersCsvReader.Result read(String text) {
    return OffersCsvReader.read(text, PRODUCTS::contains, registry, NOW);
  }

  private void rejected(String row, String code) {
    OffersCsvReader.Result result = read(HEADER + row + "\n");
    assertThat(result.valid()).isFalse();
    assertThat(result.offers()).isEmpty();
    assertThat(result.problems()).extracting(ImportProblem::code).contains(code);
    assertThat(result.problems()).allSatisfy(p -> assertThat(p.location()).isEqualTo("row 2"));
  }

  @Test
  void readsTheContractExample() {
    OffersCsvReader.Result result =
        read(
            HEADER
                + "GOLD-A,TR,Fixture Pharmacy,800.00,TRY,true,https://shop.example.com/gold-a,2026-11-20T09:00:00Z\n"
                + "GOLD-B,TR,\"Northline Store, Kadikoy\",915.50,TRY,false,https://northline.example.com/p?id=gold-b&src=feed,2026-11-19T18:30:00+03:00\n");
    assertThat(result.valid()).isTrue();
    assertThat(result.offers()).hasSize(2);
    OfferValues second = result.offers().get(1);
    assertThat(second.retailer()).isEqualTo("Northline Store, Kadikoy");
    assertThat(second.price()).isEqualByComparingTo("915.50");
    assertThat(second.inStock()).isFalse();
    assertThat(second.checkedAt()).isEqualTo(Instant.parse("2026-11-19T15:30:00Z"));
  }

  @Test
  void headerOnlyFileIsValidAndEmpty() {
    OffersCsvReader.Result result = read(HEADER);
    assertThat(result.valid()).isTrue();
    assertThat(result.offers()).isEmpty();
  }

  @Test
  void wrongHeaderFailsTheFile() {
    OffersCsvReader.Result result = read("Product_Code,country_code\n");
    assertThat(result.problems())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.code()).isEqualTo("invalid_header");
          assertThat(p.location()).isEqualTo("row 1");
        });
  }

  @Test
  void emptyFileFailsWithInvalidHeader() {
    assertThat(read("").problems())
        .extracting(ImportProblem::code)
        .containsExactly("invalid_header");
  }

  @Test
  void malformedCsvFailsTheFile() {
    assertThat(read(HEADER + "\"GOLD-A,TR\n").problems())
        .extracting(ImportProblem::code)
        .containsExactly("invalid_csv");
  }

  @Test
  void invalidUtf8FailsTheFile() {
    byte[] bytes = (HEADER + "GOLD-A").getBytes(StandardCharsets.UTF_8);
    byte[] broken = new byte[bytes.length + 1];
    System.arraycopy(bytes, 0, broken, 0, bytes.length);
    broken[bytes.length] = (byte) 0xC3;
    assertThat(OffersCsvReader.read(broken, PRODUCTS::contains, registry, NOW).problems())
        .extracting(ImportProblem::code)
        .containsExactly("invalid_encoding");
  }

  @Test
  void validBytesAreDecoded() {
    byte[] bytes =
        ("﻿"
                + HEADER
                + "ZN40,TR,Fixture Pharmacy,240,TRY,true,http://a.example/z,2026-11-20T09:00:00Z\n")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(OffersCsvReader.read(bytes, PRODUCTS::contains, registry, NOW).offers()).hasSize(1);
  }

  @ParameterizedTest(name = "{1}: {0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "GOLD-A,TR,Fixture Pharmacy,800.00,TRY,true,https://a.example/x|wrong_column_count",
        "GOLD-A,TR,,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|missing_value",
        "NOPE,TR,Shop,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|unknown_product_code",
        "gold-a,TR,Shop,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|unknown_product_code",
        "GOLD-A,XX,Shop,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|unknown_country",
        "GOLD-A,tr,Shop,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|unknown_country",
        "GOLD-A,TR,Shop,0,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|invalid_price",
        "GOLD-A,TR,Shop,800.001,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|invalid_price",
        "GOLD-A,TR,Shop,\"1,000.00\",TRY,true,https://a.example/x,2026-11-20T09:00:00Z|invalid_price",
        "GOLD-A,TR,Shop,1e3,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|invalid_price",
        "GOLD-A,TR,Shop,10000000000.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z|invalid_price",
        "GOLD-A,TR,Shop,800.00,USD,true,https://a.example/x,2026-11-20T09:00:00Z|currency_mismatch",
        "GOLD-A,TR,Shop,800.00,try,true,https://a.example/x,2026-11-20T09:00:00Z|currency_mismatch",
        "GOLD-A,TR,Shop,800.00,TRY,TRUE,https://a.example/x,2026-11-20T09:00:00Z|invalid_in_stock",
        "GOLD-A,TR,Shop,800.00,TRY,yes,https://a.example/x,2026-11-20T09:00:00Z|invalid_in_stock",
        "GOLD-A,TR,Shop,800.00,TRY,true,javascript:alert(1),2026-11-20T09:00:00Z|invalid_offer_url",
        "GOLD-A,TR,Shop,800.00,TRY,true,ftp://a.example/x,2026-11-20T09:00:00Z|invalid_offer_url",
        "GOLD-A,TR,Shop,800.00,TRY,true,/relative/path,2026-11-20T09:00:00Z|invalid_offer_url",
        "GOLD-A,TR,Shop,800.00,TRY,true,https://,2026-11-20T09:00:00Z|invalid_offer_url",
        "GOLD-A,TR,Shop,800.00,TRY,true,https://a.example/x y,2026-11-20T09:00:00Z|invalid_offer_url",
        "GOLD-A,TR,Shop,800.00,TRY,true,https://a.example/x,2026-11-20|invalid_checked_at",
        "GOLD-A,TR,Shop,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00|invalid_checked_at",
        "GOLD-A,TR,Shop,800.00,TRY,true,https://a.example/x,2026-11-24T00:00:01Z|checked_at_in_future"
      })
  void rejectsInvalidRows(String row, String code) {
    rejected(row, code);
  }

  @Test
  void tooLongUrlIsRejected() {
    rejected(
        "GOLD-A,TR,Shop,800.00,TRY,true,https://a.example/"
            + "x".repeat(2048)
            + ",2026-11-20T09:00:00Z",
        "invalid_offer_url");
  }

  @Test
  void tooLongRetailerIsRejected() {
    rejected(
        "GOLD-A,TR,"
            + "r".repeat(101)
            + ",800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z",
        "missing_value");
  }

  @Test
  void duplicateNaturalKeyIsRejectedAndRowsAreNumberedFromTheHeader() {
    OffersCsvReader.Result result =
        read(
            HEADER
                + "GOLD-A,TR,Shop,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z\n"
                + "\n"
                + "GOLD-A,TR,Shop,810.00,TRY,true,https://a.example/y,2026-11-20T09:00:00Z\n"
                + "GOLD-A,TR,shop,810.00,TRY,true,https://a.example/y,2026-11-20T09:00:00Z\n");
    assertThat(result.problems())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.code()).isEqualTo("duplicate_offer_key");
          assertThat(p.location()).isEqualTo("row 3");
        });
  }

  private static final String VALID_ROW =
      "GOLD-A,TR,Shop %d,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z\n";

  @Test
  void moreRowsThanTheLimitFailTheFileBeforeAnyRowIsValidated() {
    ImportLimitsProperties limits = new ImportLimitsProperties(3, 10, 2048, 100);
    StringBuilder csv = new StringBuilder(HEADER);
    for (int i = 0; i < 3; i++) {
      csv.append(VALID_ROW.formatted(i));
    }
    assertThat(
            OffersCsvReader.read(csv.toString(), PRODUCTS::contains, registry, NOW, limits).valid())
        .isTrue();
    csv.append("NOPE,XX,,bad,bad,bad,bad,bad\n");
    OffersCsvReader.Result result =
        OffersCsvReader.read(csv.toString(), PRODUCTS::contains, registry, NOW, limits);
    assertThat(result.problems())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.code()).isEqualTo("too_many_records");
          assertThat(p.location()).isEqualTo("row 1");
        });
    assertThat(result.rows()).isZero();
  }

  @Test
  void aFieldLongerThanTheLimitIsARowErrorAndIsNotEchoed() {
    String huge = "M".repeat(1024 * 1024);
    OffersCsvReader.Result result =
        read(
            HEADER
                + "GOLD-A,TR,"
                + huge
                + ",800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z\n");
    assertThat(result.problems())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.code()).isEqualTo("field_too_long");
          assertThat(p.location()).isEqualTo("row 2");
          assertThat(p.message()).doesNotContain("MMMM");
        });
  }

  @Test
  void validationStopsAfterTheErrorBoundWithOneSummaryError() {
    ImportLimitsProperties limits = new ImportLimitsProperties(100_000, 10, 2048, 5);
    StringBuilder csv = new StringBuilder(HEADER);
    for (int i = 0; i < 20_000; i++) {
      csv.append("NOPE,TR,Shop ")
          .append(i)
          .append(",800.00,TRY,true,https://a.example/x,")
          .append("2026-11-20T09:00:00Z\n");
    }
    OffersCsvReader.Result result =
        OffersCsvReader.read(csv.toString(), PRODUCTS::contains, registry, NOW, limits);
    assertThat(result.problems()).hasSize(6);
    assertThat(result.problems().subList(0, 5))
        .extracting(ImportProblem::code)
        .containsOnly("unknown_product_code");
    assertThat(result.problems().get(5).code()).isEqualTo("too_many_errors");
    assertThat(result.problems().get(5).location()).isEqualTo("row 7");
    assertThat(result.rows()).isEqualTo(20_000);
  }

  @Test
  void checkTimesAreTruncatedToTheDatabasePrecision() {
    OffersCsvReader.Result result =
        read(
            HEADER
                + "GOLD-A,TR,Shop,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00.123456789Z\n");
    assertThat(result.offers().getFirst().checkedAt())
        .isEqualTo(Instant.parse("2026-11-20T09:00:00.123456Z"));
  }

  @Test
  void anUnparseableFileIsReportedWithAFixedMessageAndItsLine() {
    assertThat(read(HEADER + "GOLD-A,\"SECRET,TR\n").problems())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.code()).isEqualTo("invalid_csv");
          assertThat(p.message()).isEqualTo("the file is not valid CSV (line 2)");
        });
  }

  @Test
  void allRowErrorsAreReported() {
    OffersCsvReader.Result result =
        read(
            HEADER
                + "NOPE,TR,Shop,800.00,TRY,true,https://a.example/x,2026-11-20T09:00:00Z\n"
                + "GOLD-A,TR,Shop,800.00,EUR,true,https://a.example/x,2026-11-20T09:00:00Z\n");
    assertThat(result.problems())
        .extracting(ImportProblem::location)
        .containsExactly("row 2", "row 3");
    assertThat(result.problems().getFirst().describe())
        .isEqualTo("unknown_product_code: no product with code NOPE (row 2)");
  }
}
