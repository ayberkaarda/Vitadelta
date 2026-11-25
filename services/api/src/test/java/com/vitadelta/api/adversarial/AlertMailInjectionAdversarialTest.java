package com.vitadelta.api.adversarial;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Section 15.7 case 8 (the alert e-mail part, read back from Mailpit) and section 15.5: products
 * imported by an administrator with a script payload in the name, and with CR/LF plus {@code Bcc:}
 * in name and brand, trigger a real alert. The HTML part shows the name escaped only, the subject
 * and the headers carry no injected header or line break, nobody else receives the mail, the inbox
 * returns the name verbatim as JSON data with {@code nosniff}, and every link in the mail points to
 * the configured web base URL although the requests carried hostile {@code Host}, {@code
 * X-Forwarded-Host}, {@code Forwarded} and {@code Origin} headers.
 */
class AlertMailInjectionAdversarialTest extends AlertsAdversarialTest {

  static final String SCRIPT_NAME = "<script>alert(1)</script><img src=x onerror=alert(2)>";
  static final String VICTIM = "victim@evil.example";
  static final String CRLF_NAME = "Synthetic daily\r\nBcc: " + VICTIM + "\r\nX-Injected: 1";
  static final String CRLF_BRAND = "Synthetic Labs\r\nBcc: " + VICTIM + "\nReply-To: " + VICTIM;

  static final Map<String, String> HOSTILE_HEADERS =
      Map.of(
          "X-Forwarded-Host", "evil.example",
          "X-Forwarded-Proto", "http",
          "X-Forwarded-Port", "8443",
          "Forwarded", "host=evil.example;proto=http",
          "Referer", "https://evil.example/phish");

  private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>)]+");
  private static final Pattern HREF = Pattern.compile("(?i)(?:href|src)\\s*=\\s*\"([^\"]*)\"");

  private String importProduct(String admin, String brand, String name) {
    String code =
        PREFIX + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    String document =
        CatalogAdversarialTest.productDocument(
            code,
            brand,
            name,
            CatalogAdversarialTest.offerJson(
                "Fixture Pharmacy", "120.00", "TRY", "https://fixture-pharmacy.example/tr/p/adv"));
    AttackClient.Req request =
        req("POST", API + "/admin/imports/products").bearer(admin).json(document);
    HOSTILE_HEADERS.forEach(request::header);
    JsonNode job = awaitJob(request.send(), admin);
    assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("SUCCEEDED");
    return code;
  }

  /** A raw HTTP/1.1 exchange over a socket, so that the {@code Host} header can be forged. */
  private int rawExchange(String method, String path, String token, String body) throws Exception {
    byte[] content = body.getBytes(StandardCharsets.UTF_8);
    String head =
        method
            + " "
            + path
            + " HTTP/1.1\r\nHost: evil.example\r\nAuthorization: Bearer "
            + token
            + "\r\nContent-Type: application/json\r\nContent-Length: "
            + content.length
            + "\r\nX-Forwarded-For: "
            + AdversarialIntegrationTest.randomIp()
            + "\r\nX-Forwarded-Host: evil.example\r\nForwarded: host=evil.example;proto=http"
            + "\r\nConnection: close\r\n\r\n";
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(30_000);
      OutputStream out = socket.getOutputStream();
      out.write(head.getBytes(StandardCharsets.ISO_8859_1));
      out.write(content);
      out.flush();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      String status = reader.readLine();
      return Integer.parseInt(status.split(" ")[1]);
    }
  }

  private List<JsonNode> awaitMessages(String to, int count) {
    List<JsonNode> found = new ArrayList<>();
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(250))
        .until(() -> messagesTo(to).size() >= count);
    for (JsonNode summary : messagesToUnchecked(to)) {
      found.add(mailpitUnchecked("/api/v1/message/" + summary.path("ID").asString()));
    }
    return found;
  }

  private JsonNode messagesToUnchecked(String to) {
    try {
      return messagesTo(to);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode mailpitUnchecked(String path) {
    try {
      return mailpit(path);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void hostileProductTextsReachTheMailInertAndInjectNoHeaderOrRecipient() throws Exception {
    String admin = adminToken();
    Resp foreignOrigin =
        req("POST", API + "/admin/imports/products")
            .bearer(admin)
            .header("Origin", "https://evil.example")
            .json(
                CatalogAdversarialTest.productDocument(
                    PREFIX + "ORIGIN", "Synthetic Adversary Labs", SCRIPT_NAME, ""))
            .send();
    assertThat(foreignOrigin.status()).as("a foreign Origin is refused").isEqualTo(403);
    assertThat(count("SELECT count(*) FROM product WHERE code = ?", PREFIX + "ORIGIN")).isZero();
    String scriptCode = importProduct(admin, "Synthetic Adversary Labs", SCRIPT_NAME);
    String crlfCode = importProduct(admin, CRLF_BRAND, CRLF_NAME);
    UUID scriptProduct = productId(scriptCode);
    UUID crlfProduct = productId(crlfCode);
    clearOutbox();

    TestUser user = user();
    String token = token(user);
    String alertBody =
        "{\"productId\":\""
            + scriptProduct
            + "\",\"countryCode\":\"TR\","
            + "\"trigger\":\"PRICE_CHANGE\"}";
    assertThat(rawExchange("POST", API + "/me/alerts", token, alertBody)).isEqualTo(201);
    AttackClient.Req second =
        req("POST", API + "/me/alerts")
            .bearer(token)
            .json(
                Map.of(
                    "productId", crlfProduct.toString(),
                    "countryCode", "TR",
                    "trigger", "PRICE_CHANGE"));
    HOSTILE_HEADERS.forEach(second::header);
    assertThat(second.send().status()).isEqualTo(201);

    JsonNode job =
        awaitJob(
            importOffersCsv(
                CatalogAdversarialTest.CSV_HEADER
                    + offerRow(scriptCode, "99.00", true)
                    + offerRow(crlfCode, "98.00", true),
                admin,
                HOSTILE_HEADERS),
            admin);
    assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("SUCCEEDED");
    drainOutbox();

    List<JsonNode> messages = awaitMessages(user.email(), 2);
    assertThat(messages).hasSize(2);
    for (JsonNode message : messages) {
      String id = message.path("ID").asString();
      String html = message.path("HTML").asString();
      String text = message.path("Text").asString();
      String subject = message.path("Subject").asString();
      assertThat(html)
          .doesNotContain("<script")
          .doesNotContain("<img src=x")
          .doesNotContainPattern("(?i)<[a-z][^>]*onerror");
      assertThat(subject).doesNotContain("\r").doesNotContain("\n");
      assertThat(text).isNotBlank();
      assertThat(message.path("To").size()).isEqualTo(1);
      assertThat(message.path("To").get(0).path("Address").asString())
          .isEqualToIgnoringCase(user.email());
      assertThat(message.path("Cc").size()).as("Cc of %s", subject).isZero();
      assertThat(message.path("Bcc").size()).as("Bcc of %s", subject).isZero();
      assertThat(message.path("ReplyTo").toString()).doesNotContain(VICTIM);

      JsonNode headers = mailpit("/api/v1/message/" + id + "/headers");
      List<String> names = new ArrayList<>();
      for (Map.Entry<String, JsonNode> header : headers.properties()) {
        names.add(header.getKey());
      }
      assertThat(names)
          .as("headers of %s", subject)
          .noneMatch(n -> n.equalsIgnoreCase("Bcc") || n.equalsIgnoreCase("X-Injected"));
      assertThat(headers.toString()).doesNotContain("\\r").doesNotContain("\\n");
      assertThat(headers.path("Reply-To").toString()).doesNotContain(VICTIM);
      assertThat(headers.path("List-Unsubscribe").get(0).asString())
          .isEqualTo("<" + WEB_BASE + "/account>");

      Matcher href = HREF.matcher(html);
      int links = 0;
      while (href.find()) {
        links++;
        String link = href.group(1);
        assertThat(link.equals(WEB_BASE) || link.startsWith(WEB_BASE + "/"))
            .as("link %s in %s", link, subject)
            .isTrue();
      }
      assertThat(links).isGreaterThan(0);
      Matcher url = URL.matcher(text);
      while (url.find()) {
        assertThat(url.group()).as("text link in %s", subject).startsWith(WEB_BASE);
      }
      assertThat(html + text).doesNotContain("evil.example/").doesNotContain("://evil.example");
    }
    String scriptHtml =
        messages.stream()
            .map(m -> m.path("HTML").asString())
            .filter(h -> h.contains("&lt;script&gt;"))
            .findFirst()
            .orElse("");
    assertThat(scriptHtml).as("the script name is present, escaped").contains("&lt;img src=x");
    assertThat(messagesTo(VICTIM)).as("no mail reached the injected recipient").isEmpty();

    Resp inbox = send("GET", API + "/me/notifications?pageSize=100", token);
    assertThat(inbox.status()).isEqualTo(200);
    assertThat(inbox.header("content-type")).startsWith("application/json");
    AttackClient.assertApiSecurityHeaders(inbox);
    List<String> names = new ArrayList<>();
    List<String> brands = new ArrayList<>();
    inbox
        .json()
        .get("items")
        .forEach(item -> {
          names.add(item.get("payload").get("productName").asString());
          brands.add(item.get("payload").get("brand").asString());
        });
    assertThat(names).as("names are returned verbatim as data").contains(SCRIPT_NAME, CRLF_NAME);
    assertThat(brands).contains(CRLF_BRAND);

    for (String path : List.of("/me/saved-products?country=TR", "/me/alerts")) {
      Resp list = send("GET", API + path, token);
      assertThat(list.status()).isEqualTo(200);
      assertThat(list.header("content-type")).startsWith("application/json");
      AttackClient.assertApiSecurityHeaders(list);
      assertThat(list.body()).as(path).contains("\\r\\n").doesNotContain("\r\n");
    }
    List<String> alertNames = new ArrayList<>();
    send("GET", API + "/me/alerts", token)
        .json()
        .get("items")
        .forEach(item -> alertNames.add(item.get("product").get("name").asString()));
    assertThat(alertNames).containsExactlyInAnyOrder(SCRIPT_NAME, CRLF_NAME);
  }
}
