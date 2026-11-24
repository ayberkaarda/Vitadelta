package com.vitadelta.api.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vitadelta.api.entitlement.PlanCode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Code generation and request handling of the billing adapters without a database. */
class BillingUnitTest {

  private final PromoCodeGenerator generator = new PromoCodeGenerator();

  @Test
  void codesAreSixteenCrockfordCharactersAfterTheUpperCasePrefix() {
    Set<String> codes = new HashSet<>();
    for (int i = 0; i < 2000; i++) {
      String code = generator.generate("launch");
      assertThat(code).matches("^LAUNCH-[0-9A-HJKMNP-TV-Z]{16}$");
      assertThat(code).matches(PromoCodeGenerator.CODE_REGEX);
      codes.add(code);
    }
    assertThat(codes).hasSize(2000);
    String bare = generator.generate(null);
    assertThat(bare).hasSize(16).matches(PromoCodeGenerator.CODE_REGEX);
  }

  @Test
  void everyAlphabetCharacterOccurs() {
    Set<Character> seen = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      for (char c : generator.generate(null).toCharArray()) {
        seen.add(c);
      }
    }
    assertThat(seen).hasSize(PromoCodeGenerator.ALPHABET.length());
  }

  @Test
  void invalidPrefixesAreRejected() {
    assertThatThrownBy(() -> generator.generate("has-hyphen"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> generator.generate("A".repeat(17)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> generator.generate("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void adaptersRejectRequestsOfTheOtherKind() {
    PromoCodeBillingProvider promo =
        new PromoCodeBillingProvider(null, null, null, null, null, null);
    ManualGrantBillingProvider manual = new ManualGrantBillingProvider(null, null, null);
    UUID user = UUID.randomUUID();
    assertThatThrownBy(
            () -> promo.activate(new ActivationRequest.ManualGrant(user, PlanCode.PRO, 30, user)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> manual.activate(new ActivationRequest.PromoCode(user, "X")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> manual.activate(new ActivationRequest.ManualGrant(user, PlanCode.FREE, 30, user)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requestsAndRecordsHideOrReportWhatTheyShould() {
    UUID user = UUID.randomUUID();
    assertThat(new ActivationRequest.PromoCode(user, "SECRET-CODE").toString())
        .doesNotContain("SECRET");
    assertThat(new BillingApi.PromoRedeemRequest("SECRET-CODE").toString())
        .doesNotContain("SECRET");
    Instant now = Instant.parse("2026-11-24T00:00:00Z");
    PromoCodeRecord never = new PromoCodeRecord("C", PlanCode.PRO, 1, 1, 0, null, null);
    PromoCodeRecord ended = new PromoCodeRecord("C", PlanCode.PRO, 1, 1, 0, now, null);
    assertThat(never.expired(now)).isFalse();
    assertThat(ended.expired(now)).isTrue();
    assertThat(ended.expired(now.minusSeconds(1))).isFalse();
  }
}
