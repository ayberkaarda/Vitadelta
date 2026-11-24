package com.vitadelta.api.adversarial;

import static com.vitadelta.api.adversarial.AttackClient.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.vitadelta.api.adversarial.AttackClient.Resp;
import com.vitadelta.api.support.PostgresIntegrationTest;
import com.vitadelta.api.support.TestData;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Section 15.7 case 6, spoofing side: the proxy rule is on, but the socket peer (loopback) is not a
 * trusted proxy, so a client that rotates {@code X-Forwarded-For} on every attempt is still one
 * client and hits the per-IP login limit.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "vitadelta.proxy.trust-proxy=true",
      "vitadelta.proxy.trusted-proxies=192.0.2.0/24"
    })
class UntrustedPeerAdversarialTest extends PostgresIntegrationTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "vitadelta.identity.weak-passwords-file",
        () -> TestData.dataDir().resolve("reference/weak_passwords.txt").toString());
  }

  @LocalServerPort int port;

  @Test
  void rotatingSpoofedForwardedForFromAnUntrustedPeerStillHitsTheLimit() {
    AttackClient http = new AttackClient(port);
    ThreadLocalRandom random = ThreadLocalRandom.current();
    Resp last = null;
    for (int i = 0; i < 11; i++) {
      String spoofed = "192.0.2." + (i + 1) + ", 10." + random.nextInt(256) + ".0." + (i + 1);
      last =
          http.req("POST", "/api/v1/auth/login")
              .header("X-Forwarded-For", spoofed)
              .json(
                  Map.of(
                      "email",
                      "spoof." + UUID.randomUUID().toString().substring(0, 8) + "@example.org",
                      "password",
                      "definitely-wrong-password"))
              .send();
      if (i < 10) {
        assertThat(last.status()).as("attempt %d: %s", i + 1, last).isEqualTo(401);
      }
    }
    assertProblem(last, 429, "rate_limited");
    assertThat(Long.parseLong(last.header("retry-after"))).isPositive();
  }
}
