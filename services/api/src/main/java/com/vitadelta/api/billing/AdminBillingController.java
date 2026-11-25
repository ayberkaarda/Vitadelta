package com.vitadelta.api.billing;

import com.vitadelta.api.billing.BillingApi.CreatePromoCodeRequest;
import com.vitadelta.api.billing.BillingApi.GrantSubscriptionRequest;
import com.vitadelta.api.billing.BillingApi.PromoCodeList;
import com.vitadelta.api.entitlement.PlanCode;
import com.vitadelta.api.identity.AccountPlanSummary.SubscriptionView;
import com.vitadelta.api.identity.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Billing administration (section 8.9), role ADMIN (route matrix and method security): manual PRO
 * grant and the promo-code endpoints.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminBillingController {

  private final ManualGrantBillingProvider manualGrants;
  private final PromoCodeService promoCodes;

  public AdminBillingController(
      ManualGrantBillingProvider manualGrants, PromoCodeService promoCodes) {
    this.manualGrants = manualGrants;
    this.promoCodes = promoCodes;
  }

  @PostMapping("/api/v1/admin/users/{id}/subscription")
  SubscriptionView grant(
      @PathVariable UUID id,
      @Valid @RequestBody GrantSubscriptionRequest body,
      Authentication authentication) {
    ActivationResult result =
        manualGrants.activate(
            new ActivationRequest.ManualGrant(
                id,
                PlanCode.valueOf(body.planCode()),
                body.durationDays(),
                CurrentUser.require(authentication)));
    return result.subscription().view();
  }

  @PostMapping("/api/v1/admin/promo-codes")
  @ResponseStatus(HttpStatus.CREATED)
  ResponseEntity<PromoCodeRecord> create(
      @Valid @RequestBody CreatePromoCodeRequest body, Authentication authentication) {
    PromoCodeRecord created =
        promoCodes.create(
            body.prefix(),
            PlanCode.valueOf(body.planCode()),
            body.durationDays(),
            body.maxRedemptions(),
            body.expiresAt(),
            CurrentUser.require(authentication));
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/api/v1/admin/promo-codes")
  PromoCodeList list() {
    return new PromoCodeList(promoCodes.list());
  }

  @DeleteMapping("/api/v1/admin/promo-codes/{code}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  ResponseEntity<Void> delete(@PathVariable String code) {
    if (!promoCodes.delete(code)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.noContent().build();
  }
}
