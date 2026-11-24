package com.vitadelta.api.identity;

import java.util.Locale;

/** E-mail address normalization: the stored, compared and rate-limited form of an address. */
public final class Emails {

  private Emails() {}

  /** {@code email} without surrounding white space and in lower case. */
  public static String normalize(String email) {
    return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
  }
}
