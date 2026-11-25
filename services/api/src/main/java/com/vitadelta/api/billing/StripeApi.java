package com.vitadelta.api.billing;

import com.vitadelta.api.billing.StripeApiException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Stripe REST API calls of the adapter (ADR-0014), on the JDK HTTP client without the Stripe
 * SDK: create a Checkout Session, create a Billing Portal session, retrieve and cancel a
 * subscription.
 *
 * <p>Every request carries {@code Authorization: Bearer <secret key>} and the pinned {@code
 * Stripe-Version: }{@value #API_VERSION}, so responses have the shape this class parses whatever
 * the account's default version is. Only the configured base URL is called; redirects are not
 * followed; no retries (a failed checkout is retried by the user, a failed webhook sync by Stripe's
 * delivery retries). Bodies are form-encoded; responses are read up to {@code
 * vitadelta.billing.stripe.max-response-bytes} and parsed strictly. Object ids taken from webhook
 * payloads are checked against Stripe's id format before they become part of a URL.
 */
@Component
public class StripeApi {

  /** The pinned Stripe API version (verified on 2026-11-25, ADR-0014). */
  public static final String API_VERSION = "2026-11-18.endive";

  /** Format of a subscription id. */
  public static final Pattern SUBSCRIPTION_ID = Pattern.compile("^sub_[A-Za-z0-9]{1,200}$");

  /** Format of a Checkout Session id. */
  public static final Pattern CHECKOUT_SESSION_ID = Pattern.compile("^cs_[A-Za-z0-9_]{1,250}$");

  /** Format of a customer id. */
  public static final Pattern CUSTOMER_ID = Pattern.compile("^cus_[A-Za-z0-9]{1,200}$");

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private static final Pattern ERROR_WORD = Pattern.compile("^[a-z0-9_]{1,64}$");

  private final RestClient client;
  private final BillingProperties.Stripe settings;

  public StripeApi(BillingProperties properties) {
    this.settings = properties.stripe();
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(settings.connectTimeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(settings.readTimeout());
    this.client = RestClient.builder().requestFactory(requestFactory).build();
  }

  /**
   * What a Checkout Session is created with.
   *
   * @param customerRef the user's existing Stripe customer, or {@code null} (Checkout then creates
   *     one)
   * @param locale {@code tr} or {@code en}
   */
  public record CheckoutRequest(
      UUID userId,
      String priceId,
      String customerRef,
      String successUrl,
      String cancelUrl,
      String locale) {}

  /**
   * A created Checkout Session.
   *
   * @param id {@code cs_...}
   * @param url the hosted page
   * @param expiresAt when the session stops accepting payment, {@code null} when Stripe did not say
   */
  public record CheckoutSession(String id, String url, Instant expiresAt) {}

  /**
   * {@code POST /v1/checkout/sessions} in subscription mode for one unit of the price. The user id
   * is the session's {@code client_reference_id} and is stored as {@code user_id} in the metadata
   * of the session and of the subscription it creates.
   *
   * @return the session id and the URL of the hosted page
   */
  public CheckoutSession createCheckoutSession(CheckoutRequest request) {
    List<Map.Entry<String, String>> form = new ArrayList<>();
    String user = request.userId().toString();
    form.add(Map.entry("mode", "subscription"));
    form.add(Map.entry("line_items[0][price]", request.priceId()));
    form.add(Map.entry("line_items[0][quantity]", "1"));
    form.add(Map.entry("success_url", request.successUrl()));
    form.add(Map.entry("cancel_url", request.cancelUrl()));
    form.add(Map.entry("client_reference_id", user));
    form.add(Map.entry("metadata[user_id]", user));
    form.add(Map.entry("subscription_data[metadata][user_id]", user));
    form.add(Map.entry("locale", request.locale()));
    if (request.customerRef() != null) {
      form.add(Map.entry("customer", requireId(request.customerRef(), CUSTOMER_ID)));
    }
    JsonNode session =
        call("create checkout session", HttpMethod.POST, "/v1/checkout/sessions", form);
    String url = redirectUrl("create checkout session", session);
    JsonNode id = session.get("id");
    if (id == null || !id.isString() || !CHECKOUT_SESSION_ID.matcher(id.stringValue()).matches()) {
      throw new StripeApiException(
          "create checkout session", Reason.MALFORMED, 0, "no session id", null);
    }
    return new CheckoutSession(id.stringValue(), url, epoch(session.get("expires_at")));
  }

  /**
   * {@code POST /v1/checkout/sessions/{id}/expire}: the session stops accepting payment. Stripe
   * answers an error status for a session that is no longer open (for example completed).
   */
  public void expireCheckoutSession(String sessionId) {
    String id = requireId(sessionId, CHECKOUT_SESSION_ID);
    call(
        "expire checkout session",
        HttpMethod.POST,
        "/v1/checkout/sessions/" + id + "/expire",
        List.of());
  }

  /**
   * {@code POST /v1/billing_portal/sessions} for the customer.
   *
   * @return the URL of the hosted portal
   */
  public String createPortalSession(String customerRef, String returnUrl) {
    List<Map.Entry<String, String>> form = new ArrayList<>();
    form.add(Map.entry("customer", requireId(customerRef, CUSTOMER_ID)));
    form.add(Map.entry("return_url", returnUrl));
    JsonNode session =
        call("create portal session", HttpMethod.POST, "/v1/billing_portal/sessions", form);
    return redirectUrl("create portal session", session);
  }

  /** {@code GET /v1/subscriptions/{id}?expand[]=latest_invoice}. */
  public StripeSubscription retrieveSubscription(String subscriptionId) {
    String id = requireId(subscriptionId, SUBSCRIPTION_ID);
    JsonNode body =
        call(
            "retrieve subscription",
            HttpMethod.GET,
            "/v1/subscriptions/"
                + id
                + "?"
                + encode(List.of(Map.entry("expand[]", "latest_invoice"))),
            null);
    return parseSubscription(body);
  }

  /** {@code DELETE /v1/subscriptions/{id}}: cancels the subscription at once. */
  public void cancelSubscription(String subscriptionId) {
    String id = requireId(subscriptionId, SUBSCRIPTION_ID);
    call("cancel subscription", HttpMethod.DELETE, "/v1/subscriptions/" + id, null);
  }

  static String requireId(String value, Pattern format) {
    if (value == null || !format.matcher(value).matches()) {
      throw new IllegalArgumentException("not a Stripe object id of the expected type");
    }
    return value;
  }

  private JsonNode call(
      String operation, HttpMethod method, String path, List<Map.Entry<String, String>> form) {
    if (settings.secretKey() == null) {
      throw new IllegalStateException("the Stripe adapter is not configured");
    }
    URI uri = URI.create(settings.apiBaseUrl() + path);
    try {
      RestClient.RequestBodySpec spec =
          client
              .method(method)
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.secretKey())
              .header("Stripe-Version", API_VERSION)
              .accept(MediaType.APPLICATION_JSON);
      if (form != null) {
        spec =
            spec.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(encode(form).getBytes(StandardCharsets.UTF_8));
      }
      byte[] body =
          spec.exchange((request, response) -> {
            byte[] bytes = readBounded(operation, response.getBody());
            int status = response.getStatusCode().value();
            if (!response.getStatusCode().is2xxSuccessful()) {
              throw new StripeApiException(
                  operation, Reason.HTTP_STATUS, status, errorSummary(bytes), null);
            }
            return bytes;
          });
      return parse(operation, body);
    } catch (StripeApiException e) {
      throw e;
    } catch (RestClientException e) {
      if (e.getCause() instanceof StripeApiException cause) {
        throw cause;
      }
      Reason reason = isTimeout(e) ? Reason.TIMEOUT : Reason.CONNECTION;
      throw new StripeApiException(
          operation, reason, 0, rootCause(e).getClass().getSimpleName(), e);
    }
  }

  private byte[] readBounded(String operation, InputStream body) throws IOException {
    if (body == null) {
      return new byte[0];
    }
    byte[] bytes = body.readNBytes(settings.maxResponseBytes() + 1);
    if (bytes.length > settings.maxResponseBytes()) {
      throw new StripeApiException(operation, Reason.TOO_LARGE, 0, null, null);
    }
    return bytes;
  }

  private static JsonNode parse(String operation, byte[] body) {
    try {
      JsonNode root = JSON.readTree(body);
      if (root == null || !root.isObject()) {
        throw new StripeApiException(operation, Reason.MALFORMED, 0, "not a JSON object", null);
      }
      return root;
    } catch (JacksonException e) {
      throw new StripeApiException(operation, Reason.MALFORMED, 0, "not JSON", null);
    }
  }

  /** Stripe's {@code error.type} and {@code error.code}, when they are plain words. */
  static String errorSummary(byte[] body) {
    try {
      JsonNode error = JSON.readTree(body).get("error");
      if (error == null || !error.isObject()) {
        return null;
      }
      List<String> parts = new ArrayList<>();
      for (String field : List.of("type", "code")) {
        JsonNode value = error.get(field);
        if (value != null
            && value.isString()
            && ERROR_WORD.matcher(value.stringValue()).matches()) {
          parts.add(field + "=" + value.stringValue());
        }
      }
      return parts.isEmpty() ? null : String.join(", ", parts);
    } catch (JacksonException | IllegalArgumentException e) {
      return null;
    }
  }

  private static String redirectUrl(String operation, JsonNode session) {
    JsonNode url = session.get("url");
    if (url == null || !url.isString()) {
      throw new StripeApiException(operation, Reason.MALFORMED, 0, "no url", null);
    }
    String value = url.stringValue();
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new StripeApiException(operation, Reason.MALFORMED, 0, "invalid url", null);
    }
    if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
      throw new StripeApiException(operation, Reason.MALFORMED, 0, "url is not https", null);
    }
    return value;
  }

  /** Maps a subscription object of the pinned API version. */
  static StripeSubscription parseSubscription(JsonNode body) {
    String operation = "retrieve subscription";
    String id = text(body, "id");
    String customer = customerId(body.get("customer"));
    String status = text(body, "status");
    JsonNode livemode = body.get("livemode");
    if (id == null
        || !SUBSCRIPTION_ID.matcher(id).matches()
        || customer == null
        || status == null
        || livemode == null
        || !livemode.isBoolean()) {
      throw new StripeApiException(operation, Reason.MALFORMED, 0, "missing fields", null);
    }
    JsonNode metadata = body.get("metadata");
    String userId = metadata != null && metadata.isObject() ? text(metadata, "user_id") : null;
    Set<String> prices = new LinkedHashSet<>();
    Instant periodEnd = null;
    JsonNode items = body.get("items");
    JsonNode data = items == null ? null : items.get("data");
    if (data != null && data.isArray()) {
      for (JsonNode item : data) {
        JsonNode price = item.get("price");
        String priceId = price == null ? null : text(price, "id");
        if (priceId != null) {
          prices.add(priceId);
        }
        Instant end = epoch(item.get("current_period_end"));
        if (end != null && (periodEnd == null || end.isAfter(periodEnd))) {
          periodEnd = end;
        }
      }
    }
    return new StripeSubscription(
        id,
        customer,
        status,
        livemode.booleanValue(),
        userId,
        prices,
        periodEnd,
        invoiceStatus(body.get("latest_invoice")),
        epoch(body.get("ended_at")));
  }

  /** {@code null} without an invoice; {@code unknown} when the invoice was not expanded. */
  private static String invoiceStatus(JsonNode invoice) {
    if (invoice == null || invoice.isNull()) {
      return null;
    }
    if (!invoice.isObject()) {
      return "unknown";
    }
    String status = text(invoice, "status");
    return status == null ? "unknown" : status;
  }

  private static String customerId(JsonNode customer) {
    if (customer == null) {
      return null;
    }
    String id =
        customer.isObject()
            ? text(customer, "id")
            : customer.isString() ? customer.stringValue() : null;
    return id != null && CUSTOMER_ID.matcher(id).matches() ? id : null;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  private static Instant epoch(JsonNode value) {
    return value != null && value.isIntegralNumber()
        ? Instant.ofEpochSecond(value.longValue())
        : null;
  }

  static String encode(List<Map.Entry<String, String>> pairs) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> pair : pairs) {
      if (!out.isEmpty()) {
        out.append('&');
      }
      out.append(URLEncoder.encode(pair.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(pair.getValue(), StandardCharsets.UTF_8));
    }
    return out.toString();
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
