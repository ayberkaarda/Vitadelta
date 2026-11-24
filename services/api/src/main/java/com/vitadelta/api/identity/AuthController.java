package com.vitadelta.api.identity;

import com.vitadelta.api.common.problem.ProblemCode;
import com.vitadelta.api.common.problem.ProblemException;
import com.vitadelta.api.common.ratelimit.RateLimitPolicy;
import com.vitadelta.api.common.ratelimit.RateLimiter;
import com.vitadelta.api.common.web.ClientIpResolver;
import com.vitadelta.api.identity.IdentityApi.AcceptedMessage;
import com.vitadelta.api.identity.IdentityApi.AuthTokenResponse;
import com.vitadelta.api.identity.IdentityApi.EmailRequest;
import com.vitadelta.api.identity.IdentityApi.LoginRequest;
import com.vitadelta.api.identity.IdentityApi.RegisterRequest;
import com.vitadelta.api.identity.IdentityApi.ResetPasswordRequest;
import com.vitadelta.api.identity.IdentityApi.TokenRequest;
import com.vitadelta.api.identity.IdentityApi.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth/**} (section 8.2). The forgery checks of the cookie-scoped endpoints run in
 * {@link AuthRequestGuard} before the body is read; the per-IP rate limits use the client IP of
 * {@link ClientIpResolver} (trusted-proxy rule).
 */
@RestController
@RequestMapping(AuthController.BASE)
public class AuthController {

  public static final String BASE = "/api/v1/auth";

  static final String REGISTER_MESSAGE = "auth.register.check_email";
  static final String RESEND_MESSAGE = "auth.verify.resent";
  static final String FORGOT_MESSAGE = "auth.forgot.check_email";

  private final AuthService auth;
  private final AnonymousIdentityService anonymous;
  private final AuthCookies cookies;
  private final RateLimiter rateLimiter;
  private final ClientIpResolver clientIp;

  public AuthController(
      AuthService auth,
      AnonymousIdentityService anonymous,
      AuthCookies cookies,
      RateLimiter rateLimiter,
      ClientIpResolver clientIp) {
    this.auth = auth;
    this.anonymous = anonymous;
    this.cookies = cookies;
    this.rateLimiter = rateLimiter;
    this.clientIp = clientIp;
  }

  private void limit(RateLimitPolicy policy, HttpServletRequest request) {
    rateLimiter.check(policy, clientIp.resolve(request));
  }

  @PostMapping("/register")
  ResponseEntity<AcceptedMessage> register(
      @Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
    limit(RateLimitPolicy.REGISTER_PER_IP, request);
    auth.register(body, anonymous.currentIdHash(request));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.SET_COOKIE, cookies.clearAnonymous())
        .body(new AcceptedMessage(REGISTER_MESSAGE));
  }

  @PostMapping("/verify-email")
  ResponseEntity<Void> verifyEmail(
      @Valid @RequestBody TokenRequest body, HttpServletRequest request) {
    limit(RateLimitPolicy.VERIFY_RESET_PER_IP, request);
    auth.verifyEmail(body.token());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/resend-verification")
  ResponseEntity<AcceptedMessage> resendVerification(
      @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
    limit(RateLimitPolicy.FORGOT_RESEND_PER_IP, request);
    auth.resendVerification(body.email());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AcceptedMessage(RESEND_MESSAGE));
  }

  @PostMapping("/login")
  ResponseEntity<AuthTokenResponse> login(
      @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    limit(RateLimitPolicy.LOGIN_PER_IP, request);
    AuthService.Session session = auth.login(body, request.getHeader(HttpHeaders.USER_AGENT));
    return sessionResponse(session);
  }

  @PostMapping("/refresh")
  ResponseEntity<AuthTokenResponse> refresh(HttpServletRequest request) {
    limit(RateLimitPolicy.REFRESH_PER_IP, request);
    String token =
        AuthCookies.read(request, AuthCookies.REFRESH)
            .orElseThrow(() ->
                ProblemException.of(ProblemCode.UNAUTHORIZED, "The session cookie is missing."));
    AuthService.Session session =
        auth.refresh(token, request.getHeader(HttpHeaders.USER_AGENT), cookies.clearRefresh());
    return sessionResponse(session);
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(HttpServletRequest request) {
    AuthCookies.read(request, AuthCookies.REFRESH).ifPresent(auth::logout);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookies.clearRefresh())
        .build();
  }

  @PostMapping("/forgot-password")
  ResponseEntity<AcceptedMessage> forgotPassword(
      @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
    limit(RateLimitPolicy.FORGOT_RESEND_PER_IP, request);
    auth.forgotPassword(body.email());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AcceptedMessage(FORGOT_MESSAGE));
  }

  @PostMapping("/reset-password")
  ResponseEntity<Void> resetPassword(
      @Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
    limit(RateLimitPolicy.VERIFY_RESET_PER_IP, request);
    auth.resetPassword(body.token(), body.newPassword());
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<AuthTokenResponse> sessionResponse(AuthService.Session session) {
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken()))
        .body(
            new AuthTokenResponse(
                session.accessToken().value(),
                "Bearer",
                session.accessToken().expiresIn(),
                UserProfile.of(session.user())));
  }
}
