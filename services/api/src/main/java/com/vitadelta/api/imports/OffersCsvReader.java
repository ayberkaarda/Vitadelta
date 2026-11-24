package com.vitadelta.api.imports;

import com.vitadelta.api.catalog.OfferValues;
import com.vitadelta.api.common.csv.CsvFormatException;
import com.vitadelta.api.common.csv.CsvParser;
import com.vitadelta.api.common.csv.CsvRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reads and validates an offers CSV file (format in {@code
 * contracts/schemas/offers-csv.columns.md}). Every row is validated; the result carries either
 * offers or row errors, never a partial set.
 */
public final class OffersCsvReader {

  public static final List<String> HEADER =
      List.of(
          "product_code",
          "country_code",
          "retailer",
          "price",
          "currency",
          "in_stock",
          "url",
          "checked_at");

  /** Validated offers, or the problems that prevent the file from being applied. */
  public record Result(List<OfferValues> offers, List<ImportProblem> problems) {

    public Result {
      offers = List.copyOf(offers);
      problems = List.copyOf(problems);
    }

    public boolean valid() {
      return problems.isEmpty();
    }
  }

  private OffersCsvReader() {}

  /**
   * Reads raw file bytes; invalid UTF-8 fails the whole file with {@code invalid_encoding}.
   *
   * @param productExists whether a product with the given code exists
   * @param now import time; {@code checked_at} later than this is rejected
   */
  public static Result read(
      byte[] content, Predicate<String> productExists, CatalogRegistry registry, Instant now) {
    Optional<String> text = Utf8.decode(content);
    if (text.isEmpty()) {
      return failure(ImportProblem.atRow(1, "invalid_encoding", "file is not valid UTF-8"));
    }
    return read(text.get(), productExists, registry, now);
  }

  /** Reads already decoded text. */
  public static Result read(
      String text, Predicate<String> productExists, CatalogRegistry registry, Instant now) {
    List<CsvRecord> records;
    try {
      records = CsvParser.parse(text);
    } catch (CsvFormatException e) {
      return failure(ImportProblem.atRow(1, "invalid_csv", e.getMessage()));
    }
    if (records.isEmpty() || !records.getFirst().fields().equals(HEADER)) {
      return failure(
          ImportProblem.atRow(1, "invalid_header", "header must be " + String.join(",", HEADER)));
    }
    List<ImportProblem> problems = new ArrayList<>();
    List<OfferValues> offers = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (CsvRecord record : records.subList(1, records.size())) {
      int row = record.number();
      if (record.size() != HEADER.size()) {
        problems.add(
            ImportProblem.atRow(
                row,
                "wrong_column_count",
                "expected " + HEADER.size() + " fields, found " + record.size()));
        continue;
      }
      int before = problems.size();
      for (int i = 0; i < HEADER.size(); i++) {
        if (record.get(i).isEmpty()) {
          problems.add(ImportProblem.atRow(row, "missing_value", HEADER.get(i) + " is empty"));
        }
      }
      if (problems.size() > before) {
        continue;
      }
      Optional<OfferValues> offer = validateRow(record, productExists, registry, now, problems);
      if (offer.isPresent()) {
        OfferValues o = offer.get();
        if (!keys.add(o.productCode() + "\u0000" + o.countryCode() + "\u0000" + o.retailer())) {
          problems.add(
              ImportProblem.atRow(
                  row,
                  "duplicate_offer_key",
                  "another row has product "
                      + o.productCode()
                      + ", country "
                      + o.countryCode()
                      + ", retailer "
                      + o.retailer()));
        } else {
          offers.add(o);
        }
      }
    }
    return problems.isEmpty() ? new Result(offers, List.of()) : new Result(List.of(), problems);
  }

  private static Optional<OfferValues> validateRow(
      CsvRecord record,
      Predicate<String> productExists,
      CatalogRegistry registry,
      Instant now,
      List<ImportProblem> problems) {
    int row = record.number();
    int before = problems.size();
    String productCode = record.get(0);
    String countryCode = record.get(1);
    String retailer = record.get(2);
    String currency = record.get(4);
    String inStock = record.get(5);
    String url = record.get(6);
    if (!OfferRules.PRODUCT_CODE.matcher(productCode).matches()
        || !productExists.test(productCode)) {
      problems.add(
          ImportProblem.atRow(row, "unknown_product_code", "no product with code " + productCode));
    }
    Optional<String> countryCurrency =
        OfferRules.COUNTRY_CODE.matcher(countryCode).matches()
            ? registry.currencyOf(countryCode)
            : Optional.empty();
    if (countryCurrency.isEmpty()) {
      problems.add(ImportProblem.atRow(row, "unknown_country", "unknown country " + countryCode));
    }
    if (!OfferRules.isValidRetailer(retailer)) {
      problems.add(
          ImportProblem.atRow(row, "missing_value", "retailer must have 1 to 100 characters"));
    }
    Optional<BigDecimal> price = OfferRules.parsePrice(record.get(3));
    if (price.isEmpty()) {
      problems.add(ImportProblem.atRow(row, "invalid_price", "invalid price " + record.get(3)));
    }
    boolean currencyMatches =
        OfferRules.CURRENCY_CODE.matcher(currency).matches()
            && countryCurrency.map(currency::equals).orElse(true);
    if (!currencyMatches) {
      problems.add(
          ImportProblem.atRow(
              row,
              "currency_mismatch",
              "currency " + currency + " differs from the currency of " + countryCode));
    }
    if (!inStock.equals("true") && !inStock.equals("false")) {
      problems.add(ImportProblem.atRow(row, "invalid_in_stock", "in_stock must be true or false"));
    }
    if (!OfferRules.isValidUrl(url)) {
      problems.add(
          ImportProblem.atRow(
              row, "invalid_offer_url", "url must be an absolute http or https URI"));
    }
    Optional<Instant> checkedAt = OfferRules.parseCheckedAt(record.get(7));
    if (checkedAt.isEmpty()) {
      problems.add(
          ImportProblem.atRow(
              row, "invalid_checked_at", "checked_at must be an RFC 3339 date-time with offset"));
    } else if (checkedAt.get().isAfter(now)) {
      problems.add(ImportProblem.atRow(row, "checked_at_in_future", "checked_at is in the future"));
    }
    if (problems.size() > before) {
      return Optional.empty();
    }
    return Optional.of(
        new OfferValues(
            productCode,
            countryCode,
            retailer,
            price.orElseThrow(),
            currency,
            Boolean.parseBoolean(inStock),
            url,
            checkedAt.orElseThrow()));
  }

  private static Result failure(ImportProblem problem) {
    return new Result(List.of(), List.of(problem));
  }
}
