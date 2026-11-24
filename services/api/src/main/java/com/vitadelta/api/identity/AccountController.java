package com.vitadelta.api.identity;

import com.vitadelta.api.identity.IdentityApi.DeleteAccountRequest;
import com.vitadelta.api.identity.IdentityApi.Me;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** {@code /api/v1/me}: profile, settings and account deletion of the signed-in user. */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

  private final AccountService accounts;
  private final AuthCookies cookies;

  public AccountController(AccountService accounts, AuthCookies cookies) {
    this.accounts = accounts;
    this.cookies = cookies;
  }

  @GetMapping
  Me me(Authentication authentication) {
    return accounts.me(CurrentUser.require(authentication));
  }

  @PatchMapping
  Me update(Authentication authentication, @RequestBody JsonNode body) {
    return accounts.update(CurrentUser.require(authentication), body);
  }

  @DeleteMapping
  ResponseEntity<Void> delete(
      Authentication authentication, @Valid @RequestBody DeleteAccountRequest body) {
    accounts.delete(CurrentUser.require(authentication), body.password());
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh())
        .build();
  }
}
