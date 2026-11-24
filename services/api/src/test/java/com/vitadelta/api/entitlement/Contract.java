package com.vitadelta.api.entitlement;

import com.vitadelta.api.identity.ContractSchemas;

/**
 * Contract schema validation of response bodies through the identity tests' helper ({@link
 * ContractSchemas}), so every test validates against the same loading of {@code
 * contracts/public-api.openapi.yaml}.
 */
public final class Contract {

  private Contract() {}

  /** Asserts that {@code json} conforms to component schema {@code schema}. */
  public static void assertConforms(String schema, String json) {
    ContractSchemas.assertConforms(schema, json);
  }
}
