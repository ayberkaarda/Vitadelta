package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertNoLeak;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.identity.SecureTokens;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/**
 * Section 15.4 re-attacked from outside, independently of the implementing package's own tests: a
 * full hostile session with the request, security and application loggers at {@code TRACE} leaves
 * no token, cookie, password, promo code or full e-mail address in any log line; error bodies of
 * every kind name no class, package, SQL or host; the metrics endpoint is not on the public port
 * and its labels carry no user identifier; a hostile {@code X-Request-Id} (CR, LF, folded lines,
 * control and line-separator characters, script payloads, 10 kB) never reaches a log line or a
 * response unsanitised (section 15.7 case 18); and no API response sends HSTS in the dev profile
 * (case 11).
 */
@ExtendWith(OutputCaptureExtension.class)
class LeakageAdversarialTest extends AdversarialIntegrationTest {

  private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  /**
   * Loggers raised to {@code TRACE} for the session: the application and the request pipeline (MVC,
   * message converters, security). The ORM bind-parameter logger and the servlet container wire
   * loggers are left out on purpose: they print bound SQL values or raw request bytes by design and
   * must stay off in every profile.
   */
  private static final List<String> TRACED =
      List.of(
          "com.vitadelta",
          "org.springframework.web",
          "org.springframework.http",
          "org.springframework.security");

  @Autowired LoggingSystem loggingSystem;
  @Autowired Environment environment;
  @LocalManagementPort int managementPort;

  private Map<String, LogLevel> raiseLoggers() {
    Map<String, LogLevel> previous = new LinkedHashMap<>();
    for (String name : TRACED) {
      LoggerConfiguration config = loggingSystem.getLoggerConfiguration(name);
      previous.put(name, config == null ? null : config.getConfiguredLevel());
      loggingSystem.setLogLevel(name, LogLevel.TRACE);
    }
    return previous;
  }

  private void restoreLoggers(Map<String, LogLevel> previous) {
    previous.forEach(loggingSystem::setLogLevel);
  }

  private Resp guardedJson(String method, String path, String json) {
    return req(method, path).header("X-Requested-With", "vitadelta").json(json).send();
  }

  // ---------- Log leakage of a full hostile session ----------

  @Test
  void aFullHostileSessionAtTraceLeavesNoSecretInAnyLogLine(CapturedOutput output) {
    Set<String> secrets = new LinkedHashSet<>();
    String email = uniqueEmail();
    String newPassword = "copper-meadow-violin-" + UUID.randomUUID().toString().substring(0, 6);
    String wrongPassword = "wrong-" + UUID.randomUUID().toString().substring(0, 12);
    String unquoted = "unquotedSecret" + UUID.randomUUID().toString().replace("-", "");
    secrets.add(email);
    secrets.add(URLEncoder.encode(email, StandardCharsets.UTF_8));
    secrets.add(email.toUpperCase(Locale.ROOT));
    secrets.add(PASSWORD);
    secrets.add(newPassword);
    secrets.add(wrongPassword);
    secrets.add(unquoted);
    Map<String, LogLevel> previous = raiseLoggers();
    try {
      register(email, PASSWORD);
      String verifyToken = mail().awaitToken(email, 1);
      secrets.add(verifyToken);
      String guessed = SecureTokens.newToken();
      secrets.add(guessed);
      assertThat(postJson(API + "/auth/verify-email", Map.of("token", guessed)).status())
          .isEqualTo(400);
      assertThat(postJson(API + "/auth/verify-email", Map.of("token", verifyToken)).status())
          .isEqualTo(204);

      ip = randomIp();
      assertThat(
              postJson(API + "/auth/login", Map.of("email", email, "password", wrongPassword))
                  .status())
          .isEqualTo(401);
      // Broken JSON that carries the password as a bare token, and as a truncated string.
      Resp bare =
          req("POST", API + "/auth/login")
              .json("{\"email\":\"" + email + "\",\"password\":" + unquoted + "}")
              .send();
      assertThat(bare.status()).isBetween(400, 422);
      Resp truncated =
          req("POST", API + "/auth/login")
              .json("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD)
              .send();
      assertThat(truncated.status()).isBetween(400, 422);
      Resp typed =
          req("POST", API + "/auth/login")
              .json("{\"email\":[\"" + email + "\"],\"password\":{\"v\":\"" + PASSWORD + "\"}}")
              .send();
      assertThat(typed.status()).isBetween(400, 422);

      Session session = login(email, PASSWORD);
      secrets.add(session.accessToken());
      secrets.add(session.refreshToken());
      assertThat(get(API + "/me", session.accessToken()).status()).isEqualTo(200);
      String forgedBearer =
          session.accessToken().substring(0, session.accessToken().length() - 4) + "AAAA";
      secrets.add(forgedBearer);
      assertThat(get(API + "/me", forgedBearer).status()).isEqualTo(401);

      Resp rotated = refresh(session.refreshToken());
      assertThat(rotated.status()).isEqualTo(200);
      String successor = rotated.cookieValue("vd_refresh");
      secrets.add(successor);
      secrets.add(rotated.json().get("accessToken").asString());
      String garbageCookie = SecureTokens.newToken();
      secrets.add(garbageCookie);
      assertThat(refresh(garbageCookie).status()).isEqualTo(401);
      clock.advance(Duration.ofSeconds(25));
      assertThat(refresh(session.refreshToken()).status()).isEqualTo(401);

      Resp anonymous = req("GET", API + "/session/quota").send();
      String anonCookie = anonymous.cookieValue("vd_anon");
      secrets.add(anonCookie);
      String tamperedAnon = anonCookie.substring(0, anonCookie.length() - 3) + "xyz";
      secrets.add(tamperedAnon);
      req("GET", API + "/session/quota").cookie("vd_anon", tamperedAnon).send();
      req("GET", API + "/session/quota").cookie("vd_anon", anonCookie).send();

      ip = randomIp();
      assertThat(postJson(API + "/auth/forgot-password", Map.of("email", email)).status())
          .isEqualTo(202);
      String resetToken = mail().awaitToken(email, 2);
      secrets.add(resetToken);
      assertThat(
              postJson(
                      API + "/auth/reset-password",
                      Map.of("token", resetToken, "newPassword", newPassword))
                  .status())
          .isEqualTo(204);
      Session again = login(email, newPassword);
      secrets.add(again.accessToken());
      secrets.add(again.refreshToken());

      String admin = adminToken();
      secrets.add(admin);
      Resp search =
          req("GET", API + "/admin/users?q=" + URLEncoder.encode(email, StandardCharsets.UTF_8))
              .bearer(admin)
              .send();
      assertThat(search.status()).as(search.toString()).isEqualTo(200);
      Resp created =
          req("POST", API + "/admin/promo-codes")
              .bearer(admin)
              .json(Map.of("planCode", "PRO", "durationDays", 30, "maxRedemptions", 2))
              .send();
      assertThat(created.status()).as(created.toString()).isEqualTo(201);
      String promo = created.json().get("code").asString();
      secrets.add(promo);
      String unknownPromo = randomCode();
      secrets.add(unknownPromo);
      ip = randomIp();
      Resp redeemed =
          req("POST", API + "/me/subscription/promo")
              .bearer(again.accessToken())
              .json(Map.of("code", promo))
              .send();
      assertThat(redeemed.status()).as(redeemed.toString()).isEqualTo(200);
      req("POST", API + "/me/subscription/promo")
          .bearer(again.accessToken())
          .json(Map.of("code", promo))
          .send();
      req("POST", API + "/me/subscription/promo")
          .bearer(again.accessToken())
          .json(Map.of("code", unknownPromo))
          .send();
      req("POST", API + "/me/subscription/promo")
          .bearer(again.accessToken())
          .json("{\"code\":" + unknownPromo.replace("-", "") + "}")
          .send();
      secrets.add(unknownPromo.replace("-", ""));

      Resp wrongDelete =
          req("DELETE", API + "/me")
              .bearer(again.accessToken())
              .json(Map.of("password", wrongPassword))
              .send();
      assertThat(wrongDelete.status()).isGreaterThanOrEqualTo(400);
      Resp deleted =
          req("DELETE", API + "/me")
              .bearer(again.accessToken())
              .json(Map.of("password", newPassword))
              .send();
      assertThat(deleted.status()).as(deleted.toString()).isEqualTo(204);
    } finally {
      restoreLoggers(previous);
    }

    String logs = output.getAll();
    assertThat(logs).as("the session was logged at trace level").contains("Account deleted");
    List<String> leaked = new ArrayList<>();
    for (String secret : secrets) {
      if (logs.contains(secret)) {
        leaked.add(secret + " in: " + lineWith(logs, secret));
      }
    }
    // Parts of credentials are leaks too.
    for (String token : secrets) {
      if (token.matches("[A-Za-z0-9_-]{43}") && logs.contains(token.substring(0, 20))) {
        leaked.add("prefix of " + token + " in: " + lineWith(logs, token.substring(0, 20)));
      }
    }
    assertThat(leaked).as("credentials or personal data in the log").isEmpty();
  }

  private static String lineWith(String logs, String fragment) {
    for (String line : logs.split("\\R")) {
      if (line.contains(fragment)) {
        return line.length() > 400 ? line.substring(0, 400) + "..." : line;
      }
    }
    return "";
  }

  // ---------- Error bodies ----------

  private List<String> forbiddenInBodies() {
    List<String> markers =
        new ArrayList<>(
            List.of(
                "com.vitadelta",
                "vitadelta.api",
                ".java",
                "java.",
                "jakarta.",
                "Caused by",
                "jdbc",
                "JDBC",
                "SELECT ",
                "INSERT ",
                "UPDATE ",
                "DELETE FROM",
                " WHERE ",
                "constraint",
                "localhost",
                "127.0.0.1",
                "0:0:0:0",
                "Hikari",
                "HHH",
                "PSQL",
                "ERROR:",
                "Whitelabel",
                "Apache",
                "Spring",
                "nimbus",
                "Argon2",
                "argon2"));
    String url = environment.getProperty("spring.datasource.url", "");
    if (url.startsWith("jdbc:")) {
      URI db = URI.create(url.substring(5));
      if (db.getHost() != null) {
        markers.add(db.getHost());
      }
      if (db.getPort() > 0) {
        markers.add(":" + db.getPort());
      }
    }
    try {
      String host = InetAddress.getLocalHost().getHostName();
      if (host != null && host.length() > 3) {
        markers.add(host);
      }
    } catch (UnknownHostException e) {
      markers.add("unknown-host-marker-never-written");
    }
    return markers;
  }

  @Test
  void errorBodiesOfEveryKindNameNoClassSqlOrHost() {
    String user = accessToken(insertUser(true), "USER", true);
    UUID broken = insertUser(true);
    String brokenEmail =
        jdbc.sql("SELECT email FROM app_user WHERE id = ?")
            .param(broken)
            .query(String.class)
            .single();
    Map<String, Resp> responses = new LinkedHashMap<>();
    responses.put("malformed login", req("POST", API + "/auth/login").json("{\"email\":").send());
    responses.put(
        "typed login",
        req("POST", API + "/auth/login").json("{\"email\":1,\"password\":[]}").send());
    responses.put(
        "unusable stored hash",
        req("POST", API + "/auth/login")
            .json(Map.of("email", brokenEmail, "password", "some-long-password"))
            .send());
    responses.put(
        "huge number",
        req("POST", API + "/recommendations/query")
            .json("{\"country\":\"TR\",\"pageSize\":99999999999999999999999}")
            .send());
    responses.put(
        "NaN body",
        req("POST", API + "/recommendations/query")
            .json("{\"country\":\"TR\",\"pageSize\":NaN}")
            .send());
    responses.put("bad uuid", get(API + "/me/alerts/not-a-uuid", user));
    responses.put("bad uuid product", get(API + "/products/zz'%20OR%201=1?country=TR", null));
    responses.put("bad page", get(API + "/products?country=TR&page=99999999999999999999", null));
    responses.put("unknown route", get(API + "/no/such/route", user));
    responses.put("dot segments", get(API + "/%2e%2e/actuator/env", null));
    responses.put("method", req("PUT", API + "/auth/login").json("{}").send());
    responses.put(
        "media type", req("POST", API + "/auth/register").body("<a/>", "application/xml").send());
    responses.put(
        "accept xml",
        req("GET", API + "/me").bearer(user).header("Accept", "application/xml").send());
    responses.put(
        "patch me tree",
        req("PATCH", API + "/me")
            .bearer(user)
            .json("{\"locale\":{\"$ne\":null},\"defaultCountry\":\"' OR 1=1--\"}")
            .send());
    responses.put(
        "admin status bad value",
        req("POST", API + "/admin/users/" + broken + "/status")
            .bearer(adminToken())
            .json("{\"status\":\"DELETED; DROP TABLE app_user\"}")
            .send());
    responses.put("error path", req("GET", "/error").send());
    responses.put("refresh cookie overflow", refresh("A".repeat(9000)));

    List<String> markers = forbiddenInBodies();
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Resp> entry : responses.entrySet()) {
      Resp response = entry.getValue();
      if (response.status() < 400) {
        problems.add(entry.getKey() + ": not an error: " + response);
        continue;
      }
      try {
        assertNoLeak(response);
      } catch (AssertionError e) {
        problems.add(entry.getKey() + ": " + e.getMessage());
      }
      for (String marker : markers) {
        if (response.body() != null && response.body().contains(marker)) {
          problems.add(entry.getKey() + ": body names '" + marker + "': " + response.body());
        }
      }
      for (String header : List.of("server", "x-powered-by", "x-application-context")) {
        if (response.header(header) != null) {
          problems.add(entry.getKey() + ": header " + header + ": " + response.header(header));
        }
      }
    }
    assertThat(problems).as("error answers that disclose internals").isEmpty();
  }

  @Test
  void noApiResponseSendsHstsInTheDevProfile() {
    String user = accessToken(insertUser(true), "USER", true);
    List<Resp> responses =
        List.of(
            req("GET", API + "/health").send(),
            get(API + "/me", user),
            get(API + "/me", null),
            get(API + "/no/such/route", null),
            guardedJson("POST", API + "/auth/refresh", "{}"),
            postJson(API + "/auth/login", Map.of("email", uniqueEmail(), "password", "x-y-z")),
            req("OPTIONS", API + "/auth/login")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .send(),
            req("GET", API + "/health").header("X-Forwarded-Proto", "https").send());
    for (Resp response : responses) {
      assertThat(response.headers().allValues("strict-transport-security"))
          .as(response.toString())
          .isEmpty();
    }
  }

  // ---------- Metrics ----------

  @Test
  void metricsAreNotOnThePublicPortAndTheirLabelsCarryNoUserIdentifier() {
    for (String path :
        List.of(
            "/actuator/metrics",
            "/actuator/metrics/http.server.requests",
            "/actuator/prometheus",
            "/metrics",
            "/api/v1/metrics",
            "/api/v1/actuator/metrics",
            "/api/v1/../actuator/metrics",
            "/api/v1/%2e%2e/actuator/metrics",
            "/%61ctuator/metrics",
            "/actuator/metrics;x=y",
            "/ACTUATOR/METRICS")) {
      Resp response = http.req("GET", path).ip(ip).send();
      assertThat(response.status()).as("%s -> %s", path, response).isIn(400, 401, 403, 404);
      assertThat(response.body())
          .as(path)
          .doesNotContain("\"names\"", "availableTags", "measurements");
    }

    // A session full of identifiers, some of them in places a careless tag would copy.
    String email = uniqueEmail();
    UUID userId = registerVerified(email);
    Session session = login(email, PASSWORD);
    UUID unknown = UUID.randomUUID();
    String requestId = "adv-metric-" + UUID.randomUUID().toString().substring(0, 8);
    String promo = randomCode();
    get(API + "/me", session.accessToken());
    get(API + "/" + email, session.accessToken());
    get(API + "/me/alerts/" + unknown, session.accessToken());
    req("PATCH", API + "/me/alerts/" + userId)
        .bearer(session.accessToken())
        .requestId(requestId)
        .json("{\"active\":true}")
        .send();
    get(API + "/products/" + unknown + "?country=TR", null);
    get(API + "/admin/users/" + userId, session.accessToken());
    req("POST", API + "/me/subscription/promo")
        .bearer(session.accessToken())
        .json(Map.of("code", promo))
        .send();
    String limitedIp = randomIp();
    for (int i = 0; i < 11; i++) {
      http.req("POST", API + "/auth/login")
          .ip(limitedIp)
          .json(Map.of("email", email, "password", "not-the-password"))
          .send();
    }
    refresh(session.refreshToken());

    AttackClient management = new AttackClient(managementPort);
    Resp names = management.req("GET", "/actuator/metrics").send();
    assertThat(names.status()).as(names.toString()).isEqualTo(200);
    List<String> identifiers =
        List.of(
            email,
            email.substring(0, email.indexOf('@')),
            userId.toString(),
            unknown.toString(),
            requestId,
            promo,
            limitedIp,
            ip,
            session.accessToken().substring(0, 30),
            session.refreshToken().substring(0, 20));
    Pattern uuid = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-");
    Pattern address = Pattern.compile("\\b10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b|@");
    List<String> problems = new ArrayList<>();
    int tagValues = 0;
    for (JsonNode name : names.json().get("names")) {
      Resp metric = management.req("GET", "/actuator/metrics/" + name.asString()).send();
      if (metric.status() != 200) {
        continue;
      }
      for (JsonNode tag : metric.json().path("availableTags")) {
        for (JsonNode value : tag.path("values")) {
          tagValues++;
          String text = value.asString();
          String where = name.asString() + "{" + tag.get("tag").asString() + "=" + text + "}";
          for (String identifier : identifiers) {
            if (text.contains(identifier)) {
              problems.add(where + " contains " + identifier);
            }
          }
          if (uuid.matcher(text).find() || address.matcher(text).find()) {
            problems.add(where + " looks like a user identifier");
          }
        }
      }
    }
    assertThat(tagValues).as("metrics with labels were recorded").isPositive();
    assertThat(problems).as("metric labels with user identifiers").isEmpty();
  }

  // ---------- Request id spoofing ----------

  /** A raw HTTP/1.1 exchange: the bytes are written exactly as given. */
  record RawResponse(int status, Map<String, String> headers, String text) {}

  private RawResponse rawExchange(byte[] request) {
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(15_000);
      OutputStream out = socket.getOutputStream();
      out.write(request);
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      String text = buffer.toString(StandardCharsets.ISO_8859_1);
      if (text.isEmpty()) {
        return new RawResponse(0, Map.of(), text);
      }
      String[] lines = text.split("\r\n");
      int status = Integer.parseInt(lines[0].split(" ")[1]);
      Map<String, String> headers = new LinkedHashMap<>();
      for (int i = 1; i < lines.length && !lines[i].isEmpty(); i++) {
        int colon = lines[i].indexOf(':');
        if (colon > 0) {
          headers.put(
              lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
              lines[i].substring(colon + 1).trim());
        }
      }
      return new RawResponse(status, headers, text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private byte[] statusRequest(String admin, UUID target, byte[] requestIdHeaderBytes) {
    String body = "{\"status\":\"DISABLED\"}";
    ByteArrayOutputStream request = new ByteArrayOutputStream();
    request.writeBytes(
        ("POST "
                + API
                + "/admin/users/"
                + target
                + "/status HTTP/1.1\r\n"
                + "Host: localhost:"
                + port
                + "\r\n"
                + "Authorization: Bearer "
                + admin
                + "\r\n"
                + "X-Forwarded-For: "
                + randomIp()
                + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: "
                + body.length()
                + "\r\n"
                + "Connection: close\r\n")
            .getBytes(StandardCharsets.ISO_8859_1));
    request.writeBytes(requestIdHeaderBytes);
    request.writeBytes(("\r\n" + body).getBytes(StandardCharsets.ISO_8859_1));
    return request.toByteArray();
  }

  private static byte[] header(String raw) {
    return raw.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The one place a rejected header line may appear: the container's own parse-error line, which
   * renders control characters as {@code 0xNN} inside a JSON string, so no line or field can be
   * forged and no markup is carried.
   */
  private static boolean containerRejection(String line) {
    Matcher rejected = Pattern.compile("The HTTP header line \\[([^\\]]*)\\]").matcher(line);
    return line.contains("\"logger_name\":\"org.apache.coyote.http11.Http11Processor\"")
        && rejected.find()
        && !rejected.group(1).matches("(?s).*(\\\\[rnt]|\\\\u00|<script).*");
  }

  @Test
  void spoofedRequestIdsNeverReachALogLineOrAResponseUnsanitised(CapturedOutput output) {
    String admin = adminToken();
    String marker = "FORGED" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String script = "<script>alert('" + marker + "')</script>";
    Map<String, byte[]> attacks = new LinkedHashMap<>();
    attacks.put("CR", header("X-Request-Id: ok-1\r" + marker + "\r\n"));
    attacks.put(
        "LF with a forged JSON line",
        header("X-Request-Id: ok-2\n{\"level\":\"ERROR\",\"message\":\"" + marker + "\"}\r\n"));
    attacks.put("CRLF header injection", header("X-Request-Id: ok-3\r\nX-" + marker + ": 1\r\n"));
    attacks.put("folded line", header("X-Request-Id: ok-4\r\n " + marker + "\r\n"));
    attacks.put("tab", header("X-Request-Id: ok-5\t" + marker + "\r\n"));
    attacks.put("line separator", header("X-Request-Id: ok-6 " + marker + "\r\n"));
    attacks.put("next line", header("X-Request-Id: ok-7\u0085" + marker + "\r\n"));
    attacks.put(
        "NUL and VT",
        ("X-Request-Id: ok-8\u0000" + marker + "\u000b\r\n").getBytes(StandardCharsets.ISO_8859_1));
    attacks.put("encoded CRLF", header("X-Request-Id: ok-9%0d%0a" + marker + "\r\n"));
    attacks.put("script", header("X-Request-Id: " + script + "\r\n"));
    attacks.put(
        "10 kB script",
        header("X-Request-Id: " + (script + "-").repeat(10_240 / script.length()) + "\r\n"));
    attacks.put(
        "JSON breakout",
        header("X-Request-Id: a\",\"level\":\"ERROR\",\"m\":\"" + marker + "\r\n"));
    attacks.put(
        "valid first, hostile second",
        header("X-Request-Id: adv-first\r\nX-Request-Id: " + marker + "<b>\r\n"));
    attacks.put(
        "hostile first, valid second",
        header("X-Request-Id: " + marker + "<b>\r\nX-Request-Id: adv-second\r\n"));

    List<String> problems = new ArrayList<>();
    List<String> generated = new ArrayList<>();
    for (Map.Entry<String, byte[]> attack : attacks.entrySet()) {
      UUID target = insertUser(true);
      RawResponse response = rawExchange(statusRequest(admin, target, attack.getValue()));
      String label = attack.getKey();
      if (response.status() == 0) {
        continue;
      }
      if (response.text().contains(marker) || response.text().contains("<script")) {
        problems.add(label + ": response echoes the payload: " + response.text());
      }
      String id = response.headers().get("x-request-id");
      if (id != null) {
        if (!REQUEST_ID.matcher(id).matches()) {
          problems.add(label + ": response request id " + id);
        }
        if (response.status() == 200) {
          generated.add(id);
        }
      } else if (response.status() < 400) {
        problems.add(label + ": a handled request without X-Request-Id");
      }
    }
    assertThat(generated).as("some requests reached the application and were logged").isNotEmpty();

    String logs = output.getAll();
    for (String line : logs.split("\\R")) {
      if ((line.contains(marker) || line.contains("<script")) && !containerRejection(line)) {
        problems.add("the payload reached the log: " + line);
      }
    }
    for (String id : generated) {
      if (!logs.contains(id)) {
        problems.add("no log line carries the request id " + id);
      }
    }
    Matcher ids = Pattern.compile("\"requestId\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(logs);
    while (ids.find()) {
      if (!REQUEST_ID.matcher(ids.group(1)).matches()) {
        problems.add("a logged request id is not sanitised: " + ids.group(1));
      }
    }
    for (String line : logs.split("\\R")) {
      if (line.startsWith("{") && !line.endsWith("}")) {
        problems.add("a broken JSON log line: " + line);
      }
    }
    assertThat(problems).isEmpty();
  }
}
