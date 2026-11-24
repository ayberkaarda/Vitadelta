package com.vitadelta.api.config.properties;

import com.vitadelta.api.common.web.IpRange;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reverse-proxy trust ({@code VITADELTA_TRUST_PROXY}, {@code VITADELTA_TRUSTED_PROXIES}).
 *
 * @param trustProxy whether {@code X-Forwarded-For} may be read at all
 * @param trustedProxies CIDR ranges of proxy peers whose {@code X-Forwarded-For} is read; required
 *     (non-empty) when {@code trustProxy} is true
 */
@ConfigurationProperties(prefix = "vitadelta.proxy")
public record ProxyProperties(
    @DefaultValue("false") boolean trustProxy, List<String> trustedProxies) {

  public ProxyProperties {
    trustedProxies =
        trustedProxies == null
            ? List.of()
            : trustedProxies.stream().map(String::strip).filter(c -> !c.isEmpty()).toList();
    for (String cidr : trustedProxies) {
      try {
        IpRange.parse(cidr);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "VITADELTA_TRUSTED_PROXIES contains an invalid CIDR range: " + cidr, e);
      }
    }
    if (trustProxy && trustedProxies.isEmpty()) {
      throw new IllegalArgumentException(
          "VITADELTA_TRUSTED_PROXIES must list the proxy CIDR ranges when VITADELTA_TRUST_PROXY=true");
    }
  }

  /** The parsed trusted ranges. */
  public List<IpRange> trustedRanges() {
    return trustedProxies.stream().map(IpRange::parse).toList();
  }
}
