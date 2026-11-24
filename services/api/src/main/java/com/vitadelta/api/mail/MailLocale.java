package com.vitadelta.api.mail;

import java.util.Locale;

/** The languages e-mail templates exist in; every other language falls back to {@link #EN}. */
public enum MailLocale {
  TR("tr"),
  EN("en");

  private final String directory;
  private final Locale locale;

  MailLocale(String directory) {
    this.directory = directory;
    this.locale = Locale.forLanguageTag(directory);
  }

  /** Template directory under {@code resources/mail/}. */
  public String directory() {
    return directory;
  }

  public Locale locale() {
    return locale;
  }

  /** The template language for {@code requested}; {@code null} or unsupported gives {@link #EN}. */
  public static MailLocale of(Locale requested) {
    if (requested != null && TR.directory.equals(requested.getLanguage())) {
      return TR;
    }
    return EN;
  }
}
