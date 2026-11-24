package com.vitadelta.api.config.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.support.TestEnvironment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

class PropertiesValidationTest {

  private static final String SECRET = TestEnvironment.randomHex(16);
  private static final String OTHER = TestEnvironment.randomHex(16);

  @Test
  void secretsNeedAtLeast32Bytes() {
    SecretsProperties ok = new SecretsProperties(SECRET, "", OTHER);
    assertThat(ok.jwtSecretPrevious()).isNull();
    assertThat(ok.jwtSecretBytes()).hasSize(32);
    assertThat(ok.cookieSecretBytes()).hasSize(32);
    assertThat(ok.jwtSecretPreviousBytes()).isNull();
    assertThat(ok.toString()).doesNotContain(SECRET).doesNotContain(OTHER).contains("<redacted>");
    String previous = TestEnvironment.randomHex(20);
    assertThat(new SecretsProperties(SECRET, previous, OTHER).jwtSecretPreviousBytes()).hasSize(40);

    assertThatThrownBy(() -> new SecretsProperties(null, null, OTHER))
        .hasMessageContaining("VITADELTA_JWT_SECRET is not set");
    assertThatThrownBy(() -> new SecretsProperties("short", null, OTHER))
        .hasMessageContaining("VITADELTA_JWT_SECRET is too short");
    assertThatThrownBy(() -> new SecretsProperties(SECRET, null, " "))
        .hasMessageContaining("VITADELTA_COOKIE_SECRET is not set");
    assertThatThrownBy(() -> new SecretsProperties(SECRET, "short", OTHER))
        .hasMessageContaining("VITADELTA_JWT_SECRET_PREVIOUS is too short");
    assertThatThrownBy(() -> new SecretsProperties(SECRET, SECRET, OTHER))
        .hasMessageContaining("must differ");
    assertThatThrownBy(() -> new SecretsProperties(SECRET, null, SECRET))
        .hasMessageContaining("must differ");
  }

  @Test
  void webBaseUrlIsRequiredAndHttpsOutsideDev() {
    WebProperties web =
        new WebProperties(
            List.of(" https://vitadelta.example ", ""), "https://vitadelta.example/", true, true);
    assertThat(web.baseUrl()).isEqualTo("https://vitadelta.example");
    assertThat(web.origins()).containsExactly("https://vitadelta.example");
    assertThat(web.link("/auth/verify")).isEqualTo("https://vitadelta.example/auth/verify");
    assertThat(web.isAllowedOrigin("HTTPS://vitadelta.example")).isTrue();
    assertThat(web.isAllowedOrigin("https://evil.example")).isFalse();
    assertThat(web.isAllowedOrigin(null)).isFalse();
    assertThatThrownBy(() -> web.link("auth")).isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> new WebProperties(List.of(), "", true, true))
        .hasMessageContaining("VITADELTA_WEB_BASE_URL is not set");
    assertThatThrownBy(() -> new WebProperties(List.of(), "http://localhost:8080", true, true))
        .hasMessageContaining("must use https outside the dev profile");
    assertThat(new WebProperties(null, "http://localhost:8080", false, false).baseUrl())
        .isEqualTo("http://localhost:8080");
    assertThatThrownBy(() -> new WebProperties(List.of(), "/relative", false, true))
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> new WebProperties(List.of(), "ftp://host.example", false, true))
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> new WebProperties(List.of(), "https://host.example/?a=1", true, true))
        .hasMessageContaining("query");
    assertThatThrownBy(() -> new WebProperties(List.of(), "https://host example", true, true))
        .hasMessageContaining("not a valid URL");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://vitadelta.example/path",
        "vitadelta.example",
        "ftp://vitadelta.example",
        "https://user@vitadelta.example",
        "https://vitadelta.example?x"
      })
  void webOriginsMustBeBareOrigins(String origin) {
    assertThatThrownBy(() -> new WebProperties(List.of(origin), "https://a.example", true, true))
        .hasMessageContaining("VITADELTA_WEB_ORIGINS");
  }

  @Test
  void proxyRangesAreValidated() {
    ProxyProperties proxy = new ProxyProperties(true, List.of(" 172.30.80.0/24 ", "", "fd00::/8"));
    assertThat(proxy.trustedProxies()).containsExactly("172.30.80.0/24", "fd00::/8");
    assertThat(proxy.trustedRanges()).hasSize(2);
    assertThat(new ProxyProperties(false, null).trustedProxies()).isEmpty();
    assertThatThrownBy(() -> new ProxyProperties(true, List.of()))
        .hasMessageContaining("VITADELTA_TRUSTED_PROXIES must list");
    assertThatThrownBy(() -> new ProxyProperties(false, List.of("not-a-range")))
        .hasMessageContaining("invalid CIDR");
  }

  @Test
  void mailSenderIsRequiredAndValid() {
    assertThat(new MailSettingsProperties(" no-reply@vitadelta.example ").from())
        .isEqualTo("no-reply@vitadelta.example");
    assertThatThrownBy(() -> new MailSettingsProperties(null))
        .hasMessageContaining("VITADELTA_MAIL_FROM is not set");
    assertThatThrownBy(() -> new MailSettingsProperties("not an address"))
        .hasMessageContaining("not a valid address");
  }

  @Test
  void anonymousLimitsAreNotNegative() {
    assertThat(new AnonymousProperties(3, 3).queryLimit()).isEqualTo(3);
    assertThatThrownBy(() -> new AnonymousProperties(-1, 3))
        .hasMessageContaining("VITADELTA_ANON_QUERY_LIMIT");
    assertThatThrownBy(() -> new AnonymousProperties(3, -1))
        .hasMessageContaining("VITADELTA_ANON_VISIBLE_RESULTS");
  }

  @Test
  void bootstrapAdminIsAllOrNothing() {
    BootstrapAdminProperties none = new BootstrapAdminProperties("", "");
    assertThat(none.configured()).isFalse();
    String password = TestEnvironment.randomHex(8);
    BootstrapAdminProperties admin =
        new BootstrapAdminProperties(" admin@vitadelta.example ", password);
    assertThat(admin.configured()).isTrue();
    assertThat(admin.email()).isEqualTo("admin@vitadelta.example");
    assertThat(admin.toString()).doesNotContain(password).doesNotContain("admin@");
    assertThatThrownBy(() -> new BootstrapAdminProperties("admin@vitadelta.example", null))
        .hasMessageContaining("set together");
    assertThatThrownBy(() -> new BootstrapAdminProperties(null, password))
        .hasMessageContaining("set together");
    assertThatThrownBy(() -> new BootstrapAdminProperties("admin", password))
        .hasMessageContaining("not an e-mail address");
  }

  @Test
  void operationsAreValidated() {
    OperationsProperties operations = new OperationsProperties(false, 30, " privacy@example.org ");
    assertThat(operations.jobsEnabled()).isFalse();
    assertThat(operations.privacyContact()).isEqualTo("privacy@example.org");
    assertThat(new OperationsProperties(true, 1, " ").privacyContact()).isNull();
    assertThatThrownBy(() -> new OperationsProperties(true, 0, null))
        .hasMessageContaining("VITADELTA_UNVERIFIED_ACCOUNT_TTL_DAYS");
  }

  @Test
  void bodyLimitsPerPath() {
    HttpLimitsProperties limits =
        new HttpLimitsProperties(
            DataSize.ofKilobytes(64), DataSize.ofMegabytes(20), List.of("/api/v1/admin/imports/"));
    assertThat(limits.limitFor("/api/v1/recommendations/query")).isEqualTo(65_536);
    assertThat(limits.limitFor("/api/v1/admin/imports/offers")).isEqualTo(20_971_520);
    assertThatThrownBy(
            () -> new HttpLimitsProperties(DataSize.ofBytes(0), DataSize.ofMegabytes(1), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
