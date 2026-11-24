package com.vitadelta.api.identity;

/**
 * Seam to the FX module: whether a currency may be stored as a user's display currency ({@code
 * PATCH /me}). The identity package registers {@link CountryCurrencyPolicy} (the currencies of the
 * country registry) only when no other bean of this type exists; the FX module replaces it with the
 * full supported set (including {@code VITADELTA_FX_EXTRA_CURRENCIES}).
 */
public interface DisplayCurrencyPolicy {

  /** Whether {@code currency} (ISO 4217, upper case) is a supported display currency. */
  boolean isSupported(String currency);
}
