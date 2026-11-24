package com.vitadelta.api.common.web;

import com.vitadelta.api.config.properties.ProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP address of a request (project specification section 8.2).
 *
 * <ul>
 *   <li>With {@code VITADELTA_TRUST_PROXY=false} the client IP is the socket peer address and
 *       {@code X-Forwarded-For} is ignored entirely.
 *   <li>With {@code VITADELTA_TRUST_PROXY=true} the header is read <b>only</b> when the socket peer
 *       is inside one of {@code VITADELTA_TRUSTED_PROXIES}; the <b>right-most</b> entry (the one
 *       the trusted proxy added) is used and every other entry is ignored. A missing or malformed
 *       right-most entry falls back to the socket peer.
 * </ul>
 *
 * The socket peer comes from {@link HttpServletRequest#getRemoteAddr()}; the application keeps
 * {@code server.forward-headers-strategy=none}, so the container never rewrites it.
 */
@Component
public class ClientIpResolver {

  static final String FORWARDED_FOR = "X-Forwarded-For";

  private final boolean trustProxy;
  private final List<IpRange> trustedProxies;

  public ClientIpResolver(ProxyProperties properties) {
    this.trustProxy = properties.trustProxy();
    this.trustedProxies = properties.trustedRanges();
  }

  /** The client IP address as text (normalized literal, never a host name). */
  public String resolve(HttpServletRequest request) {
    String peerText = request.getRemoteAddr();
    Optional<InetAddress> peer = IpRange.parseLiteral(peerText);
    if (peer.isEmpty()) {
      return peerText;
    }
    if (trustProxy && trustedProxies.stream().anyMatch(range -> range.contains(peer.get()))) {
      Optional<InetAddress> forwarded = rightMostForwarded(request).flatMap(IpRange::parseLiteral);
      if (forwarded.isPresent()) {
        return forwarded.get().getHostAddress();
      }
    }
    return peer.get().getHostAddress();
  }

  private static Optional<String> rightMostForwarded(HttpServletRequest request) {
    Enumeration<String> headers = request.getHeaders(FORWARDED_FOR);
    String last = null;
    while (headers != null && headers.hasMoreElements()) {
      last = headers.nextElement();
    }
    if (last == null) {
      return Optional.empty();
    }
    String[] entries = last.split(",", -1);
    String entry = entries[entries.length - 1].strip();
    return entry.isEmpty() ? Optional.empty() : Optional.of(entry);
  }
}
