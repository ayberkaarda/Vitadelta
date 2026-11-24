package com.vitadelta.api.catalog;

import java.util.regex.Pattern;

/** Sentinels and syntax of nutrient form codes (form sentinel rule). */
public final class FormCodes {

  /** Reference-table form meaning "applies to every form", including {@code UNKNOWN}. */
  public static final String ANY = "ANY";

  /** Product form used when the label form is not in the registry or not stated. */
  public static final String UNKNOWN = "UNKNOWN";

  /** Nutrient code of generic unit-conversion rows. */
  public static final String GENERIC_NUTRIENT = "*";

  /** Syntax shared by nutrient codes and form codes. */
  public static final Pattern REGISTRY_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

  private FormCodes() {}
}
