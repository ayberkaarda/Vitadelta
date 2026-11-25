package com.vitadelta.api.support;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Values of the required {@code VITADELTA_*} variables for tests. Secrets are generated randomly
 * once per test JVM; nothing secret is stored in the repository.
 */
public final class TestEnvironment {

  /** Public base URL of the web app in tests. */
  public static final String WEB_BASE_URL = "https://vitadelta.test";

  /** The single allowed browser origin in tests. */
  public static final String WEB_ORIGIN = "https://vitadelta.test";

  public static final String MAIL_FROM = "no-reply@vitadelta.test";

  /** Controller contact of the privacy notice in tests. */
  public static final String PRIVACY_CONTACT = "privacy@vitadelta.test";

  /** Scoring service URL of tests that do not stub the scoring service. */
  public static final String SCORING_BASE_URL = "http://127.0.0.1:9";

  private static final String JWT_SECRET = randomHex(48);
  private static final String COOKIE_SECRET = randomHex(48);

  private TestEnvironment() {}

  /** The JWT signing secret of this test JVM. */
  public static String jwtSecret() {
    return JWT_SECRET;
  }

  /** The cookie secret of this test JVM. */
  public static String cookieSecret() {
    return COOKIE_SECRET;
  }

  /** The required variables, plus a random management port for web-server tests. */
  public static Map<String, String> required() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("VITADELTA_JWT_SECRET", JWT_SECRET);
    values.put("VITADELTA_COOKIE_SECRET", COOKIE_SECRET);
    values.put("VITADELTA_WEB_BASE_URL", WEB_BASE_URL);
    values.put("VITADELTA_WEB_ORIGINS", WEB_ORIGIN);
    values.put("VITADELTA_MAIL_FROM", MAIL_FROM);
    values.put("VITADELTA_PRIVACY_CONTACT", PRIVACY_CONTACT);
    values.put("VITADELTA_SMTP_HOST", "localhost");
    values.put("VITADELTA_SMTP_PORT", "2525");
    // Discard port: tests without a scoring stub get a fast connection error.
    values.put("VITADELTA_SCORING_BASE_URL", SCORING_BASE_URL);
    values.put("management.server.port", "0");
    return values;
  }

  /** {@code bytes} random bytes as lowercase hex. */
  public static String randomHex(int bytes) {
    byte[] value = new byte[bytes];
    new SecureRandom().nextBytes(value);
    return HexFormat.of().formatHex(value);
  }
}
