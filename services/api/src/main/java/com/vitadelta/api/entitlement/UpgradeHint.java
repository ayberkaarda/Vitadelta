package com.vitadelta.api.entitlement;

/**
 * Call to action of a limit error (contract schema {@code UpgradeHint}): the i18n key of the notice
 * and the relative path of the link.
 */
public record UpgradeHint(String messageKey, String ctaPath) {

  /** Anonymous trial used up: sign up. */
  public static final UpgradeHint SIGN_UP = new UpgradeHint("quota.exhausted", "/auth/register");

  /** Path of the upgrade page. */
  public static final String UPGRADE_PATH = "/upgrade";
}
