package com.vitadelta.api.common.web;

import java.io.IOException;

/**
 * A request body exceeded its size limit while it was being read. It is an {@link IOException} so
 * that it propagates through body readers; the exception handler maps it to {@code 413} {@code
 * validation_failed}.
 */
public class PayloadTooLargeException extends IOException {

  private static final long serialVersionUID = 1L;

  public PayloadTooLargeException(long limit) {
    super("request body exceeds " + limit + " bytes");
  }
}
