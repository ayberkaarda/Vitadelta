package com.vitadelta.api.fx;

import com.vitadelta.api.fx.FxProviderException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default {@link FxRateProvider}: the Frankfurter v2 latest-rates endpoint with the ECB provider
 * selected explicitly (ADR-0009), {@code GET {VITADELTA_FX_BASE_URL}/rates?base=TRY
 * &quotes=EUR,GBP,USD&providers=ECB}.
 *
 * <p>Only the configured base URL is ever called (no redirects are followed, no URL comes from a
 * caller). Connect timeout 2 s, read timeout 5 s, no retries (the refresh job retries on its next
 * run). The answer is parsed strictly as the verified shape, a JSON array of {@code {date, base,
 * quote, rate}} objects, and rejected as a whole ({@link FxProviderException}) when:
 *
 * <ul>
 *   <li>the status is not 2xx, the content type is not JSON, or the body exceeds {@code
 *       vitadelta.fx.max-response-bytes};
 *   <li>the body is not JSON, not an array, or a row is not an object with a string {@code date}
 *       (ISO date), string {@code base}, string {@code quote} and numeric {@code rate} (other
 *       members are ignored);
 *   <li>the array is empty, a row names another base, an unrequested or repeated quote, or a
 *       requested quote is missing;
 *   <li>a rate is not positive, is {@code 10^10} or more (outside {@code numeric(18,8)}), or rounds
 *       to 0 at 8 decimals;
 *   <li>the rows carry <b>different dates</b>: one fetch must describe one ECB publication, so a
 *       mixed answer is rejected instead of picking a date (ECB publishes all currencies at once;
 *       every verified answer had one date);
 *   <li>the date lies more than one day after today (UTC), which no real publication does.
 * </ul>
 *
 * A rejection names what the provider does not publish, so the refresh job can request the base
 * again without it: a 400 or 422 answer reports the requested quotes its body names (for example
 * {@code {"status":422,"message":"invalid currency: XYZ"}}, verified on 2026-11-25); the base
 * counts as rejected only when a 422 answer is exactly that message for the requested base ({@link
 * #namesInvalidBase}); a 200 answer that leaves requested quotes out (the verified answer for an
 * ISO code the ECB does not publish, such as {@code ARS}) reports the missing ones.
 *
 * <p>Rates are parsed from the JSON text as decimals (never through a double) and rounded half-even
 * to the 8 stored decimals. Failure messages carry at most a {@link ResponseSummary} of the body.
 */
@Component
public class FrankfurterFxRateProvider implements FxRateProvider {

  /** {@code fx_rate.source} of the rows fetched here. */
  public static final String SOURCE = "FRANKFURTER";

  /** Provider key of the ECB reference rates in Frankfurter v2. */
  static final String PROVIDER = "ECB";

  /** Exclusive upper bound of a storable rate ({@code numeric(18,8)}). */
  static final BigDecimal MAX_RATE = BigDecimal.TEN.pow(10);

  /** Decimals of a stored rate. */
  static final int RATE_SCALE = 8;

  private static final Pattern CODE = Pattern.compile("^[A-Z]{3}$");

  private static final Pattern NAMED_CODE = Pattern.compile("(?<![A-Za-z])[A-Z]{3}(?![A-Za-z])");

  /** The verified message of a 422 answer for a currency the provider does not know. */
  private static final Pattern INVALID_CURRENCY = Pattern.compile("invalid currency: ([A-Z]{3})");

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private final RestClient client;
  private final String baseUrl;
  private final int maxResponseBytes;
  private final Clock clock;

  public FrankfurterFxRateProvider(FxProperties properties, Clock clock) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.connectTimeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    this.client = RestClient.builder().requestFactory(requestFactory).build();
    this.baseUrl = properties.baseUrl();
    this.maxResponseBytes = properties.maxResponseBytes();
    this.clock = clock;
  }

  @Override
  public String source() {
    return SOURCE;
  }

  @Override
  public FxQuote fetchLatest(String baseCurrency, Set<String> quoteCurrencies) {
    requireCode(baseCurrency);
    if (quoteCurrencies == null || quoteCurrencies.isEmpty()) {
      throw new IllegalArgumentException("at least one quote currency is required");
    }
    Set<String> quotes = new TreeSet<>(quoteCurrencies);
    quotes.forEach(FrankfurterFxRateProvider::requireCode);
    if (quotes.contains(baseCurrency)) {
      throw new IllegalArgumentException("the base currency cannot be a quote currency");
    }
    URI uri =
        UriComponentsBuilder.fromUriString(baseUrl)
            .path("/rates")
            .queryParam("base", baseCurrency)
            .queryParam("quotes", String.join(",", quotes))
            .queryParam("providers", PROVIDER)
            .build()
            .toUri();
    return parse(baseCurrency, quotes, fetch(uri, baseCurrency, quotes));
  }

  private byte[] fetch(URI uri, String base, Set<String> quotes) {
    try {
      return client
          .get()
          .uri(uri)
          .accept(MediaType.APPLICATION_JSON)
          .exchange((request, response) -> {
            byte[] body = readBounded(response.getBody());
            if (!response.getStatusCode().is2xxSuccessful()) {
              int status = response.getStatusCode().value();
              Set<String> named = namedCodes(body);
              Set<String> unsupported = new TreeSet<>(quotes);
              unsupported.retainAll(named);
              throw new FxProviderException(
                  Reason.HTTP_STATUS,
                  "status " + status + ", " + ResponseSummary.of(body),
                  status,
                  status == 400 || status == 422 ? unsupported : Set.of(),
                  status == 422 && namesInvalidBase(body, base));
            }
            MediaType type = response.getHeaders().getContentType();
            if (type == null
                || !(type.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || "json".equals(type.getSubtypeSuffix()))) {
              throw new FxProviderException(
                  Reason.MALFORMED,
                  "content type "
                      + (type == null ? "missing" : type.getType() + "/" + type.getSubtype())
                      + ", "
                      + ResponseSummary.of(body));
            }
            return body;
          });
    } catch (FxProviderException e) {
      throw e;
    } catch (RestClientException e) {
      if (e.getCause() instanceof FxProviderException cause) {
        throw cause;
      }
      Reason reason = isTimeout(e) ? Reason.TIMEOUT : Reason.CONNECTION;
      throw new FxProviderException(
          reason, "request failed: " + rootCause(e).getClass().getSimpleName(), e);
    }
  }

  private byte[] readBounded(InputStream body) throws IOException {
    if (body == null) {
      return new byte[0];
    }
    byte[] bytes = body.readNBytes(maxResponseBytes + 1);
    if (bytes.length > maxResponseBytes) {
      throw new FxProviderException(
          Reason.TOO_LARGE, "body exceeds " + maxResponseBytes + " bytes");
    }
    return bytes;
  }

  /** Parses and checks the verified v2 shape; see the class description for the rules. */
  FxQuote parse(String base, Set<String> quotes, byte[] body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JacksonException e) {
      throw new FxProviderException(
          Reason.MALFORMED, "body is not JSON, " + ResponseSummary.of(body));
    }
    if (root == null || !root.isArray()) {
      throw new FxProviderException(
          Reason.MALFORMED, "body is not a JSON array, " + ResponseSummary.of(body));
    }
    if (root.isEmpty()) {
      // With a single requested quote, an empty answer means that pair is not published.
      throw new FxProviderException(
          Reason.INVALID_ROWS,
          "no rates in the answer",
          0,
          quotes.size() == 1 ? quotes : Set.of(),
          false);
    }
    LocalDate asOf = null;
    Map<String, BigDecimal> rates = new TreeMap<>();
    int index = 0;
    for (JsonNode row : root) {
      String where = "row " + index++;
      if (!row.isObject()) {
        throw new FxProviderException(Reason.MALFORMED, where + " is not an object");
      }
      JsonNode date = row.get("date");
      JsonNode rowBase = row.get("base");
      JsonNode quote = row.get("quote");
      JsonNode rate = row.get("rate");
      if (date == null
          || !date.isString()
          || rowBase == null
          || !rowBase.isString()
          || quote == null
          || !quote.isString()
          || rate == null
          || !rate.isNumber()) {
        throw new FxProviderException(
            Reason.MALFORMED, where + " lacks a string date, base, quote or a numeric rate");
      }
      LocalDate rowDate;
      try {
        rowDate = LocalDate.parse(date.stringValue());
      } catch (DateTimeParseException e) {
        throw new FxProviderException(Reason.MALFORMED, where + " has an invalid date");
      }
      if (!base.equals(rowBase.stringValue())) {
        throw new FxProviderException(Reason.INVALID_ROWS, where + " is for another base");
      }
      String code = quote.stringValue();
      if (!quotes.contains(code)) {
        throw new FxProviderException(Reason.INVALID_ROWS, where + " has an unrequested quote");
      }
      if (rates.containsKey(code)) {
        throw new FxProviderException(Reason.INVALID_ROWS, where + " repeats quote " + code);
      }
      if (asOf != null && !asOf.equals(rowDate)) {
        throw new FxProviderException(Reason.INVALID_ROWS, "the rows carry different dates");
      }
      asOf = rowDate;
      rates.put(code, validRate(rate.decimalValue(), where));
    }
    if (!rates.keySet().equals(quotes)) {
      Set<String> missing = new TreeSet<>(quotes);
      missing.removeAll(rates.keySet());
      throw new FxProviderException(
          Reason.INVALID_ROWS, "quotes missing: " + missing, 0, missing, false);
    }
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    if (asOf.isAfter(today.plusDays(1))) {
      throw new FxProviderException(Reason.INVALID_ROWS, "the rate date lies in the future");
    }
    return new FxQuote(base, asOf, rates);
  }

  private static BigDecimal validRate(BigDecimal value, String where) {
    if (value.signum() <= 0 || value.compareTo(MAX_RATE) >= 0) {
      throw new FxProviderException(Reason.INVALID_ROWS, where + " has a rate out of range");
    }
    BigDecimal scaled = value.setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    if (scaled.signum() <= 0) {
      throw new FxProviderException(Reason.INVALID_ROWS, where + " has a rate out of range");
    }
    return scaled;
  }

  /**
   * Whether a 422 body is the verified "unknown currency" answer for {@code base}: a JSON object
   * whose {@code message} member is a string that is exactly {@code invalid currency: <base>}
   * (other members are ignored). Free text that merely contains the code, a message naming another
   * or several currencies, and a body that is not such an object are not.
   */
  static boolean namesInvalidBase(byte[] body, String base) {
    if (body == null || body.length == 0) {
      return false;
    }
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JacksonException e) {
      return false;
    }
    if (root == null || !root.isObject()) {
      return false;
    }
    JsonNode message = root.get("message");
    if (message == null || !message.isString()) {
      return false;
    }
    Matcher matcher = INVALID_CURRENCY.matcher(message.stringValue());
    return matcher.matches() && matcher.group(1).equals(base);
  }

  /** The currency codes (three upper-case letters) an error body names. */
  static Set<String> namedCodes(byte[] body) {
    Set<String> codes = new TreeSet<>();
    if (body == null) {
      return codes;
    }
    Matcher matcher = NAMED_CODE.matcher(new String(body, StandardCharsets.UTF_8));
    while (matcher.find()) {
      codes.add(matcher.group());
    }
    return codes;
  }

  private static void requireCode(String code) {
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("not an ISO 4217 code: " + code);
    }
  }

  private static boolean isTimeout(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
        return true;
      }
    }
    return false;
  }

  private static Throwable rootCause(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root;
  }
}
