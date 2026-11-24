package com.vitadelta.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.config.properties.ProxyProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

  private static MockHttpServletRequest request(String peer, String... forwarded) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(peer);
    for (String value : forwarded) {
      request.addHeader("X-Forwarded-For", value);
    }
    return request;
  }

  private final ClientIpResolver trusting =
      new ClientIpResolver(new ProxyProperties(true, List.of("172.30.80.0/24", "fd00::/8")));
  private final ClientIpResolver untrusting =
      new ClientIpResolver(new ProxyProperties(false, List.of("172.30.80.0/24")));

  @Test
  void withoutTrustTheHeaderIsIgnored() {
    assertThat(untrusting.resolve(request("172.30.80.5", "198.51.100.7"))).isEqualTo("172.30.80.5");
  }

  @Test
  void trustedPeerUsesTheRightMostEntry() {
    assertThat(trusting.resolve(request("172.30.80.5", "203.0.113.9, 198.51.100.7")))
        .isEqualTo("198.51.100.7");
    assertThat(trusting.resolve(request("172.30.80.5", "203.0.113.9", "198.51.100.7")))
        .isEqualTo("198.51.100.7");
    assertThat(trusting.resolve(request("fd00::1", "2001:db8::5")))
        .isEqualTo("2001:db8:0:0:0:0:0:5");
  }

  @Test
  void untrustedPeerIgnoresTheHeader() {
    assertThat(trusting.resolve(request("198.51.100.20", "203.0.113.9")))
        .isEqualTo("198.51.100.20");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"203.0.113.9, ", "203.0.113.9, not-an-ip", "example.com", "198.51.100.7, 999.1.1.1"})
  void malformedRightMostEntryFallsBackToThePeer(String header) {
    assertThat(trusting.resolve(request("172.30.80.5", header))).isEqualTo("172.30.80.5");
  }

  @Test
  void missingHeaderAndNonLiteralPeer() {
    assertThat(trusting.resolve(request("172.30.80.5"))).isEqualTo("172.30.80.5");
    assertThat(trusting.resolve(request("unknown-peer", "198.51.100.7"))).isEqualTo("unknown-peer");
  }

  @Test
  void ipRangesMatchByPrefix() {
    IpRange range = IpRange.parse("10.1.0.0/16");
    assertThat(range.contains(IpRange.parseLiteral("10.1.255.3").orElseThrow())).isTrue();
    assertThat(range.contains(IpRange.parseLiteral("10.2.0.1").orElseThrow())).isFalse();
    assertThat(range.contains(IpRange.parseLiteral("::1").orElseThrow())).isFalse();
    assertThat(range.toString()).isEqualTo("10.1.0.0/16");
    IpRange host = IpRange.parse("192.0.2.1");
    assertThat(host.contains(IpRange.parseLiteral("192.0.2.1").orElseThrow())).isTrue();
    assertThat(host.contains(IpRange.parseLiteral("192.0.2.2").orElseThrow())).isFalse();
    assertThat(IpRange.parse("0.0.0.0/0").contains(IpRange.parseLiteral("8.8.8.8").orElseThrow()))
        .isTrue();
    assertThat(
            IpRange.parse("2001:db8::/33")
                .contains(IpRange.parseLiteral("[2001:db8:7fff::1]").orElseThrow()))
        .isTrue();
    assertThat(
            IpRange.parse("2001:db8::/33")
                .contains(IpRange.parseLiteral("2001:db8:8000::1").orElseThrow()))
        .isFalse();
    assertThat(IpRange.parseLiteral(null)).isEmpty();
    assertThat(IpRange.parseLiteral("")).isEmpty();
    assertThat(IpRange.parseLiteral("a b")).isEmpty();
  }

  @Test
  void invalidRangesAreRejected() {
    for (String bad : new String[] {"", "10.0.0.0/33", "10.0.0.0/x", "host.example/8", "::/129"}) {
      assertThatThrownBy(() -> IpRange.parse(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> IpRange.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
