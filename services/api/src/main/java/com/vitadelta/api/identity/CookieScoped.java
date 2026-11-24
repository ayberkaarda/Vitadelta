package com.vitadelta.api.identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a cookie-setting or cookie-reading auth handler method (section 8.1). {@link
 * AuthRequestGuard} enforces the forgery rules on every request mapped to such a handler, whatever
 * spelling of the path reached it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CookieScoped {

  /** Whether {@code X-Requested-With: vitadelta} is required as well. */
  boolean requestedWith() default false;
}
