package com.vitadelta.api.entitlement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Contract schema validation of response bodies through the identity tests' helper ({@code
 * com.vitadelta.api.identity.ContractSchemas}, package-private there), so every test validates
 * against the same loading of {@code contracts/public-api.openapi.yaml}.
 */
public final class Contract {

  private static final Method ASSERT_CONFORMS = assertConformsMethod();

  private Contract() {}

  private static Method assertConformsMethod() {
    try {
      Method method =
          Class.forName("com.vitadelta.api.identity.ContractSchemas")
              .getDeclaredMethod("assertConforms", String.class, String.class);
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("identity ContractSchemas helper not found", e);
    }
  }

  /** Asserts that {@code json} conforms to component schema {@code schema}. */
  public static void assertConforms(String schema, String json) {
    try {
      ASSERT_CONFORMS.invoke(null, schema, json);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(e.getCause());
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
