package com.vitadelta.api.mail;

import com.vitadelta.api.config.properties.WebProperties;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Links of the web app used in e-mails. They are built only from {@code VITADELTA_WEB_BASE_URL},
 * never from request headers. One-time tokens travel in the URL fragment ({@code #token=}), which
 * browsers never send to a server.
 */
@Component
public class MailLinks {

  /** Path of the e-mail verification page of the web app. */
  public static final String VERIFY_PATH = "/auth/verify";

  /** Path of the password reset page of the web app. */
  public static final String RESET_PATH = "/auth/reset";

  /** Path of the login page of the web app. */
  public static final String LOGIN_PATH = "/auth/login";

  /** Path of the forgotten-password page of the web app. */
  public static final String FORGOT_PATH = "/auth/forgot";

  /** Path of the account page of the web app. */
  public static final String ACCOUNT_PATH = "/account";

  /** Path of the privacy notice of the web app. */
  public static final String PRIVACY_PATH = "/privacy";

  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,256}");

  private final WebProperties web;

  public MailLinks(WebProperties web) {
    this.web = web;
  }

  /** The base URL of the web app (no trailing slash). */
  public String home() {
    return web.baseUrl();
  }

  public String verifyEmail(String token) {
    return web.link(VERIFY_PATH) + "#token=" + checkToken(token);
  }

  public String resetPassword(String token) {
    return web.link(RESET_PATH) + "#token=" + checkToken(token);
  }

  public String login() {
    return web.link(LOGIN_PATH);
  }

  public String forgotPassword() {
    return web.link(FORGOT_PATH);
  }

  public String account() {
    return web.link(ACCOUNT_PATH);
  }

  public String privacy() {
    return web.link(PRIVACY_PATH);
  }

  /**
   * Returns {@code token} when it is a base64url value of 16 to 256 characters.
   *
   * @throws IllegalArgumentException otherwise (the message never contains the value)
   */
  static String checkToken(String token) {
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw new IllegalArgumentException("token must be a base64url value of 16 to 256 characters");
    }
    return token;
  }
}
