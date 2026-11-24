package com.vitadelta.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OfferRulesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://shop.example/p",
        "http://shop.example",
        "HTTPS://SHOP.EXAMPLE/P",
        "https://shop.example:8443/p?q=1&r=2#frag",
        "https://user:secret@shop.example/p",
        "https://user@shop.example:443/p",
        "http://[2001:db8::1]:8080/",
        "http://192.0.2.10/p",
        "https://bücher.example/katalog",
        "https://مثال.example/",
        "https://xn--bcher-kva.example/",
        "https://shop_1.example/p",
        "https://shop.example./p"
      })
  void acceptsAbsoluteHttpUrlsWithAHost(String url) {
    assertThat(OfferRules.isValidUrl(url)).as(url).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://user@:443/path",
        "https://:443/",
        "https://user@/x",
        "https://user:pw@/x",
        "https://a@b@host.example/x",
        "https://host.example:99999/",
        "https://host.example:port/",
        "https://[not-ipv6]/",
        "https://-host.example/",
        "https://host..example/",
        "https:///path",
        "https://",
        "javascript:alert(1)",
        "ftp://host.example/",
        "/relative",
        "https://host.example/a b",
        ""
      })
  void rejectsUrlsWithoutAValidHost(String url) {
    assertThat(OfferRules.isValidUrl(url)).as(url).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.01", "1", "9999999999.99", "12.50"})
  void acceptsPrices(String price) {
    assertThat(OfferRules.isValidPrice(new BigDecimal(price))).isTrue();
    assertThat(OfferRules.parsePrice(price)).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "1.001", "10000000000", "1e3", "1,5", " 1"})
  void rejectsPrices(String price) {
    assertThat(OfferRules.parsePrice(price)).isEmpty();
  }
}
